package app.autopilot.android;

import android.content.SharedPreferences;
import android.os.SystemClock;
import io.github.oviron.libmihomo.Clash;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Independent sampling executors; no network work runs on UI or the selection worker. */
final class AndroidNetworkTelemetry implements AutoCloseable {
    interface ExitProbe extends AutoCloseable {NetworkTelemetryState.Exit check(int port)throws Exception;void cancel();void close();}
    interface NativeReader {String totals();String rates();}
    interface Store {NetworkTelemetryState.Pair load()throws Exception;default void stage(NetworkTelemetryState.Pair value){}boolean save(NetworkTelemetryState.Pair value);}
    /** Shared memory is authoritative between engine instances; disk writes never hold its lock. */
    static final class UsageStore implements Store {
        private static final Map<SharedPreferences,UsageStore> STORES=new WeakHashMap<>();
        static synchronized UsageStore forPreferences(SharedPreferences prefs){return STORES.computeIfAbsent(prefs,UsageStore::new);}
        private final SharedPreferences prefs;private final Object disk=new Object();private NetworkTelemetryState.Pair latest;
        private UsageStore(SharedPreferences prefs){this.prefs=prefs;}
        public synchronized NetworkTelemetryState.Pair load(){if(latest==null)latest=new NetworkTelemetryState.Pair(prefs.getLong("upload",0),prefs.getLong("download",0));return latest;}
        public synchronized void stage(NetworkTelemetryState.Pair value){latest=value;}
        public boolean save(NetworkTelemetryState.Pair ignored){synchronized(disk){NetworkTelemetryState.Pair value=load();return prefs.edit().putLong("upload",value.up).putLong("download",value.down).commit();}}
    }
    private final NetworkTelemetryState model;private final NativeReader reader;private final ExitProbe probe;private final Store store;private final Consumer<JSONObject> changed;
    private final ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService exitWorker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean started=new AtomicBoolean(),closed=new AtomicBoolean(),checking=new AtomicBoolean();
    private volatile long lastSave;private final LongSupplier now,elapsed;
    AndroidNetworkTelemetry(SharedPreferences prefs,Consumer<JSONObject> changed){this(new NativeReader(){public String totals(){return Clash.INSTANCE.getTotalTraffic();}public String rates(){return Clash.INSTANCE.getTraffic();}},new NetworkExitProbe(),UsageStore.forPreferences(prefs),changed,System::currentTimeMillis,SystemClock::elapsedRealtime);}
    AndroidNetworkTelemetry(NativeReader reader,ExitProbe probe,Store store,Consumer<JSONObject> changed,LongSupplier now,LongSupplier elapsed){
        this.reader=reader;this.probe=probe;this.store=store;this.changed=changed;this.now=now;this.elapsed=elapsed;
        NetworkTelemetryState.Pair saved=null;boolean valid=true;try{saved=store.load();if(saved==null)valid=false;}catch(Exception failure){valid=false;}
        model=new NetworkTelemetryState(saved,valid);
    }
    static JSONObject idleStatus(SharedPreferences prefs){
        try{return new NetworkTelemetryState(UsageStore.forPreferences(prefs).load(),true).json();}
        catch(RuntimeException invalid){return new NetworkTelemetryState(null,false).json();}
    }
    synchronized void start(){if(!closed.get()&&started.compareAndSet(false,true))sampler.scheduleWithFixedDelay(this::poll,0,2,TimeUnit.SECONDS);}
    void target(NetworkTelemetryState.Route route,long generation,boolean available){if(closed.get())return;if(model.target(route,generation,available)){probe.cancel();publish();} }
    JSONObject status(){return model.json();}
    void checkExitNow(){if(!closed.get()&&model.route()!=null)requestExit();}
    void poll(){
        if(closed.get())return;
        long generation=model.generation();
        if(model.active())try{NetworkTelemetryState.Pair total=NetworkTelemetryState.parsePair(reader.totals()),rate=NetworkTelemetryState.parsePair(reader.rates());synchronized(this){if(!closed.get()){model.counters(generation,total,rate,elapsed.getAsLong(),now.getAsLong());if(model.persistent())store.stage(model.totals());}}}
        catch(Throwable failure){model.trafficFailure(generation);}
        long at=now.getAsLong(),checked=model.exitAt();if(model.route()!=null&&(checked==0||at<checked||at-checked>=60_000))requestExit();
        if(at-lastSave>=30_000)save();publish();
    }
    private void requestExit(){
        if(!checking.compareAndSet(false,true))return;NetworkTelemetryState.Ticket ticket=model.beginExit();
        if(ticket==null){checking.set(false);return;}publish();
        try{exitWorker.execute(()->{try{NetworkTelemetryState.Exit value=probe.check(ticket.route.port);model.completeExit(ticket,value,now.getAsLong());}
            catch(Exception failure){model.failExit(ticket,now.getAsLong());}finally{checking.set(false);publish();}});}catch(RejectedExecutionException failure){checking.set(false);}
    }
    private void save(){lastSave=now.getAsLong();if(model.persistent())try{if(!store.save(model.totals()))model.persistenceFailed();}catch(RuntimeException failure){model.persistenceFailed();}}
    private synchronized void publish(){if(!closed.get())changed.accept(model.json());}
    @Override public void close(){
        synchronized(this){if(!closed.compareAndSet(false,true))return;model.close();if(model.persistent())store.stage(model.totals());changed.accept(model.json());}
        probe.close();exitWorker.shutdownNow();
        // Stop is immediate on the UI thread; durable final counters are written off-thread.
        try{sampler.execute(()->{save();sampler.shutdown();});}catch(RejectedExecutionException ignored){sampler.shutdownNow();}
    }
}
