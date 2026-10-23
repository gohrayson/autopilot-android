package app.autopilot.android;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.oviron.libmihomo.Clash;
import io.github.oviron.libmihomo.InvokeInterface;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Opt-in, fresh hosted emulator: ordinary START/discovery, real native selector and other-UID TUN. */
@RunWith(AndroidJUnit4.class)
public final class DiscoveryRuntimeTest {
    private static final String HEALTHY="Autopilot Discovery Japan 00 Healthy";
    private static final int POOL_SIZE=52;
    private static final String READY="discovery-ready.json",COMPLETE="discovery-complete";
    private static final List<String> HANDOFFS=List.of(READY,READY+".tmp",COMPLETE,COMPLETE+".tmp");

    @Test(timeout=240000) public void productionDiscoverySelectsEarlyAndRetestPreservesOtherUidTraffic()throws Exception {
        Assume.assumeTrue("Explicit hosted public TLS transport opt-in is required",
            "true".equals(InstrumentationRegistry.getArguments().getString("hostedAllowPublicTransport")));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        AutopilotApp app=(AutopilotApp)context.getApplicationContext();
        assertNull("Require a freshly cleared disposable emulator, with no running VPN",app.engine);
        assertTrue("Provider accounts and imported nodes must be absent",app.providers.nodes().isEmpty());
        assertNull("Host runner must grant emulator ACTIVATE_VPN",VpnService.prepare(context));
        assertEquals("Require fresh default policy",ProductSettings.merge(null,new JSONObject()).toString(),app.settings().toString());
        assertEquals("No prior TUN startup is allowed",0,tunStarts(app));
        deleteHandoffs(context);
        Files.deleteIfExists(new File(context.getFilesDir(),"discovery-evidence.json").toPath());
        Files.deleteIfExists(new File(context.getFilesDir(),"discovery-fixture.json").toPath());
        JSONObject evidence=new JSONObject().put("required",new JSONArray().put("chatgpt"))
            .put("poolSize",POOL_SIZE).put("sdk",Build.VERSION.SDK_INT).put("abi",Build.SUPPORTED_ABIS[0]);
        SocksFixture fixture=new SocksFixture();
        try {
            // Required services is the sole policy change. Every production timing, retry,
            // forced-pass, native-window, proof, and watchdog setting remains its default.
            app.setSettings(new JSONObject().put("required",new JSONArray().put("chatgpt")));
            assertEquals(ProductSettings.merge(null,new JSONObject().put("required",new JSONArray().put("chatgpt"))).toString(),app.settings().toString());
            JSONArray outbounds=new JSONArray();
            outbounds.put(outbound(HEALTHY,"healthy",fixture.port()));
            for(int index=1;index<POOL_SIZE;index++)outbounds.put(outbound(String.format(Locale.ROOT,"Autopilot Discovery Japan %02d Bad",index),String.format(Locale.ROOT,"bad%02d",index),fixture.port()));
            app.providers.importConfig(new JSONObject().put("proxies",outbounds).toString());
            assertEquals(POOL_SIZE,app.providers.nodes().size());
            assertEquals("Healthy fixture must be first in the actual imported source",HEALTHY,app.providers.nodes().get(0).name);
            long started=System.nanoTime();
            context.startForegroundService(new Intent(context,AutopilotVpnService.class).setAction(AutopilotVpnService.START));
            JSONObject connected=awaitSelected(app,started);
            long earlyMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
            assertTrue("Healthy selection must precede the unfinished bad tail",unfinished(connected));
            JSONObject healthyRow=null;JSONArray visible=connected.getJSONArray("nodes");
            for(int index=0;index<visible.length();index++)if(HEALTHY.equals(visible.getJSONObject(index).optString("name")))healthyRow=visible.getJSONObject(index);
            assertNotNull("Selected healthy node must be visible in production state",healthyRow);
            assertTrue("The required ChatGPT service must be eligible",SelectionPolicy.usable(healthyRow.getString("chatgpt")));
            assertEquals("Claude service eligibility is not claimed by this fixture","not-required",healthyRow.getString("claude"));
            assertEquals("Gemini service eligibility is not claimed by this fixture","not-required",healthyRow.getString("gemini"));
            assertEquals(HEALTHY,nativeSelection());
            assertEquals("Discovery must establish the production TUN once",1,tunStarts(app));
            evidence.put("earlySelectionMs",earlyMillis).put("selectedBeforeTail",true)
                .put("initialDiscovery",connected.getJSONObject("discovery"));

            long selectedAt=System.currentTimeMillis();
            long priorWatchdog=connected.getJSONObject("watchdog").optLong("lastCheckAt");
            JSONObject monitored=awaitBackgroundWatchdog(app,selectedAt,priorWatchdog);
            evidence.put("watchdogAdvancedDuringDiscovery",true).put("watchdog",monitored.getJSONObject("watchdog"));
            assertEquals(HEALTHY,nativeSelection());
            assertEquals(1,tunStarts(app));
            handoff(context,app,fixture,"connected",evidence);

            JSONObject before=app.state();
            assertTrue("Retest fixture must still have unfinished discovery work",unfinished(before));
            long cycle=before.getJSONObject("discovery").getLong("cycle");
            int cursor=before.getJSONObject("discovery").getInt("cursor");
            long priorRetest=before.getJSONObject("discovery").getLong("lastAppliedRetestId");
            VpnEngine engine=app.engine;assertNotNull(engine);
            engine.retest(); // Public production action: no reflection, profile/selector seeding, or direct promotion.
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            JSONObject retesting=app.state();boolean observed=false;
            while(System.nanoTime()<deadline){
                retesting=app.state();assertRetained(app,retesting);
                JSONObject discovery=retesting.getJSONObject("discovery");
                assertEquals("Retest must coalesce into the current logical cycle",cycle,discovery.getLong("cycle"));
                assertTrue("Retest may not rewind the discovery cursor",discovery.getInt("cursor")>=cursor);
                if(discovery.getLong("lastAppliedRetestId")==priorRetest+1){
                    assertTrue("Manual retest must request a forced scan",discovery.getBoolean("force"));
                    assertEquals("Manual retest must retain production forced-pass policy",ProductContract.policyInt("forcedSweepPasses"),discovery.getInt("passes"));
                    assertEquals("DISCOVERY_COALESCED",discovery.getString("code"));observed=true;break;
                }
                Thread.sleep(100);
            }
            assertTrue("Production retest was not observed as a coalesced force request",observed);
            assertTrue("Retest traffic probe must run while discovery remains active",unfinished(retesting));
            evidence.put("retestCoalesced",true).put("manualRetestAcknowledged",true).put("priorRetestId",priorRetest)
                .put("retestDiscovery",retesting.getJSONObject("discovery"));
            handoff(context,app,fixture,"retest",evidence);
            assertRetained(app,app.state());assertEquals(HEALTHY,nativeSelection());
            assertEquals("Retest must retain the same production TUN",1,tunStarts(app));
            assertEquals("Both other-UID stage probes must reach the fixture",2,fixture.markerHits.get());
            assertTrue("The failed tail must exercise actual SOCKS handshakes",fixture.badSessions.get()>0);
            assertTrue("The actual three-origin watchdog must traverse the relay",fixture.hosts.containsAll(SocksFixture.TLS_HOSTS));
            assertTrue("Encrypted request bytes must traverse the healthy outbound",fixture.sent.get()>0);
            assertTrue("Encrypted response bytes must traverse the healthy outbound",fixture.received.get()>0);
            assertEquals("Fixture must reject no unexpected destinations",0,fixture.denied.get());
            assertNull("Fixture resource/protocol failures are not masked",fixture.failure.get());
            evidence.put("nativeSelectorPreserved",true).put("tunStarts",1).put("separateUidConnected",true)
                .put("separateUidRetest",true).put("platformTls",true).put("threeWatchdogOrigins",true);
        }finally {
            try {
                context.startService(new Intent(context,AutopilotVpnService.class).setAction(AutopilotVpnService.STOP));
                long stopDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(app.engine!=null&&System.nanoTime()<stopDeadline)Thread.sleep(100);
                assertNull("Production Stop must release its engine",app.engine);
                assertFalse("Production Stop must release its TUN",app.state().optBoolean("tun"));
                evidence.put("stopCleanup",true);
            }finally {
                try{fixture.close();}finally{
                    writeAtomic(context,"discovery-fixture.json",fixture.stats());
                    evidence.put("fixtureClosed",fixture.closed.get()).put("openFixtureSockets",fixture.sockets.size());
                    writeAtomic(context,"discovery-evidence.json",evidence);
                    deleteHandoffs(context);
                }
            }
        }
        assertEquals(0,fixture.sockets.size());
        String receipt="AUTOPILOT_DISCOVERY_RUNTIME_ACCEPTED earlySelectionMs="+evidence.getLong("earlySelectionMs")
            +" pool=52 required=chatgpt selectedBeforeTail=true watchdogAdvanced=true retestCoalesced=true manualRetestAcknowledged=true"
            +" nativeSelector=true tunStarts=1 separateUidConnected=true separateUidRetest=true platformTls=true stopCleanup=true fixtureCleanup=true";
        System.out.println(receipt);
        Bundle status=new Bundle();status.putString("stream",receipt+"\n");InstrumentationRegistry.getInstrumentation().sendStatus(2,status);
    }

