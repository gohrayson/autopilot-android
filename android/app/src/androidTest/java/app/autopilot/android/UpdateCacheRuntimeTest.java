package app.autopilot.android;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import static org.junit.Assert.*;

/** Real Android download cache/atomic publication and cleanup, with path spellings that alias. */
@RunWith(AndroidJUnit4.class)
public final class UpdateCacheRuntimeTest {
    @Test(timeout=30000) public void canonicalAliasCleanupKeepsCompleteApkAndLeavesResumeFiles()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File parent=context.getCacheDir().getCanonicalFile(),root=new File(parent,"update-cache-runtime-"+UUID.randomUUID());assertTrue(root.mkdir());
        File bridge=new File(root,"bridge");assertTrue(bridge.mkdir());File alias=new File(bridge,"..");
        String feed="https://updates.example.org/cache/latest.json",apk="https://updates.example.org/cache/app.apk";
        byte[] payload=new byte[1024];for(int i=0;i<payload.length;i++)payload[i]=(byte)(i*31);
        StringBuilder hash=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(payload))hash.append(String.format(Locale.ROOT,"%02x",value&255));
        byte[] manifest=new JSONObject().put("schemaVersion",1).put("packageName",UpdateIdentity.PACKAGE_NAME).put("versionCode",2147483000)
                .put("versionName","99.0.0-cache").put("minSdk",26).put("apkUrl",apk).put("sha256",hash.toString()).put("sizeBytes",payload.length).toString().getBytes(StandardCharsets.UTF_8);
        try{
            UpdateDownload downloader=new UpdateDownload(uri->{
                assertTrue(uri.toASCIIString().equals(feed)||uri.toASCIIString().equals(apk));byte[] body=uri.toASCIIString().equals(feed)?manifest:payload;
                return new UpdateDownload.Response(){public int status(){return 200;}public long contentLength(){return body.length;}public InputStream body(){return new ByteArrayInputStream(body);}public void close(){}};
            });
            UpdateManifest release=downloader.fetchManifest(feed,UpdateIdentity.PACKAGE_NAME);
            File complete=downloader.download(release,alias);
            assertEquals(UpdateCache.apk(root,release.versionCode),complete);
            assertFalse("Old cleanup compares these unequal path spellings",complete.equals(new File(alias,complete.getName())));
            assertArrayEquals(payload,Files.readAllBytes(complete.toPath()));
            File stale=UpdateCache.apk(root,release.versionCode-1);Files.write(stale.toPath(),new byte[]{1});
            File resume=new File(root,"autopilot-resume-fixture");assertTrue(resume.mkdir());File part=new File(resume,"part-0");Files.write(part.toPath(),new byte[]{2});
            UpdateCache.cleanup(alias,complete);
            assertArrayEquals("The download must still exist after ready-cache cleanup",payload,Files.readAllBytes(complete.toPath()));
            assertFalse(stale.exists());assertTrue(part.isFile());
            System.out.println("AUTOPILOT_UPDATE_CACHE_ALIAS_ACCEPTED real-downloader checksum atomic-publication canonical-alias kept-complete-apk stale-apk-removed resume-kept");
        }finally{deleteOwned(root,parent);}
    }
    private static void deleteOwned(File root,File parent)throws IOException{
        assertEquals(parent,root.getCanonicalFile().getParentFile());assertTrue(root.getName().startsWith("update-cache-runtime-"));deleteChild(root,root.getCanonicalPath());
    }
    private static void deleteChild(File file,String root)throws IOException{
        String path=file.getCanonicalPath();assertTrue(path.equals(root)||path.startsWith(root+File.separator));
        File[] children=file.listFiles();if(children!=null)for(File child:children)deleteChild(child,root);assertTrue(!file.exists()||file.delete());
    }
}
