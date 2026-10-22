package app.autopilot.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Actual platform HTTPS CONNECT sockets; no synthetic HTTP response, account, TUN or TLS override. */
@RunWith(AndroidJUnit4.class)
public final class ReachabilityRuntimeTest {
    @Test(timeout=20000) public void independentWatchdogBoundsRealConnectsWhileControllerIsBusy()throws Exception{
        CountDownLatch busy=new CountDownLatch(1),release=new CountDownLatch(1),recovered=new CountDownLatch(1);
        ExecutorService controller=Executors.newSingleThreadExecutor();AtomicReference<Thread> owner=new AtomicReference<>();
        controller.execute(()->{owner.set(Thread.currentThread());busy.countDown();try{release.await();}catch(InterruptedException expected){Thread.interrupted();}});
        assertTrue(busy.await(1,TimeUnit.SECONDS));
        try(Blackhole proxy=new Blackhole();ReachabilityChecks transport=new ReachabilityChecks(700,(url,route)->(javax.net.ssl.HttpsURLConnection)url.openConnection(route));
            ConnectionWatchdog watchdog=new ConnectionWatchdog(transport,observation->{if(observation.code==ConnectionWatchdog.Code.CONFIRMED_FAILED){owner.get().interrupt();controller.execute(recovered::countDown);}},System::currentTimeMillis,200,100)){
            watchdog.target(new ConnectionWatchdog.Snapshot(1,"fixture-node",1,proxy.port()));
            assertTrue("A blocked controller may not starve real HTTPS monitoring",recovered.await(8,TimeUnit.SECONDS));
            assertEquals("Recovery yielded discovery rather than waiting for it",1,release.getCount());
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(proxy.closedPeers.get()<6&&System.nanoTime()<until)Thread.sleep(20);
            assertEquals("Two rounds must open all independent origins",6,proxy.accepted.get());
            assertEquals("Absolute deadlines must close every CONNECT socket",6,proxy.closedPeers.get());
            assertNull(proxy.failure.get());
            System.out.println("ANDROID_HEALTH_TRANSPORT_ACCEPTED independentController=true origins=3 rounds=2 confirmed=true absoluteDeadlineClosesConnect=true platformHttpsConnect=true");
        }finally{release.countDown();controller.shutdownNow();assertTrue("Controller leaked",controller.awaitTermination(2,TimeUnit.SECONDS));}
        // Explicit cancellation uses the same actual platform sockets, independently of the watchdog deadline.
        try(Blackhole proxy=new Blackhole();ReachabilityChecks transport=new ReachabilityChecks(10000,(url,route)->(javax.net.ssl.HttpsURLConnection)url.openConnection(route))){
            AtomicReference<Throwable> outcome=new AtomicReference<>();Thread request=new Thread(()->{try{transport.check(proxy.port());}catch(Throwable failure){outcome.set(failure);}},"health-cancel-owner");
            request.start();assertTrue(proxy.threeRequests.await(3,TimeUnit.SECONDS));request.interrupt();request.join(2500);
            assertFalse("Cancelled HTTPS owner leaked",request.isAlive());assertTrue(outcome.get() instanceof InterruptedException);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(proxy.closedPeers.get()<3&&System.nanoTime()<until)Thread.sleep(20);
            assertEquals(3,proxy.closedPeers.get());assertNull(proxy.failure.get());
        }
    }
    private static final class Blackhole implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();final ExecutorService clients=Executors.newCachedThreadPool();
        final AtomicInteger accepted=new AtomicInteger(),closedPeers=new AtomicInteger();final AtomicBoolean closing=new AtomicBoolean();
        final AtomicReference<Throwable> failure=new AtomicReference<>();final CountDownLatch threeRequests=new CountDownLatch(3);final Thread accept;
        Blackhole()throws IOException{
            accept=new Thread(()->{while(!closing.get())try{Socket socket=listener.accept();accepted.incrementAndGet();sockets.add(socket);clients.execute(()->read(socket));}catch(Exception error){if(!closing.get())failure.compareAndSet(null,error);}},"health-blackhole-accept");accept.start();
        }
        int port(){return listener.getLocalPort();}
        void read(Socket socket){try{
            socket.setSoTimeout(5000);InputStream input=socket.getInputStream();ByteArrayOutputStream header=new ByteArrayOutputStream();int matched=0;
            while(header.size()<8192&&matched<4){int value=input.read();if(value<0)throw new EOFException();header.write(value);matched=value=="\r\n\r\n".charAt(matched)?matched+1:value=='\r'?1:0;}
            String request=header.toString(StandardCharsets.US_ASCII.name());assertTrue(request,request.startsWith("CONNECT "));
            assertTrue(request,request.startsWith("CONNECT www.gstatic.com:443 ")||request.startsWith("CONNECT api.openai.com:443 ")||request.startsWith("CONNECT api.anthropic.com:443 "));
            threeRequests.countDown();if(input.read()==-1)closedPeers.incrementAndGet();
        }catch(Throwable error){if(!closing.get())failure.compareAndSet(null,error);}finally{sockets.remove(socket);try{socket.close();}catch(IOException ignored){}}}
        public void close()throws Exception{closing.set(true);listener.close();for(Socket socket:sockets)socket.close();clients.shutdownNow();accept.join(2000);assertFalse(accept.isAlive());assertTrue(clients.awaitTermination(2,TimeUnit.SECONDS));}
    }
}
