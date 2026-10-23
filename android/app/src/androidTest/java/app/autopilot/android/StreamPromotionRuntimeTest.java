package app.autopilot.android;

import android.content.*;
import android.net.VpnService;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.oviron.libmihomo.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Hosted emulator only: real discovery/selector/TUN and a continuous separate-UID stream. */
@RunWith(AndroidJUnit4.class)
public final class StreamPromotionRuntimeTest {
    private static final String FIRST="Autopilot Stream Japan 00 First",SECOND="Autopilot Stream Japan 01 Second";
    private static final String READY="stream-promotion-ready.json",COMPLETE="stream-promotion-complete";
    @Test(timeout=240000) public void expiredRankingPromotionPreservesOldSocketAndConfirmedFailureClosesIt()throws Exception {
        Assume.assumeTrue("Require explicit hosted public TLS opt-in","true".equals(InstrumentationRegistry.getArguments().getString("hostedAllowPublicTransport")));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();AutopilotApp app=(AutopilotApp)context.getApplicationContext();
        assertNull(app.engine);assertTrue(app.providers.nodes().isEmpty());assertNull(VpnService.prepare(context));
        assertEquals(ProductSettings.merge(null,new JSONObject()).toString(),app.settings().toString());
        for(String name:List.of(READY,READY+".tmp",COMPLETE,COMPLETE+".tmp","stream-promotion-evidence.json"))Files.deleteIfExists(new File(context.getFilesDir(),name).toPath());
        JSONObject evidence=new JSONObject();Fixture fixture=new Fixture();
        try{
            app.setSettings(new JSONObject().put("required",new JSONArray().put("chatgpt")));
            app.providers.importConfig(new JSONObject().put("proxies",new JSONArray().put(outbound(FIRST,"first",fixture.port())).put(outbound(SECOND,"second",fixture.port()))).toString());
            context.startForegroundService(new Intent(context,AutopilotVpnService.class).setAction(AutopilotVpnService.START));
            await(60000,()->FIRST.equals(app.state().optString("current"))&&app.state().optBoolean("tun"),"Initial production discovery selection");
            assertEquals(FIRST,nativeSelection());assertEquals(1,tunStarts(app));
            await(20000,()->app.state().optJSONObject("watchdog").optLong("lastSuccessAt")>0,"Initial real watchdog completion before clock expiry");
            String nonce=UUID.randomUUID().toString();write(context,READY,new JSONObject().put("uid",android.os.Process.myUid()).put("tun",true).put("marker",fixture.marker).put("nonce",nonce));
            await(35000,()->fixture.acknowledged.get()>=3,"Separate-UID established stream acknowledgements");
            VpnEngine engine=app.engine;assertNotNull(engine);
            // A deterministic clock-expiry fixture, confined to the actual controller lane.
            // Cancel only obsolete discovery completions, age the selected ledger, and leave
            // the real selector, TUN, established sockets and policy durations untouched.
            onWorker(engine,()->{
                invoke(engine,"preemptSweep");
                DiscoveryCoordinator<?> discovery=(DiscoveryCoordinator<?>)field(engine,"discovery");discovery.cancelled();
                Node selected=(Node)field(engine,"current");assertEquals(FIRST,selected.name);
                @SuppressWarnings("unchecked") Map<String,SelectionPolicy.Health> ledger=(Map<String,SelectionPolicy.Health>)field(engine,"health");
                SelectionPolicy.Health h=ledger.get(selected.id);assertNotNull(h);assertFalse(h.appRejected);
                SelectionPolicy.Health replacement=ledger.values().stream().filter(row->SECOND.equals(row.name)).findFirst().orElseThrow();
                assertFalse("Initial auth failure must not fabricate independent application proof",replacement.appRejected);
                assertEquals("Initial failed native/fallback checks must not bypass a real application cooldown",0,replacement.appFailureUntil);
                long expired=System.currentTimeMillis()-Math.max(SelectionPolicy.SERVICE_TTL,Math.max(SelectionPolicy.LATENCY_TTL,ProductContract.policyLong("transportRetentionMs")))-1000;
                h.latencyAt=h.transportAt=h.servicesAt=h.appVerifiedAt=expired;
                assertFalse(h.qualified(System.currentTimeMillis()));assertFalse(h.retainSelectedTransport(System.currentTimeMillis()));
                fixture.firstEnabled.set(false);fixture.secondEnabled.set(true);
                evidence.put("oldProofExpiredBeforeDiscovery",true);
            });
            int switches=events(app,"NODE_SWITCH"),upgrades=events(app,"HEALTHY_UPGRADE");long promotionStarted=System.nanoTime();
            engine.retest(); // Public production action: fresh native + HTTPS + service proof drives promotion.
            await(20000,()->SECOND.equals(app.state().optString("current")),"Fresh replacement promoted by actual discovery");
            long promotionMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-promotionStarted);
            assertEquals(SECOND,nativeSelection());assertEquals(1,tunStarts(app));
            assertEquals("Proof expiry must not become a destructive switch",switches,events(app,"NODE_SWITCH"));
            assertEquals(upgrades+1,events(app,"HEALTHY_UPGRADE"));
            onWorker(engine,()->{
                @SuppressWarnings("unchecked") Map<String,SelectionPolicy.Health> ledger=(Map<String,SelectionPolicy.Health>)field(engine,"health");
                SelectionPolicy.Health old=ledger.values().stream().filter(h->FIRST.equals(h.name)).findFirst().orElseThrow();
                assertFalse("The regression must exercise stale selected proof",old.qualified(System.currentTimeMillis()));
            });
            fixture.promoted.set(true);
            await(10000,()->fixture.afterPromotionAcks.get()>=3&&fixture.freshSecondConnections.get()>=1,"Same old socket continuity and new socket through replacement");
            assertFalse("Healthy promotion closed the established stream",fixture.streamClosed.get());
            evidence.put("automaticDiscoveryPromotion",true).put("promotionMs",promotionMillis).put("oldSocketContinuous",true)
                .put("oldSocketMessagesAfterPromotion",fixture.afterPromotionAcks.get()).put("newSocketUsesSecondOutbound",true);
            // Only future connection attempts fail. The already established first-node
            // stream still runs until the real two-round HTTPS watchdog invokes REJECT.
            fixture.secondEnabled.set(false);
            await(65000,()->events(app,"APPLICATION_PATH_FAILED")>0&&app.state().optString("current").isEmpty(),"Production watchdog confirmed broad failure");
            assertEquals("REJECT",nativeSelection());
            await(10000,fixture.streamClosed::get,"Confirmed recovery closes old native stream");
            evidence.put("confirmedWatchdogFailure",true).put("confirmedRecoveryClosedStream",true).put("nativeReject",true).put("tunStarts",tunStarts(app));
            File completed=new File(context.getFilesDir(),COMPLETE);
            await(15000,completed::isFile,"Separate-UID probe final receipt");
            JSONObject result=new JSONObject(new String(Files.readAllBytes(completed.toPath()),StandardCharsets.UTF_8));
            assertEquals(nonce,result.getString("nonce"));assertEquals("PASS",result.getString("result"));assertNotEquals(android.os.Process.myUid(),result.getInt("uid"));
            assertTrue(fixture.tlsHosts.containsAll(Fixture.HOSTS));assertTrue(fixture.tlsSent.get()>0&&fixture.tlsReceived.get()>0);
            assertEquals(0,fixture.denied.get());assertNull(fixture.failure.get());
            evidence.put("separateUid",true).put("platformTls",true).put("threeWatchdogOrigins",true);
        }finally{
            try{
                context.startService(new Intent(context,AutopilotVpnService.class).setAction(AutopilotVpnService.STOP));
                await(15000,()->app.engine==null,"Production Stop engine cleanup");assertFalse(app.state().optBoolean("tun"));evidence.put("stopCleanup",true);
            }finally{
                fixture.close();evidence.put("fixtureClosed",true).put("openFixtureSockets",fixture.sockets.size());
                write(context,"stream-promotion-evidence.json",evidence);write(context,"stream-promotion-fixture.json",fixture.stats());
                for(String name:List.of(READY,READY+".tmp",COMPLETE,COMPLETE+".tmp"))Files.deleteIfExists(new File(context.getFilesDir(),name).toPath());
            }
        }
        String receipt="AUTOPILOT_STREAM_PROMOTION_RUNTIME_ACCEPTED automaticDiscovery=true staleOldProof=true sameOldSocket=true newSocketNewOutbound=true confirmedRecoveryCloses=true separateUid=true tunStarts=1 platformTls=true stopCleanup=true fixtureCleanup=true";
        System.out.println(receipt);Bundle status=new Bundle();status.putString("stream",receipt+"\n");InstrumentationRegistry.getInstrumentation().sendStatus(2,status);
    }
    private static JSONObject outbound(String name,String user,int port)throws Exception{return new JSONObject().put("name",name).put("type","socks5").put("server","127.0.0.1").put("port",port).put("username",user).put("password","synthetic-stream-only");}
    private interface Checked {boolean get()throws Exception;}
    private interface Job {void run()throws Exception;}
    private static void await(long millis,Checked condition,String purpose)throws Exception{long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);while(System.nanoTime()<until){if(condition.get())return;Thread.sleep(50);}fail(purpose+" timed out");}
    private static Object field(Object target,String name)throws Exception{Field f=VpnEngine.class.getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void invoke(VpnEngine engine,String name)throws Exception{Method method=VpnEngine.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(engine);}
    private static void onWorker(VpnEngine engine,Job job)throws Exception{((ScheduledExecutorService)field(engine,"worker")).submit(()->{try{job.run();}catch(Exception failure){throw new RuntimeException(failure);}}).get(10,TimeUnit.SECONDS);}
    private static int events(AutopilotApp app,String code){int count=0;for(String line:app.events().split("\\r?\\n"))if(line.endsWith(" "+code))count++;return count;}
    private static int tunStarts(AutopilotApp app){return events(app,"TUN_STARTED");}
    private static String nativeSelection()throws Exception{CompletableFuture<String> reply=new CompletableFuture<>();Clash.INSTANCE.invokeAction(new JSONObject().put("id","stream-selector-"+System.nanoTime()).put("method","getProxies").put("data",JSONObject.NULL).toString(),(InvokeInterface)reply::complete);JSONObject value=new JSONObject(reply.get(5,TimeUnit.SECONDS));assertEquals(0,value.getInt("code"));Object raw=value.get("data");JSONObject data=raw instanceof JSONObject?(JSONObject)raw:new JSONObject(String.valueOf(raw));return data.getJSONObject("proxies").getJSONObject("AUTOPILOT").getString("now");}
    private static void write(Context context,String name,JSONObject value)throws Exception{File temporary=new File(context.getFilesDir(),name+".tmp");try(FileOutputStream out=new FileOutputStream(temporary)){out.write(value.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}Files.move(temporary.toPath(),new File(context.getFilesDir(),name).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}

    private static final class Fixture implements AutoCloseable {
        static final Set<String> HOSTS=Set.of("www.gstatic.com","api.openai.com","api.anthropic.com");
        final String marker="AUTOPILOT_STREAM_"+UUID.randomUUID();
        final ServerSocket listener=new ServerSocket(0,64,InetAddress.getByName("127.0.0.1"));
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();final Set<String> tlsHosts=ConcurrentHashMap.newKeySet();
        final AtomicBoolean firstEnabled=new AtomicBoolean(true),secondEnabled=new AtomicBoolean(),promoted=new AtomicBoolean(),streamClosed=new AtomicBoolean(),closed=new AtomicBoolean();
        final AtomicInteger acknowledged=new AtomicInteger(),afterPromotionAcks=new AtomicInteger(),freshSecondConnections=new AtomicInteger(),denied=new AtomicInteger();
        final AtomicLong tlsSent=new AtomicLong(),tlsReceived=new AtomicLong();final AtomicReference<String> failure=new AtomicReference<>();
        final ExecutorService workers=pool(16,64,"stream-socks"),pumps=pool(12,64,"stream-tls");final Thread accept;
        Fixture()throws IOException{accept=new Thread(()->{while(!closed.get())try{Socket client=listener.accept();sockets.add(client);workers.execute(()->serve(client));}catch(IOException failure){if(!closed.get())this.failure.compareAndSet(null,"ACCEPT_FAILED");}},"stream-fixture-accept");accept.setDaemon(true);accept.start();}
        int port(){return listener.getLocalPort();}
        private void serve(Socket client){Socket remote=null;
            try{
                client.setSoTimeout(10000);DataInputStream input=new DataInputStream(client.getInputStream());OutputStream output=client.getOutputStream();
                if(input.readUnsignedByte()!=5)throw new IOException("SOCKS_VERSION");int methods=input.readUnsignedByte();boolean auth=false;for(int i=0;i<methods;i++)if(input.readUnsignedByte()==2)auth=true;if(!auth)throw new IOException("AUTH_REQUIRED");output.write(new byte[]{5,2});output.flush();
                if(input.readUnsignedByte()!=1)throw new IOException("AUTH_VERSION");byte[] user=new byte[input.readUnsignedByte()];input.readFully(user);byte[] password=new byte[input.readUnsignedByte()];input.readFully(password);String username=new String(user,StandardCharsets.US_ASCII);
                if(!Arrays.equals(password,"synthetic-stream-only".getBytes(StandardCharsets.US_ASCII))||!Set.of("first","second").contains(username))throw new IOException("AUTH_INVALID");
                if("first".equals(username)?!firstEnabled.get():!secondEnabled.get()){output.write(new byte[]{1,1});output.flush();return;}
                output.write(new byte[]{1,0});output.flush();if(input.readUnsignedByte()!=5||input.readUnsignedByte()!=1||input.readUnsignedByte()!=0)throw new IOException("SOCKS_COMMAND");
                int type=input.readUnsignedByte();String host;if(type==1){byte[] ip=new byte[4];input.readFully(ip);host=InetAddress.getByAddress(ip).getHostAddress();}else if(type==3){byte[] name=new byte[input.readUnsignedByte()];input.readFully(name);host=new String(name,StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);}else throw new IOException("ADDRESS_DENIED");int port=input.readUnsignedShort();
                if(port==80&&"203.0.113.7".equals(host)){accepted(output);stream(client,input,output,username);return;}
                if(port==80&&"203.0.113.8".equals(host)){accepted(output);String request=line(input);if(!("FRESH "+marker).equals(request))throw new IOException("FRESH_MARKER");output.write(("NEW "+username+" "+marker+"\n").getBytes(StandardCharsets.US_ASCII));output.flush();if("second".equals(username))freshSecondConnections.incrementAndGet();return;}
                if(port!=443||!HOSTS.contains(host)){denied.incrementAndGet();throw new IOException("DESTINATION_DENIED");}
                remote=new Socket();sockets.add(remote);remote.connect(new InetSocketAddress(host,443),5000);remote.setSoTimeout(10000);accepted(output);tlsHosts.add(host);
                byte[] prefix=new byte[3];input.readFully(prefix);if(prefix[0]!=22||prefix[1]!=3||(prefix[2]&255)>4){denied.incrementAndGet();throw new IOException("TLS_REQUIRED");}
                remote.getOutputStream().write(prefix);remote.getOutputStream().flush();tlsSent.addAndGet(3);Socket upstream=remote;
                Future<?> response=pumps.submit(()->copy(upstream,client,tlsReceived));copy(client,upstream,tlsSent);response.cancel(true);
            }catch(IOException expected){if(!closed.get()&&!(expected instanceof EOFException)&&!(expected instanceof SocketException)&&!(expected instanceof SocketTimeoutException))failure.compareAndSet(null,"PROTOCOL_OR_PUBLIC_CONNECT_FAILED");}
            finally{closeSocket(client);if(remote!=null)closeSocket(remote);}
        }
        private void stream(Socket client,InputStream input,OutputStream output,String user)throws IOException{
            if(!"first".equals(user)||!("STREAM "+marker).equals(line(input)))throw new IOException("STREAM_IDENTITY");
            int sequence=0;try{while(!closed.get()){
                boolean after=promoted.get();output.write((marker+" first "+(after?"PROMOTED":"INITIAL")+" "+(++sequence)+"\n").getBytes(StandardCharsets.US_ASCII));output.flush();
                if(!("ACK "+sequence).equals(line(input)))throw new EOFException("Stream acknowledgement lost");acknowledged.incrementAndGet();if(after)afterPromotionAcks.incrementAndGet();
                try{Thread.sleep(200);}catch(InterruptedException cancelled){Thread.currentThread().interrupt();return;}
            }}finally{streamClosed.set(true);}
        }
        private static String line(InputStream input)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();while(bytes.size()<512){int value=input.read();if(value<0)throw new EOFException();if(value=='\n')return bytes.toString(StandardCharsets.US_ASCII.name());bytes.write(value);}throw new IOException("LINE_LIMIT");}
        private static void accepted(OutputStream out)throws IOException{out.write(new byte[]{5,0,0,1,127,0,0,1,0,0});out.flush();}
        private static void copy(Socket source,Socket target,AtomicLong count){try{byte[] buffer=new byte[16384];int n;InputStream input=source.getInputStream();OutputStream output=target.getOutputStream();while((n=input.read(buffer))!=-1){output.write(buffer,0,n);output.flush();count.addAndGet(n);}}catch(IOException ignored){}finally{try{target.shutdownOutput();}catch(IOException ignored){}}}
        private void closeSocket(Socket socket){sockets.remove(socket);try{socket.close();}catch(IOException ignored){}}
        private static ExecutorService pool(int threads,int queued,String name){return new ThreadPoolExecutor(threads,threads,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(queued),job->{Thread thread=new Thread(job,name);thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());}
        JSONObject stats()throws Exception{return new JSONObject().put("acknowledged",acknowledged.get()).put("afterPromotionAcks",afterPromotionAcks.get()).put("freshSecondConnections",freshSecondConnections.get()).put("streamClosed",streamClosed.get()).put("tlsHosts",new JSONArray(new TreeSet<>(tlsHosts))).put("tlsSent",tlsSent.get()).put("tlsReceived",tlsReceived.get()).put("denied",denied.get()).put("failure",failure.get()==null?"":failure.get()).put("openSockets",sockets.size()).put("closed",closed.get());}
        @Override public void close()throws Exception{closed.set(true);listener.close();accept.join(5000);for(Socket socket:sockets)closeSocket(socket);workers.shutdownNow();pumps.shutdownNow();assertTrue(workers.awaitTermination(6,TimeUnit.SECONDS));assertTrue(pumps.awaitTermination(6,TimeUnit.SECONDS));assertFalse(accept.isAlive());assertTrue(sockets.isEmpty());}
    }
}
