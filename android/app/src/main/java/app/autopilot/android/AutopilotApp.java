package app.autopilot.android;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AutopilotApp extends Application {
    public ProviderRepository providers;
    public UpdateManager updates;
    public volatile VpnEngine engine;
    public final ExecutorService providerExecutor = Executors.newSingleThreadExecutor();
    public volatile String providerBusy = "";
    private volatile JSONObject snapshot = new JSONObject();
    private volatile JSONObject networkTelemetry = new NetworkTelemetryState(null,true).json();
    private volatile String nodeFingerprint="";
    private volatile JSONObject lastCoreDiagnostic;
    private volatile JSONObject currentCoreDiagnostic;
    private SharedPreferences prefs;
    private volatile JSONObject productSettings;
    private volatile String appliedRouting;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean listenerNotificationQueued=new AtomicBoolean();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
    private ProviderWorkQueue providerJobs;
    private final AtomicBoolean recoverySettlementQueued=new AtomicBoolean();
    @Override public void onCreate() {
        super.onCreate();
        try(InputStream contract=getAssets().open("product-contract.json")){ProductContract.install(contract);}catch(Exception invalid){throw new IllegalStateException("Product contract is unavailable",invalid);}
        prefs = getSharedPreferences("preferences", MODE_PRIVATE);
        networkTelemetry=AndroidNetworkTelemetry.idleStatus(getSharedPreferences("network-telemetry",MODE_PRIVATE));
        GeoRouting.ensure(this);
        try{
            JSONObject saved=new JSONObject(prefs.getString("productSettings","{}"));
            if(!prefs.contains("productSettings")){
                saved.put("preferredRegions",new JSONArray(prefs.getString("preferred","[]")));
                Set<String> legacy=prefs.getStringSet("bypass",Set.of());
                if(!legacy.isEmpty())saved.put("splitMode","bypass").put("bypassApps",new JSONArray(legacy));
            }
            productSettings=ProductSettings.merge(null,saved);
        }catch(Exception invalid){productSettings=ProductSettings.merge(null,new JSONObject());log("settings","SAVED_SETTINGS_REJECTED");}
        try{String saved=prefs.getString("coreDiagnostic","");if(saved.length()<=8192&&!saved.isEmpty())lastCoreDiagnostic=CoreDiagnostics.restore(new JSONObject(saved));}catch(Exception ignored){}
        providers = new ProviderRepository(this);
        providerJobs=new ProviderWorkQueue(providerExecutor,()->engine,owner->owner instanceof VpnEngine&&((VpnEngine)owner).providerWorkAllowed(),owner->owner instanceof VpnEngine&&((VpnEngine)owner).accountRecoveryJobAllowed(),new ProviderWorkQueue.Work(){
            public void due(java.util.function.BooleanSupplier active){try{if(active.getAsBoolean())providers.refreshDue(preferred());}catch(Exception failure){log("provider","REFRESH_FAILED");}}
            public void catalogs(String reason,java.util.function.BooleanSupplier active){providers.refreshCatalogsForRecovery(reason,preferred(),active);}
            public void failed(List<String> names,java.util.function.BooleanSupplier active){try{if(active.getAsBoolean())providers.refreshFailedNodes(names,preferred());}catch(ProviderException failure){log("provider",failure.code);}}
        },this::changed,()->log("provider","RECOVERY_QUEUE_FAILED"));
        updates = new UpdateManager(this);
        updates.start();
        providers.onChanged = () -> {
            String fingerprint=String.join(",",providers.nodes().stream().map(n->n.id).collect(java.util.stream.Collectors.toList()));
            if(!fingerprint.equals(nodeFingerprint)){nodeFingerprint=fingerprint;VpnEngine e=engine;if(e!=null)e.nodesChanged();}
            changed();
        };
        ticker.scheduleWithFixedDelay(() -> {
            if (engine != null && providerBusy.isEmpty()) providerJobs.due();
        }, 30, 30, TimeUnit.SECONDS);
    }
    public List<String> preferred() {
        return new ArrayList<>(ProductSettings.strings(settings().optJSONArray("preferredRegions")));
    }
    public void setPreferred(List<String> regions) {
        try{setSettings(new JSONObject().put("preferredRegions",new JSONArray(new LinkedHashSet<>(regions))));}catch(JSONException invalid){throw new IllegalArgumentException(invalid);}
        if(providerJobs!=null)providerJobs.due();
    }
    public synchronized JSONObject settings(){try{return new JSONObject(productSettings.toString());}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
    public synchronized void setSettings(JSONObject patch){
        JSONObject next=ProductSettings.merge(productSettings,patch);
        if(next.toString().equals(productSettings.toString()))return;
        if(!prefs.edit().putString("productSettings",next.toString()).commit())throw new IllegalStateException("Android could not save settings");
        productSettings=next;log("settings","SETTINGS_UPDATED");VpnEngine current=engine;if(current!=null)current.settingsChanged();changed();
    }
    public Set<String> bypassPackages(){return ProductSettings.strings(settings().optJSONArray("bypassApps"));}
    public boolean routingRestartPending(){return engine!=null&&appliedRouting!=null&&!appliedRouting.equals(ProductSettings.routingIdentity(settings()));}
    void routingApplied(JSONObject value){appliedRouting=ProductSettings.routingIdentity(value);changed();}
    void routingReleased(){appliedRouting=null;changed();}
    public boolean vpnWanted(){return prefs.getBoolean("vpnWanted",false);}
    public void setVpnWanted(boolean wanted){prefs.edit().putBoolean("vpnWanted",wanted).apply();}
    public void setBypassPackages(Set<String> packages){try{setSettings(new JSONObject().put("bypassApps",new JSONArray(packages)).put("splitMode",packages.isEmpty()?"off":"bypass"));}catch(JSONException impossible){throw new IllegalArgumentException(impossible);}}
    public void publish(JSONObject state){
        snapshot=state;providers.currentNodeName=state.optString("current","");changed();
        VpnEngine owner=engine;
        if(owner!=null&&recoverySettlementQueued.compareAndSet(false,true))providerExecutor.execute(()->{
            try{
                if(engine!=owner||!owner.providerWorkAllowed())return;
                JSONObject current=snapshot;String name=current.optString("current");JSONArray rows=current.optJSONArray("nodes");
                if(rows!=null&&!name.isEmpty())for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&name.equals(row.optString("name"))&&row.optBoolean("qualified"))providers.recoveryQualified(row.optString("provider"),name,row.optLong("applicationCheckedAt"));}
            }finally{recoverySettlementQueued.set(false);}
        });
    }
    public void requestRecovery(List<String> names){
        if(providerJobs!=null)providerJobs.failed(names);
    }
    public void requestAccountRecovery(String reason){if(providerJobs!=null)providerJobs.catalogs(reason);}
    public void publishNetworkTelemetry(JSONObject telemetry){networkTelemetry=telemetry;changed();}
    public JSONObject state(){try{
        JSONObject result=new JSONObject(snapshot.toString());VpnEngine owner=engine;boolean active=owner!=null&&owner.providerWorkAllowed()&&!settings().optBoolean("paused")&&AccountRecoveryPolicy.enabled(settings());
        boolean healthy=false;String selected=result.optString("current");JSONObject watchdog=result.optJSONObject("watchdog");String code=watchdog==null?"":watchdog.optString("code");JSONArray rows=result.optJSONArray("nodes");
        if("connected".equals(result.optString("phase"))&&Set.of("HEALTHY","IDLE","CHECKING").contains(code)&&rows!=null&&!selected.isEmpty())for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&selected.equals(row.optString("name"))&&row.optBoolean("qualified"))healthy=true;}
        return result.put("network",new JSONObject(networkTelemetry.toString())).put("accountRecovery",AccountRecoveryStatus.lifecycle(providers==null?null:providers.recoveryStatus(),active,healthy,owner==null?0:owner.accountRecoveryPendingUntil(),System.currentTimeMillis()));
    }catch(JSONException impossible){throw new IllegalStateException(impossible);}}
    /** Persisted failures are history until a current run reports another rejection. */
    public JSONObject coreDiagnostic(){JSONObject saved=currentCoreDiagnostic;return saved==null?null:CoreDiagnostics.restore(saved);}
    public JSONObject historicalCoreDiagnostic(){JSONObject saved=lastCoreDiagnostic;return saved==null?null:CoreDiagnostics.restore(saved);}
    public void recordCoreAccepted(CoreDiagnostics.Stage stage){
        JSONObject current=currentCoreDiagnostic;
        if(stage!=CoreDiagnostics.Stage.BASELINE||current!=null&&"baseline".equals(current.optString("stage")))currentCoreDiagnostic=null;
    }
    public void recordCoreDiagnostic(CoreDiagnostics.Diagnostic diagnostic,CoreDiagnostics.Stage stage,int skippedCount){
        JSONObject safe=diagnostic.json();try{safe.put("stage",stage.value).put("at",System.currentTimeMillis()).put("skippedCount",Math.max(0,Math.min(ProfileValidation.MAX_DROPS,skippedCount)));}catch(JSONException ignored){}
        currentCoreDiagnostic=safe;lastCoreDiagnostic=safe;prefs.edit().putString("coreDiagnostic",safe.toString()).apply();
        log("core-"+stage.value,diagnostic.logCode());changed();
    }
    public void recordBaselineDiagnostic(JSONObject diagnostic){
        JSONObject safe=CoreDiagnostics.restore(diagnostic);
        if(safe==null||!"baseline".equals(safe.optString("stage")))return;
        try{safe.put("at",System.currentTimeMillis()).put("skippedCount",0);}catch(JSONException ignored){}
        currentCoreDiagnostic=safe;lastCoreDiagnostic=safe;prefs.edit().putString("coreDiagnostic",safe.toString()).apply();
        log("core-baseline",safe.optString("code"));changed();
    }
    public void changed(){
        UpdateManager updater=updates;if(updater!=null)updater.connectionStateChanged();
        // State and updater route guards change immediately. Coalesce only the
        // expensive main-thread views when eight native callbacks arrive together.
        if(listenerNotificationQueued.compareAndSet(false,true))main.post(()->{listenerNotificationQueued.set(false);for(Runnable listener:listeners)listener.run();});
    }
    public void listen(Runnable listener){listeners.addIfAbsent(listener);}
    public void unlisten(Runnable listener){listeners.remove(listener);}
    /** Callers pass fixed component/code identifiers, never exception text or configurations. */
    public synchronized void log(String component,String code){
        if(!component.matches("[A-Za-z0-9_-]{1,32}") || !code.matches("[A-Za-z0-9_.:-]{1,96}"))return;
        try {
            File file = new File(getFilesDir(),"diagnostics.log");
            if(file.length()>512*1024) { File prior=new File(getFilesDir(),"diagnostics.previous.log"); if(prior.exists())prior.delete(); file.renameTo(prior); }
            try(FileOutputStream out=new FileOutputStream(file,true)){out.write((Instant.now()+" "+component+" "+code+"\n").getBytes(StandardCharsets.UTF_8));}
        } catch(IOException ignored){}
    }
    public String diagnostics(){
        StringBuilder out=new StringBuilder("Autopilot Android "+BuildConfig.VERSION_NAME+"\nAndroid "+android.os.Build.VERSION.RELEASE+" / "+android.os.Build.SUPPORTED_ABIS[0]+"\n");
        JSONObject s=state(); out.append("VPN: ").append(s.optString("phase","idle")).append("\n");
        JSONObject settings=settings();out.append("Auto paused: ").append(settings.optBoolean("paused")).append("\nGlobal mode: ").append(settings.optBoolean("globalMode"))
            .append("\nSplit mode: ").append(settings.optString("splitMode")).append("\nApp routing restart pending: ").append(routingRestartPending()).append("\n");
        out.append("Required services: ").append(settings.optJSONArray("required")).append("\nSelection intervals: tick=").append(settings.optLong("tickMs")).append(" sweep=").append(settings.optLong("sweepMs")).append(" verify=").append(settings.optLong("verifyMs"))
            .append("\nSelection thresholds: maxLatency=").append(settings.optLong("maxLatencyMs")).append(" failures=").append(settings.optInt("failLimit")).append(" switchRatio=").append(settings.optDouble("switchRatio")).append(" dwell=").append(settings.optLong("minDwellMs")).append(" topK=").append(settings.optInt("checkTopK"))
            .append("\nRegional database verified: ").append(AndroidRouting.geoAvailable(this)).append("\n");
        out.append("VPN error: ").append(s.optString("error","")).append("\nBaseline accepted: ").append(s.optBoolean("coreBaselineAccepted"))
            .append("\nProfile accepted: ").append(s.optBoolean("coreProfileAccepted")).append("\nSkipped nodes: ").append(s.optInt("skippedCount")).append("\n");
        JSONObject core=coreDiagnostic();out.append("Current core diagnostic: ").append(core==null?"none":core.toString()).append("\n");
        JSONObject historical=historicalCoreDiagnostic();if(historical!=null)out.append("Last core failure (historical): ").append(historical).append("\n");
        out.append("Health check path: ").append(s.optString("checkPath","not started")).append("\n")
            .append("Other-app tunnel traffic: not verified by node checks\n")
            .append("Resolver policy: ").append(s.optString("resolverPolicy",CoreDnsConfig.POLICY_ID)).append("\n")
            .append("TUN stack: ").append(s.optString("tunStack","not started")).append("\n");
        JSONArray health=s.optJSONArray("nodeHealth");if(health!=null)out.append("Node health: ").append(health).append("\n");
        JSONObject network=s.optJSONObject("networkSignals");if(network!=null)out.append("Native network observations (session only, not per-node): ").append(network).append("\n");
        JSONObject watchdog=s.optJSONObject("watchdog");if(watchdog!=null)out.append("Active HTTPS watchdog: ").append(watchdog).append("\n");
        JSONObject discovery=s.optJSONObject("discovery");if(discovery!=null)out.append("Node discovery: ").append(discovery).append("\n");
        JSONObject recovery=s.optJSONObject("accountRecovery");if(recovery!=null)out.append("Account node recovery: ").append(recovery).append("\n")
            .append("Provider API path: direct HTTPS on excluded Autopilot UID; a working VPN node does not prove account API reachability\n");
        if(recovery!=null&&"idle".equals(recovery.optString("phase")))out.append("Last account recovery result (historical): ").append(AccountRecoveryStatus.safe(providers.recoveryStatus())).append("\n");
        JSONObject telemetry=s.optJSONObject("network");if(telemetry!=null){JSONObject exit=telemetry.optJSONObject("exit"),traffic=telemetry.optJSONObject("traffic");
            out.append("Exit measurement: ").append(exit==null?"STOPPED":exit.optString("code","STOPPED")).append("\n")
                .append("VPN traffic counters: ").append(traffic==null?"STOPPED":traffic.optString("code","STOPPED")).append("\n")
                .append("Recorded usage storage: ").append(traffic==null?"unavailable":traffic.optString("persistence","unavailable")).append("\n");}
        if(updates!=null)out.append(updates.diagnostic());
        JSONArray skipped=s.optJSONArray("skippedNodes");if(skipped!=null&&skipped.length()>0)out.append("Skipped diagnostics: ").append(skipped).append("\n");
        for(String p:List.of("onevpn","octohide","import")){
            JSONObject a=providers.status(p);out.append(p).append(": ").append(a.optString("phase")).append(" / ").append(a.optString("code")).append(" / ").append(a.optInt("nodeCount")).append(" nodes\n");
            out.append("Retry: ").append(a.optLong("retryAt")).append("\n");
            JSONObject last=a.optJSONObject("lastDiagnostic");
            if(last!=null)out.append("Last diagnostic: ").append(last).append("\n");
        }
        try { File f=new File(getFilesDir(),"diagnostics.log"); if(f.exists()) {byte[] b=java.nio.file.Files.readAllBytes(f.toPath());String text=new String(b,StandardCharsets.UTF_8);out.append(text.substring(Math.max(0,text.length()-48000)));} }catch(IOException ignored){}
        return out.toString();
    }
    public String events(){
        StringBuilder out=new StringBuilder();
        for(String name:List.of("diagnostics.previous.log","diagnostics.log"))try{
            File file=new File(getFilesDir(),name);if(file.isFile()){String text=new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);out.append(text.substring(Math.max(0,text.length()-48000)));}
        }catch(IOException ignored){}
        return out.length()==0?"No events recorded.":out.toString();
    }
}
