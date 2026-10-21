package app.autopilot.android;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class ServiceChecksTest {
    @Test public void claudeUsesMeasuredRedirectAndChallengeSignals(){
        assertEquals("ok",ServiceChecks.claude(302,"/login", ""));
        assertEquals("blocked",ServiceChecks.claude(302,"/app-unavailable-in-region", ""));
        assertEquals("unknown",ServiceChecks.claude(302,"/unexpected", ""));
        assertEquals("ok",ServiceChecks.claude(200,null,""));
        assertEquals("challenged",ServiceChecks.claude(403,null,"Just a moment cf-chl"));
        assertEquals("unknown",ServiceChecks.claude(403,null,"other failure"));
    }
    @Test public void openAiDistinguishesRegionBlocksFromDatacenterChallenges(){
        assertEquals("ok",ServiceChecks.chatgpt(200,"{\"cookie_consent_required\":false}"));
        assertEquals("blocked",ServiceChecks.chatgpt(403,"unsupported_country_region_territory"));
        assertEquals("challenged",ServiceChecks.chatgpt(403,"challenge"));
        assertEquals("unknown",ServiceChecks.chatgpt(200,"unrecognized response"));
        assertEquals("unknown",ServiceChecks.chatgpt(500,null));
    }
    @Test public void geminiRequiresActualGoogleExitCountryMarker(){
        assertEquals("ok",ServiceChecks.gemini("body ,2,1,200,\"MYS\" more"));
        for(String c:new String[]{"CHN","HKG","MAC","RUS","BLR","CUB","IRN","PRK","SYR"})assertEquals("blocked",ServiceChecks.gemini(",2,1,200,\""+c+"\""));
        assertEquals("unknown",ServiceChecks.gemini("Google Gemini is available"));
        assertEquals("unknown",ServiceChecks.gemini(null));
    }
    @Test(timeout=10000) public void stalledConnectRequestsCancelWithoutWaitingForReadTimeout() throws Exception {
        // A real loopback HTTP proxy accepts CONNECT and deliberately never responds.
        // This exercises the same blocked network hop that Stop and country changes must cancel.
        try(ServerSocket listener=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));ServiceChecks checks=new ServiceChecks()){
            List<Socket> sockets=Collections.synchronizedList(new ArrayList<>());
            Set<String> targets=ConcurrentHashMap.newKeySet();CountDownLatch requests=new CountDownLatch(3),returned=new CountDownLatch(1);
            ExecutorService acceptor=Executors.newSingleThreadExecutor();ExecutorService readers=Executors.newFixedThreadPool(3);
            try{
                acceptor.submit(()->{try{for(int i=0;i<3;i++){Socket socket=listener.accept();sockets.add(socket);readers.submit(()->{
                    try{BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String line=in.readLine();if(line!=null)targets.add(line);
                        while((line=in.readLine())!=null&&!line.isEmpty()){}requests.countDown();
                    }catch(IOException ignored){}
                });}}catch(IOException ignored){}});
                Thread checking=new Thread(()->{try{checks.check(listener.getLocalPort());}catch(InterruptedException expected){}finally{returned.countDown();}},"service-check-cancel-test");
                checking.start();assertTrue("HTTPS checks must use three per-node CONNECTs",requests.await(4,TimeUnit.SECONDS));
                long before=System.nanoTime();checking.interrupt();
                assertTrue("Cancellation must promptly release the engine worker",returned.await(2,TimeUnit.SECONDS));
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<2000);
                assertTrue(targets.stream().anyMatch(s->s.startsWith("CONNECT claude.ai:443 ")));
                assertTrue(targets.stream().anyMatch(s->s.startsWith("CONNECT api.openai.com:443 ")));
                assertTrue(targets.stream().anyMatch(s->s.startsWith("CONNECT gemini.google.com:443 ")));
            }finally{for(Socket socket:sockets)socket.close();acceptor.shutdownNow();readers.shutdownNow();}
        }
    }
}
