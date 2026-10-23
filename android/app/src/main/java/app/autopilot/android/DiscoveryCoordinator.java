package app.autopilot.android;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The production discovery control pipeline. All state/health callbacks run on the
 * controller, while native, HTTPS and service operations complete asynchronously.
 * A bounded native window scans ahead of slow HTTPS fallbacks; no input-order get()
 * can hide a completed healthy result behind a failed node.
 */
final class DiscoveryCoordinator<N> {
    interface Scheduler {
        void execute(Runnable job);
        void later(Runnable job,long delayMillis);
    }
    interface Driver<N> {
        long now();
        CompletionStage<HealthDiagnostics.Latency> nativeProbe(N node);
        CompletionStage<Integer> fallback(N node,int lane);
        CompletionStage<ConnectionWatchdog.Result> transport(N node);
        CompletionStage<ServiceChecks.Result> services(N node);
        boolean recordNative(N node,HealthDiagnostics.Latency sample);
        boolean recordFallback(N node,int millis);
        boolean recordTransport(N node,ConnectionWatchdog.Result result);
        boolean recordServices(N node,ServiceChecks.Result result);
        boolean canProve(N node);
        Comparator<N> order();
        boolean needsRoute();
        int proofLimit();
        /** Fresh proof is complete. Only first/recovery selection may happen here. */
        void proved(N node,boolean finalComparison,boolean force)throws Exception;
        N finalCandidate(Set<N> attempted,boolean force);
        void ended(boolean complete,boolean force);
        void progress(State state);
        void failed(Throwable failure);
    }
    static final class State {
        final long cycle,elapsedMillis;
        final int cursor,total,passes,nativeActive,fallbackActive,fallbackQueued,proofs,requestCount,coalescedCount;
        final boolean active,force;
        final String code;
        State(long cycle,long elapsedMillis,int cursor,int total,int passes,int nativeActive,int fallbackActive,int fallbackQueued,int proofs,int requestCount,int coalescedCount,boolean active,boolean force,String code){
            this.cycle=cycle;this.elapsedMillis=elapsedMillis;this.cursor=cursor;this.total=total;this.passes=passes;this.nativeActive=nativeActive;this.fallbackActive=fallbackActive;this.fallbackQueued=fallbackQueued;this.proofs=proofs;this.requestCount=requestCount;this.coalescedCount=coalescedCount;this.active=active;this.force=force;this.code=code;
        }
    }
    private final class Cycle {
        final long id,generation,token;
        final List<N> nodes;
        final LinkedHashSet<N> fallback=new LinkedHashSet<>(),ready=new LinkedHashSet<>(),proved=new LinkedHashSet<>(),attempted=new LinkedHashSet<>(),retried=new LinkedHashSet<>(),retry=new LinkedHashSet<>(),finalAttempted=new LinkedHashSet<>();
        long started,deadline;
        int cursor,passes,proofCount,requestCount=1,coalescedCount;
        boolean active=true,force,partialCompared;
        Cycle(List<N> nodes,long generation,boolean force){this.id=++cycles;this.nodes=List.copyOf(nodes);this.generation=generation;this.token=epoch.get();this.force=force;this.passes=force?forcedPasses:1;this.started=driver.now();this.deadline=started+budgetMillis;}
    }
    private final class Work {
        final Cycle owner;final N node;final boolean priority;final long token,generation;
        Work(Cycle owner,N node,boolean priority){this.owner=owner;this.node=node;this.priority=priority;this.token=epoch.get();this.generation=DiscoveryCoordinator.this.generation;}
    }
    private final Scheduler scheduler;
    private final Driver<N> driver;
    private final int forcedPasses,retries;
    private final long budgetMillis;
    private final AtomicLong epoch=new AtomicLong();
    private final Set<Work> nativeWork=new HashSet<>();
    private final ArrayList<Work> fallbackWork=new ArrayList<>(Arrays.asList(null,null));
    private final LinkedHashSet<N> priorities=new LinkedHashSet<>();
    private Work proof;
    private Cycle cycle,lastCycle;
    private long cycles,generation;
    private boolean pumpQueued;
    private volatile boolean closed;
    private String code="DISCOVERY_IDLE";

