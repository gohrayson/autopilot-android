package app.autopilot.android;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class ConnectionWatchdogTest {
    private final ConnectionWatchdog.Snapshot first=new ConnectionWatchdog.Snapshot(1,"first",1,27000);
    private static final class Probe implements ConnectionWatchdog.Probe {
        final Queue<Integer> failures=new ConcurrentLinkedQueue<>();final AtomicInteger calls=new AtomicInteger();
        Probe(Integer... results){failures.addAll(Arrays.asList(results));}
        public ConnectionWatchdog.Result check(int port){calls.incrementAndGet();Integer next=failures.poll();return new ConnectionWatchdog.Result(next==null?0:next,42);}
        public void cancel(){}
    }
    @Test public void googleSuccessDoesNotHideTwoApplicationFailures()throws Exception{
        Probe probe=new Probe(6,6);BlockingQueue<ConnectionWatchdog.Observation> events=new LinkedBlockingQueue<>();
        try(ConnectionWatchdog watchdog=new ConnectionWatchdog(probe,events::add,System::currentTimeMillis,50,30)){
            watchdog.target(first);ConnectionWatchdog.Observation suspect=until(events,ConnectionWatchdog.Code.SUSPECT);
            ConnectionWatchdog.Observation confirmed=until(events,ConnectionWatchdog.Code.CONFIRMED_FAILED);
            assertEquals(2,confirmed.failedOrigins);assertTrue(confirmed.at-suspect.at>=30);assertEquals(2,probe.calls.get());
        }
    }
    @Test public void transientFailureAndSingleOriginNeverTriggerBroadFailure()throws Exception{
        Probe probe=new Probe(6,0,2,0);BlockingQueue<ConnectionWatchdog.Observation> events=new LinkedBlockingQueue<>();
        try(ConnectionWatchdog watchdog=new ConnectionWatchdog(probe,events::add,System::currentTimeMillis,30,20)){
            watchdog.target(first);until(events,ConnectionWatchdog.Code.SUSPECT);until(events,ConnectionWatchdog.Code.HEALTHY);
            until(events,ConnectionWatchdog.Code.SERVICE_PATH_SUSPECT);until(events,ConnectionWatchdog.Code.HEALTHY);
            assertTrue(events.stream().noneMatch(e->e.code==ConnectionWatchdog.Code.CONFIRMED_FAILED));
        }
    }
    @Test public void sameApplicationFailureRequestsComparisonWithoutBroadCondemnation()throws Exception{
        Probe probe=new Probe(2,2);BlockingQueue<ConnectionWatchdog.Observation> events=new LinkedBlockingQueue<>();
        try(ConnectionWatchdog watchdog=new ConnectionWatchdog(probe,events::add,System::currentTimeMillis,50,20)){
            watchdog.target(first);until(events,ConnectionWatchdog.Code.SERVICE_PATH_SUSPECT);
            ConnectionWatchdog.Observation result=until(events,ConnectionWatchdog.Code.CONFIRMED_PARTIAL_FAILED);
            assertEquals(2,result.failedMask);assertEquals(1,result.failedOrigins);
        }
    }
    @Test public void staleCompletionAfterSelectionOrProfileChangeIsDiscarded()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);BlockingQueue<ConnectionWatchdog.Observation> events=new LinkedBlockingQueue<>();AtomicInteger calls=new AtomicInteger();
        ConnectionWatchdog.Probe probe=new ConnectionWatchdog.Probe(){public ConnectionWatchdog.Result check(int port)throws InterruptedException{if(calls.incrementAndGet()==1){entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}}return new ConnectionWatchdog.Result(6);}public void cancel(){}};
        try(ConnectionWatchdog watchdog=new ConnectionWatchdog(probe,events::add,System::currentTimeMillis,100,30)){
            watchdog.target(first);assertTrue(entered.await(1,TimeUnit.SECONDS));
            ConnectionWatchdog.Snapshot next=new ConnectionWatchdog.Snapshot(2,"first",2,27000);watchdog.target(next);release.countDown();
            ConnectionWatchdog.Observation suspect=until(events,ConnectionWatchdog.Code.SUSPECT);assertEquals(next,suspect.snapshot);
            assertTrue(events.stream().noneMatch(e->e.snapshot.equals(first)&&e.code!=ConnectionWatchdog.Code.CHECKING));
        }finally{release.countDown();}
    }
    @Test public void independentWatchdogConfirmsWhileControllerDiscoveryIsBlocked()throws Exception{
        CountDownLatch busy=new CountDownLatch(1),release=new CountDownLatch(1),recovered=new CountDownLatch(1);ExecutorService controller=Executors.newSingleThreadExecutor();AtomicReference<Thread> owner=new AtomicReference<>();
        controller.execute(()->{owner.set(Thread.currentThread());busy.countDown();try{release.await();}catch(InterruptedException expected){Thread.interrupted();}});
        assertTrue(busy.await(1,TimeUnit.SECONDS));
        try(ConnectionWatchdog watchdog=new ConnectionWatchdog(new Probe(6,6),event->{if(event.code==ConnectionWatchdog.Code.CONFIRMED_FAILED){owner.get().interrupt();controller.execute(recovered::countDown);}},System::currentTimeMillis,100,30)){
            watchdog.target(first);assertTrue("Read-only monitoring must not queue behind discovery",recovered.await(2,TimeUnit.SECONDS));assertEquals(1,release.getCount());
        }finally{release.countDown();controller.shutdownNow();assertTrue(controller.awaitTermination(1,TimeUnit.SECONDS));}
    }
    @Test public void exactSnapshotIncludesAllFourIdentityFields(){
        assertNotEquals(first,new ConnectionWatchdog.Snapshot(2,"first",1,27000));assertNotEquals(first,new ConnectionWatchdog.Snapshot(1,"second",1,27000));
        assertNotEquals(first,new ConnectionWatchdog.Snapshot(1,"first",2,27000));assertNotEquals(first,new ConnectionWatchdog.Snapshot(1,"first",1,27001));
    }
    @Test public void queuedObservationCannotOverrideNewerFullApplicationProof(){
        SelectionPolicy.Health selected=new SelectionPolicy.Health("first","Selected","JP");
        selected.configure(2000,List.of("chatgpt"));selected.chatgpt="ok";selected.servicesAt=2000;
        selected.transportVerified(2000,42);selected.applicationVerified(2000);
        assertTrue(selected.qualified(2000));
        assertTrue(VpnEngine.obsoleteWatchdogObservation(1999,1000,selected.appVerifiedAt));
        assertTrue(VpnEngine.obsoleteWatchdogObservation(1999,2000,0));
        // A newer/equal failure remains authoritative: a ping/transport success
        // must not hide a real current application outage.
        selected.latency(42,4000);selected.transportVerified(4000,42);
        assertFalse(VpnEngine.obsoleteWatchdogObservation(3000,2500,selected.appVerifiedAt));
        assertFalse(VpnEngine.obsoleteWatchdogObservation(2000,2000,selected.appVerifiedAt));
        assertFalse(selected.appRejected);assertEquals(0,selected.appFailureUntil);
    }
    @Test public void repeatedResumesDuringBlockedRoundDoNotCreateOrphanTimers()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        BlockingQueue<ConnectionWatchdog.Observation> events=new LinkedBlockingQueue<>();
        ConnectionWatchdog.Probe probe=new ConnectionWatchdog.Probe(){
            public ConnectionWatchdog.Result check(int port)throws InterruptedException{if(calls.incrementAndGet()==1){entered.countDown();release.await();}return new ConnectionWatchdog.Result(6);}
            public void cancel(){}
        };
        try(ConnectionWatchdog watchdog=new ConnectionWatchdog(probe,events::add,System::currentTimeMillis,80,30)){
            watchdog.target(first);assertTrue(entered.await(1,TimeUnit.SECONDS));
            for(int i=0;i<100;i++)watchdog.resume();
            assertFalse("Resume must leave the in-flight round intact",release.await(120,TimeUnit.MILLISECONDS));
            assertEquals(1,calls.get());release.countDown();
            ConnectionWatchdog.Observation confirmed=until(events,ConnectionWatchdog.Code.CONFIRMED_FAILED);assertEquals(2,calls.get());
            assertEquals(2,confirmed.failedOrigins);
            // A stopped confirmed incident must have no older timer chain left
            // to wake up, adopt the current route and restart itself.
            CountDownLatch quiet=new CountDownLatch(1);assertFalse(quiet.await(200,TimeUnit.MILLISECONDS));assertEquals(2,calls.get());
        }finally{release.countDown();}
    }
    @Test public void earlyMatchingBroadRoundsRetainTheFirstConfirmationAnchor()throws Exception{
        AtomicLong clock=new AtomicLong();AtomicInteger calls=new AtomicInteger();BlockingQueue<ConnectionWatchdog.Observation> events=new LinkedBlockingQueue<>();
        ConnectionWatchdog.Probe probe=new ConnectionWatchdog.Probe(){public ConnectionWatchdog.Result check(int port){clock.set(1000+(calls.getAndIncrement()*8L));return new ConnectionWatchdog.Result(6);}public void cancel(){}};
        try(ConnectionWatchdog watchdog=new ConnectionWatchdog(probe,events::add,clock::get,80,30)){
            watchdog.target(first);ConnectionWatchdog.Observation suspect=until(events,ConnectionWatchdog.Code.SUSPECT),confirmed=until(events,ConnectionWatchdog.Code.CONFIRMED_FAILED);
            assertEquals(1000,suspect.at);assertEquals(1032,confirmed.at);assertEquals(5,calls.get());assertTrue(confirmed.at-suspect.at>=30);
        }
    }
    @Test public void healthyRoundResetsAnEarlyPartialFailureAnchor()throws Exception{
        AtomicLong clock=new AtomicLong();AtomicInteger calls=new AtomicInteger();BlockingQueue<ConnectionWatchdog.Observation> events=new LinkedBlockingQueue<>();
        ConnectionWatchdog.Probe probe=new ConnectionWatchdog.Probe(){public ConnectionWatchdog.Result check(int port){int step=calls.getAndIncrement();clock.set(1000+step*8L);return new ConnectionWatchdog.Result(step==2?0:2);}public void cancel(){}};
        try(ConnectionWatchdog watchdog=new ConnectionWatchdog(probe,events::add,clock::get,30,30)){
            watchdog.target(first);ConnectionWatchdog.Observation healthy=until(events,ConnectionWatchdog.Code.HEALTHY),confirmed=until(events,ConnectionWatchdog.Code.CONFIRMED_PARTIAL_FAILED);
            assertEquals(1016,healthy.at);assertEquals(1056,confirmed.at);assertEquals(8,calls.get());assertEquals(2,confirmed.failedMask);
        }
    }
    static ConnectionWatchdog.Observation until(BlockingQueue<ConnectionWatchdog.Observation> events,ConnectionWatchdog.Code code)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);ConnectionWatchdog.Observation event;
        while(System.nanoTime()<deadline){event=events.poll(100,TimeUnit.MILLISECONDS);if(event!=null&&event.code==code)return event;}
        throw new AssertionError("Missing watchdog event "+code);
    }
}
