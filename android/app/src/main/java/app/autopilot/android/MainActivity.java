package app.autopilot.android;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.*;

/** Native Android UI. Network and account operations always run off the main thread. */
public final class MainActivity extends Activity {
    private static final int BLUE=0xff315fea, INK=0xff15233c, MUTED=0xff64728b, GREEN=0xff16865d, RED=0xffb53636;
    private static final int DISABLED_BUTTON_BACKGROUND=0xffe3e5e9, DISABLED_BUTTON_TEXT=0xff8b919c, SECONDARY_BUTTON_BACKGROUND=0xffedf2ff;
    private AutopilotApp app;
    private LinearLayout content, nodeList;
    private TextView phase, selected, detail, connectionHealth, countries, oneStatus, octoStatus, importStatus, routingStatus, nodeSummary, error, updateSummary;
    private Switch globalMode;
    private AlertDialog updateDialog;
    private AlertDialog reportDialog;
    private TextView updateDialogStatus, updateDialogNotes, updateProgressText, updateActionHint;
    private ProgressBar updateProgressBar;
    private Button updateCheck, updateDownload, updateInstall, updatePause;
    private String lastRenderedNodes;
    private Button connect, auto, retest;
    private final Runnable render = this::render;
    private boolean visible;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable(){public void run(){if(visible){render();app.updates.foregroundCheck();handler.postDelayed(this,3000);}}};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); app=(AutopilotApp)getApplication();
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(0xfff4f6fb);
        content=column(); content.setPadding(dp(18),dp(12),dp(18),dp(28)); scroll.addView(content);
        if(Build.VERSION.SDK_INT>=30)scroll.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            view.setPadding(i.left,i.top,i.right,i.bottom); return insets;
        });
        // API 26-29 use the legacy inset API; they do not enforce edge-to-edge.
        if(Build.VERSION.SDK_INT<30)scroll.setOnApplyWindowInsetsListener((v,i)->i);
        setContentView(scroll);
        LinearLayout header=row();
        TextView title=text("Autopilot",30,INK); title.setTypeface(null,Typeface.BOLD); header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button menu=button("•••",false); menu.setContentDescription("More options"); menu.setOnClickListener(v->menu(menu)); header.addView(menu); content.addView(header);
        addText(content,"Your VPN, on autopilot",15,MUTED);

        LinearLayout status=card(); phase=addText(status,"Ready to start",14,BLUE); selected=addText(status,"Connect an account",25,INK); selected.setTypeface(null,Typeface.BOLD);
        detail=addText(status,"Sign in below or import a configuration.",14,MUTED);
        connectionHealth=addText(status,"",13,MUTED);connectionHealth.setVisibility(View.GONE);
        error=addText(status,"",14,RED);
        connect=button("Start VPN",true); connect.setOnClickListener(v->toggleVpn()); status.addView(connect,full());
        LinearLayout actions=row(); auto=button("Resume Auto",false); auto.setOnClickListener(v->{if(app.engine!=null){if(app.settings().optBoolean("paused")||app.state().optBoolean("pinned"))app.engine.resumeAuto();else app.engine.pauseAuto();}}); actions.addView(auto,new LinearLayout.LayoutParams(0,-2,1));
        retest=button("Re-test",false); retest.setOnClickListener(v->{if(app.engine!=null)app.engine.retest();}); actions.addView(retest,new LinearLayout.LayoutParams(0,-2,1));status.addView(actions);content.addView(status);

        LinearLayout country=card();section(country,"Preferred countries");countries=addText(country,"Fastest healthy node anywhere",15,INK);
        addText(country,"Uses your first country with healthy, service-checked nodes. Falls back to other countries when needed.",13,MUTED);
        Button edit=button("Edit priority order",false);edit.setOnClickListener(v->countryDialog());country.addView(edit,full());content.addView(country);

        LinearLayout routing=card();section(routing,"Routing & automatic selection");
        globalMode=new Switch(this);globalMode.setText("Global mode");routing.addView(globalMode,full());
        globalMode.setOnCheckedChangeListener((view,on)->{if(app.settings().optBoolean("globalMode")!=on)try{app.setSettings(new JSONObject().put("globalMode",on));}catch(Exception invalid){toast(invalid.getMessage());render();}});
        routingStatus=addText(routing,"",13,MUTED);
        Button split=button("Split tunneling & websites",false);split.setOnClickListener(v->splitDialog());routing.addView(split,full());
        Button settings=button("Automatic selection settings",false);settings.setOnClickListener(v->settingsDialog());routing.addView(settings,full());content.addView(routing);

        LinearLayout accounts=card();section(accounts,"VPN accounts");
        oneStatus=accountRow(accounts,"OneVPN","onevpn");octoStatus=accountRow(accounts,"Octohide","octohide");
        addText(accounts,"Sign in on this device. Sessions and imported configurations are encrypted with Android Keystore. Passwords are not saved.",12,MUTED); content.addView(accounts);

        LinearLayout imports=card();section(imports,"Imported configuration");importStatus=addText(imports,"No imported nodes",13,MUTED);
        Button load=button("Import / replace configuration",false);load.setOnClickListener(v->importConfiguration());imports.addView(load,full());
        Button remove=button("Remove imported configuration",false);remove.setOnClickListener(v->{if(app.providers.status("import").optInt("nodeCount")==0){toast("No imported configuration");return;}new AlertDialog.Builder(this).setTitle("Remove imported configuration?").setMessage("Removes only the encrypted imported nodes. Provider accounts remain connected.").setPositiveButton("Remove",(d,w)->providerJob("import",()->app.providers.logout("import"))).setNegativeButton("Cancel",null).show();});imports.addView(remove,full());content.addView(imports);

        LinearLayout updates=card();section(updates,"App updates");updateSummary=addText(updates,"Autopilot "+BuildConfig.VERSION_NAME,13,MUTED);
        addText(updates,getString(R.string.update_download_features),12,MUTED);
        Button openUpdates=button("Updates",false);openUpdates.setOnClickListener(v->updatesDialog());updates.addView(openUpdates,full());content.addView(updates);

        LinearLayout nodes=card();section(nodes,"Nodes");nodeSummary=addText(nodes,"No nodes yet",13,MUTED);nodeList=column();nodes.addView(nodeList);content.addView(nodes);
        addText(content,"Autopilot Android · "+BuildConfig.VERSION_NAME,12,MUTED);
        render();
        handleUpdateIntent(getIntent());
    }
    @Override protected void onResume(){super.onResume();visible=true;app.listen(render);render();app.updates.connectionStateChanged();app.updates.foregroundCheck();handler.postDelayed(tick,3000);}
    @Override protected void onPause(){visible=false;app.unlisten(render);handler.removeCallbacks(tick);super.onPause();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleUpdateIntent(intent);}
    @Override protected void onDestroy(){if(updateDialog!=null)updateDialog.dismiss();if(reportDialog!=null)reportDialog.dismiss();super.onDestroy();}
    private void handleUpdateIntent(Intent intent){
        if(intent==null)return;
        if(intent.getBooleanExtra("openDiagnostics",false)){intent.removeExtra("openDiagnostics");handler.post(this::diagnostics);}
        else if(intent.getBooleanExtra("openUpdates",false)){intent.removeExtra("openUpdates");handler.post(this::updatesDialog);}
    }

    private void render(){
        if(isFinishing())return;
        JSONObject s=app.state();String p=s.optString("phase","idle"),current=s.optString("current","");
        boolean running=app.engine!=null && !Set.of("stopped","idle","error").contains(p);
        boolean connected=p.equals("connected")&&!current.isEmpty();
        JSONObject watchdog=s.optJSONObject("watchdog");
        boolean healthUncertain=connected&&healthUncertain(watchdog,System.currentTimeMillis());
        phase.setText(switch(p){
            case "starting"->"STARTING";case "testing"->"CHECKING NODES";
            case "connected"->healthUncertain?"CHECKING CONNECTION":connected?(s.optBoolean("pinned")?"CONNECTED · PINNED":"CONNECTED · AUTO"):"CHECKING NODES";
            case "degraded"->"TRAFFIC PAUSED";case "paused"->"AUTO PAUSED";
            case "error"->"NEEDS ATTENTION";case "stopped"->"DISCONNECTED";default->"READY";
        });
        phase.setTextColor(connected&&!healthUncertain?GREEN:Set.of("error","degraded").contains(p)?RED:BLUE);
        JSONArray stateNodes=s.optJSONArray("nodes");
        boolean hasNodes=!app.providers.nodes().isEmpty()||stateNodes!=null&&stateNodes.length()>0;
        selected.setText(switch(p){
            case "starting"->"Starting VPN";
            case "testing"->!current.isEmpty()?current:switch(s.optString("stage")){
                case "services"->"Checking service access";case "tunnel"->"Starting VPN";
                case "profile"->"Updating VPN";default->"Testing nodes";
            };
            case "connected"->connected?current:"Checking connection";
            case "degraded"->"Traffic paused";case "paused"->current.isEmpty()?"Auto paused":current;
            case "error"->"Connection failed";
            default->hasNodes?"Ready to connect":"Connect an account";
        });
        detail.setText(s.optString("detail",running?"Checking your connection…":"Automatic switching stays active unless you pin a node."));
        String healthMessage=connectionHealthMessage(watchdog,System.currentTimeMillis());
        connectionHealth.setText(healthMessage);connectionHealth.setVisibility(healthMessage.isEmpty()?View.GONE:View.VISIBLE);
        error.setText(errorMessage(s.optString("error","")));error.setVisibility(error.getText().length()==0?View.GONE:View.VISIBLE);
        connect.setText(running?"Stop VPN":"Start VPN");connect.setEnabled(running||!app.providers.nodes().isEmpty());
        JSONObject common=app.settings();auto.setText(common.optBoolean("paused")||s.optBoolean("pinned")?"Resume Auto":"Pause Auto");
        auto.setEnabled(running); retest.setEnabled(running&&!p.equals("starting")&&!Set.of("profile","tunnel").contains(s.optString("stage")));
        globalMode.setChecked(common.optBoolean("globalMode"));
        String routing=common.optBoolean("globalMode")?"Global: captured traffic uses the VPN, except website overrides.":"Rule mode: website overrides, then regional direct routing, then VPN.";
        if(!common.optBoolean("globalMode")&&!AndroidRouting.geoAvailable(app))routing+=" Regional database unavailable: unmatched traffic uses the VPN.";
        routing+="\nApps: "+splitModeLabel(common.optString("splitMode","off"))+". Website rules apply only to apps captured by the VPN.";
        if(app.routingRestartPending())routing+="\nApp routing changes are pending. Stop and start VPN when ready to apply them.";
        routingStatus.setText(routing);routingStatus.setTextColor(app.routingRestartPending()?RED:MUTED);
        List<String> pref=app.preferred();countries.setText(pref.isEmpty()?"Fastest healthy node anywhere":String.join("  →  ",pref.stream().map(MainActivity::countryName).collect(java.util.stream.Collectors.toList())));
        renderAccount("onevpn",oneStatus);renderAccount("octohide",octoStatus);
        JSONObject imported=app.providers.status("import");importStatus.setText(importSummary(imported));
        renderUpdates();
        JSONArray list=app.engine==null&&!p.equals("error")?null:s.optJSONArray("nodes");if(list==null){list=new JSONArray();for(Node n:app.providers.nodes()){JSONObject o=new JSONObject();try{o.put("name",n.name).put("country",n.country).put("provider",n.provider).put("status","Not tested");}catch(JSONException ignored){}list.put(o);}}
        int skippedCount=s.optInt("skippedCount");
        nodeSummary.setText(getResources().getQuantityString(R.plurals.node_summary,list.length(),list.length())+(skippedCount>0?" · "+skippedCount+" incompatible nodes skipped":""));
        // Download progress can arrive four times per second. Rebuild node views only when their
        // content or selection changes; account and update status above remain live.
        String nodesKey=current+"\n"+list;
        if(nodesKey.equals(lastRenderedNodes))return;lastRenderedNodes=nodesKey;
        nodeList.removeAllViews();
        for(int i=0;i<list.length();i++){
            JSONObject node=list.optJSONObject(i);if(node==null)continue;String name=node.optString("name");
            LinearLayout line=column();line.setPadding(dp(10),dp(12),dp(10),dp(12));
            if(name.equals(current))line.setBackground(background(0xffedf2ff,10));
            TextView label=addText(line,(name.equals(current)?"●  ":"")+name,16,INK);label.setTypeface(null,Typeface.BOLD);
            int latency=node.optInt("latency",-1);
            addText(line,node.optString("provider")+" · "+node.optString("country")+" · "+(latency>0?latency+" ms":node.optString("status","Not tested")),13,MUTED);
            String checkMessage=HealthDiagnostics.latencyMessage(node.optString("latencyCode","NOT_TESTED"));
            if(!checkMessage.isEmpty()&&node.optLong("latencyAt")>0)addText(line,checkMessage,12,MUTED);
            String checks="Claude "+mark(node.optString("claude"))+"    ChatGPT "+mark(node.optString("chatgpt"))+"    Gemini "+mark(node.optString("gemini"));
            addText(line,checks,12,MUTED);line.setOnClickListener(v->{if(app.engine!=null){app.engine.select(name,false);toast("Checking node before switching…");}else toast("Start the VPN first");});
            line.setOnLongClickListener(v->{if(app.engine!=null)new AlertDialog.Builder(this).setTitle("Pin this node?").setMessage("Autopilot will hold this node until you Resume Auto. It can still recover if the node stops working.").setPositiveButton("Pin",(d,w)->app.engine.select(name,true)).setNegativeButton("Cancel",null).show();return true;});
            nodeList.addView(line);View divider=new View(this);divider.setBackgroundColor(0xffe9edf4);nodeList.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
        }
    }
    static boolean healthUncertain(JSONObject watchdog,long now){
        if(watchdog==null)return false;
        String code=watchdog.optString("code");
        if(Set.of("SUSPECT","SERVICE_PATH_SUSPECT","CONFIRMED_FAILED","CONFIRMED_PARTIAL_FAILED","RECOVERING","NO_VERIFIED_REPLACEMENT").contains(code))return true;
        long success=watchdog.optLong("lastSuccessAt");
        return Set.of("HEALTHY","CHECKING").contains(code)&&(success<=0||now<success||now-success>45_000);
    }
    static String connectionHealthMessage(JSONObject watchdog,long now){
        if(watchdog==null)return "";
        String code=watchdog.optString("code");
        return switch(code){
            case "HEALTHY"->healthUncertain(watchdog,now)?"Connection check is overdue. Verifying access…":"HTTPS access checked at "+DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(watchdog.optLong("lastSuccessAt")));
            case "CHECKING"->"Checking HTTPS access…";
            case "SUSPECT","SERVICE_PATH_SUSPECT"->"Connection may be failing. Confirming before switching…";
            case "CONFIRMED_FAILED","RECOVERING"->"Connection failed. Verifying a replacement…";
            case "CONFIRMED_PARTIAL_FAILED"->"A service path failed. Comparing verified replacements…";
            case "NO_VERIFIED_REPLACEMENT"->"No replacement passed the checks. Autopilot is checking again.";
            default->"";
        };
    }
    private static String mark(String result){return switch(result){case "ok"->"✓";case "challenged"->"◐";case "blocked","error"->"✕";default->"·";};}
    private static String errorMessage(String code){String core=CoreDiagnostics.messageForCode(code);if(core!=null)return core;return switch(code){
        case ""->"";case "NO_NODES"->"Sign in to a VPN provider or import a configuration.";
        case "NO_HEALTHY_NODE"->"No node passed the checks. Autopilot will keep testing.";
        case "NODE_UNAVAILABLE"->"That node is no longer available. Refresh the provider’s nodes.";
        case "NODE_NOT_HEALTHY"->"The selected node did not pass all service checks.";
        case "CORE_UNAVAILABLE"->"The VPN core could not load on this device. Open Diagnostics.";
        case "VPN_OPERATION_FAILED"->"The VPN could not complete an operation. Open Diagnostics.";
        case "CURRENT_NODE_FAILED"->"The current node failed a check. Autopilot is finding a working replacement.";
        case "APPLICATION_PATH_FAILED"->"The active node lost HTTPS access. Autopilot is checking replacements.";
        case "SERVICE_PATH_DEGRADED"->"A service is not responding. The VPN is staying on the working connection while Autopilot checks alternatives.";
        case "CORE_TIMEOUT"->"The VPN core took too long to respond. Stop and start the VPN to retry.";
        case "CORE_STORAGE"->"Android could not write the VPN configuration. Check available storage.";
        case "CORE_CONFIG_REJECTED"->"The VPN core rejected the configuration. Open Diagnostics for the recorded reason.";
        case "SELECTOR_MISMATCH","CORE_ACTION_FAILED"->"The VPN core could not select the requested node. Open Diagnostics.";
        case "TUN_START_FAILED"->"Android’s VPN interface could not start. Stop any other VPN on this device and retry.";
        case "SPLIT_APPS_UNAVAILABLE"->"The apps selected for VPN-only routing are no longer installed. Edit Split tunneling before starting.";
        case "VPN_PERMISSION_REQUIRED"->"Accept Android’s VPN permission to start Autopilot.";
        default->"The VPN needs attention. Open Diagnostics for the error code.";};}
    private void renderAccount(String provider,TextView target){
        JSONObject s=app.providers.status(provider);String text=accountSummary(s,System.currentTimeMillis());
        if(provider.equals(app.providerBusy))text+="\nWorking…";target.setText(text);
    }
    static String accountSummary(JSONObject s,long now){
        String user=s.optString("username","");StringBuilder text=new StringBuilder(user.isEmpty()?"Not signed in":user);
        text.append("\n").append(s.optString("phase","disconnected")).append(" · ").append(s.optInt("nodeCount")).append(" cached nodes");
        if(s.optBoolean("connected")){
            text.append("\n").append(s.optBoolean("paid")?"Paid subscription":"Subscription status unavailable");
            long expiry=s.optLong("subscriptionEnd");if(expiry>0)text.append(expiry<now?" · expired ":" · ends ").append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(expiry)));
        }
        long refreshed=s.optLong("lastRefresh");if(refreshed>0)text.append("\nServers refreshed: ").append(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(new Date(refreshed)));
        long retry=s.optLong("retryAt");if(retry>now)text.append("\nNext request: ").append(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(new Date(retry)));
        String error=s.isNull("error")?"":s.optString("error","");if(!error.isEmpty())text.append("\n").append(error);
        return text.toString();
    }
    static String importSummary(JSONObject status){int count=status.optInt("nodeCount");String result=count==0?"No imported nodes":count+" imported nodes · "+status.optString("phase");long at=status.optLong("lastRefresh");return at>0?result+"\nImported: "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(new Date(at)):result;}
    private TextView accountRow(LinearLayout parent,String title,String provider){
        addText(parent,title,18,INK).setTypeface(null,Typeface.BOLD);TextView status=addText(parent,"Not signed in",13,MUTED);
        LinearLayout actions=row();Button login=button("Sign in",false);login.setOnClickListener(v->loginDialog(provider,title));actions.addView(login,new LinearLayout.LayoutParams(0,-2,1));
        Button refresh=button("Refresh",false);refresh.setOnClickListener(v->providerJob(provider,()->app.providers.refresh(provider,app.preferred())));actions.addView(refresh,new LinearLayout.LayoutParams(0,-2,1));
        Button signout=button("Sign out",false);signout.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Disconnect "+title+" account?").setMessage("Removes this provider’s session and cached nodes from this device.").setPositiveButton("Disconnect",(d,w)->providerJob(provider,()->app.providers.logout(provider))).setNegativeButton("Cancel",null).show());actions.addView(signout,new LinearLayout.LayoutParams(0,-2,1));parent.addView(actions);return status;
    }
    private void loginDialog(String provider,String title){
        LinearLayout form=column();form.setPadding(dp(22),dp(8),dp(22),0);
        EditText email=new EditText(this);email.setHint("Email");email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);email.setSingleLine(true);form.addView(email);
        EditText password=new EditText(this);password.setHint("Password");password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setSingleLine(true);password.setSaveEnabled(false);form.addView(password);
        addText(form,"Your password is sent directly to "+title+" over HTTPS and is not saved.",12,MUTED);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title+" account").setView(form).setPositiveButton("Sign in",null).setNegativeButton("Cancel",(d,w)->password.setText("")).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            String user=email.getText().toString().trim(),secret=password.getText().toString();
            if(user.isEmpty()||secret.isEmpty()){toast("Enter your email and password");return;}
            if(!app.providerBusy.isEmpty()){toast("An account request is already running");return;}
            password.setText("");dialog.dismiss();providerJob(provider,()->app.providers.login(provider,user,secret,app.preferred()));
        }));dialog.show();
    }
    interface AccountWork{void run()throws Exception;}
    private void providerJob(String provider,AccountWork work){
        if(!app.providerBusy.isEmpty()){toast("An account request is already running");return;}
        app.providerBusy=provider;app.changed();app.providerExecutor.execute(()->{
            try{work.run();}catch(ProviderException ex){app.log(provider,ex.code);runOnUiThread(()->toast(ex.getMessage()));}
            catch(Exception ex){app.log("provider","REQUEST_FAILED");runOnUiThread(()->toast("Could not complete the request. Open Diagnostics for details."));}
            finally{app.providerBusy="";app.changed();}
        });
    }
    private void toggleVpn(){
        JSONObject s=app.state();if(app.engine!=null&&!Set.of("idle","stopped","error").contains(s.optString("phase"))){startService(new Intent(this,AutopilotVpnService.class).setAction(AutopilotVpnService.STOP));return;}
        if(app.providers.nodes().isEmpty()){toast("Sign in to a provider or import a configuration first");return;}
        Intent permission=VpnService.prepare(this);if(permission!=null)startActivityForResult(permission,101);else startVpn();
    }
    private void startVpn(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},102);
        startForegroundService(new Intent(this,AutopilotVpnService.class).setAction(AutopilotVpnService.START));
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==101){if(result==RESULT_OK)startVpn();else toast("VPN permission was not granted");}
        if(request==103&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();providerJob("import",()->{String text;try(InputStream in=getContentResolver().openInputStream(uri)){text=readText(in,8*1024*1024);}app.providers.importConfig(text);});}
        if(request==105&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();app.providerExecutor.execute(()->{try{JSONObject patch;try(InputStream in=getContentResolver().openInputStream(uri)){patch=ProductSettings.portablePatch(new JSONObject(readText(in,1024*1024)));}JSONObject finalPatch=patch;runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Import shared preferences?").setMessage("Applies service checks, country priorities, automatic selection and website rules. Accounts and app lists stay on this device. Changes apply immediately.").setPositiveButton("Apply",(d,w)->{try{app.setSettings(finalPatch);toast("Shared preferences imported");}catch(Exception invalid){toast(invalid.getMessage());}}).setNegativeButton("Cancel",null).show());}catch(Exception invalid){runOnUiThread(()->toast("Could not import shared preferences. Use an Autopilot preferences JSON file."));}});}
        if(request==106&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();JSONObject exported=ProductSettings.portable(app.settings());app.providerExecutor.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException();out.write(exported.toString(2).getBytes(StandardCharsets.UTF_8));runOnUiThread(()->toast("Shared preferences exported. Accounts and app lists were excluded."));}catch(Exception invalid){runOnUiThread(()->toast("Could not save preferences"));}});}
    }
    private void countryDialog(){
        LinearLayout outer=column();outer.setPadding(dp(20),0,dp(20),dp(12));LinearLayout rows=column();outer.addView(rows);
        TextView hint=addText(outer,"Changes save immediately. Put your highest priority first. Empty means fastest anywhere.",13,MUTED);
        final Runnable[] draw={null};draw[0]=()->{
            rows.removeAllViews();List<String> pref=app.preferred();for(int i=0;i<pref.size();i++){String c=pref.get(i);int index=i;LinearLayout line=row();TextView label=text((i+1)+". "+countryName(c),15,INK);line.addView(label,new LinearLayout.LayoutParams(0,-2,1));
                Button up=button("↑",false);up.setMinWidth(0);up.setMinimumWidth(0);up.setEnabled(i>0);up.setOnClickListener(v->{List<String> p=app.preferred();Collections.swap(p,index,index-1);app.setPreferred(p);draw[0].run();});line.addView(up);
                Button remove=button("×",false);remove.setMinWidth(0);remove.setMinimumWidth(0);remove.setOnClickListener(v->{List<String> p=app.preferred();p.remove(c);app.setPreferred(p);draw[0].run();});line.addView(remove);rows.addView(line);}
        };draw[0].run();
        Button add=button("Add country",false);add.setOnClickListener(v->{List<String> codes=new ArrayList<>(Arrays.asList(Locale.getISOCountries()));codes.removeAll(app.preferred());codes.removeAll(ProductSettings.strings(ProductContract.policy().optJSONArray("excludedCountries")));codes.sort(Comparator.comparing(MainActivity::countryName));String[] names=codes.stream().map(MainActivity::countryName).toArray(String[]::new);new AlertDialog.Builder(this).setTitle("Choose a country").setItems(names,(d,w)->{List<String> p=app.preferred();int max=ProductContract.settingsSchema().optJSONObject("preferredRegions").optInt("maxItems");if(p.size()>=max){toast("Choose up to "+max+" countries");return;}p.add(codes.get(w));app.setPreferred(p);draw[0].run();}).show();});outer.addView(add);
        ScrollView scroll=new ScrollView(this);scroll.addView(outer);new AlertDialog.Builder(this).setTitle("Country priority").setView(scroll).setPositiveButton("Done",null).show();
    }
    private void menu(View anchor){PopupMenu menu=new PopupMenu(this,anchor);String[] options={"Updates","Import configuration","Split tunneling & websites","Automatic selection settings","Export preferences","Import preferences","Events","Diagnostics","Android app settings","About & licenses"};for(String option:options)menu.getMenu().add(option);
        menu.setOnMenuItemClickListener(item->{switch(item.getTitle().toString()){
            case "Import configuration"->importConfiguration();
            case "Updates"->updatesDialog();case "Split tunneling & websites"->splitDialog();case "Automatic selection settings"->settingsDialog();case "Export preferences"->exportPreferences();case "Import preferences"->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),105);case "Events"->reportDialog("Events",app.events());case "Diagnostics"->diagnostics();case "Android app settings"->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));case "About & licenses"->about();default->{} }return true;});menu.show();}
    private void importConfiguration(){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),103);}
    private void exportPreferences(){startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"autopilot-preferences.json"),106);}
    private String updateStatus(JSONObject state){
        String phase=state.optString("phase"),version=state.optString("versionName");
        String status=switch(phase){case "restoring"->"Checking saved update files…";case "checking"->"Checking for updates…";case "downloading"->state.optBoolean("downloadPausing")?"Pausing download…":"Downloading "+version+"…";case "verifying"->"Verifying version "+version+"…";case "preparing-install"->"Preparing Android’s installer…";case "paused"->"Download paused. Saved parts are kept.";case "interrupted"->"Download interrupted. Ready to resume.";case "installing"->"Android is installing version "+version;case "confirmation"->"Android needs your confirmation to install "+version;case "ready"->"Version "+version+" is ready to install";case "available"->"Version "+version+" is available";case "current"->"You have the latest version";case "error"->"Update needs attention";default->"Automatic update checks are ready";};
        if(state.optBoolean("busy"))status+="\n"+getString(R.string.update_operation_elapsed,Math.max(0,state.optLong("operationElapsedMs"))/1000);
        if(state.optBoolean("ready")&&phase.equals("ready")&&app.updates.automaticInstalls())status+=!state.optBoolean("installPermission")?"\nAllow updates from Autopilot to enable installation.":state.optBoolean("vpnDefersInstall")?"\nAutomatic installation waits until the VPN is stopped.":Build.VERSION.SDK_INT<31?"\nTap Install update to continue.":"\nAutomatic installation is enabled.";
        String failure=UpdateManager.message(state.optString("error"));
        return status+(failure.isEmpty()?"":"\n"+failure+" ("+state.optString("error")+")");
    }
    private void renderUpdates(){
        JSONObject state=app.updates.state();
        String summary=getString(R.string.update_version_summary,BuildConfig.VERSION_NAME,updateStatus(state));
        updateSummary.setText(summary);
        if(updateDialog==null||!updateDialog.isShowing())return;
        long checked=state.optLong("lastChecked");
        String checkedText=checked==0?"Never":DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(new Date(checked));
        updateDialogStatus.setText(getString(R.string.update_checked_summary,summary,checkedText));
        String notes=state.optString("notes");updateDialogNotes.setText(notes);updateDialogNotes.setVisibility(notes.isEmpty()?View.GONE:View.VISIBLE);
        long total=Math.max(0,Math.min(UpdateManifest.MAX_APK_BYTES,state.optLong("downloadTotalBytes")));
        long downloaded=Math.max(0,Math.min(total,state.optLong("downloadedBytes")));
        String currentPhase=state.optString("phase");boolean busy=state.optBoolean("busy");
        boolean indeterminate=busy&&Set.of("restoring","checking","verifying","preparing-install").contains(currentPhase);
        int percent=total==0?0:(int)(downloaded*100/total);boolean showProgress=state.optBoolean("available")&&total>0;
        updateProgressBar.setVisibility(showProgress||indeterminate?View.VISIBLE:View.GONE);updateProgressBar.setIndeterminate(indeterminate);
        updateProgressBar.setContentDescription(indeterminate?"Update operation in progress":"Update download progress");
        updateProgressText.setVisibility(showProgress&&!indeterminate?View.VISIBLE:View.GONE);updateProgressBar.setProgress(percent);
        if(showProgress){
            String transfer=getString(R.string.update_download_amount,percent,formatBytes(downloaded),formatBytes(total));
            if("downloading".equals(state.optString("phase"))){
                long speed=Math.max(0,state.optLong("downloadBytesPerSecond")),eta=state.optLong("downloadEtaSeconds",-1);
                int connections=Math.max(0,Math.min(UpdateProgress.MAX_CONNECTIONS,state.optInt("downloadConnections")));
                transfer+="\n"+getString(R.string.update_download_speed_eta,formatBytes(speed),formatEta(eta))
                        +"\n"+getResources().getQuantityString(R.plurals.update_active_connections,connections,connections);
            }
            updateProgressText.setText(transfer);
        }
        updateCheck.setEnabled(!busy);updateCheck.setText(busy&&"checking".equals(currentPhase)?R.string.update_checking:R.string.update_check_now);
        updateDownload.setEnabled(!busy&&state.optBoolean("available")&&!state.optBoolean("ready"));updateInstall.setEnabled(!busy&&state.optBoolean("ready"));
        updateDownload.setText(state.optBoolean("canResume")?R.string.update_resume_download:R.string.update_download);
        updateInstall.setText("preparing-install".equals(currentPhase)?R.string.update_preparing_install:R.string.update_install);
        String hint=updateActionHint(state),failureCode=state.optString("error");
        if(!failureCode.isEmpty()&&!busy)hint=UpdateManager.message(failureCode)+" ("+failureCode+")\n"+hint;
        updateActionHint.setText(hint);updateActionHint.setTextColor(failureCode.isEmpty()?MUTED:RED);
        updatePause.setVisibility(busy&&"downloading".equals(state.optString("phase"))?View.VISIBLE:View.GONE);updatePause.setEnabled(state.optBoolean("canPause"));
        updatePause.setText(state.optBoolean("downloadPausing")?R.string.update_pausing_download:R.string.update_pause_download);
    }
    private String updateActionHint(JSONObject state){
        if(state.optBoolean("busy"))return switch(state.optString("phase")){
            case "checking"->getString(R.string.update_hint_checking,state.optString("feedHost"));
            case "verifying"->getString(R.string.update_hint_verifying);
            case "preparing-install"->getString(R.string.update_hint_installing);
            case "downloading"->getString(R.string.update_hint_downloading);
            default->getString(R.string.update_hint_wait);
        };
        if(state.optBoolean("ready"))return getString(R.string.update_hint_ready);
        if(state.optBoolean("available"))return getString(R.string.update_hint_available);
        return getString("current".equals(state.optString("phase"))?R.string.update_hint_current:R.string.update_hint_check);
    }
    private String formatBytes(long bytes){return android.text.format.Formatter.formatShortFileSize(this,Math.max(0,bytes));}
    private String formatEta(long seconds){
        if(seconds<0)return getString(R.string.update_eta_unknown);
        if(seconds<60)return getString(R.string.update_eta_seconds,seconds);
        if(seconds<3600)return getString(R.string.update_eta_minutes,seconds/60,seconds%60);
        return getString(R.string.update_eta_hours,seconds/3600,(seconds%3600)/60);
    }
    private void updatesDialog(){
        if(isFinishing()||isDestroyed()||(updateDialog!=null&&updateDialog.isShowing()))return;
        LinearLayout body=column();body.setPadding(dp(20),dp(8),dp(20),dp(12));
        updateDialogStatus=addText(body,"",14,INK);updateDialogNotes=addText(body,"",13,MUTED);updateDialogNotes.setTextIsSelectable(true);
        updateProgressBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);updateProgressBar.setMax(100);body.addView(updateProgressBar,full());
        updateProgressText=addText(body,"",13,MUTED);
        updateActionHint=addText(body,"",13,MUTED);updateActionHint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addText(body,getString(R.string.update_download_features),12,MUTED);
        updateCheck=button(getString(R.string.update_check_now),false);updateCheck.setOnClickListener(v->{updateAction(app.updates.requestCheckNow());});body.addView(updateCheck,full());
        updateDownload=button("Download update",false);updateDownload.setOnClickListener(v->{updateAction(app.updates.requestDownloadNow());});body.addView(updateDownload,full());
        updatePause=button(getString(R.string.update_pause_download),false);updatePause.setOnClickListener(v->app.updates.pauseDownload());body.addView(updatePause,full());
        updateInstall=button("Install update",true);updateInstall.setOnClickListener(v->installUpdate());body.addView(updateInstall,full());
        addText(body,"Installing restarts Autopilot. Reopen it to reconnect. Your accounts and preferences are kept.",12,MUTED);
        CheckBox automatic=new CheckBox(this);automatic.setText(R.string.update_automatic_checks);automatic.setChecked(app.updates.automaticChecks());body.addView(automatic);
        CheckBox wifi=new CheckBox(this);wifi.setText(R.string.update_wifi_downloads);wifi.setChecked(app.updates.wifiDownloads());body.addView(wifi);
        CheckBox install=new CheckBox(this);install.setText(R.string.update_automatic_installs);install.setChecked(app.updates.automaticInstalls());install.setEnabled(Build.VERSION.SDK_INT>=31);body.addView(install);
        automatic.setOnCheckedChangeListener((b,on)->app.updates.preferences(on,wifi.isChecked(),install.isChecked()));wifi.setOnCheckedChangeListener((b,on)->app.updates.preferences(automatic.isChecked(),on,install.isChecked()));install.setOnCheckedChangeListener((b,on)->app.updates.preferences(automatic.isChecked(),wifi.isChecked(),on));
        Button permission=button("Allow installation from Autopilot",false);permission.setVisibility(getPackageManager().canRequestPackageInstalls()?View.GONE:View.VISIBLE);permission.setOnClickListener(v->installPermission());body.addView(permission,full());
        addText(body,"Checks on app launch, every 15 minutes while open, and about every 6 hours in the background. Android may delay background checks or require installation confirmation. Automatic installation never stops a running VPN.",12,MUTED);
        Button server=button("Update server",false);server.setOnClickListener(v->updateServerDialog());body.addView(server,full());
        ScrollView scroll=new ScrollView(this);scroll.addView(body);
        updateDialog=new AlertDialog.Builder(this).setTitle("App updates").setView(scroll).setPositiveButton("Close",null).create();
        updateDialog.setOnDismissListener(d->{updateDialog=null;updateDialogStatus=null;updateDialogNotes=null;updateProgressBar=null;updateProgressText=null;updateActionHint=null;updateCheck=null;updateDownload=null;updatePause=null;updateInstall=null;});updateDialog.show();renderUpdates();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED&&!getPreferences(MODE_PRIVATE).getBoolean("updateNotificationAsked",false)){
            getPreferences(MODE_PRIVATE).edit().putBoolean("updateNotificationAsked",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},104);
        }
    }
    private void updateServerDialog(){
        EditText url=new EditText(this);url.setText(app.updates.feedUrl());url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);url.setSingleLine(false);url.setSelectAllOnFocus(true);
        AlertDialog server=new AlertDialog.Builder(this).setTitle("Public HTTPS update feed").setMessage("The default GitHub server is ready to use. Only change this for your own release feed.").setView(url).setPositiveButton("Save",null).setNeutralButton("Use default",(d,w)->{try{app.updates.setFeedUrl(UpdateManager.DEFAULT_FEED);updateAction(app.updates.requestCheckNow());}catch(UpdateDownload.UpdateException error){toast(UpdateManager.message(error.code));}}).setNegativeButton("Cancel",null).create();
        server.setOnShowListener(d->server.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{app.updates.setFeedUrl(url.getText().toString());server.dismiss();updateAction(app.updates.requestCheckNow());}catch(UpdateDownload.UpdateException error){toast(UpdateManager.message(error.code));}}));server.show();
    }
    private void installUpdate(){
        if(!getPackageManager().canRequestPackageInstalls()){
            installPermission();
            return;
        }
        updateAction(app.updates.requestPrepareInstall(intent->{if(isFinishing()||isDestroyed()||!visible)return;try{startActivity(intent);}catch(ActivityNotFoundException unavailable){toast("Android’s package installer could not open. Try again from Updates.");}}));
    }
    private void updateAction(boolean accepted){if(!accepted)toast(UpdateManager.message("UPDATE_BUSY"));renderUpdates();}
    private void installPermission(){
        toast("Allow Autopilot to install updates, then return to Updates. Android may still ask you to confirm installation.");
        try{startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));}catch(ActivityNotFoundException unavailable){toast("Open Android app settings and allow installs from Autopilot.");}
    }
    private AlertDialog settingsDialog(){
        JSONObject saved=app.settings();LinearLayout body=column();body.setPadding(dp(20),dp(8),dp(20),dp(12));
        addText(body,"Both platforms use these selection rules. Changes save together and apply immediately.",13,MUTED);
        Map<String,CheckBox> services=new LinkedHashMap<>();Set<String> required=ProductSettings.strings(saved.optJSONArray("required"));
        for(String service:ProductContract.services()){CheckBox checked=new CheckBox(this);checked.setText(service);checked.setChecked(required.contains(service));body.addView(checked);services.put(service,checked);}
        addText(body,"Select at least one required service. A challenge page is usable but scores below unrestricted access.",12,MUTED);
        Map<String,EditText> fields=new LinkedHashMap<>();
        String[][] catalog={{"tickMs","Current-node check (seconds)","1000"},{"sweepMs","Node sweep interval (seconds)","1000"},{"verifyMs","Service-result lifetime (seconds)","1000"},{"maxLatencyMs","Maximum latency (milliseconds)","1"},{"failLimit","Consecutive failures before failover","1"},{"switchRatio","Replacement score as % of current (lower = larger improvement)","0.01"},{"minDwellMs","Minimum time before healthy upgrade (seconds)","1000"},{"checkTopK","Promising nodes to verify per sweep","1"}};
        for(String[] item:catalog){addText(body,item[1],13,INK);EditText field=new EditText(this);field.setSingleLine(true);field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);double factor=Double.parseDouble(item[2]),value=saved.optDouble(item[0])/factor;field.setText(value==Math.rint(value)?Long.toString((long)value):Double.toString(value));body.addView(field,full());fields.put(item[0],field);}
        ScrollView scroll=new ScrollView(this);scroll.addView(body);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Automatic selection").setView(scroll).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            JSONObject patch=new JSONObject();JSONArray chosen=new JSONArray();for(Map.Entry<String,CheckBox> service:services.entrySet())if(service.getValue().isChecked())chosen.put(service.getKey());patch.put("required",chosen);
            for(String[] item:catalog){double value=Double.parseDouble(fields.get(item[0]).getText().toString())*Double.parseDouble(item[2]);if(!Double.isFinite(value))throw new IllegalArgumentException("Enter valid numbers");if(item[0].equals("switchRatio"))patch.put(item[0],value);else{if(value!=Math.rint(value))throw new IllegalArgumentException("Use whole values for "+item[1]);patch.put(item[0],(long)value);}}
            app.setSettings(patch);dialog.dismiss();toast("Selection settings saved");
        }catch(Exception invalid){toast(invalid instanceof NumberFormatException?"Enter valid numbers":invalid.getMessage());}}));dialog.show();return dialog;
    }
    private AlertDialog splitDialog(){
        JSONObject saved=app.settings();LinearLayout body=column();body.setPadding(dp(20),dp(8),dp(20),dp(12));
        addText(body,"Website overrides apply first inside the VPN. Android routes excluded apps outside the VPN, so website overrides cannot capture them. App-list changes apply after you stop and start VPN; website changes apply now.",13,MUTED);
        Spinner mode=new Spinner(this);String[] modes={"off","bypass","only"};mode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{splitModeLabel("off"),splitModeLabel("bypass"),splitModeLabel("only")}));int selectedMode=Arrays.asList(modes).indexOf(saved.optString("splitMode"));mode.setSelection(Math.max(0,selectedMode));body.addView(mode,full());
        addText(body,getString(R.string.split_lists_hint),12,MUTED);
        LinkedHashMap<String,Set<String>> chosen=new LinkedHashMap<>();chosen.put("bypass",new LinkedHashSet<>(ProductSettings.strings(saved.optJSONArray("bypassApps"))));chosen.put("only",new LinkedHashSet<>(ProductSettings.strings(saved.optJSONArray("onlyApps"))));
        List<ResolveInfo> found=getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0);TreeMap<String,String> apps=new TreeMap<>();
        for(ResolveInfo info:found)if(!info.activityInfo.packageName.equals(getPackageName()))apps.put(info.loadLabel(getPackageManager())+"\n"+info.activityInfo.packageName,info.activityInfo.packageName);
        Set<String> listed=new HashSet<>(apps.values());for(Set<String> set:chosen.values())for(String pkg:set)if(!listed.contains(pkg)&&!pkg.equals(getPackageName())){apps.put(pkg+" (not listed)",pkg);listed.add(pkg);}
        Button copy=button("",false);body.addView(copy,full());
        TextView appHeading=addText(body,"",15,INK);appHeading.setTypeface(null,Typeface.BOLD);TextView appHint=addText(body,"",13,MUTED);
        TextView emptyHint=addText(body,getString(R.string.split_empty_list_hint),12,MUTED);LinearLayout appRows=column();body.addView(appRows);
        Runnable refreshChoices=()->{String current=modes[mode.getSelectedItemPosition()];if(current.equals("off")){appHint.setText(R.string.split_all_hint);copy.setVisibility(View.GONE);emptyHint.setVisibility(View.GONE);return;}String source=current.equals("bypass")?"only":"bypass";Set<String> sourceApps=chosen.get(source),destination=chosen.get(current);appHint.setText(current.equals("bypass")?R.string.split_bypass_hint:destination.isEmpty()?R.string.split_only_empty_hint:R.string.split_only_hint);copy.setText(current.equals("bypass")?R.string.split_copy_vpn_only:R.string.split_copy_excluded);copy.setVisibility(!sourceApps.isEmpty()&&!sourceApps.equals(destination)?View.VISIBLE:View.GONE);emptyHint.setVisibility(destination.isEmpty()?View.VISIBLE:View.GONE);};
        Runnable draw=()->{appRows.removeAllViews();String current=modes[mode.getSelectedItemPosition()];appRows.setVisibility(current.equals("off")?View.GONE:View.VISIBLE);appHeading.setText(current.equals("bypass")?R.string.split_bypass_heading:current.equals("only")?R.string.split_only_heading:R.string.split_all_heading);refreshChoices.run();if(!current.equals("off"))for(Map.Entry<String,String> item:apps.entrySet()){CheckBox check=new CheckBox(this);check.setText(item.getKey());check.setChecked(chosen.get(current).contains(item.getValue()));check.setOnCheckedChangeListener((b,on)->{if(on)chosen.get(current).add(item.getValue());else chosen.get(current).remove(item.getValue());refreshChoices.run();});appRows.addView(check);}};
        copy.setOnClickListener(v->{String destination=modes[mode.getSelectedItemPosition()];if(destination.equals("off"))return;String source=destination.equals("bypass")?"only":"bypass";LinkedHashSet<String> sourceApps=new LinkedHashSet<>(chosen.get(source));if(sourceApps.isEmpty()||sourceApps.equals(chosen.get(destination)))return;Runnable replace=()->{chosen.put(destination,new LinkedHashSet<>(sourceApps));draw.run();};if(chosen.get(destination).isEmpty())replace.run();else new AlertDialog.Builder(this).setTitle(R.string.split_replace_title).setMessage(getString(R.string.split_replace_message,splitModeLabel(source))).setPositiveButton(R.string.split_replace_action,(d,w)->replace.run()).setNegativeButton("Cancel",null).show();});
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> parent,View view,int pos,long id){draw.run();}});draw.run();
        addText(body,"Always direct websites (one domain per line)",13,INK);EditText direct=domainField(saved.optJSONArray("directDomains"));body.addView(direct,full());
        addText(body,"Always through VPN websites (one domain per line)",13,INK);EditText proxy=domainField(saved.optJSONArray("proxyDomains"));body.addView(proxy,full());
        ScrollView scroll=new ScrollView(this);scroll.addView(body);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Split tunneling").setView(scroll).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{app.setSettings(new JSONObject().put("splitMode",modes[mode.getSelectedItemPosition()]).put("bypassApps",new JSONArray(chosen.get("bypass"))).put("onlyApps",new JSONArray(chosen.get("only"))).put("directDomains",domainLines(direct.getText().toString())).put("proxyDomains",domainLines(proxy.getText().toString())));dialog.dismiss();toast(app.routingRestartPending()?"Saved. App routing applies after you stop and start VPN.":"Routing settings saved");}catch(Exception invalid){toast(invalid.getMessage());}}));dialog.show();return dialog;
    }
    private String splitModeLabel(String mode){return getString(mode.equals("bypass")?R.string.split_mode_bypass:mode.equals("only")?R.string.split_mode_only:R.string.split_mode_all);}
    private EditText domainField(JSONArray values){EditText field=new EditText(this);field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);field.setMinLines(2);field.setText(String.join("\n",ProductSettings.strings(values)));field.setHint("example.com");return field;}
    private static JSONArray domainLines(String input){JSONArray result=new JSONArray();for(String line:input.split("[\\r\\n]+"))if(!line.trim().isEmpty())result.put(line.trim());return result;}
    private void diagnostics(){reportDialog("Diagnostics",app.diagnostics());}
    private void reportDialog(String title,String report){if(reportDialog!=null)reportDialog.dismiss();TextView text=text(report,12,INK);text.setTextIsSelectable(true);text.setPadding(dp(18),dp(8),dp(18),dp(8));ScrollView scroll=new ScrollView(this);scroll.addView(text);reportDialog=new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Copy",(d,w)->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Autopilot "+title,report));toast("Copied "+title.toLowerCase(Locale.ROOT));}).setNeutralButton("Share",(d,w)->{Intent share=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,"Autopilot "+title).putExtra(Intent.EXTRA_TEXT,report);startActivity(Intent.createChooser(share,"Share "+title.toLowerCase(Locale.ROOT)));}).setNegativeButton("Close",null).show();}
    private void about(){String license="Autopilot Android "+BuildConfig.VERSION_NAME+"\n\nMihomo v1.19.32 · libmihomo-android v0.3.11\nGPL-3.0. Android source and build instructions are included with this APK release.\n\nSnakeYAML: Apache-2.0. Kotlin: Apache-2.0.\n\n";for(String file:List.of("COPYING","APACHE-2.0.txt")){try(InputStream in=getAssets().open(file)){license+=readText(in,100000)+"\n\n";}catch(IOException ignored){}}TextView text=text(license,12,INK);text.setPadding(dp(18),0,dp(18),0);ScrollView scroll=new ScrollView(this);scroll.addView(text);new AlertDialog.Builder(this).setTitle("About & licenses").setView(scroll).setPositiveButton("Close",null).show();}
    private static String readText(InputStream input,int limit)throws IOException{
        if(input==null)throw new IOException("Could not open configuration");
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
        while((count=input.read(buffer))!=-1){if(out.size()+count>limit)throw new IOException("Configuration too large");out.write(buffer,0,count);}
        return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
    private static String countryName(String code){return new Locale("",code).getDisplayCountry(Locale.ENGLISH)+" ("+code+")";}
    private LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private LinearLayout row(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.HORIZONTAL);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    private TextView text(String label,int size,int color){TextView text=new TextView(this);text.setText(label);text.setTextSize(size);text.setTextColor(color);text.setLineSpacing(dp(3),1);return text;}
    private TextView addText(LinearLayout target,String label,int size,int color){TextView text=text(label,size,color);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(8);target.addView(text,params);return text;}
    private void section(LinearLayout card,String label){TextView text=addText(card,label,18,INK);text.setTypeface(null,Typeface.BOLD);}
    private LinearLayout card(){LinearLayout card=column();card.setPadding(dp(18),dp(18),dp(18),dp(14));card.setBackground(background(Color.WHITE,18));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(16);card.setLayoutParams(params);return card;}
    private Button button(String text,boolean primary){
        Button button=new Button(this);button.setText(text);button.setAllCaps(false);button.setTextSize(14);button.setMinHeight(dp(46));
        int[][] states={new int[]{-android.R.attr.state_enabled},new int[]{}};
        button.setBackgroundTintList(new android.content.res.ColorStateList(states,new int[]{DISABLED_BUTTON_BACKGROUND,primary?BLUE:SECONDARY_BUTTON_BACKGROUND}));
        button.setTextColor(new android.content.res.ColorStateList(states,new int[]{DISABLED_BUTTON_TEXT,primary?Color.WHITE:BLUE}));
        return button;
    }
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout.LayoutParams full(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);return p;}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
}
