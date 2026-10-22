package app.autopilot.android;

import org.junit.Test;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.cert.Certificate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class ReachabilityChecksTest {
    private static class Response extends HttpsURLConnection {
        final int status;final CountDownLatch wait;final AtomicBoolean disconnected=new AtomicBoolean();
        Response(URL url,int status,CountDownLatch wait){super(url);this.status=status;this.wait=wait;}
        public int getResponseCode()throws IOException{if(wait!=null)try{wait.await();}catch(InterruptedException e){throw new InterruptedIOException();}if(disconnected.get())throw new IOException();return status;}
        public InputStream getInputStream(){return new ByteArrayInputStream(new byte[]{1});}
        public InputStream getErrorStream(){return new ByteArrayInputStream(new byte[]{1});}
        public void disconnect(){disconnected.set(true);if(wait!=null)wait.countDown();}
        public boolean usingProxy(){return true;}public void connect(){}public String getCipherSuite(){return "fixture";}
        public Certificate[] getLocalCertificates(){return null;}public Certificate[] getServerCertificates(){return null;}
    }
    @Test public void firstOriginDeadlineDoesNotEraseCompletedOtherOrigins()throws Exception{
        CountDownLatch google=new CountDownLatch(1);
        try(ReachabilityChecks checks=new ReachabilityChecks(150,(url,proxy)->new Response(url,200,url.getHost().contains("gstatic")?google:null))){
            ConnectionWatchdog.Result result=checks.check(27000);assertEquals("Only the hung Google endpoint failed",1,result.failedMask);assertFalse(result.broadFailure());assertTrue(result.latencyMillis>0);
        }
    }
    @Test public void independentApplicationFailuresAreVisibleWithGoogleHealthy()throws Exception{
        try(ReachabilityChecks checks=new ReachabilityChecks(300,(url,proxy)->new Response(url,url.getHost().contains("gstatic")?204:503,null))){assertEquals(6,checks.check(27000).failedMask);}
    }
    @Test public void authenticationResponsesProveTransportAndUseExplicitLoopbackProxy()throws Exception{
        AtomicInteger opened=new AtomicInteger();
        try(ReachabilityChecks checks=new ReachabilityChecks(300,(url,proxy)->{assertEquals("https",url.getProtocol());assertEquals(Proxy.Type.HTTP,proxy.type());InetSocketAddress address=(InetSocketAddress)proxy.address();assertEquals("127.0.0.1",address.getHostString());assertEquals(27001,address.getPort());opened.incrementAndGet();return new Response(url,403,null);})){assertEquals(0,checks.check(27001).failedMask);assertEquals(3,opened.get());}
    }
    @Test public void interruptionDisconnectsEveryOutstandingRequest()throws Exception{
        CountDownLatch opened=new CountDownLatch(3),blocked=new CountDownLatch(1);AtomicInteger closed=new AtomicInteger();AtomicReference<Throwable> result=new AtomicReference<>();
        try(ReachabilityChecks checks=new ReachabilityChecks(10_000,(url,proxy)->new Response(url,200,blocked){@Override public int getResponseCode()throws IOException{opened.countDown();return super.getResponseCode();}@Override public void disconnect(){super.disconnect();closed.incrementAndGet();}})){
            Thread owner=new Thread(()->{try{checks.check(27000);}catch(Throwable failure){result.set(failure);}});owner.start();assertTrue(opened.await(1,TimeUnit.SECONDS));owner.interrupt();owner.join(1000);assertFalse(owner.isAlive());assertTrue(result.get() instanceof InterruptedException);assertTrue(closed.get()>=3);
        }
    }
}
