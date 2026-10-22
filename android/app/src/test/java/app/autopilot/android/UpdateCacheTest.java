package app.autopilot.android;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public final class UpdateCacheTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();

    @Test public void cleanupUsesCanonicalIdentityAndFailsClosedForExternalKeep()throws Exception{
        File root=temporary.newFolder("updates"),bridge=new File(root,"bridge");assertTrue(bridge.mkdir());
        File alias=new File(bridge,".."),keep=UpdateCache.apk(root,7),stale=UpdateCache.apk(root,6);
        byte[] verified={1,2,3,4};Files.write(keep.toPath(),verified);Files.write(stale.toPath(),new byte[]{5});
        File resume=new File(root,"autopilot-resume-fixture");assertTrue(resume.mkdir());File part=new File(resume,"part-0");Files.write(part.toPath(),new byte[]{6});
        File unrelated=new File(root,"account.json");Files.write(unrelated.toPath(),new byte[]{7});
        assertFalse("Old File.equals comparison treats these as different files",keep.equals(new File(alias,keep.getName())));
        assertEquals(keep,new File(alias,keep.getName()).getCanonicalFile());
        UpdateCache.cleanup(alias,keep);
        assertArrayEquals(verified,Files.readAllBytes(keep.toPath()));assertFalse(stale.exists());assertTrue(part.isFile());assertTrue(unrelated.isFile());
        Files.write(stale.toPath(),new byte[]{5});
        File outside=temporary.newFile("outside.apk");
        try{UpdateCache.cleanup(alias,outside);fail("An external keep file must fail closed");}
        catch(UpdateDownload.UpdateException expected){assertEquals("UPDATE_CACHE_ERROR",expected.code);}
        assertTrue(stale.isFile());assertTrue(keep.isFile());assertTrue(outside.isFile());
    }
}