    DiscoveryCoordinator(Scheduler scheduler,Driver<N> driver,long budgetMillis,int forcedPasses,int retries){
        if(budgetMillis<=0||forcedPasses<1||retries<0)throw new IllegalArgumentException("Invalid discovery policy");
        this.scheduler=scheduler;this.driver=driver;this.budgetMillis=budgetMillis;this.forcedPasses=forcedPasses;this.retries=retries;
    }
    /** May be called from the UI/control caller before its controller job runs. */
    void invalidate(){epoch.incrementAndGet();}
    /** Controller only. Repeated requests upgrade/coalesce the same logical scan. */
    void request(List<N> nodes,long generation,boolean force){
        if(closed)return;
        this.generation=generation;
        boolean arm=false;
        if(cycle==null||!valid(cycle)||cycle.generation!=generation||cycle.nodes.size()!=nodes.size()||!new HashSet<>(cycle.nodes).equals(new HashSet<>(nodes))){
            cycle=new Cycle(nodes,generation,force);code="DISCOVERY_STARTED";
            arm=true;
        }else{
            cycle.requestCount++;cycle.coalescedCount++;
            if(force){cycle.force=true;cycle.passes=forcedPasses;}
            if(!cycle.active){cycle.active=true;cycle.partialCompared=false;cycle.started=driver.now();cycle.deadline=cycle.started+budgetMillis;code="DISCOVERY_RESUMED";arm=true;}
            else code="DISCOVERY_COALESCED";
        }
        Cycle requested=cycle;long deadline=requested.deadline;
        if(arm){
            scheduler.later(()->{if(cycle==requested&&valid(requested)&&requested.active&&requested.deadline==deadline)queuePump();},Math.max(1,deadline-driver.now()));
            scheduler.later(()->{if(cycle==requested&&valid(requested)&&requested.active&&requested.deadline==deadline)queuePump();},Math.max(1,deadline-driver.now()-Math.min(21_000,budgetMillis/2)));
        }
        queuePump();
    }
    /** Active-route RTT uses the same eight permits, never a ninth native call. */
    void priority(N node){if(!closed&&node!=null){priorities.add(node);queuePump();}}
    void cancelled(){
        if(cycle!=null&&!valid(cycle)){cycle=null;code="DISCOVERY_CANCELLED";}
        priorities.clear();publish();
    }
    void close(){closed=true;invalidate();}
    private boolean valid(Cycle owner){return !closed&&owner!=null&&owner.token==epoch.get()&&owner.generation==generation;}
    private void queuePump(){if(closed||pumpQueued)return;pumpQueued=true;scheduler.execute(()->{pumpQueued=false;try{pump();}catch(Throwable failure){driver.failed(failure);}});}
    private void pump(){
        if(closed)return;
        Cycle found=cycle;
        if(found!=null&&!valid(found)){cycle=null;found=null;code="DISCOVERY_CANCELLED";}
        final Cycle c=found;
        if(c!=null&&c.active&&driver.now()>=c.deadline){c.active=false;code="DISCOVERY_BUDGET_EXHAUSTED";driver.ended(false,c.force);}
        while(nativeWork.size()<8&&!priorities.isEmpty()){
            N node=priorities.iterator().next();priorities.remove(node);
            if(nativeWork.stream().noneMatch(w->w.node.equals(node)))startNative(c,node,true);
        }
        if(c==null||!c.active){publish();return;}
        // Initial native discovery always precedes retry work. Failed native nodes
        // do not occupy the scan window while their independent HTTPS fallback runs.
        while(nativeWork.size()<8&&c.cursor<c.nodes.size()*c.passes){
            N node=c.nodes.get(c.cursor%c.nodes.size());
            if(nativeWork.stream().anyMatch(w->w.node.equals(node)))break;
            c.cursor++;startNative(c,node,false);
        }
        while(nativeWork.size()<8&&c.cursor>=c.nodes.size()*c.passes&&!c.retry.isEmpty()){
            N node=c.retry.iterator().next();if(nativeWork.stream().anyMatch(w->w.node.equals(node)))break;c.retry.remove(node);startNative(c,node,false);
        }
        for(int lane=0;lane<2;lane++)if(fallbackWork.get(lane)==null&&!c.fallback.isEmpty()){
            N node=c.fallback.iterator().next();c.fallback.remove(node);startFallback(c,node,lane);
        }
        // A partial slice gets one normal comparison under dwell/improvement and
        // country rules. Reserve the HTTPS+service deadlines; force is consumed
        // only by the final comparison after the complete logical forced scan.
        if(proof==null&&!c.partialCompared&&driver.now()>=c.deadline-Math.min(21_000,budgetMillis/2)&&!driver.needsRoute()){
            c.partialCompared=true;N candidate=driver.finalCandidate(Set.of(),false);
            if(candidate!=null)startProof(c,candidate,true,false);
        }
        if(proof==null){
            N candidate=c.ready.stream().filter(n->!c.attempted.contains(n)&&driver.canProve(n)).min(driver.order()).orElse(null);
            if(candidate!=null&&(driver.needsRoute()||c.proofCount<driver.proofLimit())){
                c.ready.remove(candidate);c.attempted.add(candidate);c.proofCount++;startProof(c,candidate,false,false);
            }
        }
        boolean allNative=c.cursor>=c.nodes.size()*c.passes&&c.retry.isEmpty()&&nativeWork.stream().noneMatch(w->w.owner==c);
        boolean allFallback=c.fallback.isEmpty()&&fallbackWork.stream().noneMatch(w->w!=null&&w.owner==c);
        boolean moreProof=c.ready.stream().anyMatch(n->!c.attempted.contains(n)&&driver.canProve(n))&&(driver.needsRoute()||c.proofCount<driver.proofLimit());
        if(allNative&&allFallback&&proof==null&&!moreProof){
            N candidate=driver.finalCandidate(Collections.unmodifiableSet(c.finalAttempted),c.force);
            if(candidate!=null&&c.finalAttempted.size()<ProductContract.policyInt("recoveryCandidateLimit")){c.finalAttempted.add(candidate);startProof(c,candidate,true,c.force);}
            else{c.active=false;code="DISCOVERY_COMPLETED";driver.ended(true,c.force);lastCycle=c;cycle=null;}
        }
        publish();
    }
    private void startNative(Cycle c,N node,boolean priority){
        Work work=new Work(c,node,priority);nativeWork.add(work);
        try{driver.nativeProbe(node).whenComplete((sample,failure)->scheduler.execute(()->{
            nativeWork.remove(work);
            if(!closed&&work.token==epoch.get()&&work.generation==generation&&(c==null?priority:valid(c))){
                boolean ok=driver.recordNative(node,failure==null&&sample!=null?sample:HealthDiagnostics.failed(HealthDiagnostics.LatencyCode.CHECK_TIMEOUT));
                if(c!=null){if(ok)c.ready.add(node);else{c.fallback.add(node);if(retries>0&&c.retried.add(node))c.retry.add(node);}}
            }
            queuePump();
        }));}catch(Throwable failure){nativeWork.remove(work);driver.failed(failure);}
    }
    private void startFallback(Cycle c,N node,int lane){
        Work work=new Work(c,node,false);fallbackWork.set(lane,work);
        try{driver.fallback(node,lane).whenComplete((millis,failure)->scheduler.execute(()->{
            fallbackWork.set(lane,null);
            if(valid(c)&&driver.recordFallback(node,failure==null&&millis!=null?millis:0))c.ready.add(node);
            queuePump();
        }));}catch(Throwable failure){fallbackWork.set(lane,null);driver.failed(failure);}
    }
    private void startProof(Cycle c,N node,boolean finalComparison,boolean comparisonForce){
        Work work=new Work(c,node,false);proof=work;
        // The discovery measurement is not the independent proof. Always make a
        // fresh HTTPS round and required-service check before any promotion.
        try{driver.transport(node).whenComplete((result,failure)->scheduler.execute(()->{
            if(proof!=work)return;
            if(!valid(c)||!c.active||driver.now()>=c.deadline){proof=null;c.attempted.remove(node);c.finalAttempted.remove(node);c.ready.add(node);queuePump();return;}
            if(failure!=null||result==null||!driver.recordTransport(node,result)){proof=null;queuePump();return;}
            try{driver.services(node).whenComplete((services,serviceFailure)->scheduler.execute(()->{
                if(proof!=work)return;proof=null;
                if(valid(c)&&serviceFailure==null&&services!=null&&driver.recordServices(node,services)){
                    try{driver.proved(node,finalComparison,comparisonForce);c.proved.add(node);}catch(Throwable error){driver.failed(error);}
                }
                queuePump();
            }));}catch(Throwable error){proof=null;driver.failed(error);queuePump();}
        }));}catch(Throwable failure){proof=null;driver.failed(failure);}
    }
    private void publish(){
        Cycle c=cycle==null?lastCycle:cycle;int activeFallback=0;for(Work work:fallbackWork)if(work!=null)activeFallback++;
        driver.progress(new State(c==null?cycles:c.id,c==null?0:Math.max(0,driver.now()-c.started),c==null?0:c.cursor,c==null?0:c.nodes.size(),c==null?0:c.passes,nativeWork.size(),activeFallback,c==null?0:c.fallback.size(),c==null?0:c.proofCount,c==null?0:c.requestCount,c==null?0:c.coalescedCount,c!=null&&c.active,c!=null&&c.force,code));
    }
}
