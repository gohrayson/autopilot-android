package app.autopilot.android;

import android.os.Build;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Opt-in public-feed probe: platform TLS and the real manifest parser through an observable CONNECT relay. */
@RunWith(AndroidJUnit4.class)
public final class UpdateHttpsTransportRuntimeTest {
    private static final String FEED="https://github.com/gohrayson/autopilot-android/releases/latest/download/latest.json";
    private static final Set<String> HOSTS=Set.of("github.com","release-assets.githubusercontent.com","objects.githubusercontent.com");

    @Test(timeout=60000) public void githubManifestUsesTrustedTlsThroughExplicitConnectProxy()throws Exception{
        Bundle arguments=InstrumentationRegistry.getArguments();
        Assume.assumeTrue("Explicit live public-feed opt-in is required", "true".equals(arguments.getString("updateTransportLive")));
        actualConnectRequestAborts(false);
        actualConnectRequestAborts(true);
        List<String> routes=Collections.synchronizedList(new ArrayList<>());
        try(ConnectRelay relay=new ConnectRelay()){
            UpdateManifest manifest=new UpdateDownload(relay::port,routes::add).fetchManifest(FEED,UpdateIdentity.PACKAGE_NAME);
            assertEquals(UpdateIdentity.PACKAGE_NAME,manifest.packageName);assertTrue(manifest.versionCode>0);
            assertTrue(manifest.sizeBytes>0);assertTrue(manifest.sha256.matches("[0-9a-f]{64}"));
            assertEquals("github.com",URI.create(manifest.apkUrl).getHost());
            assertFalse(routes.isEmpty());for(String route:routes)assertEquals("vpn",route);
            assertTrue("The actual HTTPS request must CONNECT through the explicit loopback route",relay.connects.get()>0);
            assertTrue(relay.hosts.contains("github.com"));
            assertTrue("Actual encrypted TLS request bytes must traverse the relay",relay.sent.get()>0);
            assertTrue("Actual encrypted TLS response bytes must traverse the relay",relay.received.get()>0);
            assertNull("The relay may not synthesize a manifest or ignore forwarding errors",relay.failure.get());
            String receipt="AUTOPILOT_UPDATE_HTTPS_ACCEPTED package="+manifest.packageName+" versionCode="+manifest.versionCode
                    +" connects="+relay.connects.get()+" hosts="+new TreeSet<>(relay.hosts)+" tlsSent="+relay.sent.get()
                    +" tlsReceived="+relay.received.get()+" explicitLoopback=true platformTls=true parsedManifest=true cancelClosesConnect=true deadlineClosesConnect=true SDK="+Build.VERSION.SDK_INT;
            System.out.println(receipt);
            Bundle status=new Bundle();status.putString("stream",receipt+"\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(2,status);
        }
    }

    /** Real platform CONNECT reads must be closed by the session watchdog, including before TLS begins. */
    private static void actualConnectRequestAborts(boolean deadline)throws Exception{
        ServerSocket listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
        AtomicReference<Socket> accepted=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        CountDownLatch request=new CountDownLatch(1),peerClosed=new CountDownLatch(1);AtomicBoolean closing=new AtomicBoolean();
        Thread sink=new Thread(()->{
            try{Socket socket=listener.accept();accepted.set(socket);socket.setSoTimeout(5000);
                assertTrue(ConnectRelay.header(socket.getInputStream()).startsWith("CONNECT github.com:443 "));request.countDown();
                if(socket.getInputStream().read()==-1)peerClosed.countDown();
            }catch(Throwable error){if(!closing.get())failure.compareAndSet(null,error);}
        },"updater-connect-blackhole");sink.setDaemon(true);sink.start();
        AtomicReference<Throwable> outcome=new AtomicReference<>();
        UpdateDownload.Transport transport=new UpdateDownload.HttpsTransport(listener::getLocalPort,null,(uri,proxy)->(javax.net.ssl.HttpsURLConnection)uri.toURL().openConnection(proxy));
        UpdateDownload download=new UpdateDownload(transport,deadline?1500:30000);
        Thread owner=new Thread(()->{try{download.fetchManifest(FEED,UpdateIdentity.PACKAGE_NAME);}catch(Throwable error){outcome.set(error);}},"updater-connect-abort-owner");owner.setDaemon(true);
        try{
            owner.start();assertTrue("Actual CONNECT request did not reach loopback proxy",request.await(3,TimeUnit.SECONDS));
            if(!deadline)owner.interrupt();owner.join(3500);
            assertFalse("The real HTTPS request did not abort promptly",owner.isAlive());
            assertTrue(String.valueOf(outcome.get()),outcome.get() instanceof UpdateDownload.UpdateException);
            assertEquals(deadline?"UPDATE_TIMEOUT":"UPDATE_CANCELLED",((UpdateDownload.UpdateException)outcome.get()).code);
            assertTrue("Abort must close the proxy TCP connection",peerClosed.await(2,TimeUnit.SECONDS));assertNull(failure.get());
        }finally{
            closing.set(true);listener.close();Socket socket=accepted.get();if(socket!=null)socket.close();
            owner.interrupt();owner.join(5000);sink.join(5000);assertFalse("HTTPS abort owner leaked",owner.isAlive());assertFalse("Blackhole proxy leaked",sink.isAlive());
        }
    }

    private static final class ConnectRelay implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));
        final ExecutorService workers=Executors.newCachedThreadPool(action->{Thread thread=new Thread(action,"updater-connect-relay");thread.setDaemon(true);return thread;});
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();final Set<String> hosts=ConcurrentHashMap.newKeySet();
        final AtomicInteger connects=new AtomicInteger();final AtomicLong sent=new AtomicLong(),received=new AtomicLong();
        final AtomicReference<Throwable> failure=new AtomicReference<>();final AtomicBoolean closed=new AtomicBoolean();
        final Thread accept;
        ConnectRelay()throws IOException{
            accept=new Thread(()->{
                while(!closed.get())try{Socket client=listener.accept();sockets.add(client);workers.execute(()->relay(client));}
                catch(IOException|RejectedExecutionException error){if(!closed.get())failure.compareAndSet(null,error);}
            },"updater-connect-accept");accept.setDaemon(true);accept.start();
        }
        int port(){return listener.getLocalPort();}
        private void relay(Socket client){
            Socket upstream=new Socket();sockets.add(upstream);
            try{
                client.setSoTimeout(20000);
                String request=header(client.getInputStream());String first=request.substring(0,request.indexOf("\r\n"));
                String[] fields=first.split(" ");
                if(fields.length!=3||!"CONNECT".equals(fields[0])||!fields[1].endsWith(":443"))throw new IOException("Unexpected CONNECT target");
                String host=fields[1].substring(0,fields[1].length()-4).toLowerCase(Locale.ROOT);
                if(!HOSTS.contains(host))throw new IOException("Unexpected CONNECT host");
                upstream.connect(new InetSocketAddress(host,443),10000);upstream.setSoTimeout(20000);
                client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));client.getOutputStream().flush();
                hosts.add(host);connects.incrementAndGet();
                Future<?> response=workers.submit(()->copy(upstream,client,received));
                copy(client,upstream,sent);
                response.cancel(true);
            }catch(Exception error){if(!closed.get())failure.compareAndSet(null,error);}
            finally{closeSocket(client);closeSocket(upstream);}
        }
        private void copy(Socket source,Socket target,AtomicLong count){
            try{InputStream input=source.getInputStream();OutputStream output=target.getOutputStream();byte[] buffer=new byte[16384];int bytes;
                while((bytes=input.read(buffer))!=-1){output.write(buffer,0,bytes);output.flush();count.addAndGet(bytes);}
            }catch(IOException error){if(!closed.get()&&!source.isClosed()&&!target.isClosed()&&!(error instanceof SocketException))failure.compareAndSet(null,error);}
            finally{try{target.shutdownOutput();}catch(IOException ignored){}}
        }
        private static String header(InputStream input)throws IOException{
            ByteArrayOutputStream output=new ByteArrayOutputStream();int matched=0;
            while(output.size()<8192){int value=input.read();if(value<0)throw new EOFException();output.write(value);
                matched=value=="\r\n\r\n".charAt(matched)?matched+1:value=='\r'?1:0;if(matched==4)return output.toString(StandardCharsets.US_ASCII.name());}
            throw new IOException("Oversized CONNECT header");
        }
        private void closeSocket(Socket socket){sockets.remove(socket);try{socket.close();}catch(IOException ignored){}}
        @Override public void close()throws Exception{
            closed.set(true);listener.close();for(Socket socket:sockets)closeSocket(socket);
            workers.shutdownNow();accept.join(5000);assertFalse("CONNECT accept thread leaked",accept.isAlive());
            assertTrue("CONNECT relay workers leaked",workers.awaitTermination(5,TimeUnit.SECONDS));assertTrue(sockets.isEmpty());
        }
    }
}
