package app.autopilot.android;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.*;

/** Independent read-only monitor. Its callback must dispatch mutations onto the controller lane. */
public final class ConnectionWatchdog implements AutoCloseable {
    public static final long INTERVAL=15_000,CONFIRM_DELAY=4000;
    public enum Code { IDLE,CHECKING,HEALTHY,SUSPECT,SERVICE_PATH_SUSPECT,CONFIRMED_PARTIAL_FAILED,CONFIRMED_FAILED,RECOVERING,NO_VERIFIED_REPLACEMENT }
    public static final class Snapshot {
        public final long profileGeneration,selectionEpoch;
        public final String nodeId;
        public final int port;
        public Snapshot(long generation,String node,long epoch,int port){profileGeneration=generation;nodeId=node;selectionEpoch=epoch;this.port=port;}
        @Override public boolean equals(Object other){if(!(other instanceof Snapshot))return false;Snapshot s=(Snapshot)other;return profileGeneration==s.profileGeneration&&selectionEpoch==s.selectionEpoch&&port==s.port&&nodeId.equals(s.nodeId);}
        @Override public int hashCode(){return Objects.hash(profileGeneration,nodeId,selectionEpoch,port);}
    }
    public static final class Result {
        public final int failedMask,latencyMillis;
        public Result(int failedMask){this(failedMask,0);}
        public Result(int failedMask,int latencyMillis){this.failedMask=failedMask&7;this.latencyMillis=latencyMillis;}
        public int failedOrigins(){return Integer.bitCount(failedMask);}
        public boolean broadFailure(){return failedOrigins()>=2;}
    }
    public static final class Observation {
        public final Snapshot snapshot;public final Code code;public final long at,lastSuccessAt;public final int failedOrigins,failedMask,consecutiveFailures,latencyMillis;
        Observation(Snapshot snapshot,Code code,long at,long lastSuccess,int mask,int consecutive,int latency){this.snapshot=snapshot;this.code=code;this.at=at;lastSuccessAt=lastSuccess;failedMask=mask;failedOrigins=Integer.bitCount(mask);consecutiveFailures=consecutive;latencyMillis=latency;}
    }
    public interface Probe {Result check(int port)throws InterruptedException;void cancel();}
    private final Probe probe;private final Consumer<Observation> sink;private final LongSupplier clock;
    private final long interval,confirmDelay;
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor();
    private Snapshot active;private ScheduledFuture<?> next;private boolean closed,checking,accelerate;
    private int consecutive,lastMask,routeErrors;private long suspectAt,lastSuccess,lastRouteError,lastExpedited;
    public ConnectionWatchdog(Probe probe,Consumer<Observation> sink){this(probe,sink,System::currentTimeMillis,INTERVAL,CONFIRM_DELAY);}
    ConnectionWatchdog(Probe probe,Consumer<Observation> sink,LongSupplier clock,long interval,long confirmDelay){this.probe=probe;this.sink=sink;this.clock=clock;this.interval=interval;this.confirmDelay=confirmDelay;}
    public synchronized void target(Snapshot snapshot){
        if(closed||Objects.equals(active,snapshot))return;
        active=snapshot;consecutive=lastMask=routeErrors=0;suspectAt=lastSuccess=0;
        if(next!=null)next.cancel(true);probe.cancel();
        if(snapshot!=null)schedule(0);
    }
    /** Exact group errors only accelerate a fresh round; they never attribute failure to a node. */
    public synchronized void routeError(){
        long now=clock.getAsLong();if(now-lastRouteError>5000)routeErrors=0;lastRouteError=now;
        if(active!=null&&++routeErrors>=3&&consecutive==0&&(lastExpedited==0||now-lastExpedited>=5000)){routeErrors=0;lastExpedited=now;if(checking)accelerate=true;else{if(next!=null)next.cancel(false);schedule(0);}}
    }
    public synchronized void resume(){if(!closed&&active!=null){consecutive=lastMask=0;suspectAt=0;schedule(interval);}}
    private synchronized void schedule(long delay){if(!closed&&active!=null)next=executor.schedule(this::run,delay,TimeUnit.MILLISECONDS);}
    private void run(){
        Snapshot captured;
        synchronized(this){if(closed||active==null)return;captured=active;checking=true;}
        emit(captured,Code.CHECKING,clock.getAsLong(),0,0);
        Result result;
        try{result=probe.check(captured.port);}catch(InterruptedException cancelled){Thread.interrupted();synchronized(this){checking=false;}return;}
        catch(RuntimeException failure){result=new Result(7);}
        synchronized(this){
            checking=false;if(closed||!captured.equals(active))return;
            long now=clock.getAsLong();Code code;
            if(result.broadFailure()){
                if(consecutive>0&&Integer.bitCount(lastMask&result.failedMask)>=2&&now>=suspectAt&&now-suspectAt>=confirmDelay){consecutive++;code=Code.CONFIRMED_FAILED;}
                else{consecutive=1;suspectAt=now;code=Code.SUSPECT;}
                lastMask=result.failedMask;
            }else if((result.failedMask&6)!=0){
                if(consecutive>0&&lastMask==result.failedMask&&now>=suspectAt&&now-suspectAt>=confirmDelay){consecutive++;code=Code.CONFIRMED_PARTIAL_FAILED;}
                else{consecutive=1;suspectAt=now;code=Code.SERVICE_PATH_SUSPECT;}
                lastMask=result.failedMask;
            }else{consecutive=lastMask=0;suspectAt=0;lastSuccess=now;code=Code.HEALTHY;}
            emit(captured,code,now,result.failedMask,result.latencyMillis);
            // A confirmed result is consumed by recovery; do not repeatedly interrupt that recovery.
            if(code!=Code.CONFIRMED_FAILED&&code!=Code.CONFIRMED_PARTIAL_FAILED){schedule(code==Code.SUSPECT||code==Code.SERVICE_PATH_SUSPECT?confirmDelay:accelerate?0:interval);accelerate=false;}
        }
    }
    private synchronized void emit(Snapshot captured,Code code,long now,int failed,int latency){if(!closed&&captured.equals(active))sink.accept(new Observation(captured,code,now,lastSuccess,failed,consecutive,latency));}
    @Override public synchronized void close(){closed=true;active=null;if(next!=null)next.cancel(true);probe.cancel();executor.shutdownNow();}
}
