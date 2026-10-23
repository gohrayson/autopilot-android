package app.autopilot.android;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

/** Real watchdog observations and the actual repository scheduler; memory transport only. */
public final class SelectedProviderRecoveryTest {
    private static final List<String> PREFERRED=List.of("MY","SG","JP");

    private static final class ProviderJobs implements Consumer<List<String>> {
        final ProviderRepository repository;
        final List<List<String>> requests=new ArrayList<>();
        final ArrayDeque<Runnable> pending=new ArrayDeque<>();
        final List<String> providerErrors=new ArrayList<>();
        ProviderJobs(ProviderRepository repository){this.repository=repository;}
        @Override public void accept(List<String> names){
            List<String> copy=List.copyOf(names);requests.add(copy);
            pending.add(()->{try{repository.refreshFailedNodes(copy,PREFERRED);}catch(ProviderException error){providerErrors.add(error.code);}});
        }
        void drain(){while(!pending.isEmpty())pending.removeFirst().run();}
    }
    private static Node node(ProviderRepository repository,String country){return repository.nodes().stream().filter(n->n.provider.equals("octohide")&&n.country.equals(country)).findFirst().orElseThrow();}
    private static ConnectionWatchdog.Snapshot snapshot(Node node,long epoch){return new ConnectionWatchdog.Snapshot(4,node.id,epoch,27000);}
    private static SelectionPolicy.Health passingRtt(Node node,long now){
        SelectionPolicy.Health health=new SelectionPolicy.Health(node.id,node.name,node.country);health.latency(100,now);return health;
    }
    private static ConnectionWatchdog.Observation observe(ConnectionWatchdog.Snapshot snapshot,int mask,ConnectionWatchdog.Code expected)throws Exception {
        BlockingQueue<ConnectionWatchdog.Observation> events=new LinkedBlockingQueue<>();AtomicInteger probes=new AtomicInteger();
        ConnectionWatchdog.Probe probe=new ConnectionWatchdog.Probe(){
            @Override public ConnectionWatchdog.Result check(int port){probes.incrementAndGet();return new ConnectionWatchdog.Result(mask,100);}
            @Override public void cancel(){}
        };
        try(ConnectionWatchdog watchdog=new ConnectionWatchdog(probe,events::add,System::currentTimeMillis,50,20)){
            watchdog.target(snapshot);ConnectionWatchdog.Observation observation=ConnectionWatchdogTest.until(events,expected);
            assertEquals(snapshot,observation.snapshot);assertTrue(probes.get()>0);return observation;
        }
    }
    private static boolean forward(SelectedProviderRecovery recovery,ConnectionWatchdog.Observation observation,Node selected,boolean comparative){
        return recovery.confirmed(observation.snapshot,selected,observation.code,comparative);
    }

    @Test public void confirmedHttpsFailureRenewsOnlySelectedOctohideExportDespitePassingNativeRtt()throws Exception {
        ProviderRepositoryTest.Fixture f=new ProviderRepositoryTest.Fixture();f.octo();f.time.advance(121000);
        Node selected=node(f.repo,"MY");f.repo.currentNodeName=selected.name;
        SelectionPolicy.Health health=passingRtt(selected,f.time.now);assertTrue(health.latencyFresh(f.time.now));assertTrue(health.connectionFresh(f.time.now));
        ProviderJobs jobs=new ProviderJobs(f.repo);SelectedProviderRecovery recovery=new SelectedProviderRecovery(jobs,()->fail("Queue must accept fixture work"));
        ConnectionWatchdog.Observation failed=observe(snapshot(selected,7),6,ConnectionWatchdog.Code.CONFIRMED_FAILED);
        assertEquals(2,failed.failedOrigins);assertEquals(100,health.latency);assertTrue(health.connectionFresh(f.time.now));
        assertTrue(forward(recovery,failed,selected,false));
        assertFalse(forward(recovery,failed,selected,false));
        assertFalse(recovery.confirmed(snapshot(selected,7),selected,failed.code,false));
        assertEquals(List.of(List.of(selected.name)),jobs.requests);assertEquals(3,f.http.exports);
        jobs.drain();
        assertEquals(4,f.http.exports);assertTrue(jobs.providerErrors.isEmpty());assertEquals(3,f.repo.nodes().size());
        assertEquals(0,f.store.saved.get("octohide").getJSONArray("failedRegions").length());
    }

