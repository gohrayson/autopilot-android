package app.autopilot.android;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real Activity rendering from controller snapshots; no VPN service or provider request. */
@RunWith(AndroidJUnit4.class)
public final class VpnStatusUiTest {
    @Test(timeout=60000) public void connectionProgressAndActiveNodeRenderTruthfully()throws Exception{
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        AutopilotApp app=(AutopilotApp)context.getApplicationContext();
        assertNull("This UI probe must never replace an active VPN",app.engine);
        assertTrue("Run on the fresh account-free debug emulator",app.providers.nodes().isEmpty());
        JSONObject original=new JSONObject(app.state().toString());
        boolean originalAutomaticChecks=app.updates.automaticChecks();
        boolean originalWifiDownloads=app.updates.wifiDownloads();
        boolean originalAutomaticInstalls=app.updates.automaticInstalls();
        MainActivity activity=null;
        try{
            // Rendering snapshots must not trigger an update installation while this probe runs.
            app.updates.preferences(false,originalWifiDownloads,false);
            activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            instrumentation.waitForIdleSync();

            show(instrumentation,app,activity,snapshot("starting","preparing","",true,false,"Preparing connection"),"STARTING","Starting VPN","Preparing connection");
            show(instrumentation,app,activity,snapshot("starting","settings","",true,false,"Checking VPN settings"),"STARTING","Starting VPN","Checking VPN settings");
            show(instrumentation,app,activity,snapshot("starting","profile","",true,false,"Loading servers: 50 nodes"),"STARTING","Starting VPN","Loading servers: 50 nodes");
            show(instrumentation,app,activity,snapshot("testing","tunnel","",true,false,"Starting VPN tunnel"),"CHECKING NODES","Starting VPN","Starting VPN tunnel");
            show(instrumentation,app,activity,snapshot("testing","connections","",true,false,"Testing connections: 8 of 50"),"CHECKING NODES","Testing nodes","Testing connections: 8 of 50");
            show(instrumentation,app,activity,snapshot("testing","services","",true,false,"Checking service access: Probe Taiwan"),"CHECKING NODES","Checking service access","Checking service access: Probe Taiwan");
            show(instrumentation,app,activity,snapshot("connected","connections","Probe Taiwan",true,false,"Testing connections: 24 of 50"),"CONNECTED · AUTO","Probe Taiwan","Testing connections: 24 of 50");
            show(instrumentation,app,activity,snapshot("connected","services","Probe Taiwan",true,false,"Checking service access: Probe Japan"),"CONNECTED · AUTO","Probe Taiwan","Checking service access: Probe Japan");
            show(instrumentation,app,activity,snapshot("connected","","Probe Taiwan",false,false,"Node connection checks passed · Auto"),"CONNECTED · AUTO","Probe Taiwan","Node connection checks passed · Auto");
            show(instrumentation,app,activity,snapshot("connected","","Probe Taiwan",false,true,"Node connection checks passed · Pinned"),"CONNECTED · PINNED","Probe Taiwan","Node connection checks passed · Pinned");
            long checkedAt=System.currentTimeMillis();
            JSONObject healthy=watchdog("HEALTHY",checkedAt);
            show(instrumentation,app,activity,snapshot("connected","","Probe Taiwan",false,false,"Auto").put("watchdog",healthy),"CONNECTED · AUTO","Probe Taiwan","Auto");
            assertTrue(text(instrumentation,activity,"connectionHealth").startsWith("HTTPS access checked at "));
            assertFalse(MainActivity.healthUncertain(healthy,checkedAt));
            assertTrue("Old success must not imply current health",MainActivity.healthUncertain(healthy,checkedAt+45_001));
            assertTrue("Future timestamps are not fresh evidence",MainActivity.healthUncertain(healthy,checkedAt-1));
            for(String code:new String[]{"SUSPECT","SERVICE_PATH_SUSPECT","CONFIRMED_FAILED","CONFIRMED_PARTIAL_FAILED","RECOVERING","NO_VERIFIED_REPLACEMENT"}){
                show(instrumentation,app,activity,snapshot("connected","","Probe Taiwan",false,false,"Auto").put("watchdog",watchdog(code,checkedAt)),"CHECKING CONNECTION","Probe Taiwan","Auto");
                assertFalse("Recovery state must be visible",text(instrumentation,activity,"connectionHealth").isEmpty());
            }
            show(instrumentation,app,activity,snapshot("connected","","Probe Taiwan",false,false,"Auto").put("watchdog",watchdog("HEALTHY",checkedAt-120_000)),"CHECKING CONNECTION","Probe Taiwan","Auto");
            assertTrue(text(instrumentation,activity,"connectionHealth").contains("overdue"));
            assertTrue(app.diagnostics().contains("Active HTTPS watchdog:"));
            show(instrumentation,app,activity,snapshot("degraded","","",false,false,"No node passed all checks. VPN blocks unverified traffic."),"TRAFFIC PAUSED","Traffic paused","No node passed all checks. VPN blocks unverified traffic.");
            show(instrumentation,app,activity,snapshot("error","","",false,false,"VPN could not start. See diagnostics.").put("error","TUN_START_FAILED"),"NEEDS ATTENTION","Connection failed","VPN could not start. See diagnostics.");
            show(instrumentation,app,activity,snapshot("stopped","","",false,false,"VPN disconnected"),"DISCONNECTED","Ready to connect","VPN disconnected");
            show(instrumentation,app,activity,snapshot("idle","","",false,false,"Sign in below or import a configuration.").put("nodes",new JSONArray()),"READY","Connect an account","Sign in below or import a configuration.");
            assertNull("Snapshot rendering must not start a VPN",app.engine);
            assertTrue("Snapshot rendering must not create provider nodes",app.providers.nodes().isEmpty());
            System.out.println("AUTOPILOT_STATUS_UI_ACCEPTED starting testing service-progress connected-auto connected-pinned traffic-paused error stopped account-required");
        }finally{
            if(activity!=null){MainActivity finalActivity=activity;instrumentation.runOnMainSync(finalActivity::finish);instrumentation.waitForIdleSync();}
            app.publish(original);
            app.updates.preferences(originalAutomaticChecks,originalWifiDownloads,originalAutomaticInstalls);
        }
    }

