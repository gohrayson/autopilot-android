package app.autopilot.android;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.oviron.libmihomo.Clash;
import io.github.oviron.libmihomo.InvokeInterface;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Fresh emulator only: production service/native TUN, fixture SOCKS/DNS, separate probe UID. */
@RunWith(AndroidJUnit4.class)
public final class NativeTunnelRuntimeTest {
    private static final String NODE="Autopilot Fixture Japan";
    private static final String MARKER_URL="http://203.0.113.7/autopilot-probe";

    @Test(timeout=240000) public void productionTunnelCarriesIpv4Ipv6AndDnsFromOtherUid()throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        AutopilotApp app=(AutopilotApp)context.getApplicationContext();
        assertNull("Disposable emulator must have no running VPN",app.engine);
        assertTrue("Disposable emulator must have no saved provider nodes",app.providers.nodes().isEmpty());
        assertNull("Grant emulator ACTIVATE_VPN appop before this test",VpnService.prepare(context));
        File ready=new File(context.getFilesDir(),"tunnel-probe-ready.json");
        File complete=new File(context.getFilesDir(),"tunnel-probe-complete");
        File readyTemporary=new File(context.getFilesDir(),"tunnel-probe-ready.json.tmp");
        File completeTemporary=new File(context.getFilesDir(),"tunnel-probe-complete.tmp");
        for(File handoff:List.of(ready,complete,readyTemporary,completeTemporary))Files.deleteIfExists(handoff.toPath());
        try {
            // Empty provider state prevents every external service/latency check. The actual
            // production service still establishes its ordinary Android routes and native TUN.
            context.startForegroundService(new Intent(context,AutopilotVpnService.class).setAction(AutopilotVpnService.START));
            long deadline=System.nanoTime()+60_000_000_000L;
            while(!app.state().optBoolean("tun")&&System.nanoTime()<deadline){
                if("error".equals(app.state().optString("phase")))fail("Production service startup: "+app.state());
                Thread.sleep(100);
            }
            assertTrue("Production service did not establish TUN: "+app.state(),app.state().optBoolean("tun"));
            VpnEngine engine=app.engine;assertNotNull(engine);
            Field workerField=VpnEngine.class.getDeclaredField("worker");workerField.setAccessible(true);
            ScheduledExecutorService worker=(ScheduledExecutorService)workerField.get(engine);
            worker.submit(()->{
                try {
                    Node synthetic=new Node(new JSONObject().put("name",NODE).put("type","socks5")
                        .put("server","proxy-fixture.autopilot.test").put("port",29480),"fixture","JP");
                    Clash.INSTANCE.stopTun();
                    // A closed local resolver is a deterministic analogue of an unreachable
                    // bootstrap resolver. Selector acceptance alone must not pass this probe.
                    configure(context,synthetic,new CoreDnsConfig(List.of("127.0.0.1:29554"),List.of("udp://127.0.0.1:29554")));
                    JSONObject blocked=action("testDelay",new JSONObject().put("proxy-name",NODE)
                        .put("test-url",MARKER_URL).put("timeout",1500).toString());
                    assertTrue("Unreachable bootstrap DNS unexpectedly reached reserved marker: "+blocked,
                        blocked.optInt("code",-1)!=0||blocked.optInt("data",-1)<=0);
                    System.out.println("AUTOPILOT_TUN_BOOTSTRAP_NEGATIVE unavailable=true");
                    configure(context,synthetic,new CoreDnsConfig(List.of("10.0.2.2:29553"),List.of("udp://10.0.2.2:29553")));
                    Method establish=VpnEngine.class.getDeclaredMethod("establish",boolean.class);establish.setAccessible(true);
                    establish.invoke(engine,true); // Exact production startTUN arguments and retained service fd.
                    JSONObject reachable=action("testDelay",new JSONObject().put("proxy-name",NODE)
                        .put("test-url",MARKER_URL).put("timeout",5000).toString());
                    assertEquals("Fixture hostname endpoint bootstrap: "+reachable,0,reachable.optInt("code",-1));
                    assertTrue("Fixture hostname endpoint bootstrap: "+reachable,reachable.optInt("data",-1)>0);
                }catch(Throwable failure){throw new RuntimeException(failure);}
            }).get(60,TimeUnit.SECONDS);
            JSONObject state=new JSONObject().put("uid",android.os.Process.myUid()).put("tun",app.state().optBoolean("tun"))
                .put("sdk",Build.VERSION.SDK_INT).put("abi",Build.SUPPORTED_ABIS[0]);
            try(FileOutputStream out=new FileOutputStream(readyTemporary)){
                out.write(state.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();
            }
            Files.move(readyTemporary.toPath(),ready.toPath(),StandardCopyOption.ATOMIC_MOVE);
            long probeDeadline=System.nanoTime()+120_000_000_000L;
            while(!complete.exists()&&System.nanoTime()<probeDeadline)Thread.sleep(100);
            assertTrue("Separate UID runner did not complete IPv4/IPv6/DNS/fake-IP probes",complete.isFile());
            assertEquals("PASS",new String(Files.readAllBytes(complete.toPath()),StandardCharsets.UTF_8).trim());
            System.out.println("AUTOPILOT_TUN_OTHER_UID_ACCEPTED "+state);
        }finally {
            try {
                context.startService(new Intent(context,AutopilotVpnService.class).setAction(AutopilotVpnService.STOP));
                long deadline=System.nanoTime()+15_000_000_000L;
                while(app.engine!=null&&System.nanoTime()<deadline)Thread.sleep(100);
                assertNull("Production VPN Stop cleanup",app.engine);
                assertFalse("Production route state after Stop",app.state().optBoolean("tun"));
            }finally {
                for(File handoff:List.of(ready,complete,readyTemporary,completeTemporary))Files.deleteIfExists(handoff.toPath());
            }
        }
    }

    private static void configure(Context context,Node node,CoreDnsConfig dns)throws Exception {
        JSONObject config=VpnEngine.configuration(List.of(node),new HashMap<>(),dns);
        // fake-ip would otherwise answer this application query without contacting the
        // upstream fixture resolver. Force a real lookup while retaining production DNS mode.
        config.getJSONObject("dns").put("fake-ip-filter",new JSONArray().put("tunnel-fixture.autopilot.test"));
        File home=new File(context.getFilesDir(),"core");
        try(FileOutputStream out=new FileOutputStream(new File(home,"config.yaml"))){out.write(CoreConfigEncoding.encode(config));out.getFD().sync();}
        CompletableFuture<String> setup=new CompletableFuture<>();
        Clash.INSTANCE.quickSetup(new JSONObject().put("home-dir",home.getAbsolutePath()).put("version",Build.VERSION.SDK_INT).toString(),
            new JSONObject().put("selected-map",new JSONObject().put("AUTOPILOT",NODE)).toString(),(InvokeInterface)setup::complete);
        assertEquals("Synthetic fixture native setup","",setup.get(20,TimeUnit.SECONDS));
    }

    private static JSONObject action(String method,Object data)throws Exception {
        CompletableFuture<String> response=new CompletableFuture<>();
        Clash.INSTANCE.invokeAction(new JSONObject().put("id","tunnel-fixture-"+System.nanoTime()).put("method",method).put("data",data).toString(),
            (InvokeInterface)response::complete);
        return new JSONObject(response.get(12,TimeUnit.SECONDS));
    }
}
