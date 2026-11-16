package app.autopilot.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;

/** Fixed strict-TLS identity lookup, explicitly through the selected node listener. */
final class NetworkExitProbe implements AndroidNetworkTelemetry.ExitProbe {
    static final String URL="https://www.cloudflare.com/cdn-cgi/trace";
    interface Opener {HttpsURLConnection open(java.net.URL url,Proxy proxy)throws IOException;}
    private final Opener opener;private HttpsURLConnection active;private boolean closed;private long revision;
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();
    NetworkExitProbe(){this((url,proxy)->(HttpsURLConnection)url.openConnection(proxy));}
    NetworkExitProbe(Opener opener){this.opener=opener;}
    @Override public NetworkTelemetryState.Exit check(int port)throws Exception{
        final long ticket;synchronized(this){if(closed||Thread.currentThread().isInterrupted()||port<1||port>65535)throw new InterruptedException();ticket=revision;}
        HttpsURLConnection connection=opener.open(new java.net.URL(URL),new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",port)));
        ScheduledFuture<?> deadline=null;
        try{
            synchronized(this){if(closed||revision!=ticket||Thread.currentThread().isInterrupted())throw new InterruptedException();active=connection;deadline=deadlines.schedule(connection::disconnect,8,TimeUnit.SECONDS);}
            connection.setConnectTimeout(5000);connection.setReadTimeout(5000);connection.setInstanceFollowRedirects(false);connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent","Autopilot-Exit-Check");connection.setRequestProperty("Accept","text/plain");
            if(connection.getResponseCode()!=200)throw new IOException("EXIT_UNAVAILABLE");
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] bytes=new byte[1024];int read;while((read=input.read(bytes))!=-1){synchronized(this){if(closed||revision!=ticket||Thread.currentThread().isInterrupted())throw new InterruptedException();}if(output.size()+read>8192)throw new IOException("EXIT_RESPONSE_SIZE");output.write(bytes,0,read);}
                synchronized(this){if(closed||revision!=ticket||Thread.currentThread().isInterrupted())throw new InterruptedException();}
                return NetworkTelemetryState.parseExit(output.toString(StandardCharsets.UTF_8.name()));
            }
        }finally{if(deadline!=null)deadline.cancel(false);connection.disconnect();synchronized(this){if(active==connection)active=null;}}
    }
    @Override public void cancel(){HttpsURLConnection connection;synchronized(this){revision++;connection=active;}if(connection!=null)connection.disconnect();}
    @Override public void close(){synchronized(this){closed=true;}cancel();deadlines.shutdownNow();}
}
