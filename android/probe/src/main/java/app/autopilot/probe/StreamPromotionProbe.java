package app.autopilot.probe;

import android.app.*;
import android.content.Intent;
import android.net.*;
import android.os.Bundle;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Disposable-emulator probe: one actual other-UID socket across a selector promotion. */
public final class StreamPromotionProbe extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle value){arguments=value;start();}
    @Override public void onStart(){
        Bundle result=new Bundle();Activity foreground=null;Socket stream=null;VpnReady network=null;int code=Activity.RESULT_CANCELED;
        result.putInt("uid",android.os.Process.myUid());
        try{
            String marker=arguments.getString("marker","");
            if(!marker.matches("AUTOPILOT_STREAM_[a-f0-9-]{36}"))throw new IOException("Invalid synthetic marker");
            foreground=startActivitySync(new Intent(getTargetContext(),ProbeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            network=new VpnReady(getTargetContext().getSystemService(ConnectivityManager.class));network.await();
            stream=new Socket();stream.connect(new InetSocketAddress("203.0.113.7",80),10000);stream.setSoTimeout(75000);
            BufferedReader input=new BufferedReader(new InputStreamReader(stream.getInputStream(),StandardCharsets.US_ASCII));
            BufferedWriter output=new BufferedWriter(new OutputStreamWriter(stream.getOutputStream(),StandardCharsets.US_ASCII));
            output.write("STREAM "+marker+"\n");output.flush();
            int received=0,promoted=0,rejected=0,recovered=0;boolean newExit=false,newConnectionRejected=false,recoveryExit=false,completed=false;String line;
            while(true){
                try{line=input.readLine();}catch(SocketException closed){line=null;}
                if(line==null)throw new IOException("Established stream closed before automatic recovery proof completed");
                String[] words=line.split(" ");
                if(words.length!=4||!marker.equals(words[0])||!"first".equals(words[1])||Integer.parseInt(words[3])!=++received)throw new IOException("Old stream identity/sequence changed");
                if("PROMOTED".equals(words[2])){
                    promoted++;
                    if(!newExit){assertFreshOutbound(marker,"second");newExit=true;}
                }else if("REJECTED".equals(words[2])){
                    if(promoted<3||!newExit)throw new IOException("REJECT preceded initial promotion proof");
                    if(!newConnectionRejected){assertNewConnectionRejected(marker);newConnectionRejected=true;}
                    rejected++;
                }else if("RECOVERED".equals(words[2])){
                    if(rejected<3||!newConnectionRejected)throw new IOException("Recovery preceded automatic REJECT continuity proof");
                    if(!recoveryExit){assertFreshOutbound(marker,"third");recoveryExit=true;}
                    recovered++;
                }else if("COMPLETE".equals(words[2])){
                    if(recovered<3||!recoveryExit)throw new IOException("Observer completed before fresh recovery outbound proof");completed=true;
                }else if(!"INITIAL".equals(words[2]))throw new IOException("Unknown stream stage");
                output.write("ACK "+received+" "+words[2]+"\n");output.flush();
                if(completed)break;
            }
            if(promoted<3||!newExit||rejected<3||!newConnectionRejected||recovered<3||!recoveryExit||!completed)throw new IOException("Automatic selector continuity proof incomplete");
            result.putInt("messages",received);result.putInt("messagesAfterPromotion",promoted);
            result.putInt("messagesAfterReject",rejected);result.putInt("messagesAfterRecovery",recovered);
            result.putString("result","PASS");result.putBoolean("sameSocket",true);result.putBoolean("newConnectionUsesPromotedOutbound",true);
            result.putBoolean("automaticRejectPreservedStream",true);result.putBoolean("newConnectionsRejected",true);
            result.putBoolean("automaticRecoveryPreservedStream",true);result.putBoolean("newConnectionUsesRecoveryOutbound",true);result.putBoolean("observerFinishedIntentionally",true);code=Activity.RESULT_OK;
        }catch(Exception failure){result.putString("result","FAIL");result.putString("error",failure.getClass().getSimpleName()+": "+failure.getMessage());}
        finally{
            if(stream!=null)try{stream.close();}catch(IOException ignored){}
            if(network!=null){result.putString("network",network.state);network.close();}
            if(foreground!=null){Activity activity=foreground;runOnMainSync(activity::finish);}
        }
        finish(code,result);
    }
    private static void assertFreshOutbound(String marker,String expected)throws IOException{
        try(Socket fresh=new Socket()){
            fresh.connect(new InetSocketAddress("203.0.113.8",80),3000);fresh.setSoTimeout(3000);
            BufferedWriter request=new BufferedWriter(new OutputStreamWriter(fresh.getOutputStream(),StandardCharsets.US_ASCII));
            request.write("FRESH "+marker+"\n");request.flush();
            String reply=new BufferedReader(new InputStreamReader(fresh.getInputStream(),StandardCharsets.US_ASCII)).readLine();
            if(!("NEW "+expected+" "+marker).equals(reply))throw new IOException("New connection used the wrong automatic outbound");
        }
    }
    private static void assertNewConnectionRejected(String marker)throws IOException{
        String reply=null;
        try(Socket fresh=new Socket()){
            fresh.connect(new InetSocketAddress("203.0.113.8",80),2000);fresh.setSoTimeout(2000);
            BufferedWriter request=new BufferedWriter(new OutputStreamWriter(fresh.getOutputStream(),StandardCharsets.US_ASCII));
            request.write("FRESH "+marker+"\n");request.flush();
            reply=new BufferedReader(new InputStreamReader(fresh.getInputStream(),StandardCharsets.US_ASCII)).readLine();
        }catch(IOException blocked){return;}
        if(reply!=null)throw new IOException("Automatic REJECT allowed a new application connection");
    }
    private static final class VpnReady extends ConnectivityManager.NetworkCallback {
        final ConnectivityManager manager;final CountDownLatch ready=new CountDownLatch(1);Network active;boolean vpn,unblocked;volatile String state="waiting";
        VpnReady(ConnectivityManager manager){this.manager=manager;manager.registerDefaultNetworkCallback(this);}
        @Override public synchronized void onAvailable(Network network){active=network;vpn=false;unblocked=false;record();}
        @Override public synchronized void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){if(network.equals(active)){vpn=capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);record();}}
        @Override public synchronized void onBlockedStatusChanged(Network network,boolean blocked){if(network.equals(active)){unblocked=!blocked;record();}}
        private void record(){state="handle="+(active==null?"none":Long.toUnsignedString(active.getNetworkHandle()))+",vpn="+vpn+",blocked="+!unblocked;if(active!=null&&vpn&&unblocked)ready.countDown();}
        void await()throws Exception{if(!ready.await(20,TimeUnit.SECONDS))throw new IOException("Other-UID VPN unavailable: "+state);}
        void close(){manager.unregisterNetworkCallback(this);}
    }
}
