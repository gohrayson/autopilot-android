package app.autopilot.android;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real Check button, controller, downloader and Activity; only public HTTPS responses are injected. */
@RunWith(AndroidJUnit4.class)
public final class UpdateStatusUiTest {
    private static final String FEED="https://updates.example.org/private-fixture-path/latest.json";
    private static final String RAW_SECRET="provider-password-fixture-never-log";

    @Test(timeout=60000) public void checkClicksRenderProgressErrorsAndReleaseActions()throws Exception{
        try(Fixture fixture=new Fixture()){
            Gate timeout=fixture.transport.next("timeout",false);
            fixture.open();
            fixture.assertButtonColors("updateCheck",true,0xffedf2ff,0xff315fea);
            fixture.assertButtonColors("updateDownload",false,0xffe3e5e9,0xff8b919c);
            fixture.assertButtonColors("updateInstall",false,0xffe3e5e9,0xff8b919c);
            fixture.click("updateCheck");
            assertTrue(timeout.opened.await(5,TimeUnit.SECONDS));
            assertEquals("checking",fixture.manager.state().optString("phase"));
            assertTrue(fixture.manager.state().optBoolean("busy"));
            assertEquals("Checking…",fixture.text("updateCheck"));
            assertFalse(fixture.enabled("updateCheck"));
            assertFalse(fixture.enabled("updateDownload"));
            assertFalse(fixture.enabled("updateInstall"));
            fixture.assertButtonColors("updateCheck",false,0xffe3e5e9,0xff8b919c);
            fixture.assertButtonColors("updateInstall",false,0xffe3e5e9,0xff8b919c);
            fixture.assertSpinner(true);
            assertTrue(fixture.text("updateDialogStatus").contains("Checking for updates…"));
            assertTrue(fixture.text("updateActionHint").contains("Contacting updates.example.org"));
            assertFalse("A stale second action must be rejected rather than queued",fixture.manager.requestCheckNow());
            assertEquals("checking",fixture.manager.state().optString("phase"));
            assertEquals(1,fixture.transport.requests.get());
            assertTrue(fixture.manager.diagnostic().contains("Update busy: true / operation: check"));
            fixture.assertSafeDiagnostics();
            timeout.release.countDown();
            fixture.awaitIdle();
            assertEquals("UPDATE_TIMEOUT",fixture.manager.state().optString("error"));
            assertTrue(fixture.text("updateDialogStatus").contains("timed out"));
            assertTrue(fixture.text("updateDialogStatus").contains("UPDATE_TIMEOUT"));
            assertEquals("Check now",fixture.text("updateCheck"));
            assertTrue(fixture.enabled("updateCheck"));
            fixture.assertButtonColors("updateCheck",true,0xffedf2ff,0xff315fea);
            fixture.assertSpinner(false);

            fixture.transport.next("http",true);
            fixture.click("updateCheck");fixture.awaitIdle();
            assertEquals("UPDATE_HTTP_ERROR",fixture.manager.state().optString("error"));
            assertTrue(fixture.text("updateDialogStatus").contains("server returned an error"));
            fixture.transport.next("network",true);
            fixture.click("updateCheck");fixture.awaitIdle();
            assertEquals("UPDATE_NETWORK_ERROR",fixture.manager.state().optString("error"));
            assertTrue(fixture.text("updateDialogStatus").contains("Could not reach"));
            fixture.assertSafeDiagnostics();

            fixture.transport.next("success",true);
            fixture.click("updateCheck");fixture.awaitIdle();
            assertEquals("available",fixture.manager.state().optString("phase"));
            assertEquals("",fixture.manager.state().optString("error"));
            assertTrue(fixture.manager.state().optLong("lastCheckSucceededAt")>0);
            assertTrue(fixture.text("updateDialogStatus").contains("99.0.0-ui is available"));
            assertTrue(fixture.enabled("updateDownload"));
            assertFalse(fixture.enabled("updateInstall"));
            fixture.assertButtonColors("updateDownload",true,0xffedf2ff,0xff315fea);
            fixture.assertButtonColors("updateInstall",false,0xffe3e5e9,0xff8b919c);
            assertTrue(fixture.text("updateActionHint").contains("Tap Download update first"));
            assertEquals("Rejected stale action must not add a request",4,fixture.transport.requests.get());
            assertNull("The update UI must never start a VPN",fixture.app.engine);
            assertTrue("The update UI must never contact a provider",fixture.app.providers.nodes().isEmpty());
            System.out.println("AUTOPILOT_UPDATE_UI_ACCEPTED actual-check-click busy indeterminate timeout http network release-discovery gated-install safe-diagnostics single-flight");
        }
    }

