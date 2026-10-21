package app.autopilot.android;

import android.content.Context;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real Android JSON, filesystem, threads, merge and checksum; injected HTTPS only. */
@RunWith(AndroidJUnit4.class)
public final class UpdateDownloadRuntimeTest {
    private static final int SIZE=6*1024*1024;
    private static final int PREFIX=64*1024;
    private static final String FEED="https://updates.example.org/autopilot-fixture/latest.json";
    private static final String APK="https://updates.example.org/autopilot-fixture/app.apk";

    @Test(timeout=90000) public void concurrentRangesPublishOnlyCompleteVerifiedBytes()throws Exception{
        File directory=privateDirectory();
        RangeTransport transport=new RangeTransport(false);
        AtomicReference<File> result=new AtomicReference<>();
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Progress progress=new Progress();
        Thread owner=null;
        try{
            UpdateDownload downloader=new UpdateDownload(transport);
            UpdateManifest manifest=downloader.fetchManifest(FEED,UpdateIdentity.PACKAGE_NAME);
            owner=startDownload(downloader,manifest,directory,progress,result,failure);
            assertTrue("Four independent ranged responses must open before any payload is released",transport.parallelOpened.await(15,TimeUnit.SECONDS));
            assertEquals(4,transport.maximumActive.get());
            assertNoVerifiedApk(directory);
            transport.releaseReads.countDown();
            finish(owner,failure);
            assertNotNull(result.get());
            assertArrayEquals(transport.payload,Files.readAllBytes(result.get().toPath()));
            assertEquals(manifest.sha256,sha256(Files.readAllBytes(result.get().toPath())));
            assertEquals(SIZE,result.get().length());
            assertEquals(SIZE,progress.lastBytes.get());
            assertTrue("Progress must expose concurrent connections",progress.maximumActive.get()>1);
            assertEquals(4,transport.partRequests.size());
            assertEquals(SIZE,requestedBytes(transport.partRequests));
            assertEquals("Range probe must occur exactly once",1,transport.probes.get());
            System.out.println("AUTOPILOT_ANDROID_MULTIPART_ACCEPTED bytes="+result.get().length()+" concurrent="+transport.maximumActive.get()+" sha256="+manifest.sha256+" SDK="+Build.VERSION.SDK_INT);
        }finally{
            stop(owner,transport);
            deleteOwnedDirectory(directory);
        }
    }

    @Test(timeout=90000) public void interruptedPrefixesResumeAcrossDownloaderInstances()throws Exception{
        File directory=privateDirectory();
        RangeTransport interrupted=new RangeTransport(true),resumed=new RangeTransport(false);
        Thread first=null,second=null;
        AtomicReference<File> firstResult=new AtomicReference<>(),secondResult=new AtomicReference<>();
        AtomicReference<Throwable> firstFailure=new AtomicReference<>(),secondFailure=new AtomicReference<>();
        try{
            UpdateDownload initialDownloader=new UpdateDownload(interrupted);
            UpdateManifest manifest=initialDownloader.fetchManifest(FEED,UpdateIdentity.PACKAGE_NAME);
            first=startDownload(initialDownloader,manifest,directory,new Progress(),firstResult,firstFailure);
            assertTrue(interrupted.parallelOpened.await(15,TimeUnit.SECONDS));
            interrupted.releaseReads.countDown();
            // Each stream reaches its second read only after the preceding prefix was
            // returned to, and written by, the production downloader.
            assertTrue("All part prefixes must have reached the filesystem",interrupted.prefixesWritten.await(15,TimeUnit.SECONDS));
            assertNoVerifiedApk(directory);
            first.interrupt();
            first.join(15000);
            assertFalse("Cancellation must join the downloader and its workers",first.isAlive());
            assertNull(firstResult.get());
            assertTrue(String.valueOf(firstFailure.get()),firstFailure.get() instanceof UpdateDownload.UpdateException);
            assertEquals("UPDATE_CANCELLED",((UpdateDownload.UpdateException)firstFailure.get()).code);
            assertNoVerifiedApk(directory);

            // A completely new instance must recover persisted metadata and ranges.
            UpdateDownload restarted=new UpdateDownload(resumed);
            long cached=restarted.resumableBytes(manifest,directory);
            assertTrue("Cancellation must retain nonzero verified range prefixes",cached>0);
            assertTrue(cached<SIZE);
            Progress progress=new Progress();
            resumed.releaseReads.countDown();
            second=startDownload(restarted,manifest,directory,progress,secondResult,secondFailure);
            finish(second,secondFailure);
            assertEquals("Initial progress must restore cached bytes before network activity",cached,progress.firstBytes.get());
            assertEquals(SIZE,progress.lastBytes.get());
            assertEquals("Only missing suffixes may be downloaded",SIZE-cached,requestedBytes(resumed.partRequests));
            assertTrue("Resume must request at least one missing suffix",resumed.partRequests.size()>0);
            assertTrue("Completed ranges must not be requested again",resumed.partRequests.size()<=interrupted.partRequests.size());
            for(Range request:resumed.partRequests){
                Range original=null;
                for(Range candidate:interrupted.partRequests)if(candidate.end==request.end)original=candidate;
                assertNotNull("A resumed suffix must belong to an original range",original);
                assertTrue("A persisted prefix must never be requested again",request.start>original.start);
            }
            assertNotNull(secondResult.get());
            assertArrayEquals(resumed.payload,Files.readAllBytes(secondResult.get().toPath()));
            assertEquals(manifest.sha256,sha256(Files.readAllBytes(secondResult.get().toPath())));
            System.out.println("AUTOPILOT_ANDROID_RESUME_ACCEPTED cached="+cached+" network="+requestedBytes(resumed.partRequests)+" total="+secondResult.get().length()+" SDK="+Build.VERSION.SDK_INT);
        }finally{
            stop(first,interrupted);
            stop(second,resumed);
            deleteOwnedDirectory(directory);
        }
    }

