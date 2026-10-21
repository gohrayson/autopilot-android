package app.autopilot.android;

import android.content.*;
import android.net.VpnService;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Uses an emulator-only SOCKS fixture; never signs in to a provider or reads desktop sessions. */
@RunWith(AndroidJUnit4.class)
public final class AndroidSmokeTest {
    @Test public void testNativeVpnStartsAndSelectsVerifiedNode()throws Exception{
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();AutopilotApp app=(AutopilotApp)context.getApplicationContext();
        SecureStore secure=new SecureStore(context);JSONObject sentinel=new JSONObject().put("fixture","autopilot-keystore-roundtrip");
        secure.save("fixture",sentinel);assertEquals(sentinel.toString(),secure.load("fixture").toString());
        byte[] stored=Files.readAllBytes(new java.io.File(context.getNoBackupFilesDir(),"accounts/fixture.enc").toPath());
        assertFalse(new String(stored,StandardCharsets.UTF_8).contains("autopilot-keystore-roundtrip"));secure.save("fixture",null);
        assertNull("Grant emulator ACTIVATE_VPN appop before running this probe",VpnService.prepare(context));
        String outbound=new JSONObject().put("name","Autopilot Probe Japan").put("type","socks5").put("server","10.0.2.2").put("port",29480).toString();
        app.providers.importConfig("{\"proxies\":["+outbound+"]}");
        instrumentation.runOnMainSync(()->app.setPreferred(List.of("JP")));
        instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        context.startForegroundService(new Intent(context,AutopilotVpnService.class).setAction(AutopilotVpnService.START));
        long deadline=System.nanoTime()+180_000_000_000L;
        JSONObject snapshot;
        do{Thread.sleep(500);snapshot=app.state();if("error".equals(snapshot.optString("phase")))fail("VPN startup: "+snapshot.optString("error"));}
        while(!"connected".equals(snapshot.optString("phase"))&&System.nanoTime()<deadline);
        assertEquals("Service availability may depend on the host's existing VPN. Snapshot: "+snapshot,"connected",snapshot.optString("phase"));
        assertTrue(snapshot.optBoolean("tun"));assertEquals("Autopilot Probe Japan",snapshot.optString("current"));
        assertNotNull(app.engine);app.log("probe","NATIVE_VPN_READY");
        java.io.File ready=new java.io.File(context.getFilesDir(),"probe-ready.json"),complete=new java.io.File(context.getFilesDir(),"probe-complete");
        if(complete.exists())assertTrue(complete.delete());
        Files.write(ready.toPath(),new JSONObject().put("uid",android.os.Process.myUid()).put("state",snapshot).toString().getBytes(StandardCharsets.UTF_8));
        long probeDeadline=System.nanoTime()+120_000_000_000L;
        while(!complete.exists()&&System.nanoTime()<probeDeadline)Thread.sleep(300);
        assertTrue("Separate-UID tunnel probe did not finish",complete.exists());
        assertEquals("PASS",new String(Files.readAllBytes(complete.toPath()),StandardCharsets.UTF_8).trim());
        context.startService(new Intent(context,AutopilotVpnService.class).setAction(AutopilotVpnService.STOP));
        long stopDeadline=System.nanoTime()+15_000_000_000L;
        while(app.engine!=null&&System.nanoTime()<stopDeadline)Thread.sleep(100);
        assertNull("VPN service did not stop",app.engine);assertFalse(app.state().optBoolean("tun"));assertFalse(app.vpnWanted());
        ready.delete();complete.delete();
    }
}
