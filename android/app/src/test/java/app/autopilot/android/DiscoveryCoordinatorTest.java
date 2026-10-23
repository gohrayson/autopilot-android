package app.autopilot.android;

import org.json.JSONObject;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Runs the production async scan pipeline with a deterministic network/controller
 * clock and the real health/selection policy, including late native callbacks. */
public class DiscoveryCoordinatorTest {
    private static final class Clock implements DiscoveryCoordinator.Scheduler {
        long now=1_000_000,sequence;int budgetTimers;
        record Job(long at,long order,Runnable action){}
        final PriorityQueue<Job> jobs=new PriorityQueue<>(Comparator.comparingLong(Job::at).thenComparingLong(Job::order));
        public void execute(Runnable job){later(job,0);}
        public void later(Runnable job,long delay){if(delay==60_000)budgetTimers++;jobs.add(new Job(now+delay,sequence++,job));}
        void until(long time){int count=0;while(!jobs.isEmpty()&&jobs.peek().at<=time){if(++count>100_000)throw new AssertionError("Busy loop");Job next=jobs.remove();now=next.at;next.action.run();}now=time;}
        void ready(){until(now);}
    }
    private static final class Fixture implements DiscoveryCoordinator.Driver<String> {
        final Clock clock=new Clock();final DiscoveryCoordinator<String> scan;
        final List<String> nodes=new ArrayList<>(),nativeStarts=new ArrayList<>(),events=new ArrayList<>();
        final Map<String,SelectionPolicy.Health> health=new LinkedHashMap<>();
        final Set<String> nativeGood=new HashSet<>(),fallbackGood=new HashSet<>(),transportBad=new HashSet<>(),serviceBad=new HashSet<>(),promotionBad=new HashSet<>();
        final Map<String,Integer> nativeMillis=new HashMap<>();
        DiscoveryCoordinator.State state;
        long firstPromotion,lastSwitch,watchdogAt;int nativeActive,nativePeak,fallbackActive,fallbackPeak,proofActive,proofPeak,promotions,forceComparisons,nativeRecords,serviceRecords,watchdogApplied,ended;
        String current="";boolean paused,pinned;long nativeDelay=5000,goodDelay=1000;
        Fixture(int count){this(count,60_000);}
        Fixture(int count,long budgetMillis){
            for(int i=0;i<count;i++){String id=String.format(Locale.ROOT,"node-%02d",i);nodes.add(id);health.put(id,new SelectionPolicy.Health(id,id,"JP"));}
            scan=new DiscoveryCoordinator<>(clock,this,budgetMillis,3,1);
        }
        public long now(){return clock.now;}
        private <T>CompletableFuture<T> response(T value,long delay,Runnable done){CompletableFuture<T> result=new CompletableFuture<>();clock.later(()->{done.run();result.complete(value);},delay);return result;}
        public CompletionStage<HealthDiagnostics.Latency> nativeProbe(String node){
            nativeStarts.add(node);nativePeak=Math.max(nativePeak,++nativeActive);
            assertTrue("Native window exceeded eight",nativeActive<=8);
            int ms=nativeGood.contains(node)?nativeMillis.getOrDefault(node,100):-1;
            try{return response(HealthDiagnostics.latency(new JSONObject().put("code",0).put("data",ms)),ms>0?goodDelay:nativeDelay,()->nativeActive--);}
            catch(Exception impossible){throw new AssertionError(impossible);}
        }
        public CompletionStage<Integer> fallback(String node,int lane){
            fallbackPeak=Math.max(fallbackPeak,++fallbackActive);assertTrue("Fallback queue became unbounded",fallbackActive<=2);
            return response(fallbackGood.contains(node)?200:0,7000,()->fallbackActive--);
        }
        public CompletionStage<ConnectionWatchdog.Result> transport(String node){
            proofPeak=Math.max(proofPeak,++proofActive);assertEquals("Concurrent proof would share round deadlines",1,proofActive);
            return response(new ConnectionWatchdog.Result(transportBad.contains(node)?7:0,200),200,()->proofActive--);
        }
        public CompletionStage<ServiceChecks.Result> services(String node){
            proofPeak=Math.max(proofPeak,++proofActive);assertEquals(1,proofActive);
            String verdict=serviceBad.contains(node)?"blocked":"ok";
            return response(new ServiceChecks.Result(verdict,verdict,verdict),300,()->proofActive--);
        }
        public boolean recordNative(String node,HealthDiagnostics.Latency sample){nativeRecords++;SelectionPolicy.Health h=health.get(node);h.latency(sample.millis,now());return sample.millis>0;}
        public boolean recordFallback(String node,int millis){if(millis<=0)return false;health.get(node).transportVerified(now(),millis);return true;}
        public boolean recordTransport(String node,ConnectionWatchdog.Result result){SelectionPolicy.Health h=health.get(node);if(result.broadFailure()){h.applicationFailed(now());return false;}if((result.failedMask&6)!=0)return false;h.transportVerified(now(),result.latencyMillis);return true;}
        public boolean recordServices(String node,ServiceChecks.Result services){serviceRecords++;SelectionPolicy.Health h=health.get(node);h.claude=services.claude;h.chatgpt=services.chatgpt;h.gemini=services.gemini;h.servicesAt=now();return h.eligibleForProof(now());}
        public boolean canProve(String node){SelectionPolicy.Health h=health.get(node);return h.connectionFresh(now())&&now()>=h.appFailureUntil;}
        public Comparator<String> order(){return Comparator.comparingDouble((String node)->health.get(node).connectionScore(now())).thenComparing(node->node);}
        public boolean needsRoute(){return !paused&&(current.isEmpty()||!health.get(current).qualified(now())&&!health.get(current).retainSelectedTransport(now()));}
        public int proofLimit(){return 8;}
        public void proved(String node,boolean finalComparison,boolean force){
            SelectionPolicy.Health h=health.get(node);if(!h.eligibleForProof(now()))return;h.applicationVerified(now());
            if(paused||pinned&&!needsRoute())return;
            SelectionPolicy.Health best=SelectionPolicy.best(health.values(),List.of(),now());
            if(best==null||!best.id.equals(node))return;
            if(needsRoute()||finalComparison&&SelectionPolicy.shouldSwitch(current.isEmpty()?null:health.get(current),h,List.of(),now(),lastSwitch,force)){
                if(promotionBad.contains(node)){h.applicationFailed(now());throw new IllegalStateException("FIXTURE_SELECTOR_REJECTED");}
                current=node;lastSwitch=now();if(firstPromotion==0)firstPromotion=now();promotions++;if(finalComparison&&force)forceComparisons++;events.add("PROMOTE:"+node);
            }
        }
        public String finalCandidate(Set<String> attempted,boolean force){
            if(paused||pinned&&!needsRoute())return null;
            SelectionPolicy.Health best=SelectionPolicy.bestForProof(health.values(),List.of(),now(),attempted,current);
            return best==null||best.id.equals(current)||attempted.contains(best.id)?null:best.id;
        }
        public void ended(boolean complete,boolean force){ended++;events.add(complete?"COMPLETED":"BUDGET");}
        public void progress(DiscoveryCoordinator.State state){this.state=state;}
        public void failed(Throwable failure){if("FIXTURE_SELECTOR_REJECTED".equals(failure.getMessage()))events.add("SELECTOR_REJECTED");else throw new AssertionError(failure);}
        void start(boolean force){scan.request(nodes,0,force);clock.ready();}
        void healthyCurrent(String node){current=node;SelectionPolicy.Health h=health.get(node);h.latency(1000,now());h.transportVerified(now(),200);h.claude=h.chatgpt=h.gemini="ok";h.servicesAt=now();h.applicationVerified(now());lastSwitch=now();}
        void watchdog(){clock.execute(()->{watchdogAt=now();watchdogApplied++;if(!current.isEmpty()){health.get(current).transportVerified(now(),200);health.get(current).selectedHealthy(now());}events.add("WATCHDOG");});}
    }

