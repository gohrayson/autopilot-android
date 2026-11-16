package app.autopilot.android;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.security.cert.Certificate;
import java.util.concurrent.atomic.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import static org.junit.Assert.*;

public class NetworkExitProbeTest {
    private static class Connection extends HttpsURLConnection {
        final int response;final byte[] body;boolean disconnected;
        Connection(URL target,int response,String body){super(target);this.response=response;this.body=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
        public void connect(){}public void disconnect(){disconnected=true;}public boolean usingProxy(){return true;}
        public int getResponseCode(){return response;}public InputStream getInputStream(){return new ByteArrayInputStream(body);}
        public String getCipherSuite(){return "fixture";}public Certificate[] getLocalCertificates(){return null;}public Certificate[] getServerCertificates(){return null;}
    }
    @Test public void fixedHttpsIdentityUsesExactSelectedNodeProxyAndPreservesTlsDefaults()throws Exception{
        AtomicReference<Proxy> proxy=new AtomicReference<>();AtomicReference<Connection> connection=new AtomicReference<>();
        try(NetworkExitProbe probe=new NetworkExitProbe((url,through)->{assertEquals(NetworkExitProbe.URL,url.toString());proxy.set(through);Connection next=new Connection(url,200,"ip=8.8.8.8\nloc=JP\n");connection.set(next);return next;})){
            assertEquals("JP",probe.check(27351).country);
            assertEquals(Proxy.Type.HTTP,proxy.get().type());InetSocketAddress selected=(InetSocketAddress)proxy.get().address();assertEquals("127.0.0.1",selected.getHostString());assertEquals(27351,selected.getPort());
            assertFalse(connection.get().getInstanceFollowRedirects());assertEquals(HttpsURLConnection.getDefaultHostnameVerifier(),connection.get().getHostnameVerifier());assertTrue(connection.get().disconnected);
        }
    }
    @Test public void redirectsOversizeAndTlsFailuresAreUnavailableRatherThanDirectFallback()throws Exception{
        for(int response:new int[]{301,302,403,500})try(NetworkExitProbe probe=new NetworkExitProbe((url,proxy)->new Connection(url,response,"ip=8.8.8.8\nloc=JP"))){try{probe.check(27351);fail("Must reject "+response);}catch(IOException expected){}}
        try(NetworkExitProbe probe=new NetworkExitProbe((url,proxy)->new Connection(url,200,"x".repeat(8193)))){try{probe.check(27351);fail("oversize");}catch(IOException expected){}}
        AtomicInteger calls=new AtomicInteger();try(NetworkExitProbe probe=new NetworkExitProbe((url,proxy)->{calls.incrementAndGet();throw new SSLHandshakeException("fixture");})){try{probe.check(27351);fail("TLS");}catch(SSLHandshakeException expected){}assertEquals(1,calls.get());}
    }
    @Test public void cancelOrCloseDuringConnectionCreationDisconnectsWithoutSending()throws Exception{
        for(boolean close:new boolean[]{false,true}){
            CountDownLatch opened=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger sent=new AtomicInteger();AtomicReference<Connection> created=new AtomicReference<>();
            NetworkExitProbe probe=new NetworkExitProbe((url,through)->{Connection value=new Connection(url,200,"ip=8.8.8.8\nloc=JP"){@Override public int getResponseCode(){sent.incrementAndGet();return 200;}};created.set(value);opened.countDown();try{if(!release.await(2,TimeUnit.SECONDS))throw new IOException("fixture timeout");}catch(InterruptedException ex){throw new IOException(ex);}return value;});
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try{Future<?> result=worker.submit(()->{try{probe.check(27351);throw new AssertionError("Cancelled lookup must not complete");}catch(InterruptedException expected){}catch(Exception ex){throw new RuntimeException(ex);}});
                assertTrue(opened.await(1,TimeUnit.SECONDS));if(close)probe.close();else probe.cancel();release.countDown();result.get(2,TimeUnit.SECONDS);
                assertTrue(created.get().disconnected);assertEquals(0,sent.get());
            }finally{release.countDown();probe.close();worker.shutdownNow();}
        }
    }
}
