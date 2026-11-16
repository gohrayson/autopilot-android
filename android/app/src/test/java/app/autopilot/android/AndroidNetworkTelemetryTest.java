package app.autopilot.android;

import org.json.*;
import org.junit.Test;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class AndroidNetworkTelemetryTest {
    private static class Store implements AndroidNetworkTelemetry.Store {NetworkTelemetryState.Pair saved=new NetworkTelemetryState.Pair(100,200);public NetworkTelemetryState.Pair load(){return saved;}public boolean save(NetworkTelemetryState.Pair value){saved=value;return true;}}
    @Test public void nativeSamplerTracksRecordedDeltaAndSurvivesMalformedNativeResponse()throws Exception{
        AtomicReference<String> nativeTotal=new AtomicReference<>("{\"up\":10,\"down\":20}");AtomicLong now=new AtomicLong(100000);Store store=new Store();
        AndroidNetworkTelemetry.NativeReader nativeReader=new AndroidNetworkTelemetry.NativeReader(){public String totals(){return nativeTotal.get();}public String rates(){return "{\"up\":2,\"down\":4}";}};
        AndroidNetworkTelemetry.ExitProbe probe=new AndroidNetworkTelemetry.ExitProbe(){public NetworkTelemetryState.Exit check(int port)throws Exception{return NetworkTelemetryState.parseExit("ip=8.8.8.8\nloc=JP");}public void cancel(){}public void close(){}};
        try(AndroidNetworkTelemetry telemetry=new AndroidNetworkTelemetry(nativeReader,probe,store,state->{},now::get,now::get)){
            telemetry.target(null,1,true);telemetry.poll();assertTrue(telemetry.status().getJSONObject("traffic").isNull("downloadRate"));
            nativeTotal.set("{\"up\":15,\"down\":28}");now.addAndGet(2000);telemetry.poll();assertEquals(8,telemetry.status().getJSONObject("traffic").getLong("sessionDownload"));
            nativeTotal.set("{}");telemetry.poll();assertEquals("TRAFFIC_UNAVAILABLE",telemetry.status().getJSONObject("traffic").getString("code"));assertTrue(telemetry.status().getJSONObject("traffic").isNull("downloadRate"));
        }
    }
    @Test public void lateExitAfterSelectionChangeCannotPublishOldIp()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);Store store=new Store();
        AndroidNetworkTelemetry.ExitProbe probe=new AndroidNetworkTelemetry.ExitProbe(){public NetworkTelemetryState.Exit check(int port)throws Exception{entered.countDown();release.await();return NetworkTelemetryState.parseExit("ip=8.8.8.8\nloc=JP");}public void cancel(){}public void close(){release.countDown();}};
        AndroidNetworkTelemetry.NativeReader reader=new AndroidNetworkTelemetry.NativeReader(){public String totals(){return "{\"up\":0,\"down\":0}";}public String rates(){return totals();}};
        try(AndroidNetworkTelemetry telemetry=new AndroidNetworkTelemetry(reader,probe,store,state->{},System::currentTimeMillis,System::currentTimeMillis)){
            telemetry.target(new NetworkTelemetryState.Route(1,1,"jp","Japan","JP",27351),1,true);telemetry.checkExitNow();assertTrue(entered.await(1,TimeUnit.SECONDS));
            telemetry.target(new NetworkTelemetryState.Route(1,2,"sg","Singapore","SG",27352),1,true);release.countDown();Thread.sleep(50);
            JSONObject exit=telemetry.status().getJSONObject("exit");assertTrue(exit.isNull("ip"));assertEquals("Singapore",exit.getString("node"));
        }
    }
    @Test public void closePublishesStoppedOnceAndIgnoresLateProbeCallbacks()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);AtomicInteger publications=new AtomicInteger();AtomicReference<Thread> callbackThread=new AtomicReference<>();
        AndroidNetworkTelemetry.ExitProbe probe=new AndroidNetworkTelemetry.ExitProbe(){public NetworkTelemetryState.Exit check(int port)throws Exception{callbackThread.set(Thread.currentThread());entered.countDown();try{while(!release.await(100,TimeUnit.MILLISECONDS)){}return NetworkTelemetryState.parseExit("ip=8.8.8.8\nloc=JP");}finally{finished.countDown();}}public void cancel(){}public void close(){release.countDown();}};
        AndroidNetworkTelemetry.NativeReader reader=new AndroidNetworkTelemetry.NativeReader(){public String totals(){return "{\"up\":0,\"down\":0}";}public String rates(){return totals();}};
        AndroidNetworkTelemetry telemetry=new AndroidNetworkTelemetry(reader,probe,new Store(),state->publications.incrementAndGet(),System::currentTimeMillis,System::currentTimeMillis);
        try{telemetry.target(new NetworkTelemetryState.Route(1,1,"jp","Japan","JP",27351),1,true);telemetry.checkExitNow();assertTrue(entered.await(1,TimeUnit.SECONDS));telemetry.close();int stopped=publications.get();assertTrue(finished.await(1,TimeUnit.SECONDS));callbackThread.get().join(1000);assertFalse(callbackThread.get().isAlive());telemetry.poll();telemetry.close();telemetry.start();assertEquals(stopped,publications.get());assertEquals("STOPPED",telemetry.status().getJSONObject("exit").getString("code"));}
        finally{release.countDown();telemetry.close();}
    }
    @Test public void sharedUsageSurvivesFastCloseRecreateAndSerializesOlderDiskFlushes()throws Exception{
        Map<String,Long> disk=new ConcurrentHashMap<>();CountDownLatch commitEntered=new CountDownLatch(1),commitRelease=new CountDownLatch(1);AtomicBoolean blockOnce=new AtomicBoolean(true);
        SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
            switch(method.getName()){
                case "hashCode":return System.identityHashCode(proxy);case "equals":return proxy==args[0];
                case "getLong":return disk.getOrDefault((String)args[0],(Long)args[1]);
                case "edit":Map<String,Long> patch=new HashMap<>();return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(editor,edit,values)->{
                    if(edit.getName().equals("putLong")){patch.put((String)values[0],(Long)values[1]);return editor;}
                    if(edit.getName().equals("commit")){if(blockOnce.compareAndSet(true,false)){commitEntered.countDown();assertTrue(commitRelease.await(2,TimeUnit.SECONDS));}disk.putAll(patch);return true;}
                    throw new UnsupportedOperationException(edit.getName());});
                default:throw new UnsupportedOperationException(method.getName());
            }
        });
        AndroidNetworkTelemetry.UsageStore store=AndroidNetworkTelemetry.UsageStore.forPreferences(prefs);assertEquals(0,store.load().up);store.stage(new NetworkTelemetryState.Pair(10,20));
        ExecutorService worker=Executors.newFixedThreadPool(2);
        try{Future<Boolean> older=worker.submit(()->store.save(new NetworkTelemetryState.Pair(10,20)));assertTrue(commitEntered.await(1,TimeUnit.SECONDS));
            store.stage(new NetworkTelemetryState.Pair(30,40));assertSame(store,AndroidNetworkTelemetry.UsageStore.forPreferences(prefs));assertEquals(30,store.load().up);
            Future<Boolean> later=worker.submit(()->store.save(new NetworkTelemetryState.Pair(10,20)));commitRelease.countDown();assertTrue(older.get(2,TimeUnit.SECONDS));assertTrue(later.get(2,TimeUnit.SECONDS));assertEquals(Long.valueOf(30),disk.get("upload"));assertEquals(Long.valueOf(40),disk.get("download"));
        }finally{commitRelease.countDown();worker.shutdownNow();}
    }
    @Test public void newEngineLoadsFinalInMemoryUsageBeforeOldDiskCommitFinishes()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<String> totals=new AtomicReference<>("{\"up\":10,\"down\":20}");AtomicLong now=new AtomicLong(1);
        Store store=new Store(){@Override public synchronized NetworkTelemetryState.Pair load(){return saved;}@Override public synchronized void stage(NetworkTelemetryState.Pair value){saved=value;}@Override public boolean save(NetworkTelemetryState.Pair ignored){entered.countDown();try{return release.await(2,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();return false;}}};
        AndroidNetworkTelemetry.NativeReader reader=new AndroidNetworkTelemetry.NativeReader(){public String totals(){return totals.get();}public String rates(){return "{\"up\":0,\"down\":0}";}};
        AndroidNetworkTelemetry.ExitProbe probe=new AndroidNetworkTelemetry.ExitProbe(){public NetworkTelemetryState.Exit check(int port){throw new AssertionError("No route lookup expected");}public void cancel(){}public void close(){}};
        AndroidNetworkTelemetry first=new AndroidNetworkTelemetry(reader,probe,store,state->{},now::get,now::get),next=null;
        try{first.target(null,1,true);first.poll();totals.set("{\"up\":15,\"down\":28}");now.set(2001);first.poll();first.close();assertTrue(entered.await(1,TimeUnit.SECONDS));
            next=new AndroidNetworkTelemetry(reader,probe,store,state->{},now::get,now::get);JSONObject status=next.status().getJSONObject("traffic");assertEquals(105,status.getLong("totalUpload"));assertEquals(208,status.getLong("totalDownload"));assertEquals(0,status.getLong("sessionUpload"));
        }finally{release.countDown();first.close();if(next!=null)next.close();}
    }
}