    @Test public void confirmedFailureRetainsExistingTwoMinuteSpacingAndDurablePendingRegion()throws Exception {
        ProviderRepositoryTest.Fixture f=new ProviderRepositoryTest.Fixture();f.octo();f.time.advance(61000);
        Node selected=node(f.repo,"MY");SelectionPolicy.Health health=passingRtt(selected,f.time.now);
        ProviderJobs jobs=new ProviderJobs(f.repo);SelectedProviderRecovery recovery=new SelectedProviderRecovery(jobs,()->fail("Queue must accept fixture work"));
        ConnectionWatchdog.Observation failed=observe(snapshot(selected,8),6,ConnectionWatchdog.Code.CONFIRMED_FAILED);
        assertTrue(health.connectionFresh(f.time.now));assertTrue(forward(recovery,failed,selected,false));jobs.drain();
        assertEquals("A confirmed application failure cannot bypass export attempt spacing",3,f.http.exports);
        assertEquals(1,f.store.saved.get("octohide").getJSONArray("failedRegions").length());
        ProviderRepository restored=new ProviderRepository(f.store,f.http,f.time,f.time::advance);
        f.time.advance(61000);restored.refreshDue(PREFERRED);
        assertEquals(4,f.http.exports);assertEquals(0,f.store.saved.get("octohide").getJSONArray("failedRegions").length());
        assertEquals("The healthy cached regions are retained",3,restored.nodes().size());
    }

    @Test public void rateLimitedRenewalPersistsCooldownAndNewNotificationsCannotBypassIt()throws Exception {
        ProviderRepositoryTest.Fixture f=new ProviderRepositoryTest.Fixture();f.octo();f.time.advance(121000);
        f.http.failExportAt=4;f.http.failExportCode="REQUEST_LIMIT_LONG";
        Node selected=node(f.repo,"MY");ProviderJobs jobs=new ProviderJobs(f.repo);SelectedProviderRecovery recovery=new SelectedProviderRecovery(jobs,()->fail("Queue must accept fixture work"));
        ConnectionWatchdog.Observation failed=observe(snapshot(selected,9),6,ConnectionWatchdog.Code.CONFIRMED_FAILED);
        assertTrue(forward(recovery,failed,selected,false));jobs.drain();
        assertEquals(List.of("RATE_LIMITED"),jobs.providerErrors);assertEquals(4,f.http.exports);
        long retryAt=f.repo.status("octohide").getLong("retryAt");assertTrue(retryAt>=f.time.now+1800000);
        int calls=f.http.octoCalls;ProviderRepository restored=new ProviderRepository(f.store,f.http,f.time,f.time::advance);
        ProviderJobs restartedJobs=new ProviderJobs(restored);SelectedProviderRecovery restarted=new SelectedProviderRecovery(restartedJobs,()->fail("Queue must accept fixture work"));
        assertTrue(restarted.confirmed(snapshot(selected,10),selected,failed.code,false));
        assertFalse(restarted.confirmed(snapshot(selected,10),selected,failed.code,false));restartedJobs.drain();
        restored.refreshDue(PREFERRED);
        assertEquals("Persisted provider cooldown prevents all additional fixture API calls",calls,f.http.octoCalls);
        assertEquals(4,f.http.exports);assertEquals(retryAt,restored.status("octohide").getLong("retryAt"));
        assertEquals(1,f.store.saved.get("octohide").getJSONArray("failedRegions").length());assertEquals(3,restored.nodes().size());
    }

    @Test public void partialServiceWideIncidentDoesNotRenewUntilFreshComparativeTransportSucceeds()throws Exception {
        ProviderRepositoryTest.Fixture f=new ProviderRepositoryTest.Fixture();f.octo();f.time.advance(121000);
        Node selected=node(f.repo,"MY"),replacement=node(f.repo,"SG");
        ProviderJobs jobs=new ProviderJobs(f.repo);SelectedProviderRecovery recovery=new SelectedProviderRecovery(jobs,()->fail("Queue must accept fixture work"));
        ConnectionWatchdog.Observation partial=observe(snapshot(selected,11),2,ConnectionWatchdog.Code.CONFIRMED_PARTIAL_FAILED);
        assertEquals(1,partial.failedOrigins);assertFalse(forward(recovery,partial,selected,false));
        assertTrue(jobs.requests.isEmpty());assertEquals(3,f.http.exports);
        ConnectionWatchdog.Observation sameOutage=observe(snapshot(replacement,12),2,ConnectionWatchdog.Code.CONFIRMED_PARTIAL_FAILED);
        boolean serviceWide=sameOutage.failedMask==partial.failedMask;
        assertTrue(serviceWide);assertFalse(forward(recovery,partial,selected,!serviceWide));assertTrue(jobs.requests.isEmpty());
        ConnectionWatchdog.Observation proved=observe(snapshot(replacement,13),0,ConnectionWatchdog.Code.HEALTHY);
        assertTrue(forward(recovery,partial,selected,proved.code==ConnectionWatchdog.Code.HEALTHY&&proved.failedMask==0));
        assertFalse(forward(recovery,partial,selected,true));jobs.drain();
        assertEquals(List.of(List.of(selected.name)),jobs.requests);assertEquals(4,f.http.exports);
    }

