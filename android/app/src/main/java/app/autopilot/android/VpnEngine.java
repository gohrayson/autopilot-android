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
    private final AutopilotVpnService service;
    private final AutopilotApp app;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final ServiceChecks checks=new ServiceChecks();
    private final AtomicBoolean running=new AtomicBoolean(),stopping=new AtomicBoolean(),sweepQueued=new AtomicBoolean(),preferencesQueued=new AtomicBoolean(),nodesQueued=new AtomicBoolean();
    private final Map<String,SelectionPolicy.Health> health=new HashMap<>();
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
    private String pinned="",phase="idle",error="",detail="";
    private long lastSwitch;
    private boolean busy;
    private boolean preserveError;
    private boolean startedOnce;
    private volatile boolean profileUsable;
    private boolean baselineAccepted;
    private volatile List<ProfileValidation.Skipped> coreSkipped=List.of();
    private long lastSweep;
    private long lastCurrentCheck;
    private File home;
    public VpnEngine(AutopilotVpnService service,AutopilotApp app){this.service=service;this.app=app;}
    public void start(){if(!running.compareAndSet(false,true))return;submit(()->{
        phase="starting";detail="Preparing VPN";publish();
        home=new File(app.getFilesDir(),"core");if(!home.isDirectory()&&!home.mkdirs())throw new IOException("CORE_STORAGE");
        Clash.INSTANCE.load(app.getApplicationInfo().nativeLibraryDir);Clash.INSTANCE.assertReady();nativeReady=true;
        // Establish that this platform can accept our global configuration independently
        // of provider nodes. No Android interface exists while these semantic gates run.
        nodes=List.of();syncHealth();JSONObject baselineProfile=writeConfig();
        JSONObject baselineInit=initParameters(),baselineSetup=selected("REJECT");
        String baseline=quickSetup(baselineInit,baselineSetup);
        if(!"".equals(baseline)){
            CoreDiagnostics.Diagnostic diagnostic=CoreDiagnostics.classify(baseline,List.of());
            app.recordBaselineDiagnostic(CoreDiagnostics.baselineDiagnostic(baseline,home.getAbsolutePath(),baselineProfile,baselineInit,baselineSetup));
            throw new IOException(diagnostic.code);
        }
        baselineAccepted=true;app.log("core","CORE_BASELINE_ACCEPTED");
        ProfileValidation.Result profile=validateProfile(validatedNodes(),"",CoreDiagnostics.Stage.PROFILE);
        if(!profile.accepted)throw new IOException(rejectionCode(profile));
        // Core events are inspected only for the TUN lifecycle. Arbitrary upstream log text is never persisted.
        Clash.INSTANCE.setEventListener((InvokeInterface)this::event);
        action("setFileEnabled",false,5000);action("setLogcatLevel","error",5000);action("startLog",null,5000);
        verifySelector("REJECT");
        establish(false);startedOnce=true;profileUsable=true;
        worker.scheduleWithFixedDelay(()->guard(()->tick()),15,15,TimeUnit.SECONDS);
        if(nodes.isEmpty()){noNodes();publish();}
        else sweep(true);
    });}
    public void stop(){
        if(!stopping.compareAndSet(false,true))return;
        running.set(false);Thread thread=activeThread;if(thread!=null)thread.interrupt();
        try{worker.execute(()->{Thread.interrupted();cleanup();});}catch(RejectedExecutionException ignored){cleanup();}
    }
    public void retest(){if(!profileUsable||nodes.isEmpty()&&!coreSkipped.isEmpty())nodesChanged();else queueSweep(true);}
    public void select(String name,boolean pin){preemptSweep();submit(()->{
        if(!profileUsable){nodesChanged();return;}
        Node target=findByName(name);if(target==null){error="NODE_UNAVAILABLE";publish();return;}
        busy=true;detail="Checking selected node";publish();
        try{
            probeLatency(target);if(record(target).latency>0)probeServices(target);
            if(!record(target).qualified(now())){error="NODE_NOT_HEALTHY";detail="Selected node did not pass the service checks";return;}
            if(pin)pinned=target.id;else pinned="";
            if(current==null||!current.id.equals(target.id))promote(target,true);
            else{phase="connected";error="";detail=pin?"Pinned node":"Automatic selection";}
        }finally{busy=false;publish();}
    });}
    public void resumeAuto(){preemptSweep();submit(()->{pinned="";if(!profileUsable||nodes.isEmpty()&&!coreSkipped.isEmpty()){nodesChanged();return;}error="";decide(true);queueSweep(false);publish();});}
    public void preferencesChanged(){
        if(!preferencesQueued.compareAndSet(false,true))return;
        preemptSweep();
        submit(()->{preferencesQueued.set(false);if(!profileUsable){nodesChanged();return;}if(!pinned.isEmpty())return;decide(true);queueSweep(true);publish();});
    }
    public void nodesChanged(){
        if(!nodesQueued.compareAndSet(false,true))return;
        preemptSweep();
        submit(()->{
            nodesQueued.set(false);List<Node> latest=validatedNodes();
            if(sameNodes(nodes,latest)&&profileUsable)return;
            String prior=current==null?"":current.id,priorPin=pinned;
            final Node[] survivor={null};final String[] selectedName={"REJECT"};
            final ProfileValidation.Result[] validated={null};
            boolean accepted=reloadSafely(new ProfileTransition(){
                @Override public void pause(){
                    profileUsable=false;tunReady=false;current=null;pinned="";busy=true;phase="testing";
                    detail="Updating VPN profile; traffic is paused";publish();
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
                    else app.log("core","CORE_RECOVERY_ACCEPTED");
                    return "".equals(recovery);
                }
                @Override public void attach()throws Exception{
                    verifySelector(selectedName[0]);
                    establish(true);
                }
            });
            busy=false;
            if(accepted){
                profileUsable=true;current=survivor[0];pinned=current!=null&&priorPin.equals(current.id)?priorPin:"";
                phase=current==null?"testing":"connected";error="";detail="Provider nodes updated; checking health";
                if(nodes.isEmpty())noNodes();else queueSweep(true);
            }else{
                profileUsable=false;current=null;pinned="";phase="degraded";error=validated[0]==null?"CORE_CONFIG_REJECTED":rejectionCode(validated[0]);
                String message=CoreDiagnostics.messageForCode(error);detail=(message==null?"The VPN profile was rejected.":message)+" VPN is blocking traffic.";
                app.log("vpn",error);
            }
            publish();
        });
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
            ensureRunning();nodes=List.copyOf(candidates);syncHealth();writeConfig();return quickSetup(selectedName);
        },(diagnostic,skipped)->{
            coreSkipped=skipped;app.recordCoreDiagnostic(diagnostic,stage,skipped.size());publish();
        });
        coreSkipped=result.skipped;nodes=result.nodes;syncHealth();
        if(result.accepted)app.log("core","CORE_PROFILE_ACCEPTED");
        return result;
    }
    private static String rejectionCode(ProfileValidation.Result profile){return profile.limitReached?CoreDiagnostics.Kind.PROXY_LIMIT.code:profile.lastDiagnostic==null?"CORE_CONFIG_REJECTED":profile.lastDiagnostic.code;}
    private void noNodes(){
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
            else if(running.get()){busy=false;detail=current==null?"Applying latest selection":"Automatic selection";publish();}
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
        boolean guarded=startedOnce&&service.hasTunnel();
        error=code;phase=tunReady||guarded?"degraded":"error";
        String message=CoreDiagnostics.messageForCode(code);
        detail=message!=null?message+(guarded?" VPN traffic is paused.":""):tunReady?"VPN needs attention. See diagnostics.":guarded?"VPN traffic is paused. Retry with a valid profile or stop the VPN.":"VPN could not start. See diagnostics.";
        busy=false;app.log("vpn",code);publish();
        if(!tunReady&&!guarded){preserveError=true;stop();}
    }
    private void queueSweep(boolean force){
        if(!sweepQueued.compareAndSet(false,true))return;
        submit(()->{sweepQueued.set(false);if(profileUsable&&!preferencesQueued.get()&&!nodesQueued.get())sweep(force);});
    }
    private void tick()throws Exception{
        if(!tunReady||!profileUsable)return;
        checkCurrent(true);
        if(now()-lastSweep>=60_000)queueSweep(false);
        publish();
    }
    private void checkCurrent(boolean scheduleSweep)throws Exception{
        if(current!=null){
            probeLatency(current);
            lastCurrentCheck=now();
            SelectionPolicy.Health h=record(current);
            requestProviderRecovery();
            if(h.latency>0&&(now()-h.servicesAt>SelectionPolicy.SERVICE_TTL || !h.qualified(now())))probeServices(current);
            if(!h.qualified(now())){
                if(current.id.equals(pinned)){pinned="";app.log("selection","FAILED_PIN_RELEASED");}
                decide(true);
                if(current!=null&&!record(current).qualified(now())){phase="degraded";error="CURRENT_NODE_FAILED";detail="Current node failed a check; finding a verified replacement";}
                if(scheduleSweep)queueSweep(true);
            }
        }
    }
    private void sweep(boolean force)throws Exception{
        if(nodes.isEmpty()){lastSweep=now();busy=false;noNodes();publish();return;}
        inSweep=true;busy=true;if(current==null)phase="testing";detail="Testing latency and service availability";publish();
        try{
            // Bounded batches keep a large account from opening hundreds of sockets at once.
            for(int offset=0;offset<nodes.size();offset+=8){
                ensureRunning();List<Node> batch=nodes.subList(offset,Math.min(offset+8,nodes.size()));
                ArrayList<CompletableFuture<JSONObject>> pending=new ArrayList<>();
                for(Node n:batch)pending.add(actionAsync("testDelay",new JSONObject().put("proxy-name",n.name).put("test-url","https://www.gstatic.com/generate_204").put("timeout",5000).toString()));
                for(int i=0;i<batch.size();i++){
                    int latency=-1;
                    try{JSONObject r=await(pending.get(i),7500);if(r.optInt("code",-1)==0)latency=r.optInt("data",-1);}catch(TimeoutException|ExecutionException ignored){}
                    record(batch.get(i)).latency(latency,now());
                    if(current!=null&&current.id.equals(batch.get(i).id))lastCurrentCheck=now();
                }
                // A long provider list must not starve the active node's 15-second watchdog.
                if(current!=null&&now()-lastCurrentCheck>=15_000)checkCurrent(false);
                publish();
            }
            requestProviderRecovery();
            ArrayList<SelectionPolicy.Health> ordered=new ArrayList<>(health.values());ordered.sort(SelectionPolicy.order(app.preferred()));
            int qualified=0,bestTier=Integer.MAX_VALUE;
            // Test the fastest candidates in each country tier until a verified tier is found.
            // Three healthy candidates give upgrades a stability choice without probing every service on every node.
            for(SelectionPolicy.Health h:ordered){
                ensureRunning();if(!h.latencyFresh(now()))continue;
                int tier=SelectionPolicy.tier(h.country,app.preferred());
                if(qualified>=3 || qualified>0 && tier>bestTier)break;
                if(!h.qualified(now())){Node n=findById(h.id);if(n!=null)probeServices(n);publish();}
                if(h.qualified(now())){qualified++;bestTier=Math.min(bestTier,tier);}
                if(current==null || !record(current).qualified(now()))decide(true);
            }
            decide(force);lastSweep=now();
            if(current==null || !record(current).qualified(now())){
                phase="degraded";error="NO_HEALTHY_NODE";detail="No node passed all checks. VPN stays active and blocks unverified traffic.";
                if(current!=null){action("changeProxy",new JSONObject().put("group-name","AUTOPILOT").put("proxy-name","REJECT").toString(),5000);action("closeAllConnections",null,5000);current=null;pinned="";}
            }
        }finally{inSweep=false;busy=false;publish();}
    }
    private void decide(boolean force)throws Exception{
        if(!pinned.isEmpty()){
            Node held=findById(pinned);if(held!=null && record(held).qualified(now()))return;
            pinned="";app.log("selection","FAILED_PIN_RELEASED");
        }
        SelectionPolicy.Health best=SelectionPolicy.best(health.values(),app.preferred(),now());
        SelectionPolicy.Health active=current==null?null:record(current);
        if(SelectionPolicy.shouldSwitch(active,best,app.preferred(),now(),lastSwitch,force)){
            Node candidate=findById(best.id);if(candidate==null)return;
            // The actual promotion always rechecks the same service path the user will use.
            if(now()-best.servicesAt>30_000){probeServices(candidate);if(!best.qualified(now()))return;}
            boolean unhealthy=active==null||!active.qualified(now());promote(candidate,unhealthy);
        }
    }
    private void promote(Node node,boolean closeExisting)throws Exception{
        action("changeProxy",new JSONObject().put("group-name","AUTOPILOT").put("proxy-name",node.name).toString(),5000);
        verifySelector(node.name);
        if(closeExisting)action("closeAllConnections",null,5000);
        current=node;lastSwitch=now();lastCurrentCheck=now();phase="connected";error="";detail=pinned.isEmpty()?"Automatic selection":"Pinned node";
        app.log("selection",closeExisting?"NODE_SWITCH":"HEALTHY_UPGRADE");publish();
    }
    private void verifySelector(String name)throws Exception{
        JSONObject response=action("getProxies",null,5000);Object data=response.opt("data");
        JSONObject document=data instanceof JSONObject?(JSONObject)data:new JSONObject(String.valueOf(data));
        JSONObject group=document.getJSONObject("proxies").getJSONObject("AUTOPILOT");
        if(!name.equals(group.optString("now")))throw new IOException("SELECTOR_MISMATCH");
    }
    private void probeLatency(Node n)throws Exception{
        JSONObject response=action("testDelay",new JSONObject().put("proxy-name",n.name).put("test-url","https://www.gstatic.com/generate_204").put("timeout",5000).toString(),7500);
        record(n).latency(response.optInt("data",-1),now());
    }
    private void probeServices(Node n)throws InterruptedException{
        Integer port=ports.get(n.id);if(port==null)return;
        ServiceChecks.Result r=checks.check(port);SelectionPolicy.Health h=record(n);
        h.claude=r.claude;h.chatgpt=r.chatgpt;h.gemini=r.gemini;h.servicesAt=now();
    }
    private void requestProviderRecovery(){
        ArrayList<String> failed=new ArrayList<>();
        for(Node node:nodes){SelectionPolicy.Health h=record(node);
            if("octohide".equals(node.provider)&&h!=null&&h.latency<=0&&h.failures>=2)failed.add(node.name);}
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
        ensureRunning();tunStarted=new CompletableFuture<>();
        ParcelFileDescriptor descriptor=reuse?service.duplicateTunnel():service.establishTunnel();
        int fd=descriptor.detachFd();descriptor.close();
        Clash.INSTANCE.startTUN(fd,new TunInterface(){
            @Override public void protect(int socket){if(!service.protect(socket))app.log("vpn","SOCKET_PROTECT_FAILED");}
            @Override public String resolverProcess(int protocol,String source,String target,int uid){return "";}
        },"Autopilot","mixed",AutopilotVpnService.ADDRESSES,AutopilotVpnService.DNS,AutopilotVpnService.MTU);
        if(!await(tunStarted,7000))throw new IOException("TUN_START_FAILED");tunReady=true;
        app.log("vpn","TUN_STARTED");phase="testing";detail="VPN active; testing nodes";publish();
    }
    private void event(String payload){
        if(payload==null)return;
        try{
            JSONObject e=new JSONObject(payload);JSONObject message=e.optJSONObject("data");
            if(message==null||!"log".equals(message.optString("type")))return;
            JSONObject log=message.optJSONObject("data");if(log==null)return;
            String text=log.optString("Payload");CompletableFuture<Boolean> started=tunStarted;
            if(started!=null){if(text.startsWith("TUN started:"))started.complete(true);else if(text.startsWith("TUN start failed:"))started.complete(false);}
        }catch(JSONException ignored){}
    }
    private JSONObject selected(String name)throws JSONException{return new JSONObject().put("selected-map",new JSONObject().put("AUTOPILOT",name));}
    static JSONObject configuration(List<Node> nodes,Map<String,Integer> ports)throws Exception{
        ports.clear();JSONArray proxies=new JSONArray(),names=new JSONArray().put("REJECT"),listeners=new JSONArray();
        int port=27000;
        for(Node n:nodes){proxies.put(new JSONObject(n.config.toString()));names.put(n.name);ports.put(n.id,port);
            listeners.put(new JSONObject().put("name","autopilot-"+port).put("type","mixed").put("listen","127.0.0.1").put("port",port++).put("proxy",n.name).put("udp",true));}
        JSONObject dns=new JSONObject().put("enable",true).put("listen","127.0.0.1:0").put("ipv6",true)
            .put("enhanced-mode","fake-ip").put("fake-ip-range","198.18.0.1/16")
            .put("default-nameserver",new JSONArray().put("1.1.1.1").put("8.8.8.8"))
            // Bootstrap and per-node probe DNS must work before AUTOPILOT has a verified node.
            // These encrypted resolver sockets belong to our excluded/protected UID.
            .put("nameserver",new JSONArray().put("https://1.1.1.1/dns-query").put("https://8.8.8.8/dns-query"))
            .put("proxy-server-nameserver",new JSONArray().put("https://1.1.1.1/dns-query"));
        return new JSONObject().put("mode","rule").put("log-level","info").put("allow-lan",false).put("bind-address","127.0.0.1")
            .put("ipv6",true).put("find-process-mode","off").put("profile",new JSONObject().put("store-selected",false).put("store-fake-ip",false))
            .put("dns",dns).put("tun",new JSONObject().put("enable",false)).put("proxies",proxies)
            .put("proxy-groups",new JSONArray().put(new JSONObject().put("name","AUTOPILOT").put("type","select").put("proxies",names)))
            .put("listeners",listeners).put("rules",new JSONArray().put("MATCH,AUTOPILOT"));
    }
    private JSONObject writeConfig()throws Exception{
        JSONObject config=configuration(nodes,ports);
        File staging=new File(home,"config.yaml.tmp"),target=new File(home,"config.yaml");
        try(FileOutputStream out=new FileOutputStream(staging)){out.write(CoreConfigEncoding.encode(config));out.getFD().sync();}
        Files.move(staging.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
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
    private void syncHealth(){HashSet<String> ids=new HashSet<>();for(Node n:nodes){ids.add(n.id);health.computeIfAbsent(n.id,k->new SelectionPolicy.Health(n.id,n.name,n.country));}health.keySet().retainAll(ids);}
    private SelectionPolicy.Health record(Node n){return health.get(n.id);}
    private Node findById(String id){for(Node n:nodes)if(n.id.equals(id))return n;return null;}
    private Node findByName(String name){for(Node n:nodes)if(n.name.equals(name))return n;return null;}
    private static long now(){return System.currentTimeMillis();}
    private void publish(){
        try{
            JSONArray rows=new JSONArray();ArrayList<SelectionPolicy.Health> ordered=new ArrayList<>(health.values());ordered.sort(SelectionPolicy.order(app.preferred()));
            for(SelectionPolicy.Health h:ordered){Node n=findById(h.id);if(n==null)continue;
                rows.put(new JSONObject().put("name",h.name).put("country",h.country).put("provider",n.provider).put("latency",h.latency)
                    .put("score",h.score()==Integer.MAX_VALUE?-1:h.score()).put("claude",h.claude).put("chatgpt",h.chatgpt).put("gemini",h.gemini)
                    .put("status",current!=null&&current.id.equals(h.id)?"Active":h.qualified(now())?"Ready":h.latency<=0?"Unavailable":"Needs checks"));}
            JSONArray skipped=new JSONArray();for(ProfileValidation.Skipped entry:coreSkipped)skipped.put(entry.json());
            JSONObject snapshot=new JSONObject().put("phase",phase).put("current",current==null?"":current.name).put("pinned",!pinned.isEmpty())
                .put("auto",pinned.isEmpty()).put("busy",busy).put("error",error).put("detail",detail).put("lastSwitch",lastSwitch).put("nodes",rows).put("tun",tunReady);
            snapshot.put("coreBaselineAccepted",baselineAccepted).put("skippedCount",coreSkipped.size()).put("skippedNodes",skipped);
            JSONObject diagnostic=app.coreDiagnostic();if(diagnostic!=null)snapshot.put("coreDiagnostic",diagnostic);
            app.publish(snapshot);service.updateNotification(current==null?detail:current.name+(pinned.isEmpty()?" · Auto":" · Pinned"));
        }catch(JSONException ignored){}
    }
    private void cleanup(){
        running.set(false);checks.close();
        if(nativeReady){try{Clash.INSTANCE.stopTun();}catch(Throwable ignored){}try{Clash.INSTANCE.invokeAction(new JSONObject().put("id","stop-log").put("method","stopLog").put("data",JSONObject.NULL).toString(),(InvokeInterface)value->{});}catch(Throwable ignored){}
            try{Clash.INSTANCE.setEventListener((InvokeInterface)null);}catch(Throwable ignored){}}
        service.releaseTunnel();profileUsable=false;
        tunReady=false;busy=false;current=null;pinned="";
        if(preserveError){phase="error";detail="VPN could not start. See diagnostics.";}else{phase="stopped";detail="VPN disconnected";error="";}
        publish();app.log("vpn","STOPPED");
        worker.shutdownNow();service.finished(this);
    }
}
