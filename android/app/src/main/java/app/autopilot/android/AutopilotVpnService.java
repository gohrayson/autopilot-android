package app.autopilot.android;

import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.os.*;
import java.io.IOException;
import java.util.*;

/** Android owns the routes and permission; the embedded core owns only the detached TUN fd. */
public final class AutopilotVpnService extends VpnService {
    public static final String START="app.autopilot.android.START",STOP="app.autopilot.android.STOP";
    private static final String CHANNEL="autopilot-vpn";
    private static final int NOTIFICATION=7;
    static final String ADDRESSES="172.19.0.1/30,fdfe:dcba:9876::1/126",DNS="172.19.0.2";
    static final int MTU=1400;
    private final Handler main=new Handler(Looper.getMainLooper());
    private AutopilotApp app;
    private VpnEngine owned;
    /** Keeps Android's VPN interface/routes alive while the native consumer is replaced. */
    private ParcelFileDescriptor routeGuard;
    @Override public void onCreate(){super.onCreate();app=(AutopilotApp)getApplication();
        NotificationChannel channel=new NotificationChannel(CHANNEL,"Autopilot VPN",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Connection status and disconnect control");channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?"":intent.getAction();
        if(STOP.equals(action)){app.setVpnWanted(false);if(owned!=null)owned.stop();else{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}return START_NOT_STICKY;}
        boolean explicitStart=START.equals(action)||VpnService.SERVICE_INTERFACE.equals(action);
        boolean recovering=intent==null&&app.vpnWanted();
        if((!explicitStart&&!recovering)||VpnService.prepare(this)!=null){stopSelf();return START_NOT_STICKY;}
        app.setVpnWanted(true);
        Notification n=notification("Starting VPN…");
        if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(NOTIFICATION,n);
        if(owned==null){owned=new VpnEngine(this,app);app.engine=owned;owned.start();}
        return START_STICKY;
    }
    synchronized ParcelFileDescriptor establishTunnel() throws PackageManager.NameNotFoundException,IOException {
        if(routeGuard!=null)return duplicateTunnel();
        Builder builder=new Builder().setSession("Autopilot").setMtu(MTU)
            .addAddress("172.19.0.1",30).addAddress("fdfe:dcba:9876::1",126)
            .addRoute("0.0.0.0",0).addRoute("::",0).addDnsServer(DNS).setBlocking(false);
        if(Build.VERSION.SDK_INT>=29)builder.setMetered(false);
        org.json.JSONObject settings=app.settings();
        AndroidRouting.AppPlan plan=AndroidRouting.appPlan(settings,getPackageName());
        int installed=0;
        for(String pkg:plan.packages){
            try{if(plan.includeOnly)builder.addAllowedApplication(pkg);else builder.addDisallowedApplication(pkg);installed++;}
            catch(PackageManager.NameNotFoundException ignored){app.log("vpn","SPLIT_APP_REMOVED");}
        }
        // No Android Builder mixes allow/disallow. Include-only omits our own UID;
        // all other modes explicitly exclude it, keeping core/provider sockets outside TUN.
        if(plan.includeOnly&&installed==0)throw new IOException("SPLIT_APPS_UNAVAILABLE");
        ParcelFileDescriptor descriptor=builder.establish();
        if(descriptor==null)throw new IOException("VPN_PERMISSION_REQUIRED");
        routeGuard=descriptor;
        app.routingApplied(settings);
        return duplicateTunnel();
    }
    synchronized ParcelFileDescriptor duplicateTunnel() throws IOException {
        if(routeGuard==null)throw new IOException("VPN_INTERFACE_UNAVAILABLE");
        return ParcelFileDescriptor.dup(routeGuard.getFileDescriptor());
    }
    synchronized boolean hasTunnel(){return routeGuard!=null;}
    synchronized void releaseTunnel(){
        if(routeGuard==null)return;
        try{routeGuard.close();}catch(IOException ignored){app.log("vpn","ROUTE_GUARD_CLOSE_FAILED");}
        finally{routeGuard=null;app.routingReleased();}
    }
    void updateNotification(String detail){main.post(()->{if(owned!=null)getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(detail));});}
    void finished(VpnEngine engine){main.post(()->{
        if(owned!=engine)return;
        owned=null;boolean current=app.engine==engine;if(current)app.engine=null;
        // Deliberate Stop, revocation, or startup failure ends this run. A process killed
        // while running retains wanted=true for Android's sticky/Always-on recovery.
        if(current)app.setVpnWanted(false);
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();app.changed();
    });}
    private Notification notification(String detail){
        Intent open=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,AutopilotVpnService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle("Autopilot VPN").setContentText(detail).setContentIntent(content).setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE).addAction(new Notification.Action.Builder(null,"Disconnect",stop).build()).build();
    }
    @Override public void onRevoke(){app.setVpnWanted(false);app.log("vpn","PERMISSION_REVOKED");if(owned!=null)owned.stop();else stopSelf();super.onRevoke();}
    @Override public void onDestroy(){if(owned!=null)owned.stop();super.onDestroy();}
}