    @Test public void completedHealthyResultIsProvedBeforeSlowInputPrefixOrTail(){
        Fixture f=new Fixture(52);f.nativeGood.add("node-02");f.start(true);f.clock.until(1_001_600);
        assertEquals("node-02",f.current);assertTrue(f.firstPromotion<1_005_000);assertTrue(f.state.active);assertTrue(f.state.cursor<52*3);
        assertTrue(f.nativePeak<=8);assertTrue(f.fallbackPeak<=2);assertEquals(1,f.proofPeak);
    }
    @Test public void healthyLastNodeDoesNotWaitForSerializedFallbacks(){
        Fixture f=new Fixture(52);f.nativeGood.add("node-51");f.start(true);f.clock.until(1_055_000);
        assertEquals("node-51",f.current);assertTrue("First verified route exceeded soft budget",f.firstPromotion<1_060_000);assertTrue(f.state.active);
        assertTrue("Background failures should still be pending",f.state.fallbackQueued>0||f.state.fallbackActive>0||f.state.nativeActive>0);
    }
    @Test public void startupIsOnePassAndPublicForcedRequestUpgradesSameScan(){
        Fixture f=new Fixture(52);f.nativeGood.add("node-02");f.start(false);long cycle=f.state.cycle;
        f.clock.until(1_001_600);assertEquals("node-02",f.current);assertEquals(1,f.state.passes);
        int cursor=f.state.cursor;f.scan.request(f.nodes,0,true);f.clock.ready();assertEquals(cycle,f.state.cycle);assertEquals(3,f.state.passes);assertTrue(f.state.cursor>=cursor);
    }
    @Test public void partialBudgetComparisonUsesNormalThresholdsEvenDuringForcedScan(){
        for(int replacementMillis:List.of(950,20)){
            Fixture f=new Fixture(52);f.healthyCurrent("node-00");f.nativeGood.addAll(List.of("node-00","node-01"));f.nativeMillis.put("node-00",1000);f.nativeMillis.put("node-01",replacementMillis);f.start(true);
            f.clock.until(1_038_000);assertEquals("node-00",f.current);
            f.clock.until(1_041_000);assertEquals(replacementMillis==20?"node-01":"node-00",f.current);assertEquals(0,f.forceComparisons);assertTrue(f.state.active);
        }
    }
    @Test public void healthyWatchdogAppliesImmediatelyDuringInFlightNativeAndServiceWork(){
        Fixture f=new Fixture(52);f.nativeGood.add("node-02");f.start(true);f.clock.until(1_001_600);
        f.clock.until(1_016_000);assertTrue(f.state.active);f.watchdog();f.clock.ready();
        assertEquals(1,f.watchdogApplied);assertEquals(1_016_000,f.watchdogAt);assertEquals("node-02",f.current);assertTrue(f.state.active);
    }
    @Test public void activeRouteSurvivesLongDiscoveryWithFreshHttpsAndRequiredServices(){
        Fixture f=new Fixture(52);f.healthyCurrent("node-51");f.start(true);
        for(int second=15;second<=180;second+=15){f.clock.until(1_000_000+second*1000L);f.watchdog();f.clock.ready();if(!f.state.active)f.scan.request(f.nodes,0,true);f.clock.ready();assertEquals("node-51",f.current);assertTrue(f.health.get(f.current).qualified(f.now()));}
        assertTrue(f.now()-f.health.get(f.current).latencyAt>SelectionPolicy.LATENCY_TTL||f.health.get(f.current).latency<0);
        assertEquals(0,f.promotions);assertTrue(f.events.contains("BUDGET"));
    }
    @Test public void manualRetestPreservesActiveRouteAndMakesOneFinalForcedComparison(){
        Fixture f=new Fixture(2);f.healthyCurrent("node-00");f.nativeGood.addAll(f.nodes);f.nativeMillis.put("node-00",1000);f.nativeMillis.put("node-01",20);f.goodDelay=100;
        f.start(true);f.clock.until(1_000_450);assertEquals("node-00",f.current);assertEquals(0,f.promotions);
        f.clock.until(1_005_000);assertEquals("node-01",f.current);assertEquals(1,f.promotions);assertEquals(1,f.forceComparisons);assertEquals("DISCOVERY_COMPLETED",f.state.code);
    }
    @Test public void budgetCutDuringFinalProofResumesThatComparisonWithoutLosingForce(){
        Fixture f=new Fixture(2,3100);f.healthyCurrent("node-00");f.nativeGood.addAll(f.nodes);f.nativeMillis.put("node-00",1000);f.nativeMillis.put("node-01",950);f.start(true);
        f.clock.until(1_003_500);assertFalse(f.state.active);assertEquals("node-00",f.current);assertEquals(0,f.forceComparisons);
        f.scan.request(f.nodes,0,false);f.clock.ready();f.clock.until(1_005_000);
        assertEquals("node-01",f.current);assertEquals(1,f.forceComparisons);assertEquals(1,f.promotions);
    }
    @Test public void budgetResumesFairCursorRatherThanRestartingFailedPrefix(){
        Fixture f=new Fixture(52);f.nativeDelay=7500;f.start(true);f.clock.until(1_060_001);int cursor=f.state.cursor;long cycle=f.state.cycle;
        assertFalse(f.state.active);assertTrue(cursor>0&&cursor<52*3);f.clock.until(1_070_000);int before=f.nativeStarts.size();
        f.scan.request(f.nodes,0,true);f.clock.ready();assertEquals(cycle,f.state.cycle);assertTrue(f.state.cursor>=cursor);
        assertTrue(f.nativeStarts.size()>before);assertEquals(f.nodes.get(cursor%52),f.nativeStarts.get(before));
    }
    @Test public void forcedRequestsCoalesceWithoutTimersQueuesOrCursorReset(){
        Fixture f=new Fixture(52);f.start(false);long cycle=f.state.cycle;int cursor=f.state.cursor;
        ArrayList<String> reversed=new ArrayList<>(f.nodes);Collections.reverse(reversed);
        for(int i=0;i<100;i++)f.scan.request(reversed,0,true);
        f.clock.ready();assertEquals(cycle,f.state.cycle);assertEquals(cursor,f.state.cursor);assertEquals(3,f.state.passes);assertEquals(8,f.nativeStarts.size());assertEquals(1,f.clock.budgetTimers);
    }
    @Test public void cancellationIgnoresOldGenerationAndKeepsNativePermitsUntilSettlement(){
        Fixture f=new Fixture(52);f.nativeGood.add("node-00");f.start(true);assertEquals(8,f.nativeActive);
        f.scan.invalidate();f.scan.cancelled();f.scan.request(f.nodes,1,true);f.clock.ready();assertEquals(8,f.nativeStarts.size());assertEquals(0,f.nativeRecords);
        f.clock.until(1_001_100);assertEquals("Old callback must not write same-ID new ledger",0,f.nativeRecords);assertTrue(f.nativePeak<=8);
        f.clock.until(1_010_000);assertTrue(f.nativeRecords>0);assertEquals("node-00",f.current);
    }
    @Test public void latePriorityWithoutCycleCannotAliasNewProfileHealth(){
        Fixture f=new Fixture(1);f.nativeGood.add("node-00");f.scan.priority("node-00");f.clock.ready();
        f.scan.invalidate();f.scan.cancelled();f.scan.request(List.of(),1,false);f.clock.ready();f.clock.until(1_002_000);
        assertEquals(0,f.nativeRecords);assertEquals(0,f.health.get("node-00").latencyAt);
    }
    @Test public void generationChangeDuringServiceProofPreventsLatePromotion(){
        Fixture f=new Fixture(1);f.nativeGood.add("node-00");f.start(false);f.clock.until(1_001_300);
        assertEquals(1,f.proofActive);f.scan.invalidate();f.scan.cancelled();f.scan.request(f.nodes,1,false);f.clock.ready();f.clock.until(1_001_600);
        assertEquals(0,f.serviceRecords);assertEquals("",f.current);
        f.clock.until(1_004_000);assertEquals("node-00",f.current);assertEquals(1,f.promotions);
    }
    @Test public void failedIndependentTransportOrRequiredServiceIsNeverPromoted(){
        Fixture f=new Fixture(3);f.nativeGood.addAll(f.nodes);f.transportBad.add("node-00");f.serviceBad.add("node-01");f.serviceBad.add("node-02");f.start(true);f.clock.until(1_030_000);
        assertEquals("",f.current);assertEquals(0,f.promotions);assertTrue(f.health.get("node-00").appRejected);
    }
    @Test public void rejectedSelectorWinnerCannotHideNextFreshlyVerifiedCandidate(){
        Fixture f=new Fixture(2);f.nativeGood.addAll(f.nodes);f.nativeMillis.put("node-00",20);f.nativeMillis.put("node-01",50);f.promotionBad.add("node-00");f.start(false);f.clock.until(1_005_000);
        assertEquals("node-01",f.current);assertTrue(f.events.contains("SELECTOR_REJECTED"));assertTrue(f.health.get("node-00").appRejected);assertEquals(1,f.promotions);
    }
    @Test public void nativeFailureStillAllowsMeasuredHttpsFallbackWithoutFabricatedRtt(){
        Fixture f=new Fixture(1);f.fallbackGood.add("node-00");f.start(true);f.clock.until(1_020_000);
        assertEquals("node-00",f.current);assertEquals(-1,f.health.get("node-00").latency);assertTrue(f.health.get("node-00").applicationLatency>0);
    }
    @Test public void pausedOrPinnedRouteIsNotReplacedByBackgroundProof(){
        for(boolean pause:List.of(false,true)){Fixture f=new Fixture(2);f.healthyCurrent("node-00");f.paused=pause;f.pinned=!pause;f.nativeGood.addAll(f.nodes);f.nativeMillis.put("node-01",20);f.start(true);f.clock.until(1_030_000);assertEquals("node-00",f.current);assertEquals(0,f.promotions);}
    }
    @Test public void stopPreventsLateCallbacksFromStartingMoreNetworkWork(){
        Fixture f=new Fixture(52);f.start(true);f.scan.close();f.clock.until(1_060_000);
        assertEquals(8,f.nativeStarts.size());assertEquals(0,f.nativeRecords);assertEquals(0,f.fallbackPeak);assertEquals("",f.current);
    }
    @Test public void smallPoolForcedPassesNeverOverlapSameNodeNativeProbe(){
        Fixture f=new Fixture(1);f.nativeGood.add("node-00");f.start(true);assertEquals(1,f.nativeStarts.size());f.clock.until(1_010_000);assertEquals(3,f.nativeStarts.size());assertEquals(1,f.nativePeak);
    }
}