    @Test(timeout=60000) public void readyUpdateSkipsAutomaticChecksAndKeepsInstallEnabled()throws Exception{
        try(Fixture fixture=new Fixture()){
            // This test seeds only the controller's already-verified state to isolate scheduling.
            // It never installs or bypasses the production APK/hash/signature verification paths.
            UpdateManifest release=UpdateManifest.parse(fixture.transport.manifest,FEED,fixture.context.getPackageName());
            put(fixture.manager,"available",release);put(fixture.manager,"ready",release);put(fixture.manager,"phase","ready");
            fixture.manager.preferences(true,false,false);
            fixture.open();
            fixture.manager.foregroundCheck();
            CountDownLatch completed=new CountDownLatch(1);
            fixture.manager.backgroundCheck(completed::countDown);
            assertTrue("A skipped JobService check must still complete",completed.await(5,TimeUnit.SECONDS));
            assertFalse(fixture.manager.state().optBoolean("busy"));
            assertEquals(0,fixture.transport.requests.get());
            assertTrue(fixture.enabled("updateInstall"));
            assertFalse(fixture.enabled("updateDownload"));
            fixture.assertButtonColors("updateInstall",true,0xff315fea,0xffffffff);
            fixture.assertButtonColors("updateDownload",false,0xffe3e5e9,0xff8b919c);
            assertTrue(fixture.text("updateActionHint").contains("Automatic checks wait"));

            fixture.transport.next("network",true);
            fixture.click("updateCheck");fixture.awaitIdle();
            assertEquals("UPDATE_NETWORK_ERROR",fixture.manager.state().optString("error"));
            assertEquals("ready",fixture.manager.state().optString("phase"));
            assertTrue("A failed manual check must keep the already-verified release",fixture.manager.state().optBoolean("ready"));
            assertTrue(fixture.enabled("updateInstall"));
            fixture.manager.foregroundCheck();
            fixture.manager.backgroundCheck(completed::countDown);
            fixture.instrumentation.waitForIdleSync();
            assertFalse(fixture.manager.state().optBoolean("busy"));
            assertEquals("Automatic checks must not fetch over the ready APK",1,fixture.transport.requests.get());
            assertNull(fixture.app.engine);
            System.out.println("AUTOPILOT_UPDATE_READY_SCHEDULING_ACCEPTED seeded-ready skips-foreground skips-background preserves-job-callback manual-failure-keeps-install no-install-invoked");
        }
    }

    @Test(timeout=60000) public void missingReadyApkInvalidatesCacheAndEnablesDownload()throws Exception{
        try(Fixture fixture=new Fixture()){
            UpdateManifest release=UpdateManifest.parse(fixture.transport.manifest,FEED,fixture.context.getPackageName());
            File cache=(File)get(fixture.manager,"directory");
            assertFalse("The fixture must start without its ready APK",UpdateCache.apk(cache,release.versionCode).exists());
            put(fixture.manager,"available",release);put(fixture.manager,"ready",release);put(fixture.manager,"phase","ready");
            Method progress=UpdateManager.class.getDeclaredMethod("setProgress",UpdateManifest.class,long.class);progress.setAccessible(true);progress.invoke(fixture.manager,release,release.sizeBytes);
            // The CI runner grants this before instrumentation starts. Changing the app-op here
            // causes Android to kill this app process and its instrumentation runner.
            assertTrue("Pre-grant installation permission on the fresh debug emulator before starting instrumentation",fixture.app.getPackageManager().canRequestPackageInstalls());
            java.util.List<Integer> sessions=sessionIds(fixture.app);
            fixture.open();
            assertTrue(fixture.enabled("updateInstall"));
            fixture.click("updateInstall");fixture.awaitIdle();
            JSONObject state=fixture.manager.state();
            assertEquals("UPDATE_LOCAL_APK_MISSING",state.optString("error"));
            assertFalse(state.optBoolean("ready"));assertTrue(state.optBoolean("available"));
            assertEquals(0,state.optLong("downloadedBytes"));assertEquals(0,state.optInt("downloadPercent"));
            assertTrue("The user can download again without another network check",fixture.enabled("updateDownload"));
            assertFalse(fixture.enabled("updateInstall"));
            assertTrue("Recovery appears next to the buttons",fixture.text("updateActionHint").contains("Tap Download update"));
            assertTrue(fixture.text("updateActionHint").contains("UPDATE_LOCAL_APK_MISSING"));
            assertEquals("Missing-file validation must precede PackageInstaller session creation",sessions,sessionIds(fixture.app));
            assertEquals("Local cache recovery must not contact a server",0,fixture.transport.requests.get());
            assertNull(fixture.app.engine);
            System.out.println("AUTOPILOT_MISSING_READY_APK_ACCEPTED actual-install-click local-missing-code ready-cleared progress-cleared download-enabled installer-not-created no-network");
        }
    }

