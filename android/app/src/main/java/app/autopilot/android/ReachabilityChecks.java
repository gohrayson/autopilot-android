package app.autopilot.android;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.HttpsURLConnection;

/** Small, independent HTTPS requests. Platform certificate/hostname verification is never changed. */
public final class ReachabilityChecks implements ConnectionWatchdog.Probe,AutoCloseable {
    static final List<String> ORIGINS=origins();
    private static List<String> origins(){org.json.JSONArray sources=ProductContract.policy().optJSONArray("watchdogOrigins");ArrayList<String> values=new ArrayList<>();for(int i=0;i<sources.length();i++)values.add(sources.optJSONObject(i).optString("url"));return List.copyOf(values);}
    interface Opener { HttpsURLConnection open(URL target,Proxy proxy)throws IOException; }
    private final ExecutorService requests=Executors.newFixedThreadPool(3);
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();
    private final Set<Round> rounds=ConcurrentHashMap.newKeySet();
    private final long timeoutMillis;
    private final Opener opener;
    private volatile boolean closed;
    private static final class Round {
        final AtomicBoolean cancelled=new AtomicBoolean();
        final Set<HttpsURLConnection> active=ConcurrentHashMap.newKeySet();
        void cancel(){cancelled.set(true);for(HttpsURLConnection connection:active)connection.disconnect();}
    }
    public ReachabilityChecks(){this(ProductContract.policyLong("watchdogRoundMs"),(url,proxy)->(HttpsURLConnection)url.openConnection(proxy));}
    ReachabilityChecks(long timeoutMillis,Opener opener){this.timeoutMillis=timeoutMillis;this.opener=opener;}
    @Override public ConnectionWatchdog.Result check(int port)throws InterruptedException {
        if(closed||Thread.currentThread().isInterrupted())throw new InterruptedException();
        if(port<1||port>65535)return new ConnectionWatchdog.Result(7);
        Round round=new Round();rounds.add(round);
        ScheduledFuture<?> deadline=deadlines.schedule(round::cancel,timeoutMillis,TimeUnit.MILLISECONDS);
        ArrayList<Future<Boolean>> work=new ArrayList<>();
        int[] timings=new int[3];
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try{
            for(int i=0;i<ORIGINS.size();i++){final int index=i;work.add(requests.submit(()->{long start=System.nanoTime();boolean reachable=fetch(round,port,ORIGINS.get(index));if(reachable)timings[index]=(int)Math.max(1,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));return reachable;}));}
            int failed=0;
            for(int i=0;i<work.size();i++)try{
                long remaining=until-System.nanoTime();
                Future<Boolean> future=work.get(i);
                boolean reachable=future.isDone()?future.get():remaining>0&&future.get(remaining,TimeUnit.NANOSECONDS);
                if(!reachable)failed|=1<<i;
            }catch(ExecutionException|TimeoutException|CancellationException failure){failed|=1<<i;}
            if(closed||Thread.currentThread().isInterrupted())throw new InterruptedException();
            ArrayList<Integer> successful=new ArrayList<>();for(int i=0;i<timings.length;i++)if((failed&(1<<i))==0&&timings[i]>0)successful.add(timings[i]);Collections.sort(successful);int latency=successful.isEmpty()?0:(int)Math.round((successful.get((successful.size()-1)/2)+successful.get(successful.size()/2))/2.0);
            return new ConnectionWatchdog.Result(failed,latency);
        }finally{
            round.cancel();deadline.cancel(false);for(Future<?> future:work)future.cancel(true);rounds.remove(round);
        }
    }
    private boolean fetch(Round round,int port,String address){
        HttpsURLConnection connection=null;
        try{
            if(round.cancelled.get()||closed)return false;
            URL target=new URL(address);
            connection=opener.open(target,new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",port)));
            round.active.add(connection);
            if(round.cancelled.get()||closed)return false;
            connection.setConnectTimeout((int)Math.min(timeoutMillis,5000));connection.setReadTimeout((int)Math.min(timeoutMillis,5000));
            connection.setInstanceFollowRedirects(false);connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent","Autopilot-Connection-Check");
            connection.setRequestProperty("Accept","application/json,text/plain,*/*");
            int status=connection.getResponseCode();
            // A strict-TLS HTTP response, including 401/403, proves transport reachability.
            // Service/country eligibility remains the separate ServiceChecks decision.
            if(status<200||status>=500||round.cancelled.get())return false;
            InputStream stream=status>=400?connection.getErrorStream():connection.getInputStream();
            if(stream!=null)try(InputStream input=stream){byte[] buffer=new byte[1024];input.read(buffer);}
            return !round.cancelled.get()&&!closed;
        }catch(IOException|RuntimeException failure){return false;}
        finally{if(connection!=null){round.active.remove(connection);connection.disconnect();}}
    }
    @Override public void cancel(){for(Round round:rounds)round.cancel();}
    @Override public void close(){closed=true;cancel();requests.shutdownNow();deadlines.shutdownNow();}
}
