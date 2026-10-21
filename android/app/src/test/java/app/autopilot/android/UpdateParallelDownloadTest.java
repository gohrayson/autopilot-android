package app.autopilot.android;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class UpdateParallelDownloadTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final int SIZE = 6 * 1024 * 1024;
    private static final byte[] PAYLOAD = new byte[SIZE];
    static { for (int i=0;i<SIZE;i++) PAYLOAD[i]=(byte)(i*31+(i>>>8)); }
    private static String hash(byte[] data) throws Exception {
        StringBuilder result=new StringBuilder();
        for(byte value:MessageDigest.getInstance("SHA-256").digest(data))result.append(String.format(Locale.ROOT,"%02x",value&255));
        return result.toString();
    }
    private static UpdateManifest manifest() throws Exception { return manifest("https://updates.example.test/app.apk",hash(PAYLOAD)); }
    private static UpdateManifest manifest(String url,String hash) throws Exception {
        URI uri=URI.create(url);
        return UpdateManifest.parse(new JSONObject().put("schemaVersion",1).put("packageName","app.autopilot.android")
                .put("versionCode",4).put("versionName","0.1.3").put("minSdk",26).put("apkUrl",url)
                .put("sizeBytes",SIZE).put("sha256",hash).toString(),uri.getScheme()+"://"+uri.getHost()+"/latest.json","app.autopilot.android");
    }
    private interface Action { void run() throws Exception; }
    private static void rejects(String expected,Action action)throws Exception{
        try{action.run();fail("Expected "+expected);}catch(UpdateDownload.UpdateException error){assertEquals(expected,error.code);}
    }
    private static UpdateDownload.Response response(int status,long first,long last,String range,long length,String encoding,InputStream body,Runnable close){
        return new UpdateDownload.Response(){
            public int status(){return status;} public long contentLength(){return length;}
            public String contentRange(){return range;} public String contentEncoding(){return encoding;}
            public InputStream body(){return body;} public void close(){close.run();}
        };
    }
    private static UpdateDownload.Response range(long first,long last){return response(206,first,last,"bytes "+first+"-"+last+"/"+SIZE,last-first+1,"identity",new ByteArrayInputStream(PAYLOAD,(int)first,(int)(last-first+1)),()->{});}
    private static class Ranges implements UpdateDownload.Transport{
        final List<long[]> requests=Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger plain=new AtomicInteger();
        public UpdateDownload.Response open(URI uri){plain.incrementAndGet();return response(200,0,SIZE-1,null,SIZE,"identity",new ByteArrayInputStream(PAYLOAD),()->{});}
        public UpdateDownload.Response open(URI uri,long first,long last)throws IOException{requests.add(new long[]{first,last});return range(first,last);}
    }
    private static boolean probe(long first,long last){return first==0&&last==0;}
    private static long requested(List<long[]> ranges){long count=0;for(long[] range:ranges)if(!probe(range[0],range[1]))count+=range[1]-range[0]+1;return count;}

    @Test(timeout=15000) public void fourActualRangesOverlapAndProgressIsSerializedMonotonic()throws Exception{
        File cache=temporary.newFolder();CountDownLatch opened=new CountDownLatch(4);AtomicInteger peak=new AtomicInteger(),active=new AtomicInteger();
        Ranges transport=new Ranges(){public UpdateDownload.Response open(URI uri,long first,long last)throws IOException{
            requests.add(new long[]{first,last});if(probe(first,last))return range(first,last);
            peak.accumulateAndGet(active.incrementAndGet(),Math::max);opened.countDown();
            InputStream input=new ByteArrayInputStream(PAYLOAD,(int)first,(int)(last-first+1)){
                public synchronized int read(byte[] bytes,int offset,int length){try{assertTrue(opened.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}return super.read(bytes,offset,length);}
            };
            return response(206,first,last,"bytes "+first+"-"+last+"/"+SIZE,last-first+1,"identity",input,active::decrementAndGet);
        }};
        AtomicLong previous=new AtomicLong(-1);AtomicInteger callbacks=new AtomicInteger(),maxProgress=new AtomicInteger();
        File complete=new UpdateDownload(transport).download(manifest(),cache,(downloaded,total,connections)->{
            assertEquals(1,callbacks.incrementAndGet());try{assertTrue(downloaded>=previous.getAndSet(downloaded));assertEquals(SIZE,total);assertTrue(connections>=0&&connections<=4);maxProgress.accumulateAndGet(connections,Math::max);}finally{callbacks.decrementAndGet();}
        });
        assertEquals(4,peak.get());assertEquals(4,maxProgress.get());assertEquals(0,active.get());
        assertEquals(SIZE,previous.get());assertEquals(SIZE,requested(transport.requests));assertArrayEquals(PAYLOAD,Files.readAllBytes(complete.toPath()));
        assertEquals(0,new UpdateDownload(transport).resumableBytes(manifest(),cache));
    }

    @Test(timeout=20000) public void interruptionClosesBlockedStreamsAndNewInstanceDownloadsOnlyMissingSuffixes()throws Exception{
        File cache=temporary.newFolder();UpdateManifest release=manifest();CountDownLatch prefixes=new CountDownLatch(4);AtomicInteger closed=new AtomicInteger();
        Ranges first=new Ranges(){public UpdateDownload.Response open(URI uri,long start,long end){
            requests.add(new long[]{start,end});if(probe(start,end))return range(start,end);
            CountDownLatch stopped=new CountDownLatch(1);AtomicBoolean isClosed=new AtomicBoolean();
            InputStream body=new InputStream(){int position=(int)start;boolean reported;
                public int read()throws IOException{byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
                public int read(byte[] bytes,int offset,int length)throws IOException{
                    if(position-start>=65536){if(!reported){reported=true;prefixes.countDown();}try{stopped.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}throw new InterruptedIOException();}
                    int count=Math.min(length,Math.min(32768,65536-(position-(int)start)));System.arraycopy(PAYLOAD,position,bytes,offset,count);position+=count;return count;
                }
            };
            return response(206,start,end,"bytes "+start+"-"+end+"/"+SIZE,end-start+1,"identity",body,()->{if(isClosed.compareAndSet(false,true)){closed.incrementAndGet();stopped.countDown();}});
        }};
        AtomicReference<Throwable> failure=new AtomicReference<>();UpdateDownload old=new UpdateDownload(first);
        Thread owner=new Thread(()->{try{old.download(release,cache);}catch(Throwable error){failure.set(error);}});
        owner.start();assertTrue(prefixes.await(5,TimeUnit.SECONDS));owner.interrupt();owner.join(5000);
        assertFalse("Cancellation leaked coordinator/workers",owner.isAlive());assertEquals(4,closed.get());
        assertTrue(failure.get() instanceof UpdateDownload.UpdateException);assertEquals("UPDATE_CANCELLED",((UpdateDownload.UpdateException)failure.get()).code);
        long cached=old.resumableBytes(release,cache);assertEquals(4L*65536,cached);assertFalse(new File(cache,"autopilot-update-4.apk").exists());
        Ranges next=new Ranges();AtomicLong initial=new AtomicLong(-1);
        File complete=new UpdateDownload(next).download(release,cache,(bytes,total,connections)->initial.compareAndSet(-1,bytes));
        assertEquals(cached,initial.get());assertEquals(SIZE-cached,requested(next.requests));
        for(long[] request:next.requests)if(!probe(request[0],request[1]))assertEquals(65536,request[0]%(SIZE/4));
        assertArrayEquals(PAYLOAD,Files.readAllBytes(complete.toPath()));
    }

    @Test public void rangeUnsupportedFallsBackOnceWithoutAppendingTheProbeBody()throws Exception{
        File cache=temporary.newFolder();AtomicInteger rangeRequests=new AtomicInteger();Ranges transport=new Ranges(){
            public UpdateDownload.Response open(URI uri,long first,long last){rangeRequests.incrementAndGet();return open(uri);}
        };
        File complete=new UpdateDownload(transport).download(manifest(),cache);
        assertEquals(1,rangeRequests.get());assertEquals(2,transport.plain.get());assertArrayEquals(PAYLOAD,Files.readAllBytes(complete.toPath()));
        assertEquals(1,cache.list().length);
    }

    @Test public void wrongRangeLengthEncodingAndOversizeDiscardOwnedResumeState()throws Exception{
        for(String mode:new String[]{"range","length","encoding","oversized"}){
            File cache=temporary.newFolder();Ranges transport=new Ranges(){public UpdateDownload.Response open(URI uri,long first,long last){
                if(probe(first,last)){
                    if(mode.equals("range"))return response(206,0,0,"bytes 0-0/"+(SIZE+1),1,"identity",new ByteArrayInputStream(new byte[]{1}),()->{});
                    if(mode.equals("length"))return response(206,0,0,"bytes 0-0/"+SIZE,2,"identity",new ByteArrayInputStream(new byte[]{1}),()->{});
                    if(mode.equals("encoding"))return response(206,0,0,"bytes 0-0/"+SIZE,1,"gzip",new ByteArrayInputStream(new byte[]{1}),()->{});
                    return range(first,last);
                }
                int actual=(int)(last-first+1)+(mode.equals("oversized")?1:-1);
                return response(206,first,last,"bytes "+first+"-"+last+"/"+SIZE,last-first+1,"identity",new ByteArrayInputStream(new byte[actual]),()->{});
            }};
            String code=mode.equals("range")?"UPDATE_RANGE_INVALID":mode.equals("encoding")?"UPDATE_RESPONSE_ENCODING":"UPDATE_RESPONSE_SIZE";
            rejects(code,()->new UpdateDownload(transport).download(manifest(),cache));
            assertEquals(mode,0,cache.list().length);
        }
    }

    @Test public void wholeHashFailureNeverReplacesPreviouslyVerifiedApk()throws Exception{
        File cache=temporary.newFolder();File existing=new File(cache,"autopilot-update-4.apk");byte[] old={1,2,3};Files.write(existing.toPath(),old);
        rejects("UPDATE_HASH_MISMATCH",()->new UpdateDownload(new Ranges()).download(manifest("https://updates.example.test/app.apk","0".repeat(64)),cache));
        assertArrayEquals(old,Files.readAllBytes(existing.toPath()));assertEquals(1,cache.list().length);
    }

    @Test public void serverFailuresHaveThreeAttemptBudgetBut429NeverRetries()throws Exception{
        for(int status:new int[]{503,429}){
            File cache=temporary.newFolder();AtomicInteger attempts=new AtomicInteger();Ranges transport=new Ranges(){public UpdateDownload.Response open(URI uri,long first,long last){attempts.incrementAndGet();return response(status,first,last,null,0,"identity",new ByteArrayInputStream(new byte[0]),()->{});}};
            rejects("UPDATE_HTTP_ERROR",()->new UpdateDownload(transport).download(manifest(),cache));
            assertEquals(status==503?3:1,attempts.get());
        }
    }

    @Test public void interruptedNetworkPrefixRetriesFromCurrentOffsetRatherThanFromZero()throws Exception{
        File cache=temporary.newFolder();AtomicBoolean failed=new AtomicBoolean();Ranges transport=new Ranges(){public UpdateDownload.Response open(URI uri,long first,long last){
            requests.add(new long[]{first,last});if(first==0&&last>0&&failed.compareAndSet(false,true)){
                InputStream body=new InputStream(){int offset;public int read()throws IOException{throw new IOException();}public int read(byte[] bytes,int at,int length)throws IOException{if(offset==65536)throw new IOException("public network interrupted");int count=Math.min(32768,Math.min(length,65536-offset));System.arraycopy(PAYLOAD,offset,bytes,at,count);offset+=count;return count;}};
                return response(206,first,last,"bytes "+first+"-"+last+"/"+SIZE,last-first+1,"identity",body,()->{});
            }return range(first,last);
        }};
        File complete=new UpdateDownload(transport).download(manifest(),cache);
        assertTrue(transport.requests.stream().anyMatch(range->range[0]==65536));assertArrayEquals(PAYLOAD,Files.readAllBytes(complete.toPath()));
    }

    @Test public void prematureRangeEofRetriesOnlyMissingSuffixAndPublishesAfterFullHash()throws Exception{
        File cache=temporary.newFolder();AtomicBoolean shortened=new AtomicBoolean();Ranges transport=new Ranges(){public UpdateDownload.Response open(URI uri,long first,long last){
            requests.add(new long[]{first,last});assertFalse(new File(cache,"autopilot-update-4.apk").exists());
            if(first==0&&last>0&&shortened.compareAndSet(false,true))return response(206,first,last,"bytes "+first+"-"+last+"/"+SIZE,last-first+1,"identity",new ByteArrayInputStream(PAYLOAD,0,65536),()->{});
            return range(first,last);
        }};
        File complete=new UpdateDownload(transport).download(manifest(),cache);
        assertTrue(transport.requests.stream().anyMatch(request->request[0]==65536));
        assertArrayEquals(PAYLOAD,Files.readAllBytes(complete.toPath()));
        assertEquals(hash(PAYLOAD),hash(Files.readAllBytes(complete.toPath())));
    }

    @Test public void repeatedPrematureEofKeepsBoundedPrefixesForNextInstance()throws Exception{
        File cache=temporary.newFolder();UpdateManifest release=manifest();AtomicInteger attempts=new AtomicInteger();
        Ranges shortened=new Ranges(){public UpdateDownload.Response open(URI uri,long first,long last){
            if(probe(first,last))return range(first,last);attempts.incrementAndGet();
            return response(206,first,last,"bytes "+first+"-"+last+"/"+SIZE,last-first+1,"identity",new ByteArrayInputStream(PAYLOAD,(int)first,(int)Math.min(65536,last-first+1)),()->{});
        }};
        UpdateDownload failed=new UpdateDownload(shortened);
        rejects("UPDATE_NETWORK_ERROR",()->failed.download(release,cache));
        long cached=failed.resumableBytes(release,cache);assertTrue(cached>0&&cached<SIZE);assertFalse(new File(cache,"autopilot-update-4.apk").exists());
        assertTrue(attempts.get()<=12);
        Ranges resumed=new Ranges();File complete=new UpdateDownload(resumed).download(release,cache);
        assertEquals(SIZE-cached,requested(resumed.requests));assertArrayEquals(PAYLOAD,Files.readAllBytes(complete.toPath()));
    }

    @Test public void cachedRangesDiscardedForFullFallbackEmitExplicitZeroIdleReset()throws Exception{
        File cache=temporary.newFolder();UpdateManifest release=manifest();
        Ranges shortened=new Ranges(){public UpdateDownload.Response open(URI uri,long first,long last){
            if(probe(first,last))return range(first,last);
            return response(206,first,last,"bytes "+first+"-"+last+"/"+SIZE,last-first+1,"identity",new ByteArrayInputStream(PAYLOAD,(int)first,65536),()->{});
        }};
        UpdateDownload failed=new UpdateDownload(shortened);rejects("UPDATE_NETWORK_ERROR",()->failed.download(release,cache));
        long cached=failed.resumableBytes(release,cache);assertTrue(cached>0);
        AtomicBoolean reset=new AtomicBoolean();AtomicLong initial=new AtomicLong(-1);AtomicInteger full=new AtomicInteger();
        Ranges unsupported=new Ranges(){
            public UpdateDownload.Response open(URI uri){if(full.incrementAndGet()==2){assertTrue(reset.get());assertFalse(new File(cache,"autopilot-update-4.apk").exists());}return super.open(uri);}
            public UpdateDownload.Response open(URI uri,long first,long last){return open(uri);}
        };
        File complete=new UpdateDownload(unsupported).download(release,cache,(bytes,total,connections)->{
            initial.compareAndSet(-1,bytes);if(bytes==0&&connections==0&&initial.get()>0)reset.set(true);
        });
        assertEquals(cached,initial.get());assertTrue(reset.get());assertArrayEquals(PAYLOAD,Files.readAllBytes(complete.toPath()));
    }

    @Test public void workerReceiving200AfterAcceptedProbeFallsBackWithoutMixedRangeBytes()throws Exception{
        File cache=temporary.newFolder();AtomicInteger probes=new AtomicInteger();Ranges transport=new Ranges(){
            public UpdateDownload.Response open(URI uri,long first,long last){if(probe(first,last)){probes.incrementAndGet();return range(first,last);}return open(uri);}
        };
        File complete=new UpdateDownload(transport).download(manifest(),cache);
        assertEquals(1,probes.get());assertArrayEquals(PAYLOAD,Files.readAllBytes(complete.toPath()));assertEquals(1,cache.list().length);
    }

    @Test public void rangeIsPropagatedAcrossAllowedRedirectsAndRetriesRestartAtStableUrl()throws Exception{
        File cache=temporary.newFolder();UpdateManifest release=manifest("https://github.com/gohrayson/autopilot-android/releases/download/v0.1.3/app.apk",hash(PAYLOAD));
        List<String> starts=Collections.synchronizedList(new ArrayList<>());AtomicInteger targets=new AtomicInteger();
        Ranges transport=new Ranges(){public UpdateDownload.Response open(URI uri,long first,long last){
            if(uri.getHost().equals("github.com")){
                starts.add(first+"-"+last);
                return new UpdateDownload.Response(){public int status(){return 302;}public long contentLength(){return 0;}public String location(){return "https://release-assets.githubusercontent.com/app.apk?temporary-signature=not-persisted";}public InputStream body(){throw new AssertionError();}public void close(){}};
            }
            targets.incrementAndGet();assertEquals("release-assets.githubusercontent.com",uri.getHost());
            return range(first,last);
        }};
        File complete=new UpdateDownload(transport).download(release,cache);
        assertEquals(5,starts.size());assertEquals(5,targets.get());assertArrayEquals(PAYLOAD,Files.readAllBytes(complete.toPath()));
    }

    @Test public void changedManifestHasSeparateCacheAndDiscardDoesNotTouchOtherIdentityOrApk()throws Exception{
        File cache=temporary.newFolder();UpdateManifest first=manifest(),second=manifest("https://updates.example.test/another.apk",hash(PAYLOAD));
        AtomicInteger calls=new AtomicInteger();Ranges failing=new Ranges(){public UpdateDownload.Response open(URI uri,long start,long end)throws IOException{
            if(probe(start,end))return range(start,end);calls.incrementAndGet();throw new IOException();
        }};
        UpdateDownload downloader=new UpdateDownload(failing);
        rejects("UPDATE_NETWORK_ERROR",()->downloader.download(first,cache));
        File[] initial=cache.listFiles();assertNotNull(initial);assertEquals(1,initial.length);String original=initial[0].getName();
        rejects("UPDATE_NETWORK_ERROR",()->downloader.download(second,cache));assertEquals(2,cache.list().length);
        File apk=new File(cache,"autopilot-update-4.apk");Files.write(apk.toPath(),new byte[]{1});
        downloader.discardResume(second,cache);assertTrue(new File(cache,original).isDirectory());assertTrue(apk.isFile());assertEquals(2,cache.list().length);
        assertEquals(0,downloader.resumableBytes(second,cache));
    }

    @Test public void discardingBeforeFirstDownloadDoesNotCreateMissingCacheButRejectsUnsafeRoots()throws Exception{
        File parent=temporary.newFolder(),missing=new File(parent,"never-created-update-cache");
        UpdateDownload downloader=new UpdateDownload(uri->{throw new AssertionError("Local discard must never connect");});
        downloader.discardResume(manifest(),missing);assertFalse(missing.exists());assertEquals(0,parent.list().length);
        rejects("UPDATE_CACHE_ERROR",()->downloader.discardResume(manifest(),null));
        File ordinary=new File(parent,"ordinary-file");Files.write(ordinary.toPath(),new byte[]{1,2});
        rejects("UPDATE_CACHE_ERROR",()->downloader.discardResume(manifest(),ordinary));assertArrayEquals(new byte[]{1,2},Files.readAllBytes(ordinary.toPath()));
    }

    @Test public void fallbackOpeningFailureAlwaysReportsNoActiveConnection()throws Exception{
        File cache=temporary.newFolder();AtomicInteger lastActive=new AtomicInteger(-1);
        Ranges unsupported=new Ranges(){
            public UpdateDownload.Response open(URI uri,long first,long last){return response(200,0,SIZE-1,null,SIZE,"identity",new ByteArrayInputStream(PAYLOAD),()->{});}
            public UpdateDownload.Response open(URI uri){throw new UncheckedIOException(new IOException());}
        };
        // Use a checked transport failure while retaining the range-only 200 probe.
        UpdateDownload.Transport transport=new UpdateDownload.Transport(){
            public UpdateDownload.Response open(URI uri)throws IOException{throw new IOException("public network failure");}
            public UpdateDownload.Response open(URI uri,long first,long last)throws IOException{return unsupported.open(uri,first,last);}
        };
        rejects("UPDATE_NETWORK_ERROR",()->new UpdateDownload(transport).download(manifest(),cache,(bytes,total,active)->lastActive.set(active)));
        assertEquals(0,lastActive.get());assertEquals(0,cache.list().length);
    }
}
