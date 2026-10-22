package app.autopilot.android;

import android.Manifest;
import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.*;
import android.net.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public release checks are independent of provider/account executors and the VPN lifecycle. */
public final class UpdateManager {
    public static final String DEFAULT_FEED = "https://github.com/gohrayson/autopilot-android/releases/latest/download/latest.json";
    private static final long INTERVAL = 6 * 60 * 60 * 1000L;
    private static final int JOB_ID = 0x41555044, NOTIFICATION_ID = 1202;
    private static final String CHANNEL = "autopilot-updates";
    private final AutopilotApp app;
    private final SharedPreferences prefs;
    private final File directory;
    private final UpdateDownload downloader;
    private final String expectedPackageName;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile Thread worker;
    private volatile boolean cancelRequested;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile UpdateManifest available, ready;
    private volatile String phase = "idle", error = "";
    private volatile Intent confirmation;
    private volatile java.util.function.Consumer<Intent> installCallback;
    private volatile UpdateManifest downloadingRelease;
    private volatile TransferProgress progress;
    private volatile String pausedReleaseHash;
    private volatile boolean userPauseRequested;
    private volatile String operation = "", route = "not-started", actionNotice = "";
    private volatile long operationStartedAt, operationStartedElapsed, operationFinishedElapsed;
    private final Object progressNotifications = new Object();
    private long lastProgressNotificationNanos, lastPublishedDownloaded;
    private static final class TransferProgress{
        final UpdateManifest release;final UpdateProgress metrics;
        TransferProgress(UpdateManifest release,UpdateProgress metrics){this.release=release;this.metrics=metrics;}
    }

