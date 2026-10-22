package app.autopilot.android;

import android.os.Build;
import android.os.ParcelFileDescriptor;
import io.github.oviron.libmihomo.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One serialized controller; preference writes and provider work stay outside network checks. */
public final class VpnEngine {
    private static final String TUN_STACK="gvisor";
    private final AutopilotVpnService service;
    private final AutopilotApp app;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final ServiceChecks checks=new ServiceChecks();
    private final ReachabilityChecks candidateReachability=new ReachabilityChecks(),watchdogReachability=new ReachabilityChecks();
    private final ConnectionWatchdog watchdog=new ConnectionWatchdog(watchdogReachability,this::watchdogResult);
    private volatile ConnectionWatchdog.Snapshot monitorTarget;
    private long profileGeneration,selectionEpoch;
    private ConnectionWatchdog.Code watchdogCode=ConnectionWatchdog.Code.IDLE;
    private long watchdogLastCheck,watchdogLastSuccess;
    private int watchdogFailedOrigins,watchdogConsecutive;
    private final AtomicBoolean running=new AtomicBoolean(),stopping=new AtomicBoolean(),sweepQueued=new AtomicBoolean(),preferencesQueued=new AtomicBoolean(),nodesQueued=new AtomicBoolean();
    private final Map<String,SelectionPolicy.Health> health=new HashMap<>();
    private final Map<String,HealthDiagnostics.Latency> latencyDiagnostics=new HashMap<>();
    private final HealthDiagnostics.NetworkSignals networkSignals=new HealthDiagnostics.NetworkSignals();
    private final Map<String,Integer> ports=new HashMap<>();
    private final AtomicLong sequence=new AtomicLong();
    private final Object operationLock=new Object();
    private volatile Thread activeThread;
    private volatile boolean inSweep;
    private boolean preempted;
    private volatile boolean nativeReady,tunReady;
    private volatile CompletableFuture<Boolean> tunStarted;
    private volatile List<Node> nodes=List.of();
    private Node current;
    private String pinned="",phase="idle",error="",detail="",stage="";
    private long lastSwitch;
    private String installedRules="";
    private final AtomicBoolean routingReloadQueued=new AtomicBoolean(),forceSweepRequested=new AtomicBoolean();
    private boolean busy;
    private boolean preserveError;
    private boolean startedOnce;
    private volatile boolean profileUsable;
    private static final class UpdateRoute {
        final int port; final long checkedAt, expiresAt;
        UpdateRoute(int port,long checkedAt,long expiresAt){this.port=port;this.checkedAt=checkedAt;this.expiresAt=expiresAt;}
    }
    private static final UpdateRoute NO_UPDATE_ROUTE=new UpdateRoute(-1,0,0);
    private volatile UpdateRoute updateRoute=NO_UPDATE_ROUTE;
    private boolean baselineAccepted;
    private volatile List<ProfileValidation.Skipped> coreSkipped=List.of();
    private long lastSweep;
    private long lastCurrentCheck,lastVerify,nextRecovery;private int recoveryAttempt,exploreCursor;private long nextTick;
    private File home;
    public VpnEngine(AutopilotVpnService service,AutopilotApp app){this.service=service;this.app=app;}
    /** Thread-safe updater snapshot. The caller uses direct only after this engine is removed. */
    public int updateProxyPort(){
        UpdateRoute route=updateRoute;long time=now();
        return running.get()&&!stopping.get()&&nativeReady&&profileUsable&&tunReady
                &&time>=route.checkedAt&&time<=route.expiresAt?route.port:-1;
    }
    public void start(){if(!running.compareAndSet(false,true))return;submit(()->{
        // The first serialized job publishes its phase before any startup I/O.
        phase="starting";busy=true;progress("preparing","Preparing connection");
        home=new File(app.getFilesDir(),"core");if(!home.isDirectory()&&!home.mkdirs())throw new IOException("CORE_STORAGE");
        Clash.INSTANCE.load(app.getApplicationInfo().nativeLibraryDir);Clash.INSTANCE.assertReady();nativeReady=true;
        // Establish that this platform can accept our global configuration independently
        // of provider nodes. No Android interface exists while these semantic gates run.
        progress("settings","Checking VPN settings");
        nodes=List.of();syncHealth();JSONObject baselineProfile=writeConfig();
        JSONObject baselineInit=initParameters(),baselineSetup=selected("REJECT");
        String baseline=quickSetup(baselineInit,baselineSetup);
        if(!"".equals(baseline)){
            CoreDiagnostics.Diagnostic diagnostic=CoreDiagnostics.classify(baseline,List.of());
            app.recordBaselineDiagnostic(CoreDiagnostics.baselineDiagnostic(baseline,home.getAbsolutePath(),baselineProfile,baselineInit,baselineSetup));
            throw new IOException(diagnostic.code);
        }
        baselineAccepted=true;app.recordCoreAccepted(CoreDiagnostics.Stage.BASELINE);app.log("core","CORE_BASELINE_ACCEPTED");
        ProfileValidation.Result profile=validateProfile(validatedNodes(),"",CoreDiagnostics.Stage.PROFILE);
        if(!profile.accepted)throw new IOException(rejectionCode(profile));
        // Core events are inspected only for the TUN lifecycle. Arbitrary upstream log text is never persisted.
        Clash.INSTANCE.setEventListener((InvokeInterface)this::event);
        action("setFileEnabled",false,5000);action("setLogcatLevel","error",5000);action("startLog",null,5000);
        verifySelector("REJECT");
        establish(false);startedOnce=true;profileUsable=true;
        worker.scheduleWithFixedDelay(()->guard(()->{if(now()>=nextTick){try{tick();}finally{nextTick=now()+settingLong("tickMs");}}}),1000,1000,TimeUnit.MILLISECONDS);
        if(nodes.isEmpty()){busy=false;stage="";noNodes();publish();}
        else sweep(true);
    });}
    public void stop(){
        if(!stopping.compareAndSet(false,true))return;
        monitorTarget=null;watchdog.close();watchdogReachability.close();candidateReachability.close();checks.cancelActive();
        updateRoute=NO_UPDATE_ROUTE;
        running.set(false);Thread thread=activeThread;if(thread!=null)thread.interrupt();
        try{worker.execute(()->{Thread.interrupted();cleanup();});}catch(RejectedExecutionException ignored){cleanup();}
    }
    public void retest(){if(!profileUsable||nodes.isEmpty()&&!coreSkipped.isEmpty())nodesChanged();else queueSweep(true);}
    public void select(String name,boolean pin){preemptSweep();submit(()->{
        if(!profileUsable){nodesChanged();return;}
        Node target=findByName(name);if(target==null){error="NODE_UNAVAILABLE";publish();return;}
        busy=true;progress("connections","Checking selected node connection");
        try{
            if(!verifyCandidate(target,0)){error="NODE_NOT_HEALTHY";detail="Selected node did not pass fresh connection and application checks";return;}
            if(pin)pinned=target.id;else{pinned="";app.setSettings(new JSONObject().put("paused",false));}
            if(current==null||!current.id.equals(target.id))promote(target,true);
            else{phase="connected";error="";detail=connectedDetail();}
        }finally{finishChecks();publish();}
    });}
    public void resumeAuto(){app.setSettings(safePatch("paused",false));preemptSweep();submit(()->{pinned="";if(!profileUsable||nodes.isEmpty()&&!coreSkipped.isEmpty()){nodesChanged();return;}error="";decide(true);if(current!=null&&phase.equals("connected"))detail=connectedDetail();queueSweep(false);publish();});}
    public void pauseAuto(){app.setSettings(safePatch("paused",true));submit(()->{detail="Auto paused; the current connection remains selected";publish();});}
    private static JSONObject safePatch(String key,Object value){try{return new JSONObject().put(key,value);}catch(JSONException impossible){throw new IllegalArgumentException(impossible);}}
    public void settingsChanged(){preferencesChanged();}
    private long settingLong(String key){return app.settings().optLong(key);}
    private boolean paused(){return app.settings().optBoolean("paused");}
    private List<String> required(){JSONArray values=app.settings().optJSONArray("required");ArrayList<String> required=new ArrayList<>();if(values!=null)for(int i=0;i<values.length();i++)required.add(values.optString(i));return required;}
    private boolean shouldSwitch(SelectionPolicy.Health active,SelectionPolicy.Health candidate,boolean force){return SelectionPolicy.shouldSwitch(active,candidate,app.preferred(),now(),lastSwitch,force,settingLong("minDwellMs"),app.settings().optDouble("switchRatio"));}
    public void preferencesChanged(){
        if(!preferencesQueued.compareAndSet(false,true))return;
        preemptSweep();
        submit(()->{preferencesQueued.set(false);syncHealth();if(reconcileRoutingSettings(installedRules,app.settings(),AndroidRouting.geoAvailable(app),()->nodesChanged(true))){publish();return;}if(!profileUsable){nodesChanged();return;}if(!pinned.isEmpty()||paused()){publish();return;}decide(false);queueSweep(false);publish();});
    }
    public void nodesChanged(){nodesChanged(false);}
    private void nodesChanged(boolean forceRouting){
        if(forceRouting)routingReloadQueued.set(true);
        if(!nodesQueued.compareAndSet(false,true))return;
        preemptSweep();
        submit(()->{
            nodesQueued.set(false);boolean forceRoutingReload=routingReloadQueued.getAndSet(false);List<Node> latest=validatedNodes();
            if(sameNodes(nodes,latest)&&profileUsable&&!forceRoutingReload)return;
            String prior=current==null?"":current.id,priorPin=pinned;
            final Node[] survivor={null};final String[] selectedName={"REJECT"};
            final ProfileValidation.Result[] validated={null};
            boolean accepted=reloadSafely(new ProfileTransition(){
                @Override public void pause(){
                    invalidateMonitor();profileGeneration++;
                    for(SelectionPolicy.Health h:health.values()){h.transportAt=0;h.applicationLatency=-1;}
                    updateRoute=NO_UPDATE_ROUTE;
                    profileUsable=false;tunReady=false;current=null;pinned="";busy=true;phase="testing";
                    stage="profile";detail="Updating VPN profile; traffic is paused";publish();
                    // The Java route guard remains open. No packets can enter a newly
                    // parsed native profile until quickSetup has reported semantic success.
                    Clash.INSTANCE.stopTun();
                }
                @Override public boolean install(boolean fallback)throws Exception{
                    if(!fallback){
                        validated[0]=validateProfile(latest,prior,CoreDiagnostics.Stage.PROFILE);
                        survivor[0]=validated[0].selected;selectedName[0]=survivor[0]==null?"REJECT":survivor[0].name;
                        return validated[0].accepted;
                    }
                    survivor[0]=null;selectedName[0]="REJECT";nodes=List.of();syncHealth();writeConfig();
                    String recovery=quickSetup("REJECT");
                    if(!"".equals(recovery))app.recordCoreDiagnostic(CoreDiagnostics.classify(recovery,List.of()),CoreDiagnostics.Stage.RECOVERY,coreSkipped.size());
                    else{app.recordCoreAccepted(CoreDiagnostics.Stage.RECOVERY);app.log("core","CORE_RECOVERY_ACCEPTED");}
                    return "".equals(recovery);
                }
                @Override public void attach()throws Exception{
                    verifySelector(selectedName[0]);
                    establish(true);
                }
            });
            busy=false;stage="";
            if(accepted){
                profileUsable=true;current=survivor[0];pinned=current!=null&&priorPin.equals(current.id)?priorPin:"";
                phase=current==null?"testing":"connected";error="";detail=current==null?"Provider nodes updated; checking health":connectedDetail();
                if(nodes.isEmpty())noNodes();else queueSweep(true);
            }else{
                profileUsable=false;current=null;pinned="";phase="degraded";error=validated[0]==null?"CORE_CONFIG_REJECTED":rejectionCode(validated[0]);
                String message=CoreDiagnostics.messageForCode(error);detail=(message==null?"The VPN profile was rejected.":message)+" VPN is blocking traffic.";
                app.log("vpn",error);
            }
            publish();
        });
    }
    static boolean reconcileRoutingSettings(String installed,JSONObject settings,boolean geoAvailable,Runnable reloadProfile)throws Exception{
        String requested=AndroidRouting.buildRules(settings,geoAvailable).toString();
        if(requested.equals(installed))return false;
        reloadProfile.run();return true;
    }
    /** The native parser applies DIRECT defaults on errors: attachment must be a separate, gated step. */
    interface ProfileTransition{
        void pause()throws Exception;
        boolean install(boolean safeEmpty)throws Exception;
        void attach()throws Exception;
    }
    static boolean reloadSafely(ProfileTransition transition)throws Exception{
        transition.pause();
        if(transition.install(false)){transition.attach();return true;}
        transition.pause();
        if(!transition.install(true))throw new IOException("CORE_CONFIG_REJECTED");
        transition.attach();return false;
    }
    private ProfileValidation.Result validateProfile(List<Node> source,String selectedId,CoreDiagnostics.Stage stage)throws Exception{
        coreSkipped=List.of();
        ProfileValidation.Result result=ProfileValidation.validate(source,selectedId,(candidates,selectedName)->{
            ensureRunning();progress("profile","Loading servers: "+candidates.size()+" nodes");
            nodes=List.copyOf(candidates);syncHealth();writeConfig();return quickSetup(selectedName);
        },(diagnostic,skipped)->{
            coreSkipped=skipped;app.recordCoreDiagnostic(diagnostic,stage,skipped.size());publish();
        });
        coreSkipped=result.skipped;nodes=result.nodes;syncHealth();
        if(result.accepted){app.recordCoreAccepted(stage);app.log("core","CORE_PROFILE_ACCEPTED");}
        return result;
    }
    private static String rejectionCode(ProfileValidation.Result profile){return profile.limitReached?CoreDiagnostics.Kind.PROXY_LIMIT.code:profile.lastDiagnostic==null?"CORE_CONFIG_REJECTED":profile.lastDiagnostic.code;}
    private void noNodes(){
        updateRoute=NO_UPDATE_ROUTE;
        phase="degraded";
        if(!coreSkipped.isEmpty()){error=CoreDiagnostics.Kind.PROXY_EMPTY.code;detail="The cached nodes are incompatible with the VPN core. See diagnostics. VPN is blocking traffic.";}
        else{error="NO_NODES";detail="No nodes. Sign in to a provider or import a profile.";}
    }
    private interface Job{void run()throws Exception;}
    private void submit(Job job){if(!running.get()||stopping.get())return;try{worker.execute(()->guard(job));}catch(RejectedExecutionException ignored){}}
    private void guard(Job job){
        if(!running.get())return;
        synchronized(operationLock){activeThread=Thread.currentThread();preempted=false;}
        try{job.run();}
        catch(InterruptedException interrupted){Thread.interrupted();
            if(running.get()&&!preempted)failure("CHECK_CANCELLED");
            else if(running.get()){busy=false;stage="";detail=current==null?"Applying latest selection":connectedDetail();publish();}
        }
        catch(Throwable exception){if(running.get()){
            String code=exception instanceof TimeoutException?"CORE_TIMEOUT":nativeReady?"VPN_OPERATION_FAILED":"CORE_UNAVAILABLE";
            Set<String> known=Set.of("CORE_STORAGE","CORE_CONFIG_REJECTED","SELECTOR_MISMATCH","CORE_ACTION_FAILED","TUN_START_FAILED","VPN_PERMISSION_REQUIRED");
            if(exception.getMessage()!=null&&(known.contains(exception.getMessage())||CoreDiagnostics.messageForCode(exception.getMessage())!=null))code=exception.getMessage();failure(code);
        }}
        finally{synchronized(operationLock){activeThread=null;preempted=false;Thread.interrupted();}}
    }
    private void preemptSweep(){synchronized(operationLock){if(inSweep&&activeThread!=null){preempted=true;activeThread.interrupt();}}}
    private void failure(String code){
        updateRoute=NO_UPDATE_ROUTE;
        boolean guarded=startedOnce&&service.hasTunnel();
        error=code;phase=tunReady||guarded?"degraded":"error";
        String message=CoreDiagnostics.messageForCode(code);
        detail=message!=null?message+(guarded?" VPN traffic is paused.":""):tunReady?"VPN needs attention. See diagnostics.":guarded?"VPN traffic is paused. Retry with a valid profile or stop the VPN.":"VPN could not start. See diagnostics.";
        busy=false;stage="";app.log("vpn",code);publish();
        if(!tunReady&&!guarded){preserveError=true;stop();}
    }
    private void queueSweep(boolean force){
        if(force)forceSweepRequested.set(true);
        if(!sweepQueued.compareAndSet(false,true))return;
        submit(()->{sweepQueued.set(false);boolean requestedForce=forceSweepRequested.getAndSet(false);
            if(!profileUsable)return;
            if(preferencesQueued.get()||nodesQueued.get()){queueSweep(requestedForce);return;}
            sweep(requestedForce);
        });
    }
    private void tick()throws Exception{
        if(!tunReady||!profileUsable)return;
        // Native RTT and independent application HTTPS are distinct measurements.
        // The watchdog continues on its own lane while this serialized discovery runs.
        if(current!=null){
            Node checked=current;probeLatency(checked);SelectionPolicy.Health h=record(checked);
            if(!h.connectionFresh(now())&&h.failures>=settingLong("failLimit")&&!paused()){
                h.applicationFailed(now());pinned="";decide(true);if(current==checked)rejectCurrent();scheduleRecovery();
            }
        }
        if(current!=null&&now()-lastVerify>=settingLong("verifyMs")){lastVerify=now();probeServices(current);if(!record(current).qualified(now())){decide(false);warnRetainedServicePath();}}
        if(current==null&&!paused()&&now()>=nextRecovery)recoverBounded();
        if(now()-lastSweep>=settingLong("sweepMs"))queueSweep(false);
        publish();
    }
    private void sweep(boolean force)throws Exception{
        if(nodes.isEmpty()){lastSweep=now();busy=false;stage="";noNodes();publish();return;}
        inSweep=true;busy=true;if(current==null)phase="testing";
        progress("connections","Testing connections: 0 of "+nodes.size());
        try{
            // Bounded batches keep a large account from opening hundreds of sockets at once.
            for(int pass=0;pass<(force?ProductContract.policyInt("forcedSweepPasses"):1);pass++)for(int offset=0;offset<nodes.size();offset+=8){
                ensureRunning();List<Node> batch=nodes.subList(offset,Math.min(offset+8,nodes.size()));
                ArrayList<CompletableFuture<JSONObject>> pending=new ArrayList<>();
                for(Node n:batch)pending.add(actionAsync("testDelay",new JSONObject().put("proxy-name",n.name).put("test-url","https://www.gstatic.com/generate_204").put("timeout",5000).toString()));
                for(int i=0;i<batch.size();i++){
                    Node checked=batch.get(i);LatencyMeasurement result=measureLatency(checked,pending.get(i));
                    for(int retry=0;!result.ok&&retry<ProductContract.policyInt("latencyRetryCount");retry++)result=measureLatency(checked,null);
                    recordMeasurement(checked,result);
                    if(current!=null&&current.id.equals(batch.get(i).id))lastCurrentCheck=now();
                    stage="connections";detail="Testing connections: "+(offset+i+1)+" of "+nodes.size()+" · pass "+(pass+1)+" of "+(force?ProductContract.policyInt("forcedSweepPasses"):1);
                }
                publish();
            }
            requestProviderRecovery();
            ArrayList<SelectionPolicy.Health> ordered=new ArrayList<>(health.values());ordered.sort(Comparator.comparingInt((SelectionPolicy.Health h)->SelectionPolicy.tier(h.country,app.preferred())).thenComparingDouble(h->h.connectionScore(now())).thenComparing(h->h.name));
            ArrayList<SelectionPolicy.Health> candidates=new ArrayList<>();
            for(SelectionPolicy.Health h:ordered)if(h.connectionFresh(now())&&(force||!h.qualified(now())))candidates.add(h);
            LinkedHashSet<SelectionPolicy.Health> budget=new LinkedHashSet<>();
            if(current!=null&&candidates.contains(record(current)))budget.add(record(current));
            for(String country:app.preferred())for(SelectionPolicy.Health h:candidates)if(country.equals(h.country)){budget.add(h);break;}
            ArrayList<SelectionPolicy.Health> exploring=new ArrayList<>(candidates);exploring.removeAll(budget);
            int limit=(int)settingLong("checkTopK");if(exploring.size()>Math.max(0,limit-budget.size()))budget.add(exploring.get(exploreCursor++%exploring.size()));
            int count=Math.max(limit,budget.size());for(SelectionPolicy.Health h:candidates){if(budget.size()>=count)break;budget.add(h);}
            for(SelectionPolicy.Health h:budget){ensureRunning();Node n=findById(h.id);if(n!=null)probeServices(n);publish();}
            decide(force);lastSweep=now();
            if(current==null || !currentUsable()){
                phase="degraded";error="NO_HEALTHY_NODE";detail="No node passed all checks. VPN stays active and blocks unverified traffic.";
                if(current!=null){action("changeProxy",new JSONObject().put("group-name","AUTOPILOT").put("proxy-name","REJECT").toString(),5000);action("closeAllConnections",null,5000);current=null;pinned="";invalidateMonitor();}
            }else warnRetainedServicePath();
        }finally{inSweep=false;finishChecks();publish();}
    }
    private void decide(boolean force)throws Exception{
        if(paused())return;
        if(!pinned.isEmpty()){
            Node held=findById(pinned);if(held!=null&&(record(held).qualified(now())||current!=null&&current.id.equals(held.id)&&currentUsable()))return;
            pinned="";app.log("selection","FAILED_PIN_RELEASED");
        }
        HashSet<String> checked=new HashSet<>(),rejected=new HashSet<>();int attempted=0;
        long deadline=now()+ProductContract.policyLong("recoveryBudgetMs");
        while(rejected.size()<health.size()){
            ensureRunning();SelectionPolicy.Health active=current==null?null:record(current);
            ArrayList<SelectionPolicy.Health> ordered=new ArrayList<>(health.values());ordered.sort(SelectionPolicy.order(app.preferred(),now()));
            SelectionPolicy.Health best=null;for(SelectionPolicy.Health h:ordered)if(!rejected.contains(h.id)&&h.eligibleForProof(now())){best=h;break;}
            if(best==null)return;
            if(active!=null&&best.id.equals(active.id)&&!best.appRejected){if(best.qualified(now())){phase="connected";error="";detail=connectedDetail();}return;}
            Node candidate=findById(best.id);if(candidate==null){rejected.add(best.id);continue;}
            if(!checked.contains(best.id)){
                if(attempted>=ProductContract.policyInt("recoveryCandidateLimit")||now()>=deadline)return;
                attempted++;checked.add(best.id);
                if(!verifyCandidate(candidate,0)){rejected.add(best.id);continue;}
                // Fresh evidence can reorder candidates. Compare the new winner before promotion.
                continue;
            }
            if(shouldSwitch(active,best,force)){promote(candidate,active==null||!active.qualified(now()));}
            return;
        }
    }
    private boolean verifyCandidate(Node node,int requiredOrigins)throws Exception{
        SelectionPolicy.Health h=record(node);if(h==null||now()<h.appFailureUntil)return false;
        for(int i=0,count=Math.max(1,3-h.sampleCount(now()));i<count;i++){probeLatency(node);if(!h.connectionFresh(now()))break;}
        Integer port=ports.get(node.id);if(port==null)return false;
        ConnectionWatchdog.Result result=candidateReachability.check(port);
        if(result.broadFailure()){h.applicationFailed(now());app.log("health","CANDIDATE_APPLICATION_PATH_FAILED");return false;}
        // Both required application origins must work. A Google-only outage has an actual measured HTTPS fallback.
        if((result.failedMask&6)!=0)return false;
        h.transportVerified(now(),result.latencyMillis);probeServices(node);if(!h.eligibleForProof(now()))return false;
        h.applicationVerified(now());return h.qualified(now());
    }
    private void promote(Node node,boolean closeExisting)throws Exception{
        // Cancellation may yield read-only discovery, never a half-applied selector mutation.
        boolean cancellable;
        synchronized(operationLock){ensureRunning();cancellable=inSweep;inSweep=false;}
        try{
            action("changeProxy",new JSONObject().put("group-name","AUTOPILOT").put("proxy-name",node.name).toString(),5000);
            verifySelector(node.name);
            if(closeExisting)action("closeAllConnections",null,5000);
            current=node;lastVerify=record(node).servicesAt;recoveryAttempt=0;nextRecovery=0;selectionEpoch++;lastSwitch=now();lastCurrentCheck=now();phase="connected";error="";resetWatchdogState(ConnectionWatchdog.Code.CHECKING);detail=connectedDetail();
            app.log("selection",closeExisting?"NODE_SWITCH":"HEALTHY_UPGRADE");publish();
        }finally{synchronized(operationLock){inSweep=cancellable;}}
    }
    private void verifySelector(String name)throws Exception{
        JSONObject response=action("getProxies",null,5000);Object data=response.opt("data");
        JSONObject document=data instanceof JSONObject?(JSONObject)data:new JSONObject(String.valueOf(data));
        JSONObject group=document.getJSONObject("proxies").getJSONObject("AUTOPILOT");
        if(!name.equals(group.optString("now")))throw new IOException("SELECTOR_MISMATCH");
    }
    private static final class LatencyMeasurement {
        final HealthDiagnostics.Latency nativeResult;final int applicationMillis;final boolean ok;
        LatencyMeasurement(HealthDiagnostics.Latency nativeResult,int applicationMillis,boolean ok){this.nativeResult=nativeResult;this.applicationMillis=applicationMillis;this.ok=ok;}
    }
    private LatencyMeasurement measureLatency(Node n,CompletableFuture<JSONObject> pending)throws Exception{
        HealthDiagnostics.Latency result;
        try{result=HealthDiagnostics.latency(await(pending==null?actionAsync("testDelay",new JSONObject().put("proxy-name",n.name).put("test-url","https://www.gstatic.com/generate_204").put("timeout",5000).toString()):pending,7500));}
        catch(TimeoutException ignored){result=HealthDiagnostics.failed(HealthDiagnostics.LatencyCode.CHECK_TIMEOUT);}
        catch(ExecutionException ignored){result=HealthDiagnostics.failed(HealthDiagnostics.LatencyCode.CORE_RESPONSE_FAILED);}
        int max=(int)settingLong("maxLatencyMs");
        result=HealthDiagnostics.threshold(result,max);
        if(result.millis>0&&result.millis<max)return new LatencyMeasurement(result,0,true);
        Integer port=ports.get(n.id);if(port==null)return new LatencyMeasurement(result,0,false);
        ConnectionWatchdog.Result fallback=candidateReachability.check(port);
        int applicationMillis=!fallback.broadFailure()&&fallback.latencyMillis>0&&fallback.latencyMillis<max?fallback.latencyMillis:0;
        return new LatencyMeasurement(result,applicationMillis,applicationMillis>0);
    }
    private void recordMeasurement(Node node,LatencyMeasurement result){recordLatency(node,result.nativeResult);if(result.applicationMillis>0)record(node).transportVerified(now(),result.applicationMillis);}
    private void probeLatency(Node n)throws Exception{recordMeasurement(n,measureLatency(n,null));}
    private void recordLatency(Node node,HealthDiagnostics.Latency result){record(node).configure((int)settingLong("maxLatencyMs"),required());record(node).latency(result.millis,now());latencyDiagnostics.put(node.id,result);
        if(current!=null&&current.id.equals(node.id)&&!record(node).qualified(now()))updateRoute=NO_UPDATE_ROUTE;}
    private String connectedDetail(){return (watchdogCode==ConnectionWatchdog.Code.SERVICE_PATH_SUSPECT?"One application path needs attention":"Node connection checks passed")+" · "+(paused()?"Paused":pinned.isEmpty()?"Auto":"Pinned");}
    private boolean currentUsable(){return current!=null&&(record(current).qualified(now())||record(current).retainSelectedTransport(now()));}
    private void warnRetainedServicePath(){if(current!=null&&!record(current).qualified(now())&&record(current).retainSelectedTransport(now())){watchdogCode=ConnectionWatchdog.Code.SERVICE_PATH_SUSPECT;phase="connected";error="SERVICE_PATH_DEGRADED";detail="Internet route remains active; one application service is unavailable. Checking alternatives.";}}
    private void resetWatchdogState(ConnectionWatchdog.Code code){watchdogCode=code;watchdogLastCheck=watchdogLastSuccess=0;watchdogFailedOrigins=watchdogConsecutive=0;}
    private void invalidateMonitor(){monitorTarget=null;watchdog.target(null);updateRoute=NO_UPDATE_ROUTE;resetWatchdogState(ConnectionWatchdog.Code.IDLE);}
    private void watchdogResult(ConnectionWatchdog.Observation observation){
        if(!observation.snapshot.equals(monitorTarget)||!running.get()||stopping.get())return;
        if(observation.code==ConnectionWatchdog.Code.CONFIRMED_FAILED)updateRoute=NO_UPDATE_ROUTE;
        if(observation.code==ConnectionWatchdog.Code.CONFIRMED_PARTIAL_FAILED)watchdog.resume();
        if(observation.code==ConnectionWatchdog.Code.CONFIRMED_FAILED||observation.code==ConnectionWatchdog.Code.CONFIRMED_PARTIAL_FAILED)preemptSweep();
        submit(()->applyWatchdog(observation));
    }
    private void applyWatchdog(ConnectionWatchdog.Observation observation)throws Exception{
        if(!observation.snapshot.equals(monitorTarget)||current==null)return;
        watchdogCode=observation.code;watchdogLastCheck=observation.at;watchdogLastSuccess=observation.lastSuccessAt;
        watchdogFailedOrigins=observation.failedOrigins;watchdogConsecutive=observation.consecutiveFailures;
        // HTTPS health has its own freshness. It must not fabricate or replace measured Google RTT samples.
        if(observation.failedOrigins<=1&&observation.latencyMillis>0){record(current).transportVerified(observation.at,observation.latencyMillis);lastCurrentCheck=observation.at;}
        if(observation.code==ConnectionWatchdog.Code.HEALTHY&&record(current).qualified(now())&&"SERVICE_PATH_DEGRADED".equals(error)){error="";detail=connectedDetail();}
        if(observation.code==ConnectionWatchdog.Code.HEALTHY)record(current).selectedHealthy(observation.at);else record(current).selectedUncertain();
        if(observation.code==ConnectionWatchdog.Code.CONFIRMED_FAILED){
            record(current).applicationFailed(now());pinned="";watchdogCode=ConnectionWatchdog.Code.RECOVERING;
            phase="degraded";error="APPLICATION_PATH_FAILED";detail="Application connections failed repeatedly; checking replacements";app.log("health","APPLICATION_PATH_FAILED");publish();
            if(!paused())decide(true);
            if(current!=null&&record(current).appRejected){
                action("changeProxy",new JSONObject().put("group-name","AUTOPILOT").put("proxy-name","REJECT").toString(),5000);action("closeAllConnections",null,5000);
                current=null;pinned="";invalidateMonitor();watchdogCode=ConnectionWatchdog.Code.NO_VERIFIED_REPLACEMENT;
                error="NO_HEALTHY_NODE";detail="No verified replacement. VPN is blocking unverified traffic.";
            }
            scheduleRecovery();
        }else if(observation.code==ConnectionWatchdog.Code.CONFIRMED_PARTIAL_FAILED&&!paused()){
            // Compare another route before blaming this node: a service-wide outage must not churn every node.
            Node prior=current;ArrayList<SelectionPolicy.Health> ordered=new ArrayList<>(health.values());ordered.sort(SelectionPolicy.order(app.preferred(),now()));
            int attempted=0;long deadline=now()+ProductContract.policyLong("recoveryBudgetMs");
            inSweep=true;
            try{for(SelectionPolicy.Health candidate:ordered){if(candidate.id.equals(prior.id)||!candidate.eligibleForProof(now()))continue;if(attempted++>=ProductContract.policyInt("comparativeCandidateLimit")||now()>deadline)break;
                Node replacement=findById(candidate.id);if(replacement!=null&&verifyCandidate(replacement,observation.failedMask)){
                    record(prior).applicationFailed(now());pinned="";promote(replacement,true);app.log("health","APPLICATION_PATH_COMPARATIVE_RECOVERY");break;
                }
            }}finally{inSweep=false;}
            if(current==prior)watchdogCode=ConnectionWatchdog.Code.SERVICE_PATH_SUSPECT;
        }
        warnRetainedServicePath();publish();
    }
    private void rejectCurrent()throws Exception{action("changeProxy",new JSONObject().put("group-name","AUTOPILOT").put("proxy-name","REJECT").toString(),5000);action("closeAllConnections",null,5000);current=null;pinned="";invalidateMonitor();phase="degraded";error="NO_HEALTHY_NODE";detail="No verified replacement. VPN is blocking unverified traffic.";}
    private void scheduleRecovery(){JSONArray delays=ProductContract.policy().optJSONArray("recoveryBackoffMs");nextRecovery=now()+delays.optLong(Math.min(recoveryAttempt++,delays.length()-1));}
    private void recoverBounded()throws Exception{if(paused())return;decide(true);if(current==null)scheduleRecovery();}
    private void progress(String step,String message){stage=step;detail=message;publish();}
    private void finishChecks(){busy=false;stage="";if(current!=null&&phase.equals("connected")&&error.isEmpty())detail=connectedDetail();}
    private void probeServices(Node n)throws InterruptedException{
        Integer port=ports.get(n.id);if(port==null)return;
        String previousStage=stage,previousDetail=detail;
        progress("services","Checking service access: "+n.name);
        try{
            ServiceChecks.Result r=checks.check(port,required());SelectionPolicy.Health h=record(n);
            h.claude=r.claude;h.chatgpt=r.chatgpt;h.gemini=r.gemini;h.servicesAt=now();if(current!=null&&current.id.equals(n.id))lastVerify=h.servicesAt;
        }finally{
            stage=previousStage;detail=previousDetail;
            if(!busy&&current!=null&&phase.equals("connected")&&error.isEmpty())detail=connectedDetail();
            publish();
        }
    }
    private void requestProviderRecovery(){
        ArrayList<String> failed=new ArrayList<>();
        for(Node node:nodes){SelectionPolicy.Health h=record(node);
            if("octohide".equals(node.provider)&&h!=null&&h.latency<=0&&h.failures>=2&&h.appRejected&&!h.connectionFresh(now()))failed.add(node.name);}
        if(!failed.isEmpty())app.requestRecovery(failed);
    }
    private String quickSetup(String name)throws Exception{
        return quickSetup(initParameters(),selected(name));
    }
    private JSONObject initParameters()throws JSONException{return new JSONObject().put("home-dir",home.getAbsolutePath()).put("version",Build.VERSION.SDK_INT);}
    private String quickSetup(JSONObject init,JSONObject setupParams)throws Exception{
        CompletableFuture<String> setup=new CompletableFuture<>();
        Clash.INSTANCE.quickSetup(init.toString(),setupParams.toString(),(InvokeInterface)setup::complete);
        return await(setup,20_000);
    }
    private void establish(boolean reuse)throws Exception{
        ensureRunning();progress("tunnel","Starting VPN tunnel");tunStarted=new CompletableFuture<>();
        ParcelFileDescriptor descriptor=reuse?service.duplicateTunnel():service.establishTunnel();
        int fd=descriptor.detachFd();descriptor.close();
        // Our UID bypasses the VPN. A mixed/system stack's kernel TCP forwarder
        // depends on replies returning through that UID's VPN routes; use a
        // userspace TCP/UDP stack so other apps' packets stay on the TUN path.
        Clash.INSTANCE.startTUN(fd,new TunInterface(){
            @Override public void protect(int socket){if(!service.protect(socket))app.log("vpn","SOCKET_PROTECT_FAILED");}
            @Override public String resolverProcess(int protocol,String source,String target,int uid){return "";}
        },"Autopilot",TUN_STACK,AutopilotVpnService.ADDRESSES,AutopilotVpnService.DNS,AutopilotVpnService.MTU);
        if(!await(tunStarted,7000))throw new IOException("TUN_START_FAILED");tunReady=true;
        app.log("vpn","TUN_STARTED");phase="testing";progress("connections","Tunnel started; checking nodes");
    }
    private void event(String payload){
        if(payload==null)return;
        try{
            JSONObject e=new JSONObject(payload);JSONObject message=e.optJSONObject("data");
            if(message==null||!"log".equals(message.optString("type")))return;
            JSONObject log=message.optJSONObject("data");if(log==null)return;
            String text=log.optString("Payload");CompletableFuture<Boolean> started=tunStarted;
            if(started!=null){if(text.startsWith("TUN started:"))started.complete(true);else if(text.startsWith("TUN start failed:"))started.complete(false);}
            // The bridge's log subscription uses the profile's info threshold,
            // independent of its error-only logcat sink. Keep only fixed codes.
            networkSignals.observe(log.optString("LogLevel"),text,now());
            if(text.matches("(?s).*\\[(?:TCP|UDP)\\] dial AUTOPILOT \\(.*")&&(text.contains("timeout")||text.contains("unreachable")||text.contains("refused")))watchdog.routeError();
        }catch(JSONException ignored){}
    }
    private JSONObject selected(String name)throws JSONException{return new JSONObject().put("selected-map",new JSONObject().put("AUTOPILOT",name));}
    static JSONObject configuration(List<Node> nodes,Map<String,Integer> ports)throws Exception{
        return configuration(nodes,ports,CoreDnsConfig.DEFAULT);
    }
    static JSONObject configuration(List<Node> nodes,Map<String,Integer> ports,CoreDnsConfig dnsPolicy)throws Exception{
        return configuration(nodes,ports,dnsPolicy,ProductContract.defaults(),false);
    }
    static JSONObject configuration(List<Node> nodes,Map<String,Integer> ports,CoreDnsConfig dnsPolicy,JSONObject settings,boolean geoAvailable)throws Exception{
        ports.clear();JSONArray proxies=new JSONArray(),names=new JSONArray().put("REJECT"),listeners=new JSONArray();
        int port=27000;
        for(Node n:nodes){proxies.put(new JSONObject(n.config.toString()));names.put(n.name);ports.put(n.id,port);
            listeners.put(new JSONObject().put("name","autopilot-"+port).put("type","mixed").put("listen","127.0.0.1").put("port",port++).put("proxy",n.name).put("udp",true));}
        JSONObject dns=dnsPolicy.build();
        return new JSONObject().put("mode","rule").put("log-level","info").put("allow-lan",false).put("bind-address","127.0.0.1")
            .put("ipv6",CoreDnsConfig.IPV6_ENABLED).put("find-process-mode","off").put("profile",new JSONObject().put("store-selected",false).put("store-fake-ip",false))
            .put("dns",dns).put("tun",new JSONObject().put("enable",false)).put("proxies",proxies)
            .put("proxy-groups",new JSONArray().put(new JSONObject().put("name","AUTOPILOT").put("type","select").put("proxies",names)))
            .put("listeners",listeners).put("rules",AndroidRouting.buildRules(settings,geoAvailable));
    }
    private JSONObject writeConfig()throws Exception{
        JSONObject config=configuration(nodes,ports,CoreDnsConfig.DEFAULT,app.settings(),AndroidRouting.geoAvailable(app));
        File staging=new File(home,"config.yaml.tmp"),target=new File(home,"config.yaml");
        try(FileOutputStream out=new FileOutputStream(staging)){out.write(CoreConfigEncoding.encode(config));out.getFD().sync();}
        Files.move(staging.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        installedRules=config.getJSONArray("rules").toString();
        return config;
    }
    private CompletableFuture<JSONObject> actionAsync(String method,Object data)throws JSONException{
        CompletableFuture<JSONObject> result=new CompletableFuture<>();
        String id=Long.toString(sequence.incrementAndGet());
        JSONObject envelope=new JSONObject().put("id",id).put("method",method).put("data",data==null?JSONObject.NULL:data);
        Clash.INSTANCE.invokeAction(envelope.toString(),(InvokeInterface)value->{
            try{JSONObject response=new JSONObject(value==null?"{}":value);
                if(!id.equals(response.optString("id"))||!method.equals(response.optString("method")))throw new JSONException("Invalid native response");
                result.complete(response);
            }catch(Exception ex){result.completeExceptionally(new IOException("CORE_RESPONSE_INVALID"));}
        });
        return result;
    }
    private JSONObject action(String method,Object data,long timeout)throws Exception{
        JSONObject response=await(actionAsync(method,data),timeout);
        if(response.optInt("code",-1)!=0)throw new IOException("CORE_ACTION_FAILED");
        return response;
    }
    private <T>T await(CompletableFuture<T> pending,long timeout)throws InterruptedException,ExecutionException,TimeoutException{
        ensureRunning();return pending.get(timeout,TimeUnit.MILLISECONDS);
    }
    private void ensureRunning()throws InterruptedException{if(!running.get()||Thread.currentThread().isInterrupted())throw new InterruptedException();}
    private List<Node> validatedNodes(){
        List<Node> source=app.providers.nodes();ArrayList<Node> result=new ArrayList<>();HashSet<String> names=new HashSet<>(),ids=new HashSet<>();
        for(Node n:source){if(result.size()>=2000)break;if(names.add(n.name)&&ids.add(n.id))result.add(n);}
        return List.copyOf(result);
    }
    private boolean sameNodes(List<Node> a,List<Node>b){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(!a.get(i).id.equals(b.get(i).id))return false;return true;}
    private void syncHealth(){HashSet<String> ids=new HashSet<>();for(Node n:nodes){ids.add(n.id);health.computeIfAbsent(n.id,k->new SelectionPolicy.Health(n.id,n.name,n.country)).configure((int)settingLong("maxLatencyMs"),required());}health.keySet().retainAll(ids);latencyDiagnostics.keySet().retainAll(ids);}
    private SelectionPolicy.Health record(Node n){return health.get(n.id);}
    private Node findById(String id){for(Node n:nodes)if(n.id.equals(id))return n;return null;}
    private Node findByName(String name){for(Node n:nodes)if(n.name.equals(name))return n;return null;}
    private static long now(){return System.currentTimeMillis();}
    private void publish(){
        // Only the serialized controller reads health/maps. Download workers consume one immutable snapshot.
        UpdateRoute next=NO_UPDATE_ROUTE;long time=now();
        if(running.get()&&!stopping.get()&&nativeReady&&profileUsable&&tunReady&&current!=null&&"connected".equals(phase)){
            SelectionPolicy.Health active=record(current);Integer port=ports.get(current.id);
            if(active!=null&&currentUsable()&&port!=null&&port>0&&port<=65535)
                next=new UpdateRoute(port,time,active.qualified(time)?Math.min(Math.max(active.latencyAt,active.transportAt)+SelectionPolicy.LATENCY_TTL,active.servicesAt+SelectionPolicy.SERVICE_TTL):active.transportAt+ProductContract.policyLong("transportRetentionMs"));
        }
        updateRoute=next;
        ConnectionWatchdog.Snapshot target=null;
        if(running.get()&&!stopping.get()&&nativeReady&&profileUsable&&tunReady&&current!=null){Integer port=ports.get(current.id);if(port!=null)target=new ConnectionWatchdog.Snapshot(profileGeneration,current.id,selectionEpoch,port);}
        if(!Objects.equals(monitorTarget,target)){monitorTarget=target;watchdog.target(target);}
        try{
            JSONArray rows=new JSONArray();ArrayList<SelectionPolicy.Health> ordered=new ArrayList<>(health.values());ordered.sort(SelectionPolicy.order(app.preferred(),now()));
            for(SelectionPolicy.Health h:ordered){Node n=findById(h.id);if(n==null)continue;
                HealthDiagnostics.Latency diagnostic=latencyDiagnostics.get(h.id);
                JSONObject row=new JSONObject().put("name",h.name).put("country",h.country).put("provider",n.provider).put("latency",h.displayLatency(time)).put("latencySource",h.displayLatency(time)<=0?JSONObject.NULL:h.latencyFresh(time)?"google-probe":"application-https")
                    .put("nativeLatency",h.latency).put("applicationLatency",h.applicationLatency).put("applicationCheckedAt",h.transportAt)
                    .put("score",Double.isFinite(h.score(time))?h.score(time):-1).put("claude",h.claude).put("chatgpt",h.chatgpt).put("gemini",h.gemini)
                    .put("latencyAt",h.latencyAt).put("servicesAt",h.servicesAt).put("latencyFresh",h.latencyFresh(now())).put("qualified",h.qualified(now()))
                    .put("latencyCode",diagnostic==null?HealthDiagnostics.LatencyCode.NOT_TESTED.name():diagnostic.code.name())
                    .put("status",current!=null&&current.id.equals(h.id)?"Active":h.latencyAt==0?"Not tested":h.qualified(now())?"Ready":h.latency<=0?"Unavailable":!h.latencyFresh(now())?"Check expired":h.servicesAt==0?"Needs checks":now()<h.servicesAt||now()-h.servicesAt>SelectionPolicy.SERVICE_TTL?"Service check expired":"Service check failed");
                if(diagnostic!=null&&diagnostic.nativeCode!=null)row.put("latencyNativeCode",diagnostic.nativeCode);
                rows.put(row);}
            JSONArray skipped=new JSONArray();for(ProfileValidation.Skipped entry:coreSkipped)skipped.put(entry.json());
            JSONObject snapshot=new JSONObject().put("phase",phase).put("current",current==null?"":current.name).put("pinned",!pinned.isEmpty())
                .put("auto",pinned.isEmpty()&&!paused()).put("paused",paused()).put("busy",busy).put("error",error).put("detail",detail).put("stage",stage).put("lastSwitch",lastSwitch).put("nodes",rows).put("tun",tunReady);
            snapshot.put("coreBaselineAccepted",baselineAccepted).put("skippedCount",coreSkipped.size()).put("skippedNodes",skipped);
            int cooldownCount=0;long nextEligible=0;for(SelectionPolicy.Health h:health.values())if(h.appRejected&&h.appFailureUntil>time){cooldownCount++;if(nextEligible==0||h.appFailureUntil<nextEligible)nextEligible=h.appFailureUntil;}
            snapshot.put("watchdog",new JSONObject().put("code",watchdogCode.name()).put("lastCheckAt",watchdogLastCheck).put("lastSuccessAt",watchdogLastSuccess)
                .put("failedOrigins",watchdogFailedOrigins).put("consecutiveFailures",watchdogConsecutive).put("cooldownCount",cooldownCount).put("nextEligibleAt",nextEligible)
                .put("checkPath","per-node mixed listener; excluded Autopilot UID"));
            snapshot.put("coreProfileAccepted",profileUsable).put("nodeHealth",HealthDiagnostics.providers(rows)).put("networkSignals",networkSignals.json())
                .put("checkPath","per-node mixed listener; excluded Autopilot UID").put("resolverPolicy",CoreDnsConfig.POLICY_ID).put("tunStack",TUN_STACK);
            JSONObject diagnostic=app.coreDiagnostic();if(diagnostic!=null)snapshot.put("coreDiagnostic",diagnostic);
            JSONObject historical=app.historicalCoreDiagnostic();if(historical!=null)snapshot.put("lastCoreFailure",historical);
            app.publish(snapshot);service.updateNotification(current==null?detail:current.name+(pinned.isEmpty()?" · Auto":" · Pinned"));
        }catch(JSONException ignored){}
    }
    private void cleanup(){
        updateRoute=NO_UPDATE_ROUTE;
        running.set(false);invalidateMonitor();watchdog.close();watchdogReachability.close();candidateReachability.close();checks.close();
        if(nativeReady){try{Clash.INSTANCE.stopTun();}catch(Throwable ignored){}try{Clash.INSTANCE.invokeAction(new JSONObject().put("id","stop-log").put("method","stopLog").put("data",JSONObject.NULL).toString(),(InvokeInterface)value->{});}catch(Throwable ignored){}
            try{Clash.INSTANCE.setEventListener((InvokeInterface)null);}catch(Throwable ignored){}}
        service.releaseTunnel();profileUsable=false;
        tunReady=false;busy=false;stage="";current=null;pinned="";
        if(preserveError){phase="error";detail="VPN could not start. See diagnostics.";}else{phase="stopped";detail="VPN disconnected";error="";}
        publish();app.log("vpn","STOPPED");
        worker.shutdownNow();service.finished(this);
    }
}
