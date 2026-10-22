package app.autopilot.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import javax.net.ssl.HttpsURLConnection;

/** Independent HTTPS checks through a node's loopback mixed listener. Never changes the active selector. */
public final class ServiceChecks implements AutoCloseable {
    private static final Pattern COUNTRY=Pattern.compile(",2,1,200,\"([A-Z]{3})\"");
    private static final Pattern CHALLENGE=Pattern.compile("just a moment|cf-chl|challenge-platform",Pattern.CASE_INSENSITIVE);
    private static final Set<String> BLOCKED=Set.of("CHN","HKG","MAC","RUS","BLR","CUB","IRN","PRK","SYR");
    private final Set<HttpURLConnection> active=ConcurrentHashMap.newKeySet();
    private final ExecutorService pool=Executors.newFixedThreadPool(3);
    private volatile boolean closed;
    public static final class Result {
        public final String claude,chatgpt,gemini;
        Result(String a,String b,String c){claude=a;chatgpt=b;gemini=c;}
        public boolean usable(){return SelectionPolicy.usable(claude)&&SelectionPolicy.usable(chatgpt)&&SelectionPolicy.usable(gemini);}
    }
    public Result check(int port) throws InterruptedException {return check(port,List.of("claude","chatgpt","gemini"));}
    public Result check(int port,List<String> required) throws InterruptedException {
        if(closed || Thread.currentThread().isInterrupted())throw new InterruptedException();
        List<String> services=List.of("claude","chatgpt","gemini");
        List<Callable<String>> tasks=new ArrayList<>();for(String service:services)tasks.add(()->required.contains(service)?fetch(port,"claude".equals(service)?"https://claude.ai/":"chatgpt".equals(service)?"https://api.openai.com/compliance/cookie_requirements":"https://gemini.google.com/",service):"not-required");
        try {
            List<Future<String>> futures=pool.invokeAll(tasks,14,TimeUnit.SECONDS);
            String[] values={"error","error","error"};
            for(int i=0;i<futures.size();i++)try{values[i]=futures.get(i).get();}catch(ExecutionException|CancellationException ignored){}
            return new Result(values[0],values[1],values[2]);
        }finally{cancelActive();}
    }
    private String fetch(int port,String address,String service) {
        HttpURLConnection connection=null;
        try {
            URL target=new URL(address);
            for(int redirect=0;redirect<5;redirect++){
                if(closed||Thread.currentThread().isInterrupted())return "error";
                connection=(HttpURLConnection)target.openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",port)));
                active.add(connection); connection.setConnectTimeout(5000);connection.setReadTimeout(7000);connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36");
                connection.setRequestProperty("Accept","text/html,application/json;q=0.9,*/*;q=0.8");connection.setRequestProperty("Accept-Language","en-US,en;q=0.9");
                int status=connection.getResponseCode();String location=connection.getHeaderField("Location");
                if(!"claude".equals(service) && status>=300&&status<400 && location!=null){
                    URL next=new URL(target,location);if(!"https".equals(next.getProtocol()))return "error";
                    connection.disconnect();active.remove(connection);connection=null;target=next;continue;
                }
                if("claude".equals(service) && status!=403)return claude(status,location,"");
                InputStream stream=status>=400?connection.getErrorStream():connection.getInputStream();
                String body=read(stream,"gemini".equals(service)?1572864:262144,"gemini".equals(service));
                return switch(service){case "claude"->claude(status,location,body);case "chatgpt"->chatgpt(status,body);default->gemini(body);};
            }
        }catch(IOException|RuntimeException ignored){}finally{if(connection!=null){active.remove(connection);connection.disconnect();}}
        return "error";
    }
    private String read(InputStream stream,int cap,boolean scan) throws IOException {
        if(stream==null)return "";
        try(InputStream in=stream;ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] chunk=new byte[8192];int count;
            while(bytes.size()<cap&&(count=in.read(chunk,0,Math.min(chunk.length,cap-bytes.size())))!=-1){
                if(closed||Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                bytes.write(chunk,0,count);
                if(scan){String text=bytes.toString(StandardCharsets.UTF_8.name());if(COUNTRY.matcher(text).find())return text;}
            }
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }
    public static String claude(int status,String location,String body) {
        String loc=location==null?"":location;
        if(status>=300&&status<400){if(loc.contains("app-unavailable-in-region"))return "blocked";return loc.contains("/login")?"ok":"unknown";}
        if(status==200)return "ok";
        return status==403&&CHALLENGE.matcher(body==null?"":body).find()?"challenged":"unknown";
    }
    public static String chatgpt(int status,String body) {
        String text=body==null?"":body;
        if(status==200&&text.contains("cookie_consent_required"))return "ok";
        if(status==403)return text.contains("unsupported_country")?"blocked":"challenged";
        return "unknown";
    }
    public static String gemini(String body) {Matcher m=COUNTRY.matcher(body==null?"":body);return !m.find()?"unknown":BLOCKED.contains(m.group(1))?"blocked":"ok";}
    public void cancelActive(){for(HttpURLConnection c:active)c.disconnect();}
    @Override public void close(){closed=true;cancelActive();pool.shutdownNow();}
}
