package app.autopilot.android;

import org.junit.Test;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Metrics fixtures model restored data, network transfer, interruption and concurrent producers. */
public class UpdateProgressTest {
    private static final long MIB=1024*1024,NANOSECOND=1_000_000_000L;
    @Test public void cachedBytesHaveProgressButNoNetworkSpeed(){
        UpdateProgress progress=new UpdateProgress(8*MIB,2*MIB,0);
        UpdateProgress.Snapshot state=progress.snapshot(2*NANOSECOND,true);
        assertEquals(2*MIB,state.downloadedBytes);assertEquals(25,state.percent);
        assertEquals(0,state.bytesPerSecond);assertEquals(-1,state.etaSeconds);assertEquals(0,state.activeConnections);
    }
    @Test public void speedAndEtaCountOnlyNewAttemptBytes(){
        UpdateProgress progress=new UpdateProgress(10*MIB,4*MIB,0);
        assertTrue(progress.update(6*MIB,10*MIB,4));
        UpdateProgress.Snapshot state=progress.snapshot(2*NANOSECOND,true);
        assertEquals(MIB,state.bytesPerSecond);assertEquals(4,state.etaSeconds);
        assertEquals(60,state.percent);assertEquals(4,state.activeConnections);
    }
    @Test public void delayedCallbacksDoNotMoveProgressBackwards(){
        UpdateProgress progress=new UpdateProgress(10*MIB,0,0);
        progress.update(8*MIB,10*MIB,4);progress.update(5*MIB,10*MIB,2);
        UpdateProgress.Snapshot state=progress.snapshot(NANOSECOND,true);
        assertEquals(8*MIB,state.downloadedBytes);assertEquals(80,state.percent);assertEquals(2,state.activeConnections);
    }
    @Test public void malformedProducerMetricsAreRejected(){
        UpdateProgress progress=new UpdateProgress(10*MIB,MIB,0);
        assertFalse(progress.update(-1,10*MIB,1));assertFalse(progress.update(11*MIB,10*MIB,1));
        assertFalse(progress.update(2*MIB,9*MIB,1));assertFalse(progress.update(2*MIB,10*MIB,-1));
        assertFalse(progress.update(2*MIB,10*MIB,5));
        UpdateProgress.Snapshot state=progress.snapshot(NANOSECOND,true);
        assertEquals(MIB,state.downloadedBytes);assertEquals(10,state.percent);assertEquals(0,state.bytesPerSecond);
    }
    @Test public void pauseAndFreshResumeExcludeBothCachedBytesAndPausedTime(){
        UpdateProgress first=new UpdateProgress(10*MIB,4*MIB,0);first.update(6*MIB,10*MIB,4);
        UpdateProgress.Snapshot paused=first.snapshot(2*NANOSECOND,false);
        assertEquals(6*MIB,paused.downloadedBytes);assertEquals(0,paused.bytesPerSecond);assertEquals(0,paused.activeConnections);assertEquals(-1,paused.etaSeconds);
        UpdateProgress resumed=new UpdateProgress(10*MIB,6*MIB,100*NANOSECOND);
        resumed.update(7*MIB,10*MIB,1);UpdateProgress.Snapshot state=resumed.snapshot(101*NANOSECOND,true);
        assertEquals(MIB,state.bytesPerSecond);assertEquals(3,state.etaSeconds);assertEquals(70,state.percent);
    }
    @Test public void extremeTimingAndCompletionStayBounded(){
        UpdateProgress progress=new UpdateProgress(UpdateManifest.MAX_APK_BYTES,0,0);
        progress.update(UpdateManifest.MAX_APK_BYTES,UpdateManifest.MAX_APK_BYTES,0);
        UpdateProgress.Snapshot state=progress.snapshot(1,true);
        assertEquals(100,state.percent);assertEquals(0,state.etaSeconds);assertTrue(state.bytesPerSecond>0);
        assertEquals(UpdateManifest.MAX_APK_BYTES*1_000_000_000L,state.bytesPerSecond);
        assertEquals(0,progress.snapshot(0,true).bytesPerSecond);
        assertEquals(0,progress.snapshot(-1,true).bytesPerSecond);
    }
    @Test public void monotonicClockMayHaveNegativeOrigin(){
        UpdateProgress progress=new UpdateProgress(10*MIB,0,-2*NANOSECOND);progress.update(MIB,10*MIB,1);
        assertEquals(MIB,progress.snapshot(-NANOSECOND,true).bytesPerSecond);
    }
    @Test public void lostRangeSupportResetsCachedProgressAndNetworkClock(){
        UpdateProgress progress=new UpdateProgress(100*MIB,99*MIB,0);
        assertEquals(99,progress.snapshot(10*NANOSECOND,true).percent);
        progress.restart(100*NANOSECOND);
        UpdateProgress.Snapshot reset=progress.snapshot(100*NANOSECOND,true);
        assertEquals(0,reset.downloadedBytes);assertEquals(0,reset.percent);assertEquals(0,reset.bytesPerSecond);assertEquals(-1,reset.etaSeconds);
        progress.update(MIB,100*MIB,1);
        UpdateProgress.Snapshot fresh=progress.snapshot(101*NANOSECOND,true);
        assertEquals(MIB,fresh.bytesPerSecond);assertEquals(99,fresh.etaSeconds);assertEquals(1,fresh.percent);
    }
    @Test public void concurrentConnectionsProduceConsistentSnapshots() throws Exception {
        UpdateProgress progress=new UpdateProgress(128*MIB,0,0);ExecutorService pool=Executors.newFixedThreadPool(4);
        try{
            Future<?>[] work=new Future<?>[4];
            for(int connection=0;connection<4;connection++){
                final int index=connection;
                work[connection]=pool.submit(()->{for(int part=index+1;part<=128;part+=4){assertTrue(progress.update(part*MIB,128*MIB,4));UpdateProgress.Snapshot state=progress.snapshot(NANOSECOND,true);assertTrue(state.downloadedBytes>=0&&state.downloadedBytes<=128*MIB);assertTrue(state.percent>=0&&state.percent<=100);}});
            }
            for(Future<?> future:work)future.get(10,TimeUnit.SECONDS);
            assertEquals(128*MIB,progress.snapshot(NANOSECOND,true).downloadedBytes);assertEquals(100,progress.snapshot(NANOSECOND,true).percent);
        }finally{pool.shutdownNow();}
    }
    @Test public void invalidCacheTotalsCannotReachUi(){
        for(long[] input:new long[][]{{0,0},{-1,0},{UpdateManifest.MAX_APK_BYTES+1,0},{100,-1},{100,101}}){
            try{new UpdateProgress(input[0],input[1],0);fail("Invalid metric accepted");}catch(IllegalArgumentException expected){assertEquals("UPDATE_PROGRESS_INVALID",expected.getMessage());}
        }
    }
}
