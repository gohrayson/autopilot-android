package app.autopilot.android;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.Bitmap;
import android.os.Build;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.github.oviron.libmihomo.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Disposable emulator only: credential-free loopback transfer and actual native/UI data paths. */
@RunWith(AndroidJUnit4.class)
public final class NetworkTelemetryRuntimeTest {
    @Test(timeout=90000) public void pinnedNativeCountersMeasureAnActualLoopbackTransfer()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();AutopilotApp app=(AutopilotApp)context.getApplicationContext();
        assertNull("Never replace a live user engine",app.engine);assertTrue("Fixture requires an empty disposable profile",app.providers.nodes().isEmpty());
        File home=new File(context.getFilesDir(),"network-telemetry-native-"+Long.toUnsignedString(System.nanoTime()));assertTrue(home.mkdir());
        ExecutorService serverThread=Executors.newSingleThreadExecutor();boolean loaded=false;
        try(ServerSocket upstream=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            upstream.setSoTimeout(15000);int mixed;
            try(ServerSocket reservation=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){mixed=reservation.getLocalPort();}
            JSONObject config=VpnEngine.configuration(List.of(),new HashMap<>());config.put("mixed-port",mixed).put("rules",new JSONArray().put("MATCH,DIRECT"));
            Files.write(new File(home,"config.yaml").toPath(),CoreConfigEncoding.encode(config));
            Clash.INSTANCE.load(context.getApplicationInfo().nativeLibraryDir);Clash.INSTANCE.assertReady();loaded=true;
            CompletableFuture<String> setup=new CompletableFuture<>();Clash.INSTANCE.quickSetup(new JSONObject().put("home-dir",home.getAbsolutePath()).put("version",Build.VERSION.SDK_INT).toString(),new JSONObject().put("selected-map",new JSONObject().put("AUTOPILOT","REJECT")).toString(),(InvokeInterface)setup::complete);
            assertEquals("Native fixture setup","",setup.get(40,TimeUnit.SECONDS));
            JSONObject listener=action("startListener");assertEquals(0,listener.getInt("code"));
            NetworkTelemetryState.Pair before=NetworkTelemetryState.parsePair(Clash.INSTANCE.getTotalTraffic());
            NetworkTelemetryState.Pair ratesBefore=NetworkTelemetryState.parsePair(Clash.INSTANCE.getTraffic());
            byte[] payload=new byte[96*1024];Arrays.fill(payload,(byte)'T');
            Future<?> served=serverThread.submit(()->{try(Socket accepted=upstream.accept()){
                accepted.setSoTimeout(10000);InputStream request=accepted.getInputStream();int matched=0,read,seen=0;byte[] end={'\r','\n','\r','\n'};
                while(matched<4&&(read=request.read())>=0){if(++seen>8192)throw new IOException("Fixture header bound");matched=read==end[matched]?matched+1:read=='\r'?1:0;}
                OutputStream output=accepted.getOutputStream();output.write(("HTTP/1.1 200 OK\r\nContent-Length: "+payload.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.write(payload);output.flush();
            }catch(IOException failure){throw new RuntimeException(failure);}});
            // A raw, credential-free loopback fixture keeps the production manifest's
            // usesCleartextTraffic=false policy intact. Real exit checks use HTTPS.
            int received=0;
            try(Socket connection=new Socket()){
                connection.connect(new InetSocketAddress("127.0.0.1",mixed),10000);connection.setSoTimeout(10000);
                String target="http://127.0.0.1:"+upstream.getLocalPort()+"/network-telemetry";
                connection.getOutputStream().write(("GET "+target+" HTTP/1.1\r\nHost: 127.0.0.1:"+upstream.getLocalPort()+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));connection.getOutputStream().flush();
                InputStream body=connection.getInputStream();ByteArrayOutputStream headers=new ByteArrayOutputStream();int matched=0,read;byte[] end={'\r','\n','\r','\n'};
                while(matched<4&&(read=body.read())>=0){if(headers.size()>=8192)throw new IOException("Fixture response header bound");headers.write(read);matched=read==end[matched]?matched+1:read=='\r'?1:0;}
                assertTrue(headers.toString(StandardCharsets.US_ASCII.name()).startsWith("HTTP/1.1 200"));assertEquals(4,matched);
                byte[] buffer=new byte[8192];while((read=body.read(buffer))!=-1)received+=read;
            }
            assertEquals(payload.length,received);served.get(10,TimeUnit.SECONDS);
            NetworkTelemetryState.Pair after=NetworkTelemetryState.parsePair(Clash.INSTANCE.getTotalTraffic());NetworkTelemetryState.Pair ratesAfter=NetworkTelemetryState.parsePair(Clash.INSTANCE.getTraffic());
            assertTrue("Native upload delta",after.up>before.up);assertTrue("Native download counts fixture payload",after.down-before.down>=payload.length);
            NetworkTelemetryState model=new NetworkTelemetryState(new NetworkTelemetryState.Pair(0,0),true);model.target(null,1,true);
            model.counters(1,before,ratesBefore,1,1);model.counters(1,after,ratesAfter,2001,2001);
            assertEquals(after.down-before.down,model.json().getJSONObject("traffic").getLong("sessionDownload"));
            System.out.println("ANDROID_NATIVE_TRAFFIC_ACCEPTED SDK="+Build.VERSION.SDK_INT+" ABI="+Build.SUPPORTED_ABIS[0]+" uploadDelta="+(after.up-before.up)+" downloadDelta="+(after.down-before.down)+" payload="+received);
        }finally{
            if(loaded)try{action("stopListener");}finally{Clash.INSTANCE.stopTun();}serverThread.shutdownNow();
            assertEquals(context.getFilesDir().getCanonicalFile(),home.getCanonicalFile().getParentFile());
            try(java.util.stream.Stream<java.nio.file.Path> files=Files.walk(home.toPath())){for(java.nio.file.Path file:files.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(file);}
        }
    }
    @Test(timeout=60000) public void actualDarkUiShowsMeasuredIdentityAndUnknownVersusRealCounters()throws Exception{
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();AutopilotApp app=(AutopilotApp)instrumentation.getTargetContext().getApplicationContext();
        assertNull("No user VPN operation in UI fixture",app.engine);JSONObject original=app.state();MainActivity activity=null;
        try{
            Intent intent=new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);activity=(MainActivity)instrumentation.startActivitySync(intent);MainActivity shown=activity;
            NetworkTelemetryState model=new NetworkTelemetryState(new NetworkTelemetryState.Pair(4096,8192),true);model.target(new NetworkTelemetryState.Route(1,1,"fixture-jp","Fixture Japan","JP",27001),1,true);
            model.completeExit(model.beginExit(),NetworkTelemetryState.parseExit("ip=8.8.8.8\nloc=JP"),System.currentTimeMillis());
            model.counters(1,new NetworkTelemetryState.Pair(10,20),new NetworkTelemetryState.Pair(100,200),1,1);
            instrumentation.runOnMainSync(()->app.publishNetworkTelemetry(model.json()));instrumentation.waitForIdleSync();
            assertTrue(text(shown,"networkExit").contains("8.8.8.8"));assertTrue(text(shown,"networkExit").contains("Japan"));assertTrue(text(shown,"trafficRates").contains("—"));
            model.counters(1,new NetworkTelemetryState.Pair(1034,2068),new NetworkTelemetryState.Pair(1024,2048),2001,2001);
            instrumentation.runOnMainSync(()->app.publishNetworkTelemetry(model.json()));instrumentation.waitForIdleSync();
            assertTrue(text(shown,"trafficRates").contains("KiB"));assertTrue(text(shown,"trafficSession").contains("KiB"));
            TextView identity=(TextView)field(shown,"networkExit");assertEquals(0xff51e1e8,identity.getCurrentTextColor());
            File image=new File(app.getFilesDir(),"network-telemetry-ui.png");Bitmap screenshot=instrumentation.getUiAutomation().takeScreenshot();assertNotNull(screenshot);
            try(FileOutputStream output=new FileOutputStream(image)){assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG,100,output));}finally{screenshot.recycle();}
            System.out.println("ANDROID_TELEMETRY_UI_ACCEPTED actualNativeActivity=true strictIdentity=true unknownFirstRate=true darkPalette=true screenshot="+image.getName());
        }finally{if(activity!=null){MainActivity close=activity;instrumentation.runOnMainSync(close::finish);}instrumentation.runOnMainSync(()->{app.publish(original);app.publishNetworkTelemetry(new NetworkTelemetryState(null,true).json());});}
    }
    private static JSONObject action(String method)throws Exception{CompletableFuture<String> value=new CompletableFuture<>();Clash.INSTANCE.invokeAction(new JSONObject().put("id","telemetry-native-"+method).put("method",method).put("data",JSONObject.NULL).toString(),(InvokeInterface)value::complete);JSONObject response=new JSONObject(value.get(10,TimeUnit.SECONDS));assertEquals(method,response.getString("method"));return response;}
    private static Object field(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static String text(MainActivity activity,String name)throws Exception{return ((TextView)field(activity,name)).getText().toString();}
}
