package app.autopilot.probe;

import android.app.*;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Isolated development APK: different UID from Autopilot, so this traverses the actual TUN. */
public final class TunnelProbe extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments){this.arguments=arguments;start();}
    @Override public void onStart(){
        Bundle result=new Bundle();HttpURLConnection c=null;Activity foreground=null;NetworkReady network=null;
        result.putInt("uid",android.os.Process.myUid());int resultCode=Activity.RESULT_CANCELED;
        try{
            if(Build.VERSION.SDK_INT<29)throw new IOException("Hosted network-policy probe requires Android 10 or later");
            foreground=startActivitySync(new Intent(getTargetContext(),ProbeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            network=new NetworkReady(getTargetContext().getSystemService(ConnectivityManager.class));
            network.awaitUnblockedVpn();
            result.putString("network",network.state);
            ActivityManager.RunningAppProcessInfo process=new ActivityManager.RunningAppProcessInfo();ActivityManager.getMyMemoryState(process);
            result.putInt("importance",process.importance);
            String target=arguments.getString("url","http://203.0.113.7/autopilot-probe");
            String expected=arguments.getString("marker","AUTOPILOT_TUNNEL_OK");
            URL url=new URL(target);
            StringBuilder resolved=new StringBuilder();for(InetAddress address:InetAddress.getAllByName(url.getHost())){
                if(resolved.length()>0)resolved.append(',');resolved.append(address.getHostAddress());}
            result.putString("resolved",resolved.toString());
            c=(HttpURLConnection)url.openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(10000);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);
            if(c.getResponseCode()!=200)throw new IOException("Unexpected HTTP status");
            ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){
                byte[] buffer=new byte[1024];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>8192)throw new IOException("Oversized probe reply");out.write(buffer,0,n);}}
            String text=new String(out.toByteArray(),StandardCharsets.UTF_8);
            if(!text.contains(expected))throw new IOException("Tunnel marker mismatch");
            result.putString("result","PASS");result.putString("target",target);resultCode=Activity.RESULT_OK;
        }catch(Exception ex){result.putString("result","FAIL");result.putString("error",ex.getClass().getSimpleName()+": "+ex.getMessage());}
        finally{
            if(c!=null)c.disconnect();
            if(network!=null){result.putString("network",network.state);network.close();}
            result.putInt("restrictBackground",getTargetContext().getSystemService(ConnectivityManager.class).getRestrictBackgroundStatus());
            if(foreground!=null){Activity activity=foreground;runOnMainSync(activity::finish);}
        }
        finish(resultCode,result);
    }

    /** No delay guess: wait for Android to report this foreground UID's VPN as unblocked. */
    private static final class NetworkReady extends ConnectivityManager.NetworkCallback {
        private final ConnectivityManager manager;
        private final CountDownLatch ready=new CountDownLatch(1);
        private Network active;
        private boolean vpn,unblocked;
        volatile String state="callback-not-started";
        NetworkReady(ConnectivityManager manager){this.manager=manager;manager.registerDefaultNetworkCallback(this);}
        @Override public synchronized void onAvailable(Network network){active=network;vpn=false;unblocked=false;record();}
        @Override public synchronized void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){
            if(!network.equals(active))return;vpn=capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);record();
        }
        @Override public synchronized void onBlockedStatusChanged(Network network,boolean blocked){
            if(!network.equals(active))return;unblocked=!blocked;record();
        }
        @Override public synchronized void onLost(Network network){if(network.equals(active)){active=null;vpn=false;unblocked=false;record();}}
        private void record(){
            state="handle="+(active==null?"none":Long.toUnsignedString(active.getNetworkHandle()))+",vpn="+vpn+",blocked="+!unblocked;
            if(active!=null&&vpn&&unblocked)ready.countDown();
        }
        void awaitUnblockedVpn()throws Exception{if(!ready.await(20,TimeUnit.SECONDS))throw new IOException("Foreground UID VPN readiness timed out: "+state);}
        void close(){manager.unregisterNetworkCallback(this);}
    }
}
