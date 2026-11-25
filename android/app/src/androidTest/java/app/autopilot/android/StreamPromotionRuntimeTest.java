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
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

/** Hosted emulator only: real discovery/selector/TUN and a continuous separate-UID stream. */
@RunWith(AndroidJUnit4.class)
public final class StreamPromotionRuntimeTest {
    private static final String FIRST="Autopilot Stream Japan 00 First",SECOND="Autopilot Stream Japan 01 Second";
    private static final String RENEWED="Autopilot Recovery Japan Fresh Catalog";
    private static final String READY="stream-promotion-ready.json",COMPLETE="stream-promotion-complete";
    @Test(timeout=300000) public void expiredRankingPromotionPreservesOldSocketAndConfirmedFailureClosesIt()throws Exception {
        Assume.assumeTrue("Require explicit hosted public TLS opt-in","true".equals(InstrumentationRegistry.getArguments().getString("hostedAllowPublicTransport")));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();AutopilotApp app=(AutopilotApp)context.getApplicationContext();
        assertNull(app.engine);assertTrue(app.providers.nodes().isEmpty());assertNull(VpnService.prepare(context));
        assertEquals(ProductSettings.merge(null,new JSONObject()).toString(),app.settings().toString());
        for(String name:List.of(READY,READY+".tmp",COMPLETE,COMPLETE+".tmp","stream-promotion-evidence.json"))Files.deleteIfExists(new File(context.getFilesDir(),name).toPath());
        JSONObject evidence=new JSONObject();Fixture fixture=new Fixture();
        ProviderRepository originalProviders=app.providers;Runnable sourceChanged=originalProviders.onChanged;
        AtomicLong accountClock=new AtomicLong(System.currentTimeMillis());
        JSONArray initialOutbounds=new JSONArray().put(outbound(FIRST,"first",fixture.port())).put(outbound(SECOND,"second",fixture.port()));
        CatalogFixture catalog=new CatalogFixture(initialOutbounds,accountClock);
        try{
            app.setSettings(new JSONObject().put("required",new JSONArray().put("chatgpt")));
            app.providers=catalog.repository;app.providers.onChanged=sourceChanged;sourceChanged.run();
            context.startForegroundService(new Intent(context,AutopilotVpnService.class).setAction(AutopilotVpnService.START));
            await(60000,()->FIRST.equals(app.state().optString("current"))&&app.state().optBoolean("tun"),"Initial production discovery selection");
            assertEquals(FIRST,nativeSelection());assertEquals(1,tunStarts(app));
            await(20000,()->app.state().optJSONObject("watchdog").optLong("lastSuccessAt")>0,"Initial real watchdog completion before clock expiry");
            String nonce=UUID.randomUUID().toString();write(context,READY,new JSONObject().put("uid",android.os.Process.myUid()).put("tun",true).put("marker",fixture.marker).put("nonce",nonce));
            await(35000,()->fixture.acknowledged.get()>=3,"Separate-UID established stream acknowledgements");
            VpnEngine engine=app.engine;assertNotNull(engine);
            evidence.put("accountRecoverySettings",new JSONObject().put("hasAutoRefresh",app.settings().has("autoRefresh")).put("enabled",AccountRecoveryPolicy.enabled(app.settings())).put("engineAllowed",engine.accountRecoveryAllowed()));
            assertTrue("The shared default must enable account recovery without a fixture override",AccountRecoveryPolicy.enabled(app.settings()));
            assertTrue("Production engine lifecycle must allow account recovery before incident evidence",engine.accountRecoveryAllowed());
            int beforeRefreshAcks=fixture.acknowledged.get();
            // Controlled selected-service evidence and elapsed incident time on the
            // real controller. Production policy durations remain unmodified and
            // execute independently against the shared JVM scenario set.
            onWorker(engine,()->{
                Node selected=(Node)field(engine,"current");
                @SuppressWarnings("unchecked") Map<String,SelectionPolicy.Health> ledger=(Map<String,SelectionPolicy.Health>)field(engine,"health");
                SelectionPolicy.Health selectedHealth=ledger.get(selected.id);assertNotNull(selectedHealth);assertTrue("Initial real HTTPS proof must be fresh before degraded fixture",selectedHealth.retainSelectedTransport(System.currentTimeMillis()));
                selectedHealth.chatgpt="error";assertFalse("The required service failure must make cached qualification false",selectedHealth.qualified(System.currentTimeMillis()));
                Field checked=VpnEngine.class.getDeclaredField("accountRecoveryAlternativesChecked");checked.setAccessible(true);checked.setBoolean(engine,true);
                invoke(engine,"warnRetainedServicePath");invokeRecoveryObservation(engine);
                AccountRecoveryTrigger trigger=(AccountRecoveryTrigger)field(engine,"accountRecoveryTrigger");
                Field since=AccountRecoveryTrigger.class.getDeclaredField("degradedAt");since.setAccessible(true);assertTrue("Actual engine evidence must start the persistent-degradation gate",since.getLong(trigger)>0);
                evidence.put("accountRecoveryGateStarted",true);since.setLong(trigger,System.currentTimeMillis()-new AccountRecoveryPolicy().persistentDegradedMs);
                int requestsBefore=events(app,"ACCOUNT_RECOVERY_REQUESTED");
                invokeRecoveryObservation(engine);
                assertEquals("Elapsed actual engine gate must emit its background recovery request",requestsBefore+1,events(app,"ACCOUNT_RECOVERY_REQUESTED"));
                evidence.put("accountRecoveryNotificationEmitted",true);
            });
            write(context,"stream-promotion-evidence.json",evidence);
            await(10000,()->catalog.requests.get()==2&&"unchanged".equals(app.providers.recoveryStatus().optString("phase")),"Actual account queue refreshed and parsed unchanged synthetic subscription");
            await(10000,()->fixture.acknowledged.get()>=beforeRefreshAcks+3,"Established other-UID stream advances through unchanged catalog refresh");
            assertEquals(FIRST,nativeSelection());assertEquals(1,tunStarts(app));assertFalse(fixture.streamClosed.get());
            evidence.put("accountRecoveryEngineSignal",true).put("accountCatalogRequested",true).put("unchangedCatalogNoTunRestart",true).put("unchangedCatalogPreservedStream",true)
                .put("recoveryTimingFixture","controller evidence and elapsed incident clock; production durations unchanged").put("unchangedCatalogHttpRequests",catalog.requests.get());
            onWorker(engine,()->{
                Node selected=(Node)field(engine,"current");@SuppressWarnings("unchecked") Map<String,SelectionPolicy.Health> ledger=(Map<String,SelectionPolicy.Health>)field(engine,"health");
                ledger.get(selected.id).chatgpt="ok";invokeRecoveryObservation(engine);
            });
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
                // Android HTTPS clients pool established CONNECT/TLS sessions. Stop
                // those fixture control-check relays so stale proof cannot be renewed
                // through a cached session after new authentication is disabled. The
                // distinct numbered other-UID application stream is never touched.
                fixture.closeControlChecks("first");
                evidence.put("oldProofExpiredBeforeDiscovery",true);
            });
            int switches=events(app,"NODE_SWITCH"),upgrades=events(app,"HEALTHY_UPGRADE");long promotionStarted=System.nanoTime();
            engine.retest(); // Public production action: fresh native + HTTPS + service proof drives promotion.
            JSONArray proofSnapshots=new JSONArray();long[] nextSnapshot={0};
            try{await(20000,()->{JSONObject snapshot=app.state();long sampleAt=System.currentTimeMillis();if(sampleAt>=nextSnapshot[0]&&proofSnapshots.length()<12){proofSnapshots.put(new JSONObject().put("at",sampleAt).put("state",snapshot).put("fixture",fixture.stats()));nextSnapshot[0]=sampleAt+2000;}return SECOND.equals(snapshot.optString("current"));},"Fresh replacement promoted by actual discovery");}
            finally{evidence.put("proofSnapshots",proofSnapshots).put("afterDiscovery",app.state());write(context,"stream-promotion-evidence.json",evidence);}
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
            fixture.secondEnabled.set(false);fixture.closeControlChecks("second");
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
            // With the deliberately failed old stream already closed, publish a
            // different valid catalog. Android's current guarded profile install
            // pauses/re-attaches TUN; do not claim stream continuity through it.
            assertEquals(1,tunStarts(app));evidence.put("tunStartsBeforeCatalogChange",1);
            catalog.outbounds.set(new JSONArray().put(outbound(RENEWED,"first",fixture.port())));catalog.clock.addAndGet(new AccountRecoveryPolicy().refreshMinGapMs+1000);fixture.firstEnabled.set(true);
            onWorker(engine,()->{
                AccountRecoveryTrigger trigger=(AccountRecoveryTrigger)field(engine,"accountRecoveryTrigger");Field deadline=AccountRecoveryTrigger.class.getDeclaredField("nextSignalAt");deadline.setAccessible(true);deadline.setLong(trigger,0);
                Method request=VpnEngine.class.getDeclaredMethod("requestAccountCatalogRecovery",String.class);request.setAccessible(true);request.invoke(engine,"no-verified-replacement");
            });
            await(15000,()->catalog.requests.get()==4&&app.providers.nodes().size()==1,"Changed valid synthetic account catalog committed");
            await(60000,()->RENEWED.equals(app.state().optString("current"))&&app.state().optBoolean("tun"),"Changed account node freshly qualified by production discovery");
            assertEquals(RENEWED,nativeSelection());assertEquals(2,tunStarts(app));
            JSONArray qualifiedRows=app.state().getJSONArray("nodes");assertEquals(1,qualifiedRows.length());assertEquals(RENEWED,qualifiedRows.getJSONObject(0).getString("name"));assertTrue(qualifiedRows.getJSONObject(0).getBoolean("qualified"));
            assertEquals("retesting",app.providers.recoveryStatus().getString("phase"));
            evidence.put("changedCatalogCommitted",true).put("changedCatalogFreshlyQualified",true).put("changedCatalogGuardedProfileTransition",true).put("tunStartsAfterCatalogChange",2).put("tunStarts",2)
                .put("catalogHttpRequests",catalog.requests.get()).put("providerApi","memory-only synthetic OneVPN HTTP boundary; no vendor requests");
        }finally{
            try{
                context.startService(new Intent(context,AutopilotVpnService.class).setAction(AutopilotVpnService.STOP));
                await(15000,()->app.engine==null,"Production Stop engine cleanup");assertFalse(app.state().optBoolean("tun"));evidence.put("stopCleanup",true);
            }finally{
                evidence.put("catalogRequestsAtCleanup",catalog.requests.get()).put("catalogRecoveryAtCleanup",app.providers.recoveryStatus());
                app.providers=originalProviders;sourceChanged.run();
                fixture.close();evidence.put("fixtureClosed",true).put("openFixtureSockets",fixture.sockets.size());
                write(context,"stream-promotion-evidence.json",evidence);write(context,"stream-promotion-fixture.json",fixture.stats());
                for(String name:List.of(READY,READY+".tmp",COMPLETE,COMPLETE+".tmp"))Files.deleteIfExists(new File(context.getFilesDir(),name).toPath());
            }
        }
        String receipt="AUTOPILOT_STREAM_PROMOTION_RUNTIME_ACCEPTED automaticDiscovery=true staleOldProof=true sameOldSocket=true newSocketNewOutbound=true confirmedRecoveryCloses=true separateUid=true accountRecovery=true unchangedCatalogPreservesStream=true changedCatalogQualified=true tunStartsBeforeCatalogChange=1 tunStartsAfterCatalogChange=2 platformTls=true stopCleanup=true fixtureCleanup=true";
        System.out.println(receipt);Bundle status=new Bundle();status.putString("stream",receipt+"\n");InstrumentationRegistry.getInstrumentation().sendStatus(2,status);
    }
    private static JSONObject outbound(String name,String user,int port)throws Exception{return new JSONObject().put("name",name).put("type","socks5").put("server","127.0.0.1").put("port",port).put("username",user).put("password","synthetic-stream-only");}
    private static void invokeRecoveryObservation(VpnEngine engine)throws Exception{Method method=VpnEngine.class.getDeclaredMethod("updateAccountRecovery",boolean.class);method.setAccessible(true);method.invoke(engine,false);}

    /** Owned memory-only account/API fixture. No credential file or vendor API is used. */
    private static final class CatalogFixture {
        final AtomicReference<JSONArray> outbounds;final AtomicLong clock;final AtomicInteger requests=new AtomicInteger();final ProviderRepository repository;
        CatalogFixture(JSONArray initial,AtomicLong clock)throws Exception{
            this.outbounds=new AtomicReference<>(initial);this.clock=clock;Map<String,JSONObject> records=new HashMap<>();JSONArray nodes=new JSONArray();
            for(int i=0;i<initial.length();i++)nodes.put(new JSONObject().put("config",initial.getJSONObject(i)).put("country","JP"));
            records.put("onevpn",new JSONObject().put("version",1).put("session",new JSONObject().put("accessToken","synthetic-access").put("refreshToken","synthetic-refresh").put("deviceId","synthetic-device").put("expiresAt",clock.get()+3600000))
                .put("account",new JSONObject().put("username","fixture@example.invalid").put("paid",true).put("subscriptionEnd",clock.get()+86400000)).put("nodes",nodes).put("lastRefresh",0).put("nextRefreshAt",clock.get()+900000));
            SecureStore store=new SecureStore(){
                @Override public JSONObject load(String provider)throws ProviderException{try{return records.containsKey(provider)?new JSONObject(records.get(provider).toString()):null;}catch(JSONException invalid){throw new ProviderException("CREDENTIAL_STORE","restore");}}
                @Override public void save(String provider,JSONObject value)throws ProviderException{try{if(value==null)records.remove(provider);else records.put(provider,new JSONObject(value.toString()));}catch(JSONException invalid){throw new ProviderException("CREDENTIAL_STORE","session-save");}}
            };
            ProviderHttp http=new ProviderHttp(){
                @Override public JSONObject one(String path,JSONObject body,String token,String stage)throws ProviderException{
                    requests.incrementAndGet();try{
                        if(path.equals("/api/user/client-info"))return new JSONObject().put("status","success").put("user",new JSONObject().put("email","fixture@example.invalid").put("isBanned",false).put("expireTime",clock.get()+86400000));
                        if(!path.equals("/api/user/subscription/encrypted"))throw new Exception("Unexpected fixture request");
                        byte[] iv=new byte[16];Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec("x9Kp2mNv7QwTr4Yb8FjHs6DcAeLg0UiZ".getBytes(StandardCharsets.UTF_8),"AES"),new IvParameterSpec(iv));
                        return new JSONObject().put("iv",Base64.getEncoder().encodeToString(iv)).put("data",Base64.getEncoder().encodeToString(cipher.doFinal(new JSONObject().put("proxies",outbounds.get()).toString().getBytes(StandardCharsets.UTF_8))));
                    }catch(Exception invalid){throw new ProviderException("INVALID_RESPONSE",stage);}
                }
                @Override public JSONObject octo(Map<String,String> fields,String stage){throw new AssertionError("Unsigned Octohide fixture must never contact a provider");}
            };
            repository=new ProviderRepository(store,http,clock::get,millis->clock.addAndGet(millis));
        }
    }
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
        static final String EXIT_HOST="www.cloudflare.com";
        final String marker="AUTOPILOT_STREAM_"+UUID.randomUUID();
        final ServerSocket listener=new ServerSocket(0,64,InetAddress.getByName("127.0.0.1"));
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();final Map<Socket,String> controlChecks=new ConcurrentHashMap<>();final Set<String> tlsHosts=ConcurrentHashMap.newKeySet(),tlsByNode=ConcurrentHashMap.newKeySet(),deniedTargets=ConcurrentHashMap.newKeySet();
        final Set<String> exitHosts=ConcurrentHashMap.newKeySet();final AtomicInteger exitSessions=new AtomicInteger();final AtomicLong exitSent=new AtomicLong(),exitReceived=new AtomicLong();
        final AtomicBoolean firstEnabled=new AtomicBoolean(true),secondEnabled=new AtomicBoolean(),promoted=new AtomicBoolean(),streamClosed=new AtomicBoolean(),closed=new AtomicBoolean();
        final AtomicInteger acknowledged=new AtomicInteger(),afterPromotionAcks=new AtomicInteger(),freshSecondConnections=new AtomicInteger(),denied=new AtomicInteger(),privateDnsValidationRefused=new AtomicInteger();
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
                // Android validates Private DNS on the exact production TUN DNS
                // address. The fixture never forwards that internal port externally.
                if(port==853&&"172.19.0.2".equals(host)){privateDnsValidationRefused.incrementAndGet();output.write(new byte[]{5,2,0,1,0,0,0,0,0,0});output.flush();return;}
                boolean exit=EXIT_HOST.equals(host);
                if(port!=443||!HOSTS.contains(host)&&!exit){deny(host,port);throw new IOException("DESTINATION_DENIED");}
                controlChecks.put(client,username);
                if(!enabled(username))return; // Close an auth-before-transition race as well.
                remote=new Socket();sockets.add(remote);remote.connect(new InetSocketAddress(host,443),5000);remote.setSoTimeout(10000);accepted(output);if(exit){exitHosts.add(host);exitSessions.incrementAndGet();}else{tlsHosts.add(host);tlsByNode.add(username+" "+host);}
                AtomicLong requestCount=exit?exitSent:tlsSent,responseCount=exit?exitReceived:tlsReceived;
                controlChecks.put(remote,username);
                if(!enabled(username))return;
                byte[] prefix=new byte[3];input.readFully(prefix);if(prefix[0]!=22||prefix[1]!=3||(prefix[2]&255)>4){deny("NON_TLS",port);throw new IOException("TLS_REQUIRED");}
                remote.getOutputStream().write(prefix);remote.getOutputStream().flush();requestCount.addAndGet(3);Socket upstream=remote;
                Future<?> response=pumps.submit(()->copy(upstream,client,responseCount));copy(client,upstream,requestCount);response.cancel(true);
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
        private void deny(String host,int port){denied.incrementAndGet();synchronized(deniedTargets){if(deniedTargets.size()<32)deniedTargets.add(host.substring(0,Math.min(host.length(),253)).replaceAll("[^A-Za-z0-9.:-]","?")+":"+port);}}
        private static void accepted(OutputStream out)throws IOException{out.write(new byte[]{5,0,0,1,127,0,0,1,0,0});out.flush();}
        private static void copy(Socket source,Socket target,AtomicLong count){try{byte[] buffer=new byte[16384];int n;InputStream input=source.getInputStream();OutputStream output=target.getOutputStream();while((n=input.read(buffer))!=-1){output.write(buffer,0,n);output.flush();count.addAndGet(n);}}catch(IOException ignored){}finally{try{target.shutdownOutput();}catch(IOException ignored){}}}
        void closeControlChecks(String user){for(Map.Entry<Socket,String> entry:controlChecks.entrySet())if(user.equals(entry.getValue()))closeSocket(entry.getKey());}
        private boolean enabled(String user){return "first".equals(user)?firstEnabled.get():secondEnabled.get();}
        private void closeSocket(Socket socket){controlChecks.remove(socket);sockets.remove(socket);try{socket.close();}catch(IOException ignored){}}
        private static ExecutorService pool(int threads,int queued,String name){return new ThreadPoolExecutor(threads,threads,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(queued),job->{Thread thread=new Thread(job,name);thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());}
        JSONObject stats()throws Exception{return new JSONObject().put("acknowledged",acknowledged.get()).put("afterPromotionAcks",afterPromotionAcks.get()).put("freshSecondConnections",freshSecondConnections.get()).put("streamClosed",streamClosed.get()).put("tlsHosts",new JSONArray(new TreeSet<>(tlsHosts))).put("tlsByNode",new JSONArray(new TreeSet<>(tlsByNode))).put("tlsSent",tlsSent.get()).put("tlsReceived",tlsReceived.get()).put("exitMeasurementSessions",exitSessions.get()).put("exitMeasurementHosts",new JSONArray(new TreeSet<>(exitHosts))).put("exitMeasurementSent",exitSent.get()).put("exitMeasurementReceived",exitReceived.get()).put("privateDnsValidationRefused",privateDnsValidationRefused.get()).put("denied",denied.get()).put("deniedTargets",new JSONArray(new TreeSet<>(deniedTargets))).put("failure",failure.get()==null?"":failure.get()).put("openSockets",sockets.size()).put("closed",closed.get());}
        @Override public void close()throws Exception{closed.set(true);listener.close();accept.join(5000);for(Socket socket:sockets)closeSocket(socket);workers.shutdownNow();pumps.shutdownNow();assertTrue(workers.awaitTermination(6,TimeUnit.SECONDS));assertTrue(pumps.awaitTermination(6,TimeUnit.SECONDS));assertFalse(accept.isAlive());assertTrue(sockets.isEmpty());}
    }
}
