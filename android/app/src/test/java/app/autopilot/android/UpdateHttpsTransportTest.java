package app.autopilot.android;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Exercises the production HTTPS transport's routing/configuration and the complete manifest/range pipeline. */
public final class UpdateHttpsTransportTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final URI FEED=URI.create(UpdateManifestTest.SOURCE);
    private interface Action { void run() throws Exception; }
    private static void rejects(String code,Action action)throws Exception{
        try{action.run();fail("Expected "+code);}catch(UpdateDownload.UpdateException failure){assertEquals(code,failure.code);}
    }
    private static void assertProxy(Proxy proxy,int port){
        assertEquals(Proxy.Type.HTTP,proxy.type());
        InetSocketAddress address=(InetSocketAddress)proxy.address();
        assertEquals("127.0.0.1",address.getAddress().getHostAddress());assertEquals(port,address.getPort());
    }
    private static String hash(byte[] bytes)throws Exception{
        StringBuilder result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();
    }
    private static class Connection extends HttpsURLConnection {
        int status=200,closes; byte[] bytes=new byte[0]; String location,range;
        IOException statusFailure;
        Connection(URI uri)throws MalformedURLException{super(uri.toURL());}
        @Override public int getResponseCode()throws IOException{if(statusFailure!=null)throw statusFailure;return status;}
        @Override public long getContentLengthLong(){return bytes.length;}
        @Override public InputStream getInputStream()throws IOException{return new ByteArrayInputStream(bytes);}
        @Override public String getHeaderField(String name){return "Location".equals(name)?location:"Content-Range".equals(name)?range:null;}
        @Override public void disconnect(){closes++;}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){}
        @Override public String getCipherSuite(){return "fixture";}
        @Override public Certificate[] getLocalCertificates(){return null;}
        @Override public Certificate[] getServerCertificates(){return null;}
    }
    private static Connection connection(URI uri){try{return new Connection(uri);}catch(Exception impossible){throw new AssertionError(impossible);}}

    @Test public void directIsExplicitAndVpnUsesOnlyLoopbackHttpWithoutChangingTls()throws Exception{
        AtomicInteger route=new AtomicInteger();List<String> observed=new ArrayList<>();List<Proxy> proxies=new ArrayList<>();
        List<Connection> opened=new ArrayList<>();
        UpdateDownload.HttpsTransport transport=new UpdateDownload.HttpsTransport(route::get,observed::add,(uri,proxy)->{
            proxies.add(proxy);Connection connection=connection(uri);opened.add(connection);return connection;
        });
        try(UpdateDownload.Response ignored=transport.open(FEED)){}
        assertSame(Proxy.NO_PROXY,proxies.get(0));
        route.set(27001);
        try(UpdateDownload.Response ignored=transport.open(FEED,10,20)){}
        assertProxy(proxies.get(1),27001);assertEquals(Arrays.asList("direct","vpn"),observed);
        Connection proxy=opened.get(1);
        assertFalse(proxy.getInstanceFollowRedirects());assertFalse(proxy.getUseCaches());
        assertEquals(20000,proxy.getConnectTimeout());assertEquals(20000,proxy.getReadTimeout());
        assertEquals("bytes=10-20",proxy.getRequestProperty("Range"));assertEquals("identity",proxy.getRequestProperty("Accept-Encoding"));
        assertNull(proxy.getRequestProperty("Authorization"));assertNull(proxy.getRequestProperty("Cookie"));
        assertSame(HttpsURLConnection.getDefaultSSLSocketFactory(),proxy.getSSLSocketFactory());
        assertSame(HttpsURLConnection.getDefaultHostnameVerifier(),proxy.getHostnameVerifier());
        for(Connection openedConnection:opened)assertEquals(1,openedConnection.closes);
    }

    @Test public void unavailableOrInvalidActiveRouteNeverOpensDirectConnection()throws Exception{
        AtomicInteger calls=new AtomicInteger();AtomicInteger route=new AtomicInteger(-1);List<String> observed=new ArrayList<>();
        UpdateDownload download=new UpdateDownload(new UpdateDownload.HttpsTransport(route::get,observed::add,(uri,proxy)->{calls.incrementAndGet();return connection(uri);}));
        for(int value:new int[]{-1,-2,65536,Integer.MAX_VALUE}){
            route.set(value);rejects("UPDATE_VPN_ROUTE_UNAVAILABLE",()->download.fetchManifest(FEED.toString(),UpdateManifestTest.PACKAGE));
        }
        assertEquals(0,calls.get());for(String value:observed)assertEquals("vpn-unavailable",value);
        UpdateDownload failedSupplier=new UpdateDownload(()->{throw new IllegalStateException("private runtime context");},observed::add);
        rejects("UPDATE_VPN_ROUTE_UNAVAILABLE",()->failedSupplier.fetchManifest(FEED.toString(),UpdateManifestTest.PACKAGE));
    }

    @Test public void routeIsReevaluatedForRedirectAndCannotFallBackWhenNodeDisappears()throws Exception{
        AtomicInteger route=new AtomicInteger(27000),requests=new AtomicInteger();List<Proxy> proxies=new ArrayList<>();
        byte[] json=UpdateManifestTest.json().toString().getBytes(StandardCharsets.UTF_8);
        UpdateDownload download=new UpdateDownload(new UpdateDownload.HttpsTransport(route::get,null,(uri,proxy)->{
            proxies.add(proxy);Connection connection=connection(uri);
            if(requests.getAndIncrement()==0){connection.status=302;connection.location="/autopilot/next.json";route.set(27001);}
            else connection.bytes=json;return connection;
        }));
        assertEquals(3,download.fetchManifest(FEED.toString(),UpdateManifestTest.PACKAGE).versionCode);
        assertProxy(proxies.get(0),27000);assertProxy(proxies.get(1),27001);
        route.set(27000);requests.set(0);proxies.clear();
        UpdateDownload unavailable=new UpdateDownload(new UpdateDownload.HttpsTransport(route::get,null,(uri,proxy)->{
            proxies.add(proxy);Connection connection=connection(uri);connection.status=302;connection.location="/autopilot/next.json";route.set(-1);return connection;
        }));
        rejects("UPDATE_VPN_ROUTE_UNAVAILABLE",()->unavailable.fetchManifest(FEED.toString(),UpdateManifestTest.PACKAGE));
        assertEquals(1,proxies.size());assertProxy(proxies.get(0),27000);
    }

    @Test public void failedProxyDoesNotRetryDirectAndTlsValidationFailureRemainsAnError()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        UpdateDownload unavailable=new UpdateDownload(new UpdateDownload.HttpsTransport(()->27000,null,(uri,proxy)->{
            calls.incrementAndGet();assertProxy(proxy,27000);throw new ConnectException("private node address");
        }));
        rejects("UPDATE_VPN_PROXY_ERROR",()->unavailable.fetchManifest(FEED.toString(),UpdateManifestTest.PACKAGE));assertEquals(1,calls.get());
        Connection certificateFailure=connection(FEED);certificateFailure.statusFailure=new SSLHandshakeException("certificate rejected");
        UpdateDownload invalidTls=new UpdateDownload(new UpdateDownload.HttpsTransport(()->27000,null,(uri,proxy)->certificateFailure));
        rejects("UPDATE_NETWORK_ERROR",()->invalidTls.fetchManifest(FEED.toString(),UpdateManifestTest.PACKAGE));assertEquals(1,certificateFailure.closes);
    }

    @Test public void redirectedManifestAndConcurrentApkRangesAllUseExplicitSelectedRoute()throws Exception{
        byte[] apk=new byte[6*1024*1024];for(int i=0;i<apk.length;i++)apk[i]=(byte)(i*31);
        JSONObject metadata=UpdateManifestTest.json().put("sizeBytes",apk.length).put("sha256",hash(apk));
        List<Connection> connections=Collections.synchronizedList(new ArrayList<>());AtomicInteger actualParts=new AtomicInteger();
        UpdateDownload download=new UpdateDownload(new UpdateDownload.HttpsTransport(()->27003,null,(uri,proxy)->{
            assertProxy(proxy,27003);
            Connection connection=new Connection(uri){
                boolean prepared;
                @Override public int getResponseCode(){
                    if(prepared)return status;prepared=true;
                    String path=uri.getPath();
                    if(path.endsWith("latest.json")){status=302;location="/autopilot/manifest.json";}
                    else if(path.endsWith("manifest.json")){bytes=metadata.toString().getBytes(StandardCharsets.UTF_8);}
                    else if(!path.endsWith("payload.apk")){status=302;location="/autopilot/payload.apk";}
                    else{
                        String requested=getRequestProperty("Range");assertNotNull(requested);
                        String[] positions=requested.substring(6).split("-");int first=Integer.parseInt(positions[0]),last=Integer.parseInt(positions[1]);
                        status=206;range="bytes "+first+"-"+last+"/"+apk.length;bytes=Arrays.copyOfRange(apk,first,last+1);
                        if(first!=0||last!=0)actualParts.incrementAndGet();
                    }
                    return status;
                }
            };
            connections.add(connection);return connection;
        }));
        UpdateManifest manifest=download.fetchManifest(FEED.toString(),UpdateManifestTest.PACKAGE);
        File result=download.download(manifest,temporary.newFolder());
        assertArrayEquals(apk,Files.readAllBytes(result.toPath()));assertEquals(4,actualParts.get());
        for(Connection connection:connections){assertTrue(connection.closes>=1);if(connection.getURL().getPath().endsWith(".apk"))assertNotNull(connection.getRequestProperty("Range"));}
    }

    @Test(timeout=5000) public void manifestDeadlineClosesBlockedHeaderRequestAndReturnsTimeout()throws Exception{
        BlockingConnection blocked=new BlockingConnection(FEED,false);
        UpdateDownload download=new UpdateDownload(new UpdateDownload.HttpsTransport(()->27000,null,(uri,proxy)->blocked),150);
        long start=System.nanoTime();
        rejects("UPDATE_TIMEOUT",()->download.fetchManifest(FEED.toString(),UpdateManifestTest.PACKAGE));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2500);assertTrue(blocked.closed.get());
        assertNoWatcher();
    }

    @Test public void transientProxyResetRecoversThroughExistingBoundedRangeRetry()throws Exception{
        byte[] apk=new byte[5*1024*1024];for(int i=0;i<apk.length;i++)apk[i]=(byte)(i*17);
        UpdateManifest manifest=UpdateManifestTest.parse(UpdateManifestTest.json().put("sizeBytes",apk.length).put("sha256",hash(apk)));
        AtomicInteger probes=new AtomicInteger();List<Connection> opened=Collections.synchronizedList(new ArrayList<>());
        UpdateDownload download=new UpdateDownload(new UpdateDownload.HttpsTransport(()->27004,null,(uri,proxy)->{
            assertProxy(proxy,27004);
            Connection connection=new Connection(uri){
                boolean prepared;
                @Override public int getResponseCode()throws IOException{
                    if(!prepared){
                        prepared=true;String requested=getRequestProperty("Range");assertNotNull(requested);
                        if("bytes=0-0".equals(requested)&&probes.incrementAndGet()==1)statusFailure=new SocketException("private transient upstream address");
                        else{
                            String[] positions=requested.substring(6).split("-");int first=Integer.parseInt(positions[0]),last=Integer.parseInt(positions[1]);
                            status=206;range="bytes "+first+"-"+last+"/"+apk.length;bytes=Arrays.copyOfRange(apk,first,last+1);
                        }
                    }
                    return super.getResponseCode();
                }
            };
            opened.add(connection);return connection;
        }));
        File result=download.download(manifest,temporary.newFolder());
        assertEquals("The failed probe must retry once using the selected proxy",2,probes.get());
        assertArrayEquals(apk,Files.readAllBytes(result.toPath()));for(Connection connection:opened)assertTrue(connection.closes>=1);
    }

    @Test public void persistentProxyResetStopsAtExistingRetryLimitWithSanitizedCode()throws Exception{
        byte[] apk=new byte[5*1024*1024];
        UpdateManifest manifest=UpdateManifestTest.parse(UpdateManifestTest.json().put("sizeBytes",apk.length).put("sha256",hash(apk)));
        AtomicInteger attempts=new AtomicInteger();
        UpdateDownload download=new UpdateDownload(new UpdateDownload.HttpsTransport(()->27004,null,(uri,proxy)->{
            assertProxy(proxy,27004);attempts.incrementAndGet();throw new SocketException("private upstream address must not escape");
        }));
        File cache=temporary.newFolder();
        rejects("UPDATE_VPN_PROXY_ERROR",()->download.download(manifest,cache));
        assertEquals("Proxy transport failures retain the existing bounded retry budget",3,attempts.get());
        File[] verified=cache.listFiles((directory,name)->name.endsWith(".apk"));assertNotNull(verified);assertEquals(0,verified.length);
    }

    @Test(timeout=5000) public void manifestInterruptionClosesBlockedBodyAndJoinsWatcher()throws Exception{
        BlockingConnection blocked=new BlockingConnection(FEED,true);
        UpdateDownload download=new UpdateDownload(new UpdateDownload.HttpsTransport(()->27000,null,(uri,proxy)->blocked));
        AtomicReference<Throwable> outcome=new AtomicReference<>();
        Thread owner=new Thread(()->{try{download.fetchManifest(FEED.toString(),UpdateManifestTest.PACKAGE);}catch(Throwable failure){outcome.set(failure);}},"updater-interrupt-test");
        owner.start();assertTrue(blocked.reading.await(2,TimeUnit.SECONDS));owner.interrupt();owner.join(2500);
        assertFalse(owner.isAlive());assertTrue(blocked.closed.get());
        assertTrue(String.valueOf(outcome.get()),outcome.get() instanceof UpdateDownload.UpdateException);
        assertEquals("UPDATE_CANCELLED",((UpdateDownload.UpdateException)outcome.get()).code);assertNoWatcher();
    }
    private static void assertNoWatcher(){
        for(Thread thread:Thread.getAllStackTraces().keySet())assertFalse("Manifest cancel watcher leaked",thread.isAlive()&&"Autopilot-update-cancel".equals(thread.getName()));
    }
    private static final class BlockingConnection extends Connection {
        final AtomicBoolean closed=new AtomicBoolean();final CountDownLatch reading=new CountDownLatch(1),disconnected=new CountDownLatch(1);final boolean body;
        BlockingConnection(URI uri,boolean body)throws Exception{super(uri);this.body=body;}
        private void block()throws IOException{
            reading.countDown();
            // Like a platform socket read, interruption alone leaves the flag set;
            // the production watcher must close this request to unblock it.
            while(disconnected.getCount()>0)java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            throw new SocketException("fixture closed");
        }
        @Override public int getResponseCode()throws IOException{if(!body)block();return 200;}
        @Override public long getContentLengthLong(){return -1;}
        @Override public InputStream getInputStream(){return new InputStream(){@Override public int read()throws IOException{block();return -1;}};}
        @Override public void disconnect(){closed.set(true);disconnected.countDown();}
    }
}