    private static JSONObject snapshot(String phase,String stage,String current,boolean busy,boolean pinned,String detail)throws Exception{
        JSONArray rows=new JSONArray().put(new JSONObject().put("name","Probe Taiwan").put("country","TW").put("provider","fixture").put("status","Not tested"));
        return new JSONObject().put("phase",phase).put("stage",stage).put("current",current).put("busy",busy).put("pinned",pinned).put("detail",detail).put("nodes",rows);
    }
    private static JSONObject watchdog(String code,long checkedAt)throws Exception{
        return new JSONObject().put("code",code).put("lastCheckAt",checkedAt).put("lastSuccessAt",checkedAt);
    }

    private static void show(Instrumentation instrumentation,AutopilotApp app,MainActivity activity,JSONObject state,String badge,String title,String detail)throws Exception{
        app.publish(state);
        instrumentation.waitForIdleSync();
        assertEquals("Visible status badge for "+state.optString("phase"),badge,text(instrumentation,activity,"phase"));
        assertEquals("Visible title for "+state.optString("phase"),title,text(instrumentation,activity,"selected"));
        assertEquals("Visible progress/detail for "+state.optString("phase"),detail,text(instrumentation,activity,"detail"));
    }

    private static String text(Instrumentation instrumentation,MainActivity activity,String name)throws Exception{
        Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);
        AtomicReference<String> value=new AtomicReference<>();
        instrumentation.runOnMainSync(()->{try{value.set(((TextView)field.get(activity)).getText().toString());}catch(IllegalAccessException failure){throw new AssertionError(failure);}});
        return value.get();
    }
}