    @Test public void providerNodeAndEvidenceGuardsRejectUnrelatedNotifications()throws Exception {
        ProviderRepositoryTest.Fixture f=new ProviderRepositoryTest.Fixture();f.octo();f.one();
        Node selected=node(f.repo,"MY"),other=node(f.repo,"SG");
        Node one=f.repo.nodes().stream().filter(n->n.provider.equals("onevpn")).findFirst().orElseThrow();
        ProviderJobs jobs=new ProviderJobs(f.repo);SelectedProviderRecovery recovery=new SelectedProviderRecovery(jobs,()->fail("Guarded notification must not queue"));
        assertFalse(recovery.confirmed(snapshot(selected,1),other,ConnectionWatchdog.Code.CONFIRMED_FAILED,false));
        assertFalse(recovery.confirmed(snapshot(one,1),one,ConnectionWatchdog.Code.CONFIRMED_FAILED,false));
        assertFalse(recovery.confirmed(snapshot(selected,1),new Node(selected.config,"import",selected.country),ConnectionWatchdog.Code.CONFIRMED_FAILED,false));
        assertFalse(recovery.confirmed(null,selected,ConnectionWatchdog.Code.CONFIRMED_FAILED,false));
        assertFalse(recovery.confirmed(snapshot(selected,1),null,ConnectionWatchdog.Code.CONFIRMED_FAILED,false));
        assertFalse(recovery.confirmed(snapshot(selected,1),selected,ConnectionWatchdog.Code.SUSPECT,false));
        assertFalse(recovery.confirmed(snapshot(selected,1),selected,ConnectionWatchdog.Code.HEALTHY,true));
        assertTrue(jobs.requests.isEmpty());assertEquals(3,f.http.exports);
    }

    @Test public void queueAndDiagnosticFailuresDoNotEscapeOrRepeatTheSameSelection()throws Exception {
        ProviderRepositoryTest.Fixture f=new ProviderRepositoryTest.Fixture();f.octo();Node selected=node(f.repo,"MY");
        AtomicInteger queues=new AtomicInteger(),diagnostics=new AtomicInteger();AtomicBoolean callerContinued=new AtomicBoolean();
        SelectedProviderRecovery recovery=new SelectedProviderRecovery(names->{queues.incrementAndGet();throw new RejectedExecutionException("fixture queue failure");},()->{diagnostics.incrementAndGet();throw new IllegalStateException("fixture diagnostic failure");});
        assertFalse(recovery.confirmed(snapshot(selected,14),selected,ConnectionWatchdog.Code.CONFIRMED_FAILED,false));callerContinued.set(true);
        assertTrue(callerContinued.get());assertEquals(1,queues.get());assertEquals(1,diagnostics.get());
        assertFalse(recovery.confirmed(snapshot(selected,14),selected,ConnectionWatchdog.Code.CONFIRMED_FAILED,false));
        assertEquals(1,queues.get());assertEquals(1,diagnostics.get());
        assertFalse(recovery.confirmed(snapshot(selected,15),selected,ConnectionWatchdog.Code.CONFIRMED_FAILED,false));
        assertEquals(2,queues.get());assertEquals(2,diagnostics.get());assertEquals(3,f.http.exports);
    }

    @Test public void staleWatchdogCompletionAfterSelectionChangeCannotQueueARegionRenewal()throws Exception {
        ProviderRepositoryTest.Fixture f=new ProviderRepositoryTest.Fixture();f.octo();Node selected=node(f.repo,"MY"),next=node(f.repo,"SG");
        ProviderJobs jobs=new ProviderJobs(f.repo);SelectedProviderRecovery recovery=new SelectedProviderRecovery(jobs,()->fail("No stale notification may queue"));
        ConnectionWatchdog.Snapshot prior=snapshot(selected,16),active=snapshot(next,17);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        AtomicReference<ConnectionWatchdog.Snapshot> target=new AtomicReference<>(prior);BlockingQueue<ConnectionWatchdog.Observation> events=new LinkedBlockingQueue<>();
        ConnectionWatchdog.Probe probe=new ConnectionWatchdog.Probe(){
            @Override public ConnectionWatchdog.Result check(int port){
                if(calls.incrementAndGet()==1){entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}return new ConnectionWatchdog.Result(6,100);}
                return new ConnectionWatchdog.Result(0,100);
            }
            @Override public void cancel(){}
        };
        try(ConnectionWatchdog watchdog=new ConnectionWatchdog(probe,event->{
            events.add(event);
            if(event.snapshot.equals(target.get()))forward(recovery,event,target.get().equals(prior)?selected:next,false);
        },System::currentTimeMillis,50,20)){
            watchdog.target(prior);assertTrue(entered.await(1,TimeUnit.SECONDS));target.set(active);watchdog.target(active);release.countDown();
            ConnectionWatchdog.Observation healthy=ConnectionWatchdogTest.until(events,ConnectionWatchdog.Code.HEALTHY);assertEquals(active,healthy.snapshot);
            assertTrue(events.stream().noneMatch(event->event.snapshot.equals(prior)&&event.code!=ConnectionWatchdog.Code.CHECKING));
            assertTrue(jobs.requests.isEmpty());assertEquals(3,f.http.exports);
        }finally{release.countDown();}
    }
}