    public UpdateManager(AutopilotApp app) {
        this(app, null, UpdateIdentity.PACKAGE_NAME);
    }
    /** Test-owned transports can exercise the real controller without a provider account. */
    UpdateManager(AutopilotApp app, UpdateDownload downloader, String expectedPackageName) {
        this.app = app;
        this.expectedPackageName = Objects.requireNonNull(expectedPackageName);
        this.downloader = downloader == null ? new UpdateDownload(() -> {
            VpnEngine engine = app.engine;
            return engine == null ? 0 : engine.updateProxyPort();
        }, this::recordRoute) : downloader;
        prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE);
        directory = new File(app.getNoBackupFilesDir(), "updates");
        pausedReleaseHash=prefs.getString("pausedReleaseHash","");
    }
    public void start() {
        schedule();
        enqueue("restore", "restoring", () -> { restore(); if (ready == null && automaticChecks()) check(true); else if("restoring".equals(phase))phase="idle"; }, null);
    }
    public boolean automaticChecks() { return prefs.getBoolean("automaticChecks", true); }
    public boolean wifiDownloads() { return prefs.getBoolean("wifiDownloads", true); }
    public boolean automaticInstalls() { return prefs.getBoolean("automaticInstalls", true); }
    public String feedUrl() { return prefs.getString("feedUrl", DEFAULT_FEED); }
    public void preferences(boolean checks, boolean wifi, boolean install) {
        prefs.edit().putBoolean("automaticChecks", checks).putBoolean("wifiDownloads", wifi).putBoolean("automaticInstalls",install).apply();
        schedule(); app.changed();
    }
    public void setFeedUrl(String url) throws UpdateDownload.UpdateException {
        String validated = UpdateManifest.publicHttpsUri(url.trim()).toASCIIString();
        if (busy.get()) throw new UpdateDownload.UpdateException("UPDATE_BUSY");
        if(validated.equals(feedUrl()))return;
        UpdateManifest previous=available;
        if(previous!=null)downloader.discardResume(previous,directory);
        abandonSession();
        prefs.edit().putString("feedUrl", validated).remove("manifest").remove("manifestFeed").remove("pausedReleaseHash").putLong("lastChecked", 0).apply();
        available = null; ready = null; progress=null;pausedReleaseHash="";phase = "idle"; error = "";
        app.changed();
    }
    public JSONObject state() {
        JSONObject state = new JSONObject(); UpdateManifest release = available;String currentPhase=phase;
        try {
            state.put("phase", currentPhase).put("error", error).put("busy", busy.get())
                    .put("operation", operation).put("operationStartedAt", operationStartedAt)
                    .put("operationElapsedMs", operationElapsedMs()).put("route", route)
                    .put("feedHost", feedHost()).put("actionNotice", actionNotice)
                    .put("lastCheckSucceededAt",prefs.getLong("lastCheckSucceededAt",0))
                    .put("lastChecked", prefs.getLong("lastChecked", 0)).put("ready", ready != null)
                    .put("available", release != null).put("versionName", release == null ? "" : release.versionName)
                    .put("notes", release == null ? "" : release.notes)
                    .put("sizeBytes", release == null ? 0 : release.sizeBytes)
                    .put("installPermission",app.getPackageManager().canRequestPackageInstalls())
                    .put("vpnDefersInstall",!vpnStopped())
                    .put("downloadPaused",isUserPaused(release))
                    .put("downloadPausing",userPauseRequested&&downloadingRelease!=null&&"downloading".equals(currentPhase))
                    .put("canPause",busy.get()&&"downloading".equals(currentPhase)&&!userPauseRequested);
            TransferProgress current=progress;
            UpdateProgress.Snapshot bytes=current==null||release==null||!sameRelease(current.release,release)?null:current.metrics.snapshot(System.nanoTime(),"downloading".equals(currentPhase));
            long downloaded=bytes==null?0:bytes.downloadedBytes,total=bytes==null?0:bytes.totalBytes;
            state.put("downloadedBytes",downloaded).put("downloadTotalBytes",total)
                    .put("downloadPercent",bytes==null?0:bytes.percent)
                    .put("downloadBytesPerSecond",bytes==null?0:bytes.bytesPerSecond)
                    .put("downloadEtaSeconds",bytes==null?-1:bytes.etaSeconds)
                    .put("downloadConnections",bytes==null?0:bytes.activeConnections)
                    .put("canResume",release!=null&&ready==null&&!busy.get()&&(isUserPaused(release)||downloaded>0));
        } catch (JSONException ignored) { }
        return state;
    }
    public String diagnostic() {
        TransferProgress current=progress;UpdateProgress.Snapshot bytes=current==null?null:current.metrics.snapshot(System.nanoTime(),"downloading".equals(phase));
        UpdateManifest cached=ready!=null?ready:available;boolean localPresent=false;long localSize=0;
        if(cached!=null)try{File file=apkFile(cached);localPresent=file.isFile();localSize=localPresent?file.length():0;}catch(IOException ignored){}
        return "Updates: " + phase + " / " + error + "\nUpdate last check: " + prefs.getLong("lastChecked", 0)
                +"\nUpdate busy: "+busy.get()+" / operation: "+operation+" / started: "+operationStartedAt+" / elapsed ms: "+operationElapsedMs()
                +"\nUpdate route: "+route+" / feed host: "+feedHost()
                +"\nUpdate last successful check: "+prefs.getLong("lastCheckSucceededAt",0)
                +"\nUpdate local APK: present="+localPresent+" / actual bytes="+localSize+" / expected bytes="+(cached==null?0:cached.sizeBytes)
                +"\nUpdate download: "+(bytes==null?0:bytes.downloadedBytes)+"/"+(bytes==null?0:bytes.totalBytes)
                +" / "+(bytes==null?0:bytes.activeConnections)+" connections\nUpdate user paused: "+isUserPaused(available)+"\n";
    }
    public void checkNow() { requestCheckNow(); }
    public boolean requestCheckNow() { return enqueue("check", "checking", () -> check(true), null); }
    public void foregroundCheck() {
        long last=prefs.getLong("lastChecked",0), now=System.currentTimeMillis();
        if(ready==null&&automaticChecks()&&!busy.get()&&(last==0||now<last||now-last>=15*60*1000L)) checkNow();
    }
    public void cancelBackgroundWork() { cancelRequested=true; Thread thread=worker; if(thread!=null)thread.interrupt(); }
    public void backgroundCheck(Runnable completed) {
        // A verified local APK needs no network hop before the user can install it.
        if(ready!=null){if(completed!=null)main.post(completed);return;}
        enqueue("background", null, () -> { if (ready == null && automaticChecks() && due()) check(true); }, completed);
    }
    public void downloadNow() { requestDownloadNow(); }
    public boolean requestDownloadNow() { return enqueue("download", "downloading", ()->{UpdateManifest release=available;if(release!=null)clearUserPause(release);download();}, null); }
    public void pauseDownload(){
        UpdateManifest release=downloadingRelease;
        if(release==null||!busy.get()||!"downloading".equals(phase)||userPauseRequested)return;
        // Persist the choice before interrupting the download's coordinating worker. The download
        // catch path flushes this marker before releasing single-flight ownership.
        pausedReleaseHash=release.sha256;userPauseRequested=true;
        prefs.edit().putString("pausedReleaseHash",release.sha256).apply();
        cancelRequested=true;Thread thread=worker;if(thread!=null)thread.interrupt();
        app.changed();
    }
    private boolean isUserPaused(UpdateManifest release){return release!=null&&release.sha256.equals(pausedReleaseHash);}
    private void clearUserPause(UpdateManifest release) throws UpdateDownload.UpdateException{
        if(!isUserPaused(release))return;
        if(!prefs.edit().remove("pausedReleaseHash").commit())throw new UpdateDownload.UpdateException("UPDATE_STORAGE_ERROR");
        pausedReleaseHash="";
    }
    public void prepareInstall(java.util.function.Consumer<Intent> openInstaller) {
        requestPrepareInstall(openInstaller);
    }
    public boolean requestPrepareInstall(java.util.function.Consumer<Intent> openInstaller) {
        return enqueue("install", "preparing-install", () -> {
            UpdateManifest release = ready;
            if (release == null) throw new UpdateDownload.UpdateException("UPDATE_NOT_READY");
            verifyReadyForInstall(release);
            Intent pending=confirmation;
            if(pending!=null){phase="confirmation";error="";app.changed();main.post(()->openInstaller.accept(new Intent(pending)));return;}
            // A pending system intent is deliberately kept only in memory. If Android killed this
            // process, this explicit tap recreates the owned session and obtains a fresh confirmation.
            abandonSession();installCallback=openInstaller;install(release,false);
        }, null);
    }
    private boolean vpnStopped(){
        String vpnPhase=app.state().optString("phase","idle");
        return !app.vpnWanted()&&(app.engine==null||Set.of("idle","stopped","error").contains(vpnPhase));
    }
    public void connectionStateChanged(){
        UpdateManifest release=ready;
        if(release==null||busy.get()||isUserPaused(release)||!automaticInstalls()||Build.VERSION.SDK_INT<31||!vpnStopped()
                ||!app.getPackageManager().canRequestPackageInstalls()||prefs.getInt("sessionId",-1)!=-1
                ||prefs.getInt("automaticAttemptVersion",0)>=release.versionCode)return;
        enqueue("install", null, ()->{
            if(!vpnStopped()||!automaticInstalls()||ready!=release||isUserPaused(release))return;
            if(!prefs.edit().putInt("automaticAttemptVersion",release.versionCode).commit())
                throw new UpdateDownload.UpdateException("UPDATE_STORAGE_ERROR");
            phase="preparing-install";error="";app.changed();install(release,true);
        },null);
    }
    private void install(UpdateManifest release,boolean automatic) throws Exception {
        if(!app.getPackageManager().canRequestPackageInstalls())throw new UpdateDownload.UpdateException("UPDATE_INSTALL_PERMISSION_REQUIRED");
        if(automatic&&(!vpnStopped()||Build.VERSION.SDK_INT<31)){phase=sessionPhase();app.changed();return;}
        File file=verifyReadyForInstall(release);
        PackageInstaller installer=app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(UpdateIdentity.PACKAGE_NAME);params.setSize(release.sizeBytes);
        if(Build.VERSION.SDK_INT>=31)params.setRequireUserAction(automatic?PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED:PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        int id=installer.createSession(params);boolean committed=false;
        try(PackageInstaller.Session session=installer.openSession(id)){
            try(InputStream input=new FileInputStream(file);OutputStream output=session.openWrite("Autopilot.apk",0,release.sizeBytes)){
                byte[] bytes=new byte[64*1024];int count;while((count=input.read(bytes))!=-1){if(Thread.currentThread().isInterrupted())throw new UpdateDownload.UpdateException("UPDATE_CANCELLED");output.write(bytes,0,count);}session.fsync(output);
            }
            // Never intentionally replace a running tunnel during an unattended update.
            if(automatic&&(!vpnStopped()||!automaticInstalls()))return;
            if(!prefs.edit().putInt("sessionId",id).putInt("sessionVersion",release.versionCode)
                    .putString("sessionState","installing").putLong("sessionAt",System.currentTimeMillis())
                    .putInt("automaticAttemptVersion",release.versionCode).commit())
                throw new UpdateDownload.UpdateException("UPDATE_STORAGE_ERROR");
            Intent result=new Intent(app,UpdateInstallReceiver.class).setAction(app.getPackageName()+".UPDATE_INSTALL_STATUS."+id);
            int flags=PendingIntent.FLAG_UPDATE_CURRENT;if(Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;
            PendingIntent callback=PendingIntent.getBroadcast(app,id,result,flags);
            phase="installing";error="";app.log("updates","UPDATE_INSTALLING");app.changed();
            session.commit(callback.getIntentSender());committed=true;
        }finally{
            if(!committed){try{installer.abandonSession(id);}catch(Exception ignored){}clearSession();installCallback=null;phase="ready";}
        }
    }
    @SuppressWarnings("deprecation") public void installResult(Intent result){
        int id=result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-1);
        if(id<0||id!=prefs.getInt("sessionId",-1)||!(app.getPackageName()+".UPDATE_INSTALL_STATUS."+id).equals(result.getAction()))return;
        String pkg=result.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);if(pkg!=null&&!UpdateIdentity.PACKAGE_NAME.equals(pkg))return;
        int status=result.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){
            Intent pending=Build.VERSION.SDK_INT>=33?result.getParcelableExtra(Intent.EXTRA_INTENT,Intent.class):result.getParcelableExtra(Intent.EXTRA_INTENT);
            if(pending==null){abandonSession();installCallback=null;fail("UPDATE_INSTALL_FAILED");return;}
            confirmation=new Intent(pending);prefs.edit().putString("sessionState","confirmation").apply();phase="confirmation";error="";
            app.log("updates","UPDATE_INSTALL_CONFIRMATION_REQUIRED");notifyReady(ready,true);
            java.util.function.Consumer<Intent> callback=installCallback;installCallback=null;
            if(callback!=null)main.post(()->callback.accept(new Intent(pending)));
        }else if(status==PackageInstaller.STATUS_SUCCESS){
            clearSession();ready=null;available=null;phase="current";error="";app.log("updates","UPDATE_INSTALLED");
        }else{
            clearSession();installCallback=null;phase="ready";
            error=switch(status){case PackageInstaller.STATUS_FAILURE_ABORTED->"UPDATE_INSTALL_CANCELLED";case PackageInstaller.STATUS_FAILURE_BLOCKED->"UPDATE_INSTALL_BLOCKED";case PackageInstaller.STATUS_FAILURE_STORAGE->"UPDATE_STORAGE_ERROR";default->"UPDATE_INSTALL_FAILED";};
            app.log("updates",error);
        }
        app.changed();
    }
    private void clearSession(){confirmation=null;prefs.edit().remove("sessionId").remove("sessionVersion").remove("sessionState").remove("sessionAt").apply();}
    private void abandonSession(){int id=prefs.getInt("sessionId",-1);if(id!=-1)try{app.getPackageManager().getPackageInstaller().abandonSession(id);}catch(Exception ignored){}clearSession();}
    /** Provider invocation independently validates every read after process restoration. */
    File fileForRead() throws IOException {
        UpdateManifest release = ready;
        if (release == null) {
            release = cachedManifest();
            if (release == null) throw new FileNotFoundException("UPDATE_NOT_READY");
        }
        File file = apkFile(release); verify(release, file); return file;
    }
    private boolean due() {
        long last = prefs.getLong("lastChecked", 0), now = System.currentTimeMillis();
        return last == 0 || now < last || now - last >= INTERVAL;
    }
    private void schedule() {
        JobScheduler jobs = app.getSystemService(JobScheduler.class);
        if (jobs == null) return;
        if (!automaticChecks()) { jobs.cancel(JOB_ID); return; }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(app, UpdateJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(INTERVAL).setPersisted(true).build();
        jobs.schedule(job);
    }
    private interface Work { void run() throws Exception; }
    private boolean enqueue(String operationName, String initialPhase, Work work, Runnable completed) {
        if (!busy.compareAndSet(false, true)) {
            // A JobService must remain alive until any in-progress app-start check/download completes.
            if (completed != null) executor.execute(() -> main.post(completed));
            else { actionNotice="UPDATE_BUSY";app.log("updates","UPDATE_ACTION_BUSY");app.changed(); }
            return false;
        }
        cancelRequested=false;
        operation=operationName;operationStartedAt=System.currentTimeMillis();operationStartedElapsed=SystemClock.elapsedRealtime();operationFinishedElapsed=0;
        actionNotice="";route="not-started";
        if(initialPhase!=null){phase=initialPhase;error="";}
        app.log("updates",switch(operationName){case "check"->"UPDATE_CHECK_STARTED";case "download"->"UPDATE_DOWNLOAD_STARTED";case "install"->"UPDATE_INSTALL_PREPARING";default->"UPDATE_BACKGROUND_STARTED";});
        app.changed();
        executor.execute(() -> {
            worker=Thread.currentThread();
            try { if(cancelRequested)throw new UpdateDownload.UpdateException("UPDATE_CANCELLED"); work.run(); }
            catch (UpdateDownload.UpdateException failure) { fail(failure.code); }
            catch (Exception failure) { fail("UPDATE_STORAGE_ERROR"); }
            finally {
                worker=null;Thread.interrupted();operationFinishedElapsed=SystemClock.elapsedRealtime();busy.set(false);
                app.log("updates",switch(operationName){case "check"->"UPDATE_CHECK_FINISHED";case "download"->"UPDATE_DOWNLOAD_FINISHED";case "install"->"UPDATE_INSTALL_PREPARATION_FINISHED";default->"UPDATE_BACKGROUND_FINISHED";});
                app.changed(); if (completed != null) main.post(completed);
            }
        });
        return true;
    }
    private void check(boolean allowAutomaticDownload) throws Exception {
        if (!expectedPackageName.equals(app.getPackageName())) throw new UpdateDownload.UpdateException("UPDATE_PREVIEW_BUILD");
        phase = "checking"; error = ""; prefs.edit().putLong("lastChecked", System.currentTimeMillis()).apply(); app.changed();
        UpdateManifest release = downloader.fetchManifest(feedUrl(), expectedPackageName);
        prefs.edit().putLong("lastCheckSucceededAt",System.currentTimeMillis()).apply();
        if (!release.isNewerThan(BuildConfig.VERSION_CODE)) {
            UpdateManifest old=available;if(old!=null)downloader.discardResume(old,directory);
            available = null; ready = null; prefs.edit().remove("manifest").remove("manifestFeed").apply();
            progress=null;
            phase = "current"; app.log("updates", "UPDATE_CURRENT"); app.changed(); return;
        }
        if (!release.supportsSdk(Build.VERSION.SDK_INT)) throw new UpdateDownload.UpdateException("UPDATE_ANDROID_UNSUPPORTED");
        UpdateManifest previousAvailable=available,previousReady=ready;
        if(previousAvailable!=null&&!sameRelease(previousAvailable,release))downloader.discardResume(previousAvailable,directory);
        if(!prefs.edit().putString("manifest", json(release).toString()).putString("manifestFeed", release.manifestUrl).commit())
            throw new UpdateDownload.UpdateException("UPDATE_STORAGE_ERROR");
        available = release;
        if(previousAvailable!=null&&!previousAvailable.sha256.equals(release.sha256)){
            pausedReleaseHash="";prefs.edit().remove("pausedReleaseHash").apply();
        }
        if(previousReady==null||!sameRelease(previousReady,release))ready=null;
        if (ready != null) {
            try{verify(ready, apkFile(ready));setProgress(release,release.sizeBytes);phase = sessionPhase();}
            catch(Exception failure){ready=null;setProgress(release,downloader.resumableBytes(release,directory));throw failure;}
        }else{
            long restored=downloader.resumableBytes(release,directory);setProgress(release,restored);
            phase=isUserPaused(release)?"paused":restored>0?"interrupted":"available";
        }
        app.log("updates", "UPDATE_AVAILABLE"); app.changed();
        if (ready == null && !isUserPaused(release)&&allowAutomaticDownload && wifiDownloads() && onWifi()) download();
    }
    private long operationElapsedMs(){
        if(operationStartedAt==0)return 0;
        long end=operationFinishedElapsed==0?SystemClock.elapsedRealtime():operationFinishedElapsed;
        return Math.max(0,end-operationStartedElapsed);
    }
    private String feedHost(){
        try{
            String host=java.net.URI.create(feedUrl()).getHost();
            return host!=null&&host.matches("[A-Za-z0-9.-]{1,253}")?host.toLowerCase(Locale.ROOT):"invalid";
        }catch(IllegalArgumentException ignored){return "invalid";}
    }
    private synchronized void recordRoute(String value){
        if(!Set.of("direct","vpn","vpn-unavailable").contains(value)||value.equals(route))return;
        route=value;app.log("updates",switch(value){case "vpn"->"UPDATE_ROUTE_VPN";case "direct"->"UPDATE_ROUTE_DIRECT";default->"UPDATE_ROUTE_UNAVAILABLE";});app.changed();
    }
    private void download() throws Exception {
        UpdateManifest release = available;
        if (release == null || !release.isNewerThan(BuildConfig.VERSION_CODE)) throw new UpdateDownload.UpdateException("UPDATE_NOT_AVAILABLE");
        // A fresh attempt starts its throughput clock after restored bytes have been counted.
        UpdateProgress attempt=setProgress(release,downloader.resumableBytes(release,directory));
        downloadingRelease=release;userPauseRequested=false;
        phase = "downloading"; error = ""; app.changed();
        try{
            File file = downloader.download(release,directory,(downloaded,total,connections)->downloadProgress(release,attempt,downloaded,total,connections));
            phase="verifying";app.changed();
            verify(release,file);
            // Successful core downloads remove their resume state; this idempotent call also
            // handles a retained completed cache from a previous interrupted validation pass.
            downloader.discardResume(release,directory);
            if(!userPauseRequested)clearUserPause(release);
            cleanup(file);
            // Publish ready only after cleanup has retained the complete file. A cached byte
            // counter describes the transfer and cannot prove that the installable file exists.
            if(!file.isFile())throw new UpdateDownload.UpdateException("UPDATE_LOCAL_APK_MISSING");
            if(file.length()!=release.sizeBytes)throw new UpdateDownload.UpdateException("UPDATE_LOCAL_APK_INVALID");
            ready=release;phase="ready";setProgress(release,release.sizeBytes);
            app.log("updates","UPDATE_READY");notifyReady(release,false);app.changed();
        }catch(Exception failure){
            long restored=ready!=null&&sameRelease(ready,release)?release.sizeBytes:downloader.resumableBytes(release,directory);setProgress(release,restored);
            if(userPauseRequested||isUserPaused(release)){
                Thread.interrupted();
                if(!prefs.edit().putString("pausedReleaseHash",release.sha256).commit())app.log("updates","UPDATE_STORAGE_ERROR");
                error="";phase=ready!=null?"ready":"paused";app.log("updates","UPDATE_DOWNLOAD_PAUSED");
            }else{
                String code=failure instanceof UpdateDownload.UpdateException?((UpdateDownload.UpdateException)failure).code:"UPDATE_STORAGE_ERROR";
                error=code;phase=ready!=null?sessionPhase():restored>0?"interrupted":"error";app.log("updates",code);
            }
            app.changed();
        }finally{downloadingRelease=null;}
    }
    private UpdateProgress setProgress(UpdateManifest release,long restored){
        UpdateProgress metrics=new UpdateProgress(release.sizeBytes,Math.max(0,Math.min(release.sizeBytes,restored)),System.nanoTime());
        progress=new TransferProgress(release,metrics);
        synchronized(progressNotifications){lastProgressNotificationNanos=System.nanoTime()-250_000_000L;lastPublishedDownloaded=restored;}
        return metrics;
    }
    private void downloadProgress(UpdateManifest release,UpdateProgress attempt,long downloaded,long total,int connections){
        TransferProgress current=progress;
        if(downloadingRelease!=release||current==null||attempt!=current.metrics)return;
        long now=System.nanoTime();
        boolean restarted=downloaded==0&&connections==0&&total==attempt.totalBytes&&attempt.snapshot(now,false).downloadedBytes>0;
        if(restarted)attempt.restart(now);
        if(!attempt.update(downloaded,total,connections))return;
        boolean notify=false;
        synchronized(progressNotifications){
            if(restarted||now-lastProgressNotificationNanos>=250_000_000L||(downloaded==total&&lastPublishedDownloaded!=total)){
                lastProgressNotificationNanos=now;lastPublishedDownloaded=downloaded;notify=true;
            }
        }
        if(restarted)app.log("updates","UPDATE_DOWNLOAD_RESTARTED");
        if(notify)app.changed();
    }
    private boolean onWifi() {
        ConnectivityManager network = app.getSystemService(ConnectivityManager.class);
        if (network == null) return false;
        Network active=network.getActiveNetwork();
        NetworkCapabilities caps=active==null?null:network.getNetworkCapabilities(active);
        // Only the app's active transport qualifies. If another VPN hides its underlay, keep the
        // release available for a manual download rather than infer Wi-Fi from an unused network.
        return caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                &&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                &&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                &&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }
    private void restore() {
        try {
            if(prefs.getInt("sessionVersion",0)<=BuildConfig.VERSION_CODE)clearSession();
            UpdateManifest release = storedManifest();
            if (release == null) { cleanup(null); return; }
            if(!release.isNewerThan(BuildConfig.VERSION_CODE)||!release.supportsSdk(Build.VERSION.SDK_INT)){
                downloader.discardResume(release,directory);cleanup(null);
                prefs.edit().remove("manifest").remove("manifestFeed").remove("pausedReleaseHash").apply();pausedReleaseHash="";return;
            }
            available = release;
            File file = apkFile(release);
            if (file.isFile()) {
                verify(release, file);cleanup(file);
                if(!file.isFile())throw new UpdateDownload.UpdateException("UPDATE_LOCAL_APK_MISSING");
                if(file.length()!=release.sizeBytes)throw new UpdateDownload.UpdateException("UPDATE_LOCAL_APK_INVALID");
                ready = release;setProgress(release,release.sizeBytes);phase = sessionPhase();
            }
            else {
                long restored=downloader.resumableBytes(release,directory);setProgress(release,restored);
                phase=isUserPaused(release)?"paused":restored>0?"interrupted":"available";cleanup(null);
            }
        } catch (Exception failure) {
            // A temporary storage/PackageManager error must not erase a previously valid APK.
            ready=null;
            if(available!=null)setProgress(available,downloader.resumableBytes(available,directory));
            fail("UPDATE_CACHE_INVALID");
        }
        app.changed();
    }
    private String sessionPhase(){
        int id=prefs.getInt("sessionId",-1);if(id==-1)return "ready";
        if(prefs.getInt("sessionVersion",0)<=BuildConfig.VERSION_CODE||app.getPackageManager().getPackageInstaller().getSessionInfo(id)==null){clearSession();return "ready";}
        return "confirmation".equals(prefs.getString("sessionState",""))?"confirmation":"installing";
    }
    private UpdateManifest cachedManifest() throws UpdateDownload.UpdateException {
        UpdateManifest release=storedManifest();
        return release!=null&&release.isNewerThan(BuildConfig.VERSION_CODE)&&release.supportsSdk(Build.VERSION.SDK_INT)?release:null;
    }
    private UpdateManifest storedManifest() throws UpdateDownload.UpdateException {
        String raw = prefs.getString("manifest", ""), feed = prefs.getString("manifestFeed", "");
        if (raw.isEmpty() || !feed.equals(feedUrl())) return null;
        return UpdateManifest.parse(raw, feed, UpdateIdentity.PACKAGE_NAME);
    }
    private File apkFile(UpdateManifest release) throws IOException {
        return UpdateCache.apk(directory,release.versionCode);
    }
    private File verifyReadyForInstall(UpdateManifest release) throws IOException {
        File file=apkFile(release);
        String invalidCode="";
        if(!file.isFile())invalidCode="UPDATE_LOCAL_APK_MISSING";
        else try{verify(release,file);return file;}
        catch(UpdateDownload.UpdateException failure){
            if(Set.of("UPDATE_RESPONSE_SIZE","UPDATE_HASH_MISMATCH","UPDATE_INVALID_APK","UPDATE_PACKAGE_MISMATCH","UPDATE_SIGNER_MISMATCH","UPDATE_VERSION_MISMATCH","UPDATE_ANDROID_UNSUPPORTED").contains(failure.code))
                invalidCode="UPDATE_LOCAL_APK_INVALID";
            else throw failure;
        }
        // A ready flag must never outlive the file that passed verification. Preserve the public
        // manifest so Download becomes available without depending on another server check.
        if(ready!=null&&sameRelease(ready,release))ready=null;
        abandonSession();installCallback=null;
        try{UpdateCache.discard(directory,file);}catch(IOException ignored){}
        try{downloader.discardResume(release,directory);}catch(UpdateDownload.UpdateException ignored){}
        setProgress(release,downloader.resumableBytes(release,directory));
        pausedReleaseHash="";
        prefs.edit().remove("pausedReleaseHash").remove("automaticAttemptVersion").remove("notifiedVersion").apply();
        NotificationManager notifications=app.getSystemService(NotificationManager.class);if(notifications!=null)notifications.cancel(NOTIFICATION_ID);
        throw new UpdateDownload.UpdateException(invalidCode);
    }
    private void verify(UpdateManifest release, File file) throws IOException {
        if (!file.isFile() || file.length() != release.sizeBytes) throw new UpdateDownload.UpdateException("UPDATE_RESPONSE_SIZE");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] bytes = new byte[64 * 1024]; int read; long size = 0;
                while ((read = input.read(bytes)) != -1) { size += read; if (size > release.sizeBytes) throw new UpdateDownload.UpdateException("UPDATE_RESPONSE_SIZE"); digest.update(bytes, 0, read); }
                if (size != release.sizeBytes) throw new UpdateDownload.UpdateException("UPDATE_RESPONSE_SIZE");
            }
            if (!hex(digest.digest()).equals(release.sha256)) throw new UpdateDownload.UpdateException("UPDATE_HASH_MISMATCH");
            PackageManager manager = app.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo installed = manager.getPackageInfo(app.getPackageName(), flags);
            PackageInfo archive = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
            if (archive == null || archive.applicationInfo == null) throw new UpdateDownload.UpdateException("UPDATE_INVALID_APK");
            UpdateIdentity.verify(release, installed.packageName, versionCode(installed), archive.packageName,
                    versionCode(archive), archive.versionName, archive.applicationInfo.minSdkVersion,
                    Build.VERSION.SDK_INT, signers(installed), signers(archive));
        } catch (UpdateDownload.UpdateException failure) { throw failure; }
        catch (Exception failure) { throw new UpdateDownload.UpdateException("UPDATE_INVALID_APK"); }
    }
    @SuppressWarnings("deprecation") private static long versionCode(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
    @SuppressWarnings("deprecation") private static Set<String> signers(PackageInfo info) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 ? info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners() : info.signatures;
        Set<String> result = new HashSet<>();
        if (signatures != null) for (Signature signature : signatures) result.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        return result;
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(); for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 255)); return out.toString();
    }
    private static boolean sameRelease(UpdateManifest a, UpdateManifest b) {
        return a.versionCode==b.versionCode&&a.sha256.equals(b.sha256)&&a.sizeBytes==b.sizeBytes&&a.minSdk==b.minSdk
                &&a.packageName.equals(b.packageName)&&a.versionName.equals(b.versionName)&&a.apkUrl.equals(b.apkUrl)&&a.manifestUrl.equals(b.manifestUrl);
    }
    private static JSONObject json(UpdateManifest value) throws JSONException {
        return new JSONObject().put("schemaVersion", value.schemaVersion).put("packageName", value.packageName)
                .put("versionCode", value.versionCode).put("versionName", value.versionName).put("minSdk", value.minSdk)
                .put("apkUrl", value.apkUrl).put("sha256", value.sha256).put("sizeBytes", value.sizeBytes)
                .put("notes", value.notes).put("publishedAt", value.publishedAt);
    }
    private void cleanup(File keep) {
        // Multipart files and their durable metadata belong exclusively to UpdateDownload.
        try{UpdateCache.cleanup(directory,keep);}catch(IOException ignored){app.log("updates","UPDATE_CACHE_CLEANUP_FAILED");}
    }
    private void fail(String code) {
        error=code;
        if(ready!=null){if(!Set.of("installing","confirmation").contains(phase))phase="ready";}
        else if(isUserPaused(available))phase="paused";
        else if(progress!=null&&progress.metrics.snapshot(System.nanoTime(),false).downloadedBytes>0)phase="interrupted";
        else phase="error";
        app.log("updates",code);app.changed();
    }
    private void notifyReady(UpdateManifest release,boolean confirmationRequired) {
        if(release==null)return;
        if (!confirmationRequired&&prefs.getInt("notifiedVersion", 0) >= release.versionCode) return;
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager notifications = app.getSystemService(NotificationManager.class); if (notifications == null) return;
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(app, NOTIFICATION_ID, new Intent(app, MainActivity.class)
                .putExtra("openUpdates", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_autopilot)
                .setContentTitle("Autopilot " + release.versionName + (confirmationRequired?" needs confirmation":" is ready"))
                .setContentText(confirmationRequired?"Tap to confirm the update in Autopilot.":"Tap to review the update and installation options.").setContentIntent(open).setAutoCancel(true).build();
        notifications.notify(NOTIFICATION_ID, notification); prefs.edit().putInt("notifiedVersion", release.versionCode).apply();
    }
    public static String message(String code) { return switch (code) {
        case "" -> "";
        case "UPDATE_BUSY" -> "An update operation is already running.";
        case "UPDATE_HTTP_ERROR" -> "The update server returned an error. Check the update-server address or try again later.";
        case "UPDATE_NETWORK_ERROR" -> "Could not reach the update server. Check your connection and try again.";
        case "UPDATE_TIMEOUT" -> "The update request timed out. Tap Check now to retry.";
        case "UPDATE_VPN_ROUTE_UNAVAILABLE" -> "The VPN has no verified update route yet. Wait for a working node, then tap Check now.";
        case "UPDATE_VPN_PROXY_ERROR" -> "The update request could not pass through the current VPN node. Try another working node, then check again.";
        case "UPDATE_CANCELLED" -> "The download was interrupted. Saved parts will be reused when it resumes.";
        case "UPDATE_RANGE_INVALID" -> "The server returned an invalid download range. Try downloading again.";
        case "UPDATE_RESPONSE_ENCODING" -> "The server changed the APK encoding. Autopilot kept it from being installed.";
        case "UPDATE_DOWNLOAD_BUSY" -> "This update already has a download running. Wait for it to finish or pause.";
        case "UPDATE_CACHE_ERROR" -> "The download cache could not be written. Check available device storage.";
        case "UPDATE_ANDROID_UNSUPPORTED" -> "This release requires a newer Android version.";
        case "UPDATE_PREVIEW_BUILD" -> "Preview builds use a separate app identity. Install a signed release APK for updates.";
        case "UPDATE_PACKAGE_MISMATCH", "UPDATE_SIGNER_MISMATCH", "UPDATE_HASH_MISMATCH", "UPDATE_VERSION_MISMATCH", "UPDATE_INVALID_APK" -> "The update failed identity or integrity verification. It will not be installed.";
        case "UPDATE_NOT_READY", "UPDATE_NOT_AVAILABLE" -> "Check for an update and download it first.";
        case "UPDATE_INSTALL_PERMISSION_REQUIRED" -> "Allow updates from Autopilot in Android settings.";
        case "UPDATE_INSTALL_CANCELLED" -> "Installation was cancelled. The verified update is kept for retry.";
        case "UPDATE_INSTALL_BLOCKED" -> "Android blocked installation. Tap Install update to retry with its confirmation screen.";
        case "UPDATE_INSTALL_FAILED" -> "Android could not install the update. Tap Install update to retry.";
        case "UPDATE_UNSAFE_URL", "UPDATE_INVALID_MANIFEST", "UPDATE_RESPONSE_SIZE", "UPDATE_REDIRECT_LIMIT" -> "The update server returned an invalid response.";
        case "UPDATE_CACHE_INVALID" -> "A cached update could not be verified. Check again to download it.";
        case "UPDATE_LOCAL_APK_MISSING" -> "The downloaded APK is no longer on this device. Tap Download update to download it again.";
        case "UPDATE_LOCAL_APK_INVALID" -> "The saved APK no longer passes verification. Tap Download update to download it again.";
        default -> "The update could not complete. Check device storage and try again.";
    }; }
}
