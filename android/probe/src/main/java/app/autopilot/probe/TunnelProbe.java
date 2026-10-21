package app.autopilot.probe;

import android.app.*;
import android.os.Bundle;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Isolated development APK: different UID from Autopilot, so this traverses the actual TUN. */
public final class TunnelProbe extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments){this.arguments=arguments;start();}
    @Override public void onStart(){
        Bundle result=new Bundle();HttpURLConnection c=null;
        try{
            String target=arguments.getString("url","http://203.0.113.7/autopilot-probe");
            String expected=arguments.getString("marker","AUTOPILOT_TUNNEL_OK");
            c=(HttpURLConnection)new URL(target).openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(10000);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);
            if(c.getResponseCode()!=200)throw new IOException("Unexpected HTTP status");
            ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){
                byte[] buffer=new byte[1024];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>8192)throw new IOException("Oversized probe reply");out.write(buffer,0,n);}}
            String text=new String(out.toByteArray(),StandardCharsets.UTF_8);
            if(!text.contains(expected))throw new IOException("Tunnel marker mismatch");
            result.putString("result","PASS");result.putInt("uid",android.os.Process.myUid());result.putString("target",target);
            finish(Activity.RESULT_OK,result);
        }catch(Exception ex){result.putString("result","FAIL");result.putString("error",ex.getClass().getSimpleName());finish(Activity.RESULT_CANCELED,result);}
        finally{if(c!=null)c.disconnect();}
    }
}