    private static java.util.List<Integer> sessionIds(AutopilotApp app){
        java.util.List<Integer> ids=new java.util.ArrayList<>();for(android.content.pm.PackageInstaller.SessionInfo session:app.getPackageManager().getPackageInstaller().getMySessions())ids.add(session.getSessionId());java.util.Collections.sort(ids);return ids;
    }

    private static final class Fixture implements AutoCloseable{
        final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        final Context context=instrumentation.getTargetContext();
        final AutopilotApp app=(AutopilotApp)context.getApplicationContext();
        final UpdateManager original=app.updates;
        final SharedPreferences prefs=app.getSharedPreferences("updates",Context.MODE_PRIVATE);
        final Map<String,?> saved=new HashMap<>(prefs.getAll());
        final ScriptedTransport transport;
        final UpdateManager manager;
        MainActivity activity;
        Fixture()throws Exception{
            assertTrue("Only the account-free debug emulator may run this fixture",context.getPackageName().endsWith(".debug"));
            assertNull("Never replace a live VPN",app.engine);assertTrue(app.providers.nodes().isEmpty());
            UpdateStatusUiTest.awaitIdle(original);
            assertTrue(prefs.edit().clear().putBoolean("automaticChecks",false).putBoolean("wifiDownloads",false).putBoolean("automaticInstalls",false).commit());
            transport=new ScriptedTransport(context.getPackageName());
            manager=new UpdateManager(app,new UpdateDownload(transport),context.getPackageName());
            instrumentation.runOnMainSync(()->app.updates=manager);
            manager.setFeedUrl(FEED);
        }
        void open()throws Exception{
            activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            instrumentation.waitForIdleSync();
            Method method=MainActivity.class.getDeclaredMethod("updatesDialog");method.setAccessible(true);
            instrumentation.runOnMainSync(()->{try{method.invoke(activity);}catch(Exception failure){throw new AssertionError(failure);}});
            instrumentation.waitForIdleSync();
        }
        void click(String name)throws Exception{Button button=(Button)get(activity,name);instrumentation.runOnMainSync(()->assertTrue(button.performClick()));instrumentation.waitForIdleSync();}
        String text(String name)throws Exception{
            TextView view=(TextView)get(activity,name);AtomicReference<String> value=new AtomicReference<>();
            instrumentation.runOnMainSync(()->value.set(view.getText().toString()));return value.get();
        }
        boolean enabled(String name)throws Exception{
            View view=(View)get(activity,name);AtomicReference<Boolean> value=new AtomicReference<>();
            instrumentation.runOnMainSync(()->value.set(view.isEnabled()));return value.get();
        }
        void assertSpinner(boolean visible)throws Exception{
            ProgressBar spinner=(ProgressBar)get(activity,"updateProgressBar");
            instrumentation.runOnMainSync(()->{assertEquals(visible,spinner.getVisibility()==View.VISIBLE);if(visible)assertTrue(spinner.isIndeterminate());});
        }
        void assertButtonColors(String name,boolean enabled,int background,int text)throws Exception{
            Button button=(Button)get(activity,name);
            instrumentation.runOnMainSync(()->{
                assertEquals("Enabled state for "+name,enabled,button.isEnabled());
                android.content.res.ColorStateList tint=button.getBackgroundTintList();
                assertNotNull("Background tint for "+name,tint);assertTrue("Background must react to enabled state",tint.isStateful());
                assertEquals("Actual background tint for "+name,background,tint.getColorForState(button.getDrawableState(),tint.getDefaultColor()));
                assertTrue("Text color must react to enabled state",button.getTextColors().isStateful());
                assertEquals("Actual text color for "+name,text,button.getCurrentTextColor());
            });
        }
        void awaitIdle()throws Exception{UpdateStatusUiTest.awaitIdle(manager);instrumentation.waitForIdleSync();}
        void assertSafeDiagnostics(){String diagnostic=manager.diagnostic();assertTrue(diagnostic.contains("feed host: updates.example.org"));assertFalse(diagnostic.contains("private-fixture-path"));assertFalse(diagnostic.contains(RAW_SECRET));assertFalse(diagnostic.contains("https://"));}
        @Override public void close()throws Exception{
            transport.release();manager.cancelBackgroundWork();UpdateStatusUiTest.awaitIdle(manager);
            if(activity!=null){instrumentation.runOnMainSync(activity::finish);instrumentation.waitForIdleSync();}
            ((ExecutorService)get(manager,"executor")).shutdownNow();
            instrumentation.runOnMainSync(()->app.updates=original);
            SharedPreferences.Editor edit=prefs.edit().clear();
            for(Map.Entry<String,?> entry:saved.entrySet()){
                String key=entry.getKey();Object value=entry.getValue();
                if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
                else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Long)edit.putLong(key,(Long)value);
                else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof Set<?>)edit.putStringSet(key,copyStrings((Set<?>)value));
            }
            assertTrue(edit.commit());
        }
    }
    private static Set<String> copyStrings(Set<?> values){Set<String> copy=new java.util.HashSet<>();for(Object value:values)copy.add((String)value);return copy;}
    private static void awaitIdle(UpdateManager manager)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(manager.state().optBoolean("busy")&&System.nanoTime()<until)Thread.sleep(20);
        assertFalse("Update operation failed to finish",manager.state().optBoolean("busy"));
    }
    private static Object get(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void put(Object object,String name,Object value)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    private static final class Gate{
        final String mode;final CountDownLatch opened=new CountDownLatch(1),release=new CountDownLatch(1);
        Gate(String mode,boolean released){this.mode=mode;if(released)release.countDown();}
    }
    private static final class ScriptedTransport implements UpdateDownload.Transport{
        final String manifest;final AtomicInteger requests=new AtomicInteger();final AtomicReference<Gate> next=new AtomicReference<>();
        ScriptedTransport(String packageName)throws Exception{
            manifest=new JSONObject().put("schemaVersion",1).put("packageName",packageName).put("versionCode",2147483000)
                    .put("versionName","99.0.0-ui").put("minSdk",26).put("apkUrl","https://updates.example.org/fixture/app.apk")
                    .put("sha256","0000000000000000000000000000000000000000000000000000000000000000").put("sizeBytes",1024).toString();
        }
        Gate next(String mode,boolean released){Gate gate=new Gate(mode,released);next.set(gate);return gate;}
        void release(){Gate gate=next.get();if(gate!=null)gate.release.countDown();}
        @Override public UpdateDownload.Response open(URI uri)throws IOException{
            assertEquals(FEED,uri.toASCIIString());requests.incrementAndGet();Gate gate=next.get();assertNotNull(gate);gate.opened.countDown();
            byte[] bytes=manifest.getBytes(StandardCharsets.UTF_8);
            return new UpdateDownload.Response(){
                @Override public int status()throws IOException{
                    try{if(!gate.release.await(10,TimeUnit.SECONDS))throw new IOException("Fixture gate timed out");}
                    catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new InterruptedIOException();}
                    return switch(gate.mode){case "timeout"->throw new SocketTimeoutException(RAW_SECRET);case "network"->throw new IOException(RAW_SECRET);case "http"->503;default->200;};
                }
                @Override public long contentLength(){return bytes.length;}
                @Override public InputStream body(){return new ByteArrayInputStream(bytes);}
                @Override public void close(){gate.release.countDown();}
            };
        }
    }
}
