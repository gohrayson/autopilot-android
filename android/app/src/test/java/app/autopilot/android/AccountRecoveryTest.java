package app.autopilot.android;

import org.junit.Test;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Production trigger, serialized work queue and repository, with memory-only API/storage boundaries. */
public final class AccountRecoveryTest {
    private static final List<String> PREFERRED=List.of("MY","SG","JP");
    private static ProviderRepositoryTest.Fixture ready()throws Exception{
        ProviderRepositoryTest.Fixture f=new ProviderRepositoryTest.Fixture();f.one();f.octo();f.time.advance(301000);return f;
    }
    private static void refresh(ProviderRepository repository){repository.refreshCatalogsForRecovery("persistent-service-degraded",PREFERRED,()->true);}

    @Test public void exactSharedRecoveryScenariosDriveTheActualTrigger()throws Exception{
        JSONObject fixtures;try(InputStream input=getClass().getResourceAsStream("/policy-scenarios.json")){assertNotNull(input);fixtures=new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
        JSONArray cases=fixtures.getJSONArray("accountRecoveryCases");assertTrue(cases.length()>0);
        for(int i=0;i<cases.length();i++){
            JSONObject scenario=cases.getJSONObject(i);AccountRecoveryTrigger trigger=new AccountRecoveryTrigger();int batches=0;
            JSONArray steps=scenario.getJSONArray("steps");for(int n=0;n<steps.length();n++){
                JSONObject step=steps.getJSONObject(n);String reason=trigger.observe(step.getString("signal"),1000000+step.getLong("offsetMs"));
                if(reason!=null){new AccountRecoveryPolicy().reason(reason);batches++;}
                assertEquals(scenario.getString("id")+" step "+n,step.getInt("expectedBatches"),batches);
            }
        }
    }

    @Test public void exactSharedScenariosAlsoExerciseBothActualRepositoryCatalogRequests()throws Exception{
        JSONObject fixtures;try(InputStream input=getClass().getResourceAsStream("/policy-scenarios.json")){fixtures=new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
        JSONArray cases=fixtures.getJSONArray("accountRecoveryCases");
        for(int i=0;i<cases.length();i++){
            JSONObject scenario=cases.getJSONObject(i);ProviderRepositoryTest.Fixture f=ready();long baseline=f.time.now;int one=f.http.oneCalls,octo=f.http.octoCalls,exports=f.http.exports;
            AccountRecoveryTrigger trigger=new AccountRecoveryTrigger();JSONArray steps=scenario.getJSONArray("steps");
            for(int n=0;n<steps.length();n++){
                JSONObject step=steps.getJSONObject(n);f.time.now=baseline+step.getLong("offsetMs");String reason=trigger.observe(step.getString("signal"),1000000+step.getLong("offsetMs"));
                if(reason!=null)f.repo.refreshCatalogsForRecovery(reason,PREFERRED,()->true);
                assertEquals(scenario.getString("id")+" OneVPN HTTP",step.getInt("expectedBatches")*2,f.http.oneCalls-one);
                assertEquals(scenario.getString("id")+" Octohide metadata HTTP",step.getInt("expectedBatches")*2,f.http.octoCalls-octo);
                assertEquals(exports,f.http.exports);
            }
        }
    }

    @Test public void persistentDegradationFetchesBothCatalogsAndNewNodesNeedFreshQualification()throws Exception{
        ProviderRepositoryTest.Fixture f=ready();int one=f.http.oneCalls,octo=f.http.octoCalls,exports=f.http.exports;
        List<String> before=f.repo.nodes().stream().map(n->n.id).toList();AtomicInteger sourceChanges=new AtomicInteger();AtomicReference<List<String>> identity=new AtomicReference<>(before);
        f.repo.onChanged=()->{List<String> current=f.repo.nodes().stream().map(n->n.id).toList();if(!identity.getAndSet(current).equals(current))sourceChanges.incrementAndGet();};
        f.http.label="Japan newly published fixture";AccountRecoveryTrigger trigger=new AccountRecoveryTrigger();long at=f.time.now;
        assertNull(trigger.observe("degraded",at));assertNull(trigger.observe("degraded",at+59999));assertEquals(one,f.http.oneCalls);
        f.time.advance(60000);String reason=trigger.observe("degraded",f.time.now);assertEquals("persistent-service-degraded",reason);f.repo.refreshCatalogsForRecovery(reason,PREFERRED,()->true);
        assertTrue(f.http.oneCalls>one);assertTrue(f.http.octoCalls>octo);assertEquals("Metadata refresh must not force healthy WireGuard leases",exports,f.http.exports);
        assertEquals(1,sourceChanges.get());assertEquals("retesting",f.repo.recoveryStatus().getString("phase"));
        Node fresh=f.repo.nodes().stream().filter(n->n.provider.equals("onevpn")).findFirst().orElseThrow();assertFalse(before.contains(fresh.id));
        SelectionPolicy.Health candidate=new SelectionPolicy.Health(fresh.id,fresh.name,fresh.country);
        assertNull("Provider success alone cannot select a new node",SelectionPolicy.best(List.of(candidate),List.of(),f.time.now));
        candidate.latency(100,f.time.now);candidate.servicesAt=f.time.now;candidate.claude=candidate.chatgpt=candidate.gemini="ok";candidate.applicationVerified(f.time.now);
        assertSame(candidate,SelectionPolicy.best(List.of(candidate),List.of(),f.time.now));
    }

    @Test public void unchangedRefreshPublishesNoSourceChangeAndPersistsSpacingAcrossRestart()throws Exception{
        ProviderRepositoryTest.Fixture f=ready();List<String> before=f.repo.nodes().stream().map(n->n.id).toList();AtomicInteger profileChanges=new AtomicInteger();
        f.repo.onChanged=()->{if(!before.equals(f.repo.nodes().stream().map(n->n.id).toList()))profileChanges.incrementAndGet();};
        refresh(f.repo);int one=f.http.oneCalls,octo=f.http.octoCalls,exports=f.http.exports;
        assertEquals("unchanged",f.repo.recoveryStatus().getString("phase"));assertEquals("Unchanged pools cannot invoke profile/TUN reload via source fingerprint",0,profileChanges.get());
        long deadline=f.store.saved.get("onevpn").getJSONObject("accountRecovery").getLong("nextAttemptAt");assertEquals(f.time.now+new AccountRecoveryPolicy().refreshMinGapMs,deadline);
        ProviderRepository restored=new ProviderRepository(f.store,f.http,f.time,f.time::advance);refresh(restored);refresh(restored);
        assertEquals(one,f.http.oneCalls);assertEquals(octo,f.http.octoCalls);assertEquals(exports,f.http.exports);assertEquals("cooldown",restored.recoveryStatus().getString("phase"));
        assertEquals(deadline,restored.recoveryStatus().getLong("nextAttemptAt"));
    }

    @Test public void cooldownIsSavedBeforeHttpAndStopPreventsStartingTheOtherProvider()throws Exception{
        ProviderRepositoryTest.Fixture f=ready();f.http.blockEntered=new CountDownLatch(1);f.http.release=new CountDownLatch(1);AtomicBoolean active=new AtomicBoolean(true);
        int octo=f.http.octoCalls;ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            Future<?> pending=worker.submit(()->f.repo.refreshCatalogsForRecovery("no-verified-replacement",PREFERRED,active::get));
            assertTrue(f.http.blockEntered.await(2,TimeUnit.SECONDS));
            JSONObject durable=f.store.saved.get("onevpn").getJSONObject("accountRecovery");assertEquals(f.time.now,durable.getLong("lastAttemptAt"));assertTrue(durable.getLong("nextAttemptAt")>f.time.now);
            active.set(false);f.http.release.countDown();pending.get(3,TimeUnit.SECONDS);assertEquals(octo,f.http.octoCalls);
        }finally{f.http.release.countDown();worker.shutdownNow();}
    }

    @Test public void oneAccountFailureDoesNotPreventOtherCatalogAndCachedNodesRemain()throws Exception{
        ProviderRepositoryTest.Fixture f=ready();int octo=f.http.octoCalls;List<String> before=f.repo.nodes().stream().map(n->n.id).toList();f.http.failOneCode="TIMEOUT";
        refresh(f.repo);assertTrue(f.http.octoCalls>octo);assertEquals(before,f.repo.nodes().stream().map(n->n.id).toList());assertEquals("error",f.repo.recoveryStatus().getString("phase"));
        int one=f.http.oneCalls;ProviderRepository restored=new ProviderRepository(f.store,f.http,f.time,f.time::advance);refresh(restored);assertEquals(one,f.http.oneCalls);
    }

    @Test public void failedSecureWritePreventsRequestsAndRecentManualRefreshIsRespected()throws Exception{
        ProviderRepositoryTest.Fixture f=ready();int one=f.http.oneCalls,octo=f.http.octoCalls;f.store.failNonNullWrites=true;refresh(f.repo);
        assertEquals(one,f.http.oneCalls);assertEquals(octo,f.http.octoCalls);assertEquals("error",f.repo.recoveryStatus().getString("phase"));
        ProviderRepositoryTest.Fixture recent=new ProviderRepositoryTest.Fixture();recent.one();recent.octo();one=recent.http.oneCalls;octo=recent.http.octoCalls;refresh(recent.repo);
        assertEquals(one,recent.http.oneCalls);assertEquals(octo,recent.http.octoCalls);assertEquals("cooldown",recent.repo.recoveryStatus().getString("phase"));
    }

    @Test public void loggedOutAndCancelledIncidentsCannotIssueRequests()throws Exception{
        ProviderRepositoryTest.Fixture f=ready();f.repo.logout("onevpn");f.repo.logout("octohide");int one=f.http.oneCalls,octo=f.http.octoCalls;refresh(f.repo);
        assertEquals(one,f.http.oneCalls);assertEquals(octo,f.http.octoCalls);assertEquals("idle",f.repo.recoveryStatus().getString("phase"));
        f=ready();one=f.http.oneCalls;octo=f.http.octoCalls;f.repo.refreshCatalogsForRecovery("no-verified-replacement",PREFERRED,()->false);assertEquals(one,f.http.oneCalls);assertEquals(octo,f.http.octoCalls);
    }

    @Test public void queuedTicksAndRecoverySignalsCoalesceAndStaleOwnerJobsAreDropped(){
        ArrayDeque<Runnable> executor=new ArrayDeque<>();Object first=new Object(),second=new Object();AtomicReference<Object> owner=new AtomicReference<>(first);AtomicBoolean allowed=new AtomicBoolean(true);List<String> jobs=new ArrayList<>();
        ProviderWorkQueue queue=new ProviderWorkQueue(executor::add,owner::get,ignored->allowed.get(),ignored->allowed.get(),new ProviderWorkQueue.Work(){
            public void due(java.util.function.BooleanSupplier active){jobs.add("due");}
            public void catalogs(String reason,java.util.function.BooleanSupplier active){jobs.add(reason);}
            public void failed(List<String> names,java.util.function.BooleanSupplier active){jobs.add(String.join(",",names));}
        },()->{},()->fail("Fixture jobs must not fail"));
        for(int i=0;i<100;i++){queue.due();queue.catalogs("persistent-service-degraded");queue.failed(List.of("Synthetic selected node"));}
        assertEquals(1,executor.size());executor.remove().run();assertEquals(List.of("Synthetic selected node","persistent-service-degraded","due"),jobs);
        queue.due();owner.set(second);executor.remove().run();assertEquals(3,jobs.size());
        queue.catalogs("no-verified-replacement");allowed.set(false);executor.remove().run();assertEquals(3,jobs.size());
    }

    @Test public void disablingAutomaticRefreshBeforeDrainCancelsOnlyHealthCatalogWork(){
        ArrayDeque<Runnable> executor=new ArrayDeque<>();Object owner=new Object();AtomicBoolean enabled=new AtomicBoolean(true);List<String> work=new ArrayList<>();
        ProviderWorkQueue queue=new ProviderWorkQueue(executor::add,()->owner,ignored->true,ignored->enabled.get(),new ProviderWorkQueue.Work(){
            public void due(java.util.function.BooleanSupplier active){work.add("due");}
            public void catalogs(String reason,java.util.function.BooleanSupplier active){work.add("catalogs");}
            public void failed(List<String> names,java.util.function.BooleanSupplier active){work.add("failed");}
        },()->{},()->fail("Queue must not fail"));
        queue.catalogs("no-verified-replacement");queue.due();enabled.set(false);executor.remove().run();assertEquals(List.of("due"),work);
        queue.catalogs("persistent-service-degraded");assertTrue(executor.isEmpty());queue.due();executor.remove().run();assertEquals(List.of("due","due"),work);
    }

    @Test public void coalescedCatalogRefreshCannotPostponeAPositivelyFailedOctohideExport()throws Exception{
        ProviderRepositoryTest.Fixture f=ready();Node selected=f.repo.nodes().stream().filter(node->node.provider.equals("octohide")&&node.country.equals("MY")).findFirst().orElseThrow();
        int exports=f.http.exports,one=f.http.oneCalls;Object owner=new Object();ArrayDeque<Runnable> executor=new ArrayDeque<>();List<String> order=new ArrayList<>();
        ProviderWorkQueue queue=new ProviderWorkQueue(executor::add,()->owner,ignored->true,ignored->true,new ProviderWorkQueue.Work(){
            public void due(java.util.function.BooleanSupplier active){fail("No periodic request was queued");}
            public void catalogs(String reason,java.util.function.BooleanSupplier active){order.add("catalogs");f.repo.refreshCatalogsForRecovery(reason,PREFERRED,active);}
            public void failed(List<String> names,java.util.function.BooleanSupplier active){order.add("targeted");try{f.repo.refreshFailedNodes(names,PREFERRED);}catch(ProviderException failure){throw new AssertionError(failure);}}
        },()->{},()->fail("Recovery must succeed"));
        queue.catalogs("no-verified-replacement");queue.failed(List.of(selected.name));executor.remove().run();
        assertEquals(List.of("targeted","catalogs"),order);assertEquals(exports+1,f.http.exports);assertEquals(one+2,f.http.oneCalls);
        assertEquals(0,f.store.saved.get("octohide").getJSONArray("failedRegions").length());
    }

    @Test public void malformedPolicyAndPublicStatusFailClosedWithoutCopyingPrivateFields()throws Exception{
        JSONObject valid=ProductContract.snapshot();
        for(Object bad:Arrays.asList(null,"60000",0,-1,1.5,3600001)){
            JSONObject copy=new JSONObject(valid.toString());copy.getJSONObject("policy").getJSONObject("accountRecovery").put("persistentDegradedMs",bad==null?JSONObject.NULL:bad);
            try{ProductContract.install(new ByteArrayInputStream(copy.toString().getBytes(StandardCharsets.UTF_8)));fail("Malformed policy accepted");}catch(IOException expected){}
        }
        assertEquals(valid.toString(),ProductContract.snapshot().toString());
        for(JSONObject bad:List.of(new JSONObject(),new JSONObject(valid.getJSONObject("policy").getJSONObject("accountRecovery").toString()).put("refreshMinGapMs",59999),
                new JSONObject(valid.getJSONObject("policy").getJSONObject("accountRecovery").toString()).put("phases",new JSONArray()),
                new JSONObject(valid.getJSONObject("policy").getJSONObject("accountRecovery").toString()).put("reasons",new JSONArray().put("same").put("same")))){
            JSONObject copy=new JSONObject(valid.toString());copy.getJSONObject("policy").put("accountRecovery",bad);
            try{ProductContract.install(new ByteArrayInputStream(copy.toString().getBytes(StandardCharsets.UTF_8)));fail("Malformed policy shape accepted");}catch(IOException expected){}
        }
        JSONObject safe=AccountRecoveryStatus.safe(null);assertEquals("idle",safe.getString("phase"));
        JSONObject hostile=new JSONObject(safe.toString()).put("phase","invented").put("token","must-not-escape");JSONObject rejected=AccountRecoveryStatus.safe(hostile);
        assertEquals("error",rejected.getString("phase"));assertFalse(rejected.toString().contains("must-not-escape"));
        for(JSONObject bad:List.of(new JSONObject(safe.toString()).put("token","must-not-escape"),new JSONObject(safe.toString()).put("reason","invented-provider-url"),new JSONObject(safe.toString()).put("reason",3))){
            JSONObject guarded=AccountRecoveryStatus.safe(bad);assertEquals("error",guarded.getString("phase"));assertFalse(guarded.toString().contains("must-not-escape"));assertFalse(guarded.toString().contains("invented-provider-url"));
        }
        for(Object bad:List.of(-1,"tomorrow",1.5)){
            JSONObject copy=new JSONObject(safe.toString()).put("nextAttemptAt",bad);assertEquals("error",AccountRecoveryStatus.safe(copy).getString("phase"));
        }
        JSONObject duplicate=new JSONObject(safe.toString()).put("providers",new JSONArray().put(ProviderRepository.object("id","onevpn","phase","idle","lastAttemptAt",null,"nextAttemptAt",null)).put(ProviderRepository.object("id","onevpn","phase","idle","lastAttemptAt",null,"nextAttemptAt",null)));
        assertEquals("error",AccountRecoveryStatus.safe(duplicate).getString("phase"));
    }

    @Test public void everySharedRecoveryPhaseHasAnExplicitUsableMessageAndFreshProofFinishesRetesting()throws Exception{
        JSONArray phases=ProductContract.policy().getJSONObject("accountRecovery").getJSONArray("phases");
        for(int i=0;i<phases.length();i++){
            String phase=phases.getString(i);JSONObject state=ProviderRepository.object("phase",phase,"reason",null,"lastAttemptAt",null,"nextAttemptAt",null,"providers",new JSONArray());
            assertEquals(phase,AccountRecoveryStatus.validate(state).getString("phase"));String message=AccountRecoveryStatus.message(state);
            if("idle".equals(phase))assertEquals("",message);else assertFalse("Missing UI for "+phase,message.isEmpty());
            assertFalse("Generic refresh message cannot claim a failed route remains connected",message.contains("connection stays active"));
        }
        ProviderRepositoryTest.Fixture f=ready();f.http.label="Japan fresh settlement fixture";refresh(f.repo);assertEquals("retesting",f.repo.recoveryStatus().getString("phase"));
        Node selected=f.repo.nodes().stream().filter(node->node.provider.equals("onevpn")).findFirst().orElseThrow();f.repo.currentNodeName=selected.name;
        f.repo.recoveryQualified("onevpn",selected.name,f.time.now-1);assertEquals("retesting",f.repo.recoveryStatus().getString("phase"));
        f.repo.recoveryQualified("onevpn",selected.name,f.time.now);assertNotEquals("retesting",f.repo.recoveryStatus().getString("phase"));
    }

    @Test public void lifecycleClearsOnlyLiveRecoveryPhaseAndKeepsAttemptHistoryWhilePendingIsExplicit()throws Exception{
        JSONObject previous=ProviderRepository.object("phase","retesting","reason","no-verified-replacement","lastAttemptAt",1000,"nextAttemptAt",301000,"providers",new JSONArray().put(ProviderRepository.object("id","onevpn","phase","retesting","lastAttemptAt",1000,"nextAttemptAt",301000)));
        for(JSONObject idle:List.of(AccountRecoveryStatus.lifecycle(previous,false,false,0,2000),AccountRecoveryStatus.lifecycle(previous,true,true,0,2000))){
            assertEquals("idle",idle.getString("phase"));assertTrue(idle.isNull("reason"));assertEquals(1000,idle.getLong("lastAttemptAt"));assertEquals(301000,idle.getLong("nextAttemptAt"));assertEquals("idle",idle.getJSONArray("providers").getJSONObject(0).getString("phase"));assertEquals("",AccountRecoveryStatus.message(idle));
        }
        JSONObject pending=AccountRecoveryStatus.lifecycle(previous,true,false,62000,2000);assertEquals("pending",pending.getString("phase"));assertEquals(62000,pending.getLong("nextAttemptAt"));
        JSONObject invalid=new JSONObject(previous.toString()).put("private","must-not-escape");assertEquals("error",AccountRecoveryStatus.lifecycle(invalid,false,true,0,2000).getString("phase"));
    }
}