    private static Thread startDownload(UpdateDownload downloader,UpdateManifest manifest,File directory,Progress progress,AtomicReference<File> result,AtomicReference<Throwable> failure){
        Thread thread=new Thread(()->{try{result.set(downloader.download(manifest,directory,progress));}catch(Throwable error){failure.set(error);}},"android-download-runtime-owner");
        thread.start();return thread;
    }
    private static void finish(Thread owner,AtomicReference<Throwable> failure)throws Exception{
        owner.join(30000);assertFalse("Download did not terminate",owner.isAlive());
        if(failure.get()!=null)throw new AssertionError("Production download failed",failure.get());
    }
    private static void stop(Thread owner,RangeTransport transport)throws Exception{
        transport.releaseReads.countDown();transport.releaseBlocked.countDown();
        if(owner!=null&&owner.isAlive()){owner.interrupt();owner.join(15000);assertFalse("Owned download thread leaked",owner.isAlive());}
    }
    private static File privateDirectory()throws IOException{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File result=new File(context.getCacheDir(),"update-download-runtime-"+UUID.randomUUID());
        assertTrue(result.mkdir());return result;
    }
    private static void assertNoVerifiedApk(File directory){
        File[] entries=directory.listFiles();assertNotNull(entries);
        for(File entry:entries)assertFalse("Unverified bytes must not be exposed as a complete APK",entry.getName().endsWith(".apk"));
    }
    private static void deleteOwnedDirectory(File directory)throws IOException{
        File parent=InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir().getCanonicalFile();
        assertEquals(parent,directory.getCanonicalFile().getParentFile());
        assertTrue(directory.getName().startsWith("update-download-runtime-"));
        deleteChild(directory,directory.getCanonicalPath());
    }
    private static void deleteChild(File file,String root)throws IOException{
        String path=file.getCanonicalPath();
        assertTrue("Cleanup escaped owned fixture directory",path.equals(root)||path.startsWith(root+File.separator));
        File[] children=file.listFiles();if(children!=null)for(File child:children)deleteChild(child,root);
        assertTrue("Could not remove owned fixture file",!file.exists()||file.delete());
    }
    private static long requestedBytes(List<Range> requests){long bytes=0;synchronized(requests){for(Range range:requests)bytes+=range.end-range.start+1;}return bytes;}
    private static String sha256(byte[] bytes)throws Exception{
        StringBuilder hash=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(Locale.ROOT,"%02x",value&255));return hash.toString();
    }
    private static final class Range{final long start,end;Range(long start,long end){this.start=start;this.end=end;}}
    private static final class Progress implements UpdateDownload.ProgressListener{
        final AtomicLong firstBytes=new AtomicLong(-1),lastBytes=new AtomicLong();
        final AtomicInteger maximumActive=new AtomicInteger();
        @Override public void onProgress(long downloaded,long total,int active){
            assertEquals(SIZE,total);assertTrue(downloaded>=0&&downloaded<=total);assertTrue(active>=0&&active<=4);
            firstBytes.compareAndSet(-1,downloaded);long previous=lastBytes.getAndSet(downloaded);assertTrue("Progress regressed",downloaded>=previous);
            maximumActive.accumulateAndGet(active,Math::max);
        }
    }
    private static final class RangeTransport implements UpdateDownload.Transport{
        final byte[] payload=new byte[SIZE];
        final byte[] manifest;
        final boolean interruptAfterPrefix;
        final List<Range> partRequests=Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger active=new AtomicInteger(),maximumActive=new AtomicInteger(),probes=new AtomicInteger();
        final CountDownLatch parallelOpened=new CountDownLatch(4),releaseReads=new CountDownLatch(1),prefixesWritten=new CountDownLatch(4),releaseBlocked=new CountDownLatch(1);
        RangeTransport(boolean interruptAfterPrefix)throws Exception{
            this.interruptAfterPrefix=interruptAfterPrefix;
            for(int i=0;i<payload.length;i++)payload[i]=(byte)((i*31+(i>>>8)*17)&255);
            manifest=new JSONObject().put("schemaVersion",1).put("packageName",UpdateIdentity.PACKAGE_NAME).put("versionCode",2147483000).put("versionName","99.0.0-runtime").put("minSdk",26).put("apkUrl",APK).put("sha256",sha256(payload)).put("sizeBytes",SIZE).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        @Override public UpdateDownload.Response open(URI uri)throws IOException{
            assertEquals("Only the injected public manifest may use an un-ranged request",FEED,uri.toASCIIString());
            return response(manifest,0,manifest.length-1,false,false);
        }
        @Override public UpdateDownload.Response open(URI uri,long start,long end)throws IOException{
            assertEquals(APK,uri.toASCIIString());assertTrue(start>=0&&end>=start&&end<SIZE);
            boolean probe=start==0&&end==0;
            if(probe)probes.incrementAndGet();
            else{partRequests.add(new Range(start,end));maximumActive.accumulateAndGet(active.incrementAndGet(),Math::max);parallelOpened.countDown();}
            return response(payload,(int)start,(int)end,true,!probe);
        }
        private UpdateDownload.Response response(byte[] bytes,int start,int end,boolean ranged,boolean part){
            AtomicBoolean closed=new AtomicBoolean();
            return new UpdateDownload.Response(){
                @Override public int status(){return ranged?206:200;}
                @Override public long contentLength(){return end-start+1L;}
                @Override public String contentRange(){return ranged?"bytes "+start+"-"+end+"/"+bytes.length:null;}
                @Override public String contentEncoding(){return "identity";}
                @Override public InputStream body(){return new InputStream(){
                    int position=start;
                    boolean prefixReported;
                    @Override public int read()throws IOException{byte[] value=new byte[1];return read(value,0,1)==-1?-1:value[0]&255;}
                    @Override public int read(byte[] output,int offset,int length)throws IOException{
                        if(length==0)return 0;
                        if(closed.get())throw new InterruptedIOException("Fixture response closed");
                        if(part){
                            await(releaseReads);
                            if(interruptAfterPrefix&&position-start>=PREFIX){
                                if(!prefixReported){prefixReported=true;prefixesWritten.countDown();}
                                await(releaseBlocked);
                            }
                        }
                        if(closed.get()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Fixture cancelled");
                        if(position>end)return -1;
                        int count=Math.min(Math.min(length,16*1024),end-position+1);
                        System.arraycopy(bytes,position,output,offset,count);position+=count;return count;
                    }
                };}
                @Override public void close(){if(closed.compareAndSet(false,true)&&part){active.decrementAndGet();releaseBlocked.countDown();}}
            };
        }
        private static void await(CountDownLatch latch)throws IOException{
            try{if(!latch.await(30,TimeUnit.SECONDS))throw new IOException("Fixture gate timed out");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("Fixture interrupted");}
        }
    }
}