    private static JSONObject outbound(String name,String username,int port)throws Exception {
        // Public synthetic RFC1929 identifiers only. No provider credential is present.
        return new JSONObject().put("name",name).put("type","socks5").put("server","127.0.0.1")
            .put("port",port).put("username",username).put("password","synthetic-fixture-only");
    }
    private static JSONObject awaitSelected(AutopilotApp app,long started)throws Exception {
        JSONObject state=app.state();
        while(System.nanoTime()-started<TimeUnit.SECONDS.toNanos(60)){
            state=app.state();
            assertDiscoveryLimits(state);
            assertFalse("Production START failed: "+state,"error".equals(state.optString("phase")));
            if(HEALTHY.equals(state.optString("current"))&&"connected".equals(state.optString("phase"))&&state.optBoolean("tun"))return state;
            Thread.sleep(100);
        }
        fail("Production discovery did not select the early healthy node within 60 seconds: "+state);return state;
    }
    private static JSONObject awaitBackgroundWatchdog(AutopilotApp app,long selectedAt,long previous)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(32);JSONObject state=app.state();
        while(System.nanoTime()<until){
            state=app.state();assertRetained(app,state);
            JSONObject watchdog=state.getJSONObject("watchdog");
            if(System.currentTimeMillis()>=selectedAt+ConnectionWatchdog.INTERVAL
                &&watchdog.optLong("lastCheckAt")>previous
                &&watchdog.optLong("lastCheckAt")>=selectedAt+ConnectionWatchdog.INTERVAL
                &&watchdog.optLong("lastSuccessAt")>=selectedAt+ConnectionWatchdog.INTERVAL){
                assertTrue("Watchdog completion must be published before background discovery finishes",unfinished(state));return state;
            }
            Thread.sleep(100);
        }
        fail("Production watchdog did not advance during background discovery: "+state);return state;
    }
    private static boolean unfinished(JSONObject state){
        JSONObject scan=state.optJSONObject("discovery");
        return scan!=null&&scan.optBoolean("active")&&(scan.optInt("cursor")<scan.optInt("total")*scan.optInt("passes")
            ||scan.optInt("nativeActive")>0||scan.optInt("fallbackActive")>0||scan.optInt("fallbackQueued")>0);
    }
    private static void assertRetained(AutopilotApp app,JSONObject state){
        assertDiscoveryLimits(state);
        assertNotNull("Production engine disappeared during discovery",app.engine);
        assertTrue("Production TUN disappeared during discovery: "+state,state.optBoolean("tun"));
        assertEquals("Background work must retain the verified selector: "+state,HEALTHY,state.optString("current"));
        assertEquals("Background work must retain the connected phase: "+state,"connected",state.optString("phase"));
        assertEquals("Background work may not rebuild the TUN",1,tunStarts(app));
    }
    private static void assertDiscoveryLimits(JSONObject state){
        JSONObject scan=state.optJSONObject("discovery");if(scan==null)return;
        assertTrue("Production native discovery window exceeded eight: "+scan,scan.optInt("nativeActive")>=0&&scan.optInt("nativeActive")<=8);
        assertTrue("Production fallback discovery exceeded two lanes: "+scan,scan.optInt("fallbackActive")>=0&&scan.optInt("fallbackActive")<=2);
    }
    private static int tunStarts(AutopilotApp app){return countEvent(app,"TUN_STARTED");}
    private static int countEvent(AutopilotApp app,String code){
        int count=0;for(String line:app.events().split("\\r?\\n"))if(line.endsWith(" "+code))count++;return count;
    }
    private static String nativeSelection()throws Exception {
        CompletableFuture<String> reply=new CompletableFuture<>();
        Clash.INSTANCE.invokeAction(new JSONObject().put("id","discovery-selector-"+System.nanoTime()).put("method","getProxies").put("data",JSONObject.NULL).toString(),(InvokeInterface)reply::complete);
        JSONObject response=new JSONObject(reply.get(5,TimeUnit.SECONDS));assertEquals(0,response.getInt("code"));
        Object raw=response.get("data");JSONObject document=raw instanceof JSONObject?(JSONObject)raw:new JSONObject(String.valueOf(raw));
        return document.getJSONObject("proxies").getJSONObject("AUTOPILOT").getString("now");
    }
    private static void handoff(Context context,AutopilotApp app,SocksFixture fixture,String stage,JSONObject evidence)throws Exception {
        String nonce=UUID.randomUUID().toString();
        Files.deleteIfExists(new File(context.getFilesDir(),COMPLETE).toPath());
        JSONObject ready=new JSONObject().put("stage",stage).put("nonce",nonce).put("uid",android.os.Process.myUid())
            .put("tun",app.state().optBoolean("tun")).put("marker",fixture.marker).put("evidence",evidence);
        assertEquals(HEALTHY,nativeSelection());writeAtomic(context,READY,ready);
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);File completed=new File(context.getFilesDir(),COMPLETE);
        while(System.nanoTime()<until){
            assertRetained(app,app.state());
            if(completed.isFile()){
                JSONObject result=new JSONObject(new String(Files.readAllBytes(completed.toPath()),StandardCharsets.UTF_8));
                assertEquals("Other-UID stage completion may not reuse stale evidence",stage,result.getString("stage"));
                assertEquals("Other-UID stage nonce may not reuse stale evidence",nonce,result.getString("nonce"));
                assertEquals("PASS",result.getString("result"));assertNotEquals(android.os.Process.myUid(),result.getInt("uid"));
                assertEquals(HEALTHY,nativeSelection());return;
            }
            Thread.sleep(100);
        }
        fail("Host did not complete separate-UID "+stage+" traffic probe");
    }
    private static void writeAtomic(Context context,String name,JSONObject value)throws Exception {
        File temporary=new File(context.getFilesDir(),name+".tmp"),target=new File(context.getFilesDir(),name);
        try(FileOutputStream stream=new FileOutputStream(temporary)){stream.write(value.toString().getBytes(StandardCharsets.UTF_8));stream.getFD().sync();}
        Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    private static void deleteHandoffs(Context context)throws Exception {for(String name:HANDOFFS)Files.deleteIfExists(new File(context.getFilesDir(),name).toPath());}

    /** SOCKS protocol only: TLS bytes are opaque and verified by the real Android HTTPS clients. */
    private static final class SocksFixture implements AutoCloseable {
        static final Set<String> TLS_HOSTS=Set.of("www.gstatic.com","api.openai.com","api.anthropic.com");
        final String marker="AUTOPILOT_DISCOVERY_"+UUID.randomUUID();
        final ServerSocket listener=new ServerSocket(0,128,InetAddress.getByName("127.0.0.1"));
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        final Set<String> hosts=ConcurrentHashMap.newKeySet();
        final AtomicInteger badSessions=new AtomicInteger(),healthySessions=new AtomicInteger(),markerHits=new AtomicInteger(),denied=new AtomicInteger(),peakSockets=new AtomicInteger();
        final AtomicLong sent=new AtomicLong(),received=new AtomicLong();
        final AtomicReference<String> failure=new AtomicReference<>();
        final AtomicBoolean closed=new AtomicBoolean();
        final ThreadPoolExecutor workers=pool(16,128,"discovery-socks");
        final ThreadPoolExecutor pumps=pool(12,64,"discovery-tls");
        final ScheduledThreadPoolExecutor deadlines=new ScheduledThreadPoolExecutor(1,daemon("discovery-deadline"));
        final Thread accept;
        SocksFixture()throws IOException {
            deadlines.setRemoveOnCancelPolicy(true);
            accept=new Thread(()->{while(!closed.get())try{
                Socket client=listener.accept();track(client);
                try{workers.execute(()->serve(client));}catch(RejectedExecutionException full){failure.compareAndSet(null,"FIXTURE_WORKERS_EXHAUSTED");closeSocket(client);}
            }catch(IOException error){if(!closed.get())failure.compareAndSet(null,"FIXTURE_ACCEPT_FAILED");}},"discovery-accept");
            accept.setDaemon(true);accept.start();
        }
        int port(){return listener.getLocalPort();}
        private void track(Socket socket){sockets.add(socket);peakSockets.accumulateAndGet(sockets.size(),Math::max);if(closed.get())closeSocket(socket);}
        private void serve(Socket client){
            boolean stalled=false;Socket upstream=null;
            try {
                client.setSoTimeout(10000);DataInputStream input=new DataInputStream(client.getInputStream());OutputStream output=client.getOutputStream();
                if(input.readUnsignedByte()!=5)throw new IOException("SOCKS_VERSION");
                int methods=input.readUnsignedByte();boolean authentication=false;
                for(int index=0;index<methods;index++)if(input.readUnsignedByte()==2)authentication=true;
                if(!authentication)throw new IOException("SOCKS_AUTH_REQUIRED");output.write(new byte[]{5,2});output.flush();
                if(input.readUnsignedByte()!=1)throw new IOException("SOCKS_AUTH_VERSION");
                byte[] user=new byte[input.readUnsignedByte()];input.readFully(user);
                byte[] password=new byte[input.readUnsignedByte()];input.readFully(password);
                String username=new String(user,StandardCharsets.US_ASCII);
                if(!Arrays.equals("synthetic-fixture-only".getBytes(StandardCharsets.US_ASCII),password))throw new IOException("SOCKS_AUTH_INVALID");
                if(username.matches("bad(?:0[1-9]|[1-4][0-9]|5[01])")){
                    badSessions.incrementAndGet();stalled=true;
                    // Leave auth unanswered until the production check times out. No worker
                    // sleeps per failed session; the bounded timer closes each socket.
                    deadlines.schedule(()->closeSocket(client),10,TimeUnit.SECONDS);return;
                }
                if(!"healthy".equals(username))throw new IOException("SOCKS_USER_INVALID");
                healthySessions.incrementAndGet();output.write(new byte[]{1,0});output.flush();
                if(input.readUnsignedByte()!=5||input.readUnsignedByte()!=1||input.readUnsignedByte()!=0)throw new IOException("SOCKS_COMMAND");
                int type=input.readUnsignedByte();String host;
                if(type==1){byte[] ip=new byte[4];input.readFully(ip);host=InetAddress.getByAddress(ip).getHostAddress();}
                else if(type==3){byte[] name=new byte[input.readUnsignedByte()];input.readFully(name);host=new String(name,StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);}
                else if(type==4){byte[] ip=new byte[16];input.readFully(ip);host=InetAddress.getByAddress(ip).getHostAddress();}
                else throw new IOException("SOCKS_ADDRESS");
                int port=input.readUnsignedShort();
                if("203.0.113.7".equals(host)&&port==80){
                    accepted(output);String header=header(input);
                    if(!header.startsWith("GET /autopilot-probe HTTP/1.")){denied.incrementAndGet();throw new IOException("MARKER_PATH_DENIED");}
                    byte[] body=marker.getBytes(StandardCharsets.US_ASCII);
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    output.write(body);output.flush();markerHits.incrementAndGet();return;
                }
                if(port!=443||!TLS_HOSTS.contains(host)){
                    denied.incrementAndGet();output.write(new byte[]{5,2,0,1,0,0,0,0,0,0});output.flush();return;
                }
                upstream=new Socket();track(upstream);upstream.connect(new InetSocketAddress(host,443),5000);upstream.setSoTimeout(10000);
                accepted(output);hosts.add(host);Socket remote=upstream;
                // Refuse plaintext tunneling even to the fixed port-443 allowlist.
                // Inspect only the TLS record prefix; never terminate TLS, inspect a
                // request path/body, or replace the platform certificate verifier.
                byte[] prefix=new byte[3];input.readFully(prefix);
                if(prefix[0]!=22||prefix[1]!=3||(prefix[2]&255)>4){denied.incrementAndGet();throw new IOException("TLS_RECORD_REQUIRED");}
                remote.getOutputStream().write(prefix);remote.getOutputStream().flush();sent.addAndGet(prefix.length);
                Future<?> response=pumps.submit(()->copy(remote,client,received));
                copy(client,remote,sent);response.cancel(true);
            }catch(RejectedExecutionException full){if(!closed.get())failure.compareAndSet(null,"FIXTURE_PUMPS_EXHAUSTED");}
            catch(IOException error){
                // Production cancellation and bad-session timeouts are expected. Public
                // eligibility failures remain visible to the production controller/test.
                if(!closed.get()&&!stalled&&!(error instanceof EOFException)&&!(error instanceof SocketException)&&!(error instanceof SocketTimeoutException))failure.compareAndSet(null,"FIXTURE_PROTOCOL_OR_PUBLIC_CONNECT_FAILED");
            }finally{if(!stalled)closeSocket(client);if(upstream!=null)closeSocket(upstream);}
        }
        private static void accepted(OutputStream output)throws IOException {output.write(new byte[]{5,0,0,1,127,0,0,1,0,0});output.flush();}
        private void copy(Socket source,Socket target,AtomicLong count){
            try{byte[] bytes=new byte[16384];InputStream input=source.getInputStream();OutputStream output=target.getOutputStream();int length;
                while((length=input.read(bytes))!=-1){output.write(bytes,0,length);output.flush();count.addAndGet(length);}
            }catch(IOException expected){}finally{try{target.shutdownOutput();}catch(IOException ignored){}}
        }
        private static String header(InputStream input)throws IOException {
            ByteArrayOutputStream output=new ByteArrayOutputStream();int matched=0;
            while(output.size()<8192){int value=input.read();if(value<0)throw new EOFException();output.write(value);
                matched=value=="\r\n\r\n".charAt(matched)?matched+1:value=='\r'?1:0;
                if(matched==4)return output.toString(StandardCharsets.US_ASCII.name());}
            throw new IOException("MARKER_HEADER_LIMIT");
        }
        JSONObject stats()throws Exception {return new JSONObject().put("badSessions",badSessions.get()).put("healthySessions",healthySessions.get())
            .put("markerHits",markerHits.get()).put("tlsSent",sent.get()).put("tlsReceived",received.get()).put("tlsHosts",new JSONArray(new TreeSet<>(hosts)))
            .put("unexpectedDestinations",denied.get()).put("peakSockets",peakSockets.get()).put("openSockets",sockets.size())
            .put("failureCode",failure.get()==null?"":failure.get()).put("closed",closed.get());}
        private void closeSocket(Socket socket){sockets.remove(socket);try{socket.close();}catch(IOException ignored){}}
        private static ThreadFactory daemon(String name){return action->{Thread thread=new Thread(action,name);thread.setDaemon(true);return thread;};}
        private static ThreadPoolExecutor pool(int threads,int queued,String name){return new ThreadPoolExecutor(threads,threads,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(queued),daemon(name),new ThreadPoolExecutor.AbortPolicy());}
        @Override public void close()throws Exception {
            closed.set(true);listener.close();accept.join(5000);assertFalse("SOCKS accept thread leaked",accept.isAlive());
            deadlines.shutdownNow();for(Socket socket:sockets)closeSocket(socket);
            workers.shutdownNow();pumps.shutdownNow();
            assertTrue("SOCKS worker leak",workers.awaitTermination(6,TimeUnit.SECONDS));
            assertTrue("TLS relay worker leak",pumps.awaitTermination(6,TimeUnit.SECONDS));
            assertTrue("SOCKS deadline worker leak",deadlines.awaitTermination(6,TimeUnit.SECONDS));
            assertTrue("Fixture socket leak",sockets.isEmpty());
        }
    }
}
