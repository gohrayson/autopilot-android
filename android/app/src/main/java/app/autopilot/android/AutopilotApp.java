package app.autopilot.android;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class AutopilotApp extends Application {
    public ProviderRepository providers;
    public UpdateManager updates;
    public volatile VpnEngine engine;
    public final ExecutorService providerExecutor = Executors.newSingleThreadExecutor();
    public volatile String providerBusy = "";
    private volatile JSONObject snapshot = new JSONObject();
    private volatile String nodeFingerprint="";
    private volatile JSONObject lastCoreDiagnostic;
    private volatile JSONObject currentCoreDiagnostic;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
    @Override public void onCreate() {
        super.onCreate(); prefs = getSharedPreferences("preferences", MODE_PRIVATE);
        try{String saved=prefs.getString("coreDiagnostic","");if(saved.length()<=8192&&!saved.isEmpty())lastCoreDiagnostic=CoreDiagnostics.restore(new JSONObject(saved));}catch(Exception ignored){}
        providers = new ProviderRepository(this);
        updates = new UpdateManager(this);
        updates.start();
        providers.onChanged = () -> {
            String fingerprint=String.join(",",providers.nodes().stream().map(n->n.id).collect(java.util.stream.Collectors.toList()));
            if(!fingerprint.equals(nodeFingerprint)){nodeFingerprint=fingerprint;VpnEngine e=engine;if(e!=null)e.nodesChanged();}
            changed();
        };
        ticker.scheduleWithFixedDelay(() -> {
            if (engine != null && providerBusy.isEmpty()) providerExecutor.execute(() -> {
                try { providers.refreshDue(preferred()); } catch (Exception ex) { log("provider", "REFRESH_FAILED"); }
            });
        }, 30, 30, TimeUnit.SECONDS);
    }
    public List<String> preferred() {
        ArrayList<String> result = new ArrayList<>();
        try { JSONArray a=new JSONArray(prefs.getString("preferred", "[]")); for(int i=0;i<a.length();i++)result.add(a.getString(i)); } catch(JSONException ignored){}
        return result;
    }
    public void setPreferred(List<String> regions) {
        if(regions.size()>32 || regions.stream().anyMatch(c-> !c.matches("[A-Z]{2}") || Set.of("CN","HK","MO").contains(c))) throw new IllegalArgumentException("Invalid countries");
        prefs.edit().putString("preferred",new JSONArray(new LinkedHashSet<>(regions)).toString()).apply();
        VpnEngine e=engine; if(e!=null)e.preferencesChanged(); changed();
        providerExecutor.execute(() -> { try { providers.refreshDue(preferred()); } catch(Exception ex){log("provider","REFRESH_FAILED");} });
    }
    public Set<String> bypassPackages(){return new HashSet<>(prefs.getStringSet("bypass",Set.of()));}
    public boolean vpnWanted(){return prefs.getBoolean("vpnWanted",false);}
    public void setVpnWanted(boolean wanted){prefs.edit().putBoolean("vpnWanted",wanted).apply();}
    public void setBypassPackages(Set<String> packages){ prefs.edit().putStringSet("bypass",new HashSet<>(packages)).apply(); changed(); }
    public void publish(JSONObject state){snapshot=state;providers.currentNodeName=state.optString("current","");changed();}
    public void requestRecovery(List<String> names){
        List<String> copy=new ArrayList<>(names);
        providerExecutor.execute(()->{try{providers.refreshFailedNodes(copy,preferred());}catch(ProviderException ex){log("provider",ex.code);}});
    }
    public JSONObject state(){return snapshot;}
    /** Persisted failures are history until a current run reports another rejection. */
    public JSONObject coreDiagnostic(){JSONObject saved=currentCoreDiagnostic;return saved==null?null:CoreDiagnostics.restore(saved);}
    public JSONObject historicalCoreDiagnostic(){JSONObject saved=lastCoreDiagnostic;return saved==null?null:CoreDiagnostics.restore(saved);}
    public void recordCoreAccepted(CoreDiagnostics.Stage stage){
        JSONObject current=currentCoreDiagnostic;
        if(stage!=CoreDiagnostics.Stage.BASELINE||current!=null&&"baseline".equals(current.optString("stage")))currentCoreDiagnostic=null;
    }
    public void recordCoreDiagnostic(CoreDiagnostics.Diagnostic diagnostic,CoreDiagnostics.Stage stage,int skippedCount){
        JSONObject safe=diagnostic.json();try{safe.put("stage",stage.value).put("at",System.currentTimeMillis()).put("skippedCount",Math.max(0,Math.min(ProfileValidation.MAX_DROPS,skippedCount)));}catch(JSONException ignored){}
        currentCoreDiagnostic=safe;lastCoreDiagnostic=safe;prefs.edit().putString("coreDiagnostic",safe.toString()).apply();
        log("core-"+stage.value,diagnostic.logCode());changed();
    }
    public void recordBaselineDiagnostic(JSONObject diagnostic){
        JSONObject safe=CoreDiagnostics.restore(diagnostic);
        if(safe==null||!"baseline".equals(safe.optString("stage")))return;
        try{safe.put("at",System.currentTimeMillis()).put("skippedCount",0);}catch(JSONException ignored){}
        currentCoreDiagnostic=safe;lastCoreDiagnostic=safe;prefs.edit().putString("coreDiagnostic",safe.toString()).apply();
        log("core-baseline",safe.optString("code"));changed();
    }
    public void changed(){UpdateManager updater=updates;if(updater!=null)updater.connectionStateChanged();main.post(()->{for(Runnable listener:listeners)listener.run();});}
    public void listen(Runnable listener){listeners.addIfAbsent(listener);}
    public void unlisten(Runnable listener){listeners.remove(listener);}
    /** Callers pass fixed component/code identifiers, never exception text or configurations. */
    public synchronized void log(String component,String code){
        if(!component.matches("[A-Za-z0-9_-]{1,32}") || !code.matches("[A-Za-z0-9_.:-]{1,96}"))return;
        try {
            File file = new File(getFilesDir(),"diagnostics.log");
            if(file.length()>512*1024) { File prior=new File(getFilesDir(),"diagnostics.previous.log"); if(prior.exists())prior.delete(); file.renameTo(prior); }
            try(FileOutputStream out=new FileOutputStream(file,true)){out.write((Instant.now()+" "+component+" "+code+"\n").getBytes(StandardCharsets.UTF_8));}
        } catch(IOException ignored){}
    }
    public String diagnostics(){
        StringBuilder out=new StringBuilder("Autopilot Android "+BuildConfig.VERSION_NAME+"\nAndroid "+android.os.Build.VERSION.RELEASE+" / "+android.os.Build.SUPPORTED_ABIS[0]+"\n");
        JSONObject s=state(); out.append("VPN: ").append(s.optString("phase","idle")).append("\n");
        out.append("VPN error: ").append(s.optString("error","")).append("\nBaseline accepted: ").append(s.optBoolean("coreBaselineAccepted"))
            .append("\nProfile accepted: ").append(s.optBoolean("coreProfileAccepted")).append("\nSkipped nodes: ").append(s.optInt("skippedCount")).append("\n");
        JSONObject core=coreDiagnostic();out.append("Current core diagnostic: ").append(core==null?"none":core.toString()).append("\n");
        JSONObject historical=historicalCoreDiagnostic();if(historical!=null)out.append("Last core failure (historical): ").append(historical).append("\n");
        out.append("Health check path: ").append(s.optString("checkPath","not started")).append("\n")
            .append("Other-app tunnel traffic: not verified by node checks\n")
            .append("Resolver policy: ").append(s.optString("resolverPolicy",CoreDnsConfig.POLICY_ID)).append("\n")
            .append("TUN stack: ").append(s.optString("tunStack","not started")).append("\n");
        JSONArray health=s.optJSONArray("nodeHealth");if(health!=null)out.append("Node health: ").append(health).append("\n");
        JSONObject network=s.optJSONObject("networkSignals");if(network!=null)out.append("Native network observations (session only, not per-node): ").append(network).append("\n");
        if(updates!=null)out.append(updates.diagnostic());
        JSONArray skipped=s.optJSONArray("skippedNodes");if(skipped!=null&&skipped.length()>0)out.append("Skipped diagnostics: ").append(skipped).append("\n");
        for(String p:List.of("onevpn","octohide")){
            JSONObject a=providers.status(p);out.append(p).append(": ").append(a.optString("phase")).append(" / ").append(a.optString("code")).append(" / ").append(a.optInt("nodeCount")).append(" nodes\n");
            out.append("Retry: ").append(a.optLong("retryAt")).append("\n");
            JSONObject last=a.optJSONObject("lastDiagnostic");
            if(last!=null)out.append("Last diagnostic: ").append(last).append("\n");
        }
        try { File f=new File(getFilesDir(),"diagnostics.log"); if(f.exists()) {byte[] b=java.nio.file.Files.readAllBytes(f.toPath());String text=new String(b,StandardCharsets.UTF_8);out.append(text.substring(Math.max(0,text.length()-48000)));} }catch(IOException ignored){}
        return out.toString();
    }
}
