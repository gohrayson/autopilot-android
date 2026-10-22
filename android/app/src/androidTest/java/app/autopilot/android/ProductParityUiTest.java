package app.autopilot.android;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import io.github.oviron.libmihomo.Clash;
import io.github.oviron.libmihomo.InvokeInterface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real settings controls on an account-free emulator; never starts VPN/provider work. */
@RunWith(AndroidJUnit4.class)
public final class ProductParityUiTest {
    @Test(timeout=120000) public void sharedSettingsRoutingAndDiagnosticsHaveWorkingControls()throws Exception{
        Instrumentation i=InstrumentationRegistry.getInstrumentation();Context context=i.getTargetContext();AutopilotApp app=(AutopilotApp)context.getApplicationContext();
        assertNull("Use isolated emulator",app.engine);assertTrue("Use account-free emulator",app.providers.nodes().isEmpty());
        assertTrue("Pinned database must be installed and verified at application startup",AndroidRouting.geoAvailable(context));
        verifyNativeRouting(context);
        JSONObject prior=app.settings(),state=new JSONObject(app.state().toString());boolean automatic=app.updates.automaticChecks(),wifi=app.updates.wifiDownloads(),install=app.updates.automaticInstalls();
        MainActivity activity=null;AlertDialog selection=null,routing=null;
        try{
            app.updates.preferences(false,wifi,false);app.setSettings(ProductContract.defaults());
            activity=(MainActivity)i.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));i.waitForIdleSync();
            MainActivity current=activity;
            selection=dialog(i,current,"settingsDialog");AlertDialog selected=selection;
            List<CheckBox> checks=views(selected.getWindow().getDecorView(),CheckBox.class);assertEquals(ProductContract.services().size(),checks.size());
            List<EditText> fields=views(selected.getWindow().getDecorView(),EditText.class);assertEquals(8,fields.size());
            i.runOnMainSync(()->{for(CheckBox check:checks)check.setChecked(false);selected.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});i.waitForIdleSync();
            assertTrue("Invalid empty services keep editor open",selected.isShowing());assertEquals(ProductContract.services(),new ArrayList<>(ProductSettings.strings(app.settings().optJSONArray("required"))));
            i.runOnMainSync(()->{checks.get(0).setChecked(true);selected.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});i.waitForIdleSync();
            assertFalse(selected.isShowing());assertEquals(1,app.settings().getJSONArray("required").length());
            routing=dialog(i,current,"splitDialog");AlertDialog split=routing;List<EditText> websites=views(split.getWindow().getDecorView(),EditText.class);assertEquals(2,websites.size());
            i.runOnMainSync(()->{websites.get(0).setText("test.example");websites.get(1).setText("https://test.example/");split.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});i.waitForIdleSync();
            assertTrue("Conflicting routes do not partially save",split.isShowing());assertEquals(0,app.settings().getJSONArray("directDomains").length());
            i.runOnMainSync(()->{websites.get(1).setText("proxy.example");split.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});i.waitForIdleSync();assertFalse(split.isShowing());
            assertEquals("test.example",app.settings().getJSONArray("directDomains").getString(0));assertEquals("proxy.example",app.settings().getJSONArray("proxyDomains").getString(0));
            Switch global=(Switch)field(current,"globalMode");i.runOnMainSync(()->global.setChecked(true));i.waitForIdleSync();assertTrue(app.settings().getBoolean("globalMode"));
            app.setSettings(new JSONObject().put("paused",true));i.waitForIdleSync();assertEquals("Resume Auto",((Button)field(current,"auto")).getText().toString());
            assertTrue(((TextView)field(current,"routingStatus")).getText().toString().contains("Global"));assertTrue(app.diagnostics().contains("Auto paused: true"));
            JSONObject account=new JSONObject().put("username","fixture@example.invalid").put("connected",true).put("paid",true).put("subscriptionEnd",System.currentTimeMillis()+86400000).put("lastRefresh",System.currentTimeMillis()).put("phase","ready").put("nodeCount",47);
            String metadata=MainActivity.accountSummary(account,System.currentTimeMillis());assertTrue(metadata.contains("Paid subscription"));assertTrue(metadata.contains("Servers refreshed:"));assertTrue(MainActivity.importSummary(new JSONObject().put("nodeCount",2).put("phase","ready")).contains("2 imported nodes"));
            assertNull("UI must not start VPN",app.engine);assertTrue("UI must not call provider APIs",app.providers.nodes().isEmpty());
            System.out.println("AUTOPILOT_PRODUCT_PARITY_UI_ACCEPTED settingsAtomic=true requiredServices=true websiteConflictGuard=true globalMode=true pauseState=true providerMetadata=true importedStatus=true noVpnMutation=true pinnedGeo=true nativeRuleAndGlobalAccepted=true");
        }finally{
            if(selection!=null){AlertDialog close=selection;i.runOnMainSync(close::dismiss);}if(routing!=null){AlertDialog close=routing;i.runOnMainSync(close::dismiss);}
            if(activity!=null){MainActivity close=activity;i.runOnMainSync(close::finish);i.waitForIdleSync();}app.setSettings(prior);app.publish(state);app.updates.preferences(automatic,wifi,install);
        }
    }
    private static void verifyNativeRouting(Context context)throws Exception{
        File home=new File(context.getFilesDir(),"product-routing-probe-"+Long.toUnsignedString(System.nanoTime()));assertTrue(home.mkdir());boolean loaded=false;
        try{
            Files.copy(new File(context.getFilesDir(),"core/geoip.metadb").toPath(),new File(home,"geoip.metadb").toPath());
            Clash.INSTANCE.load(context.getApplicationInfo().nativeLibraryDir);Clash.INSTANCE.assertReady();loaded=true;
            for(boolean global:new boolean[]{false,true}){
                JSONObject settings=ProductSettings.merge(null,new JSONObject().put("globalMode",global).put("directDomains",new JSONArray(List.of("direct.example"))).put("proxyDomains",new JSONArray(List.of("proxy.example"))));
                JSONObject config=VpnEngine.configuration(List.of(),new HashMap<>(),CoreDnsConfig.DEFAULT,settings,true);
                assertEquals(!global,config.getJSONArray("rules").toString().contains("GEOIP,CN,DIRECT"));assertFalse(config.getJSONObject("tun").getBoolean("enable"));
                try(FileOutputStream output=new FileOutputStream(new File(home,"config.yaml"))){output.write(CoreConfigEncoding.encode(config));output.getFD().sync();}
                CompletableFuture<String> completion=new CompletableFuture<>();
                JSONObject init=new JSONObject().put("home-dir",home.getAbsolutePath()).put("version",android.os.Build.VERSION.SDK_INT),setup=new JSONObject().put("selected-map",new JSONObject().put("AUTOPILOT","REJECT"));
                Clash.INSTANCE.quickSetup(init.toString(),setup.toString(),(InvokeInterface)completion::complete);
                assertEquals("Actual native routing rejected global="+global,"",completion.get(45,TimeUnit.SECONDS));
            }
        }finally{if(loaded)Clash.INSTANCE.stopTun();assertEquals(context.getFilesDir().getCanonicalFile(),home.getCanonicalFile().getParentFile());File[] files=home.listFiles();if(files!=null)for(File file:files)if(file.isFile())file.delete();home.delete();}
    }
    private static AlertDialog dialog(Instrumentation i,MainActivity activity,String name)throws Exception{Method method=MainActivity.class.getDeclaredMethod(name);method.setAccessible(true);AtomicReference<AlertDialog> result=new AtomicReference<>();i.runOnMainSync(()->{try{result.set((AlertDialog)method.invoke(activity));}catch(Exception failure){throw new AssertionError(failure);}});i.waitForIdleSync();return result.get();}
    private static Object field(MainActivity activity,String name)throws Exception{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(activity);}
    private static <T extends View>List<T> views(View view,Class<T> type){List<T> result=new ArrayList<>();if(type.isInstance(view))result.add(type.cast(view));if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int j=0;j<group.getChildCount();j++)result.addAll(views(group.getChildAt(j),type));}return result;}
}
