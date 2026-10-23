package app.autopilot.android;

import android.app.*;
import android.content.*;
import android.view.*;
import android.view.inspector.WindowInspector;
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
            verifySplitAppListEditing(i,current,app);
            Switch global=(Switch)field(current,"globalMode");i.runOnMainSync(()->global.setChecked(true));i.waitForIdleSync();assertTrue(app.settings().getBoolean("globalMode"));
            app.setSettings(new JSONObject().put("paused",true));i.waitForIdleSync();assertEquals("Resume Auto",((Button)field(current,"auto")).getText().toString());
            assertTrue(((TextView)field(current,"routingStatus")).getText().toString().contains("Global"));assertTrue(app.diagnostics().contains("Auto paused: true"));
            JSONObject account=new JSONObject().put("username","fixture@example.invalid").put("connected",true).put("paid",true).put("subscriptionEnd",System.currentTimeMillis()+86400000).put("lastRefresh",System.currentTimeMillis()).put("phase","ready").put("nodeCount",47);
            String metadata=MainActivity.accountSummary(account,System.currentTimeMillis());assertTrue(metadata.contains("Paid subscription"));assertTrue(metadata.contains("Servers refreshed:"));assertTrue(MainActivity.importSummary(new JSONObject().put("nodeCount",2).put("phase","ready")).contains("2 imported nodes"));
            assertNull("UI must not start VPN",app.engine);assertTrue("UI must not call provider APIs",app.providers.nodes().isEmpty());
            System.out.println("AUTOPILOT_PRODUCT_PARITY_UI_ACCEPTED settingsAtomic=true requiredServices=true websiteConflictGuard=true explicitAppModes=true stagedListCopy=true replaceConfirmation=true cancelUnchanged=true independentAppLists=true emptyDestinationCopy=true globalMode=true pauseState=true providerMetadata=true importedStatus=true noVpnMutation=true pinnedGeo=true nativeRuleAndGlobalAccepted=true");
        }finally{
            if(selection!=null){AlertDialog close=selection;i.runOnMainSync(close::dismiss);}if(routing!=null){AlertDialog close=routing;i.runOnMainSync(close::dismiss);}
            if(activity!=null){MainActivity close=activity;i.runOnMainSync(close::finish);i.waitForIdleSync();}app.setSettings(prior);app.publish(state);app.updates.preferences(automatic,wifi,install);
        }
    }
    private static void verifySplitAppListEditing(Instrumentation i,MainActivity activity,AutopilotApp app)throws Exception{
        String first="app.autopilot.fixture.first",second="app.autopilot.fixture.second",previous="app.autopilot.fixture.previous";
        List<String> source=List.of(first,second),oldDestination=List.of(previous);
        app.setSettings(new JSONObject().put("splitMode","only").put("onlyApps",new JSONArray(source)).put("bypassApps",new JSONArray(oldDestination)));
        String before=app.settings().toString();AlertDialog split=null;
        try{
            split=dialog(i,activity,"splitDialog");
            Spinner mode=views(split.getWindow().getDecorView(),Spinner.class).get(0);
            assertEquals(3,mode.getAdapter().getCount());assertEquals(activity.getString(R.string.split_mode_all),mode.getAdapter().getItem(0));assertEquals(activity.getString(R.string.split_mode_bypass),mode.getAdapter().getItem(1));assertEquals(activity.getString(R.string.split_mode_only),mode.getAdapter().getItem(2));assertEquals(2,mode.getSelectedItemPosition());
            assertSplitExplanation(split,activity,R.string.split_only_heading,R.string.split_only_hint);assertChecked(split,first,true);assertChecked(split,second,true);assertChecked(split,previous,false);
            selectSplitMode(i,split,1);assertSplitExplanation(split,activity,R.string.split_bypass_heading,R.string.split_bypass_hint);assertChecked(split,first,false);assertChecked(split,second,false);assertChecked(split,previous,true);
            assertEquals("Changing mode does not save or transfer either list",before,app.settings().toString());
            clickCopy(i,split,activity,R.string.split_copy_vpn_only);View confirmation=replacementWindow(i,activity);assertNotNull("Nonempty destination requires confirmation",confirmation);assertVisibleText(confirmation,activity.getString(R.string.split_replace_title));assertVisibleText(confirmation,activity.getString(R.string.split_replace_message,activity.getString(R.string.split_mode_only)));
            answerReplacement(i,activity,false);assertChecked(split,first,false);assertChecked(split,previous,true);assertEquals(before,app.settings().toString());
            clickCopy(i,split,activity,R.string.split_copy_vpn_only);answerReplacement(i,activity,true);
            assertChecked(split,first,true);assertChecked(split,second,true);assertChecked(split,previous,false);assertEquals("Copy remains staged until Save",before,app.settings().toString());
            assertEquals("Equal lists offer no redundant copy",View.GONE,copyButton(split,activity,R.string.split_copy_vpn_only).getVisibility());
            setAppChecked(i,split,first,false);selectSplitMode(i,split,2);assertChecked(split,first,true);assertChecked(split,second,true);assertChecked(split,previous,false);
            selectSplitMode(i,split,0);assertSplitExplanation(split,activity,R.string.split_all_heading,R.string.split_all_hint);assertTrue("All-app mode hides app checkboxes",views(split.getWindow().getDecorView(),CheckBox.class).isEmpty());
            selectSplitMode(i,split,1);assertChecked(split,first,false);assertChecked(split,second,true);assertChecked(split,previous,false);
            List<EditText> websites=views(split.getWindow().getDecorView(),EditText.class);AlertDialog edited=split;
            i.runOnMainSync(()->{websites.get(0).setText("collision.example");websites.get(1).setText("collision.example");edited.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});i.waitForIdleSync();
            assertTrue("Invalid websites keep copied app choices staged",split.isShowing());assertEquals("Invalid Save commits neither mode, app list nor websites",before,app.settings().toString());
            i.runOnMainSync(()->edited.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());i.waitForIdleSync();assertFalse(split.isShowing());assertEquals("Cancel discards copied and edited lists",before,app.settings().toString());

            split=dialog(i,activity,"splitDialog");selectSplitMode(i,split,1);assertChecked(split,previous,true);assertChecked(split,first,false);
            clickCopy(i,split,activity,R.string.split_copy_vpn_only);answerReplacement(i,activity,true);saveSplit(i,split);
            JSONObject copied=app.settings();assertEquals("bypass",copied.getString("splitMode"));assertEquals(source,new ArrayList<>(ProductSettings.strings(copied.getJSONArray("bypassApps"))));assertEquals("Save preserves source list",source,new ArrayList<>(ProductSettings.strings(copied.getJSONArray("onlyApps"))));
            assertEquals("test.example",copied.getJSONArray("directDomains").getString(0));assertEquals("proxy.example",copied.getJSONArray("proxyDomains").getString(0));
            split=dialog(i,activity,"splitDialog");assertEquals(1,views(split.getWindow().getDecorView(),Spinner.class).get(0).getSelectedItemPosition());setAppChecked(i,split,first,false);saveSplit(i,split);
            assertEquals(List.of(second),new ArrayList<>(ProductSettings.strings(app.settings().getJSONArray("bypassApps"))));assertEquals("Editing copied destination cannot mutate source",source,new ArrayList<>(ProductSettings.strings(app.settings().getJSONArray("onlyApps"))));
            split=dialog(i,activity,"splitDialog");selectSplitMode(i,split,2);assertChecked(split,first,true);assertChecked(split,second,true);saveSplit(i,split);
            assertEquals("only",app.settings().getString("splitMode"));assertEquals(List.of(second),new ArrayList<>(ProductSettings.strings(app.settings().getJSONArray("bypassApps"))));

            app.setSettings(new JSONObject().put("onlyApps",new JSONArray()));String emptyBefore=app.settings().toString();
            split=dialog(i,activity,"splitDialog");assertSplitExplanation(split,activity,R.string.split_only_heading,R.string.split_only_empty_hint);assertVisibleText(split,activity.getString(R.string.split_empty_list_hint));
            clickCopy(i,split,activity,R.string.split_copy_excluded);assertNull("Empty destination copies directly without confirmation",replacementWindow(i,activity));assertChecked(split,second,true);assertEquals(emptyBefore,app.settings().toString());
            assertSplitExplanation(split,activity,R.string.split_only_heading,R.string.split_only_hint);setAppChecked(i,split,second,false);assertSplitExplanation(split,activity,R.string.split_only_heading,R.string.split_only_empty_hint);assertVisibleText(split,activity.getString(R.string.split_empty_list_hint));assertEquals("Unchecking last app remains staged",emptyBefore,app.settings().toString());setAppChecked(i,split,second,true);assertSplitExplanation(split,activity,R.string.split_only_heading,R.string.split_only_hint);
            selectSplitMode(i,split,1);assertChecked(split,second,true);setAppChecked(i,split,second,false);selectSplitMode(i,split,2);assertChecked(split,second,true);saveSplit(i,split);
            assertEquals("Reverse copy has an independent destination",List.of(second),new ArrayList<>(ProductSettings.strings(app.settings().getJSONArray("onlyApps"))));assertEquals("Source changes only when explicitly edited",0,app.settings().getJSONArray("bypassApps").length());
        }finally{
            View confirmation=replacementWindow(i,activity);if(confirmation!=null)i.runOnMainSync(()->((Button)confirmation.findViewById(android.R.id.button2)).performClick());
            if(split!=null){AlertDialog close=split;i.runOnMainSync(close::dismiss);}i.waitForIdleSync();
        }
    }
    private static void selectSplitMode(Instrumentation i,AlertDialog split,int position){Spinner mode=views(split.getWindow().getDecorView(),Spinner.class).get(0);i.runOnMainSync(()->mode.setSelection(position));i.waitForIdleSync();}
    private static CheckBox appCheck(AlertDialog split,String pkg){List<CheckBox> matches=new ArrayList<>();for(CheckBox check:views(split.getWindow().getDecorView(),CheckBox.class)){String label=check.getText().toString();if(label.endsWith("\n"+pkg)||label.equals(pkg+" (not listed)"))matches.add(check);}assertEquals("Expected one app checkbox for "+pkg,1,matches.size());return matches.get(0);}
    private static void assertChecked(AlertDialog split,String pkg,boolean expected){assertEquals(pkg,expected,appCheck(split,pkg).isChecked());}
    private static void setAppChecked(Instrumentation i,AlertDialog split,String pkg,boolean checked){CheckBox check=appCheck(split,pkg);i.runOnMainSync(()->check.setChecked(checked));i.waitForIdleSync();}
    private static Button copyButton(AlertDialog split,MainActivity activity,int text){List<Button> matches=new ArrayList<>();for(Button button:views(split.getWindow().getDecorView(),Button.class))if(button.getText().toString().equals(activity.getString(text)))matches.add(button);assertEquals("Expected copy action",1,matches.size());return matches.get(0);}
    private static void clickCopy(Instrumentation i,AlertDialog split,MainActivity activity,int text){Button copy=copyButton(split,activity,text);assertEquals(View.VISIBLE,copy.getVisibility());i.runOnMainSync(copy::performClick);i.waitForIdleSync();}
    private static void saveSplit(Instrumentation i,AlertDialog split){i.runOnMainSync(()->split.getButton(AlertDialog.BUTTON_POSITIVE).performClick());i.waitForIdleSync();assertFalse("Valid Save dismisses editor",split.isShowing());}
    private static void assertVisibleText(AlertDialog split,String value){assertVisibleText(split.getWindow().getDecorView(),value);}
    private static void assertVisibleText(View root,String value){boolean present=false;for(TextView text:views(root,TextView.class))if(text.getVisibility()==View.VISIBLE&&value.equals(text.getText().toString()))present=true;assertTrue("Missing visible explanation: "+value,present);}
    private static void assertSplitExplanation(AlertDialog split,MainActivity activity,int heading,int hint){assertVisibleText(split,activity.getString(heading));assertVisibleText(split,activity.getString(hint));}
    /** Public app-window inspection; the native UI gate runs on the disposable API 35 emulator. */
    @android.annotation.TargetApi(29)
    private static View replacementWindow(Instrumentation i,MainActivity activity){AtomicReference<View> found=new AtomicReference<>();i.runOnMainSync(()->{for(View root:WindowInspector.getGlobalWindowViews()){Button action=root.findViewById(android.R.id.button1);if(action!=null&&activity.getString(R.string.split_replace_action).contentEquals(action.getText())){assertNull("Only one replace confirmation may be open",found.get());found.set(root);}}});return found.get();}
    private static void answerReplacement(Instrumentation i,MainActivity activity,boolean replace){View root=replacementWindow(i,activity);assertNotNull("Expected list-replacement confirmation",root);Button action=root.findViewById(replace?android.R.id.button1:android.R.id.button2);i.runOnMainSync(action::performClick);i.waitForIdleSync();assertNull("Confirmation closes after answer",replacementWindow(i,activity));}
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
