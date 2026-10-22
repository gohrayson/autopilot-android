package app.autopilot.android;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** APK cache ownership and identity are based on canonical files, never path spelling. */
final class UpdateCache {
    private UpdateCache() { }

    static File apk(File directory, int versionCode) throws IOException {
        File root=directory.getCanonicalFile();
        File result=new File(root,"autopilot-update-"+versionCode+".apk").getCanonicalFile();
        if(!root.equals(result.getParentFile()))throw new UpdateDownload.UpdateException("UPDATE_CACHE_ERROR");
        return result;
    }

    static void cleanup(File directory, File keep) throws IOException {
        File root=directory.getCanonicalFile();
        File retained=keep==null?null:keep.getCanonicalFile();
        // An unexpected external keep path must fail closed instead of deleting every cached APK.
        if(retained!=null&&!root.equals(retained.getParentFile()))throw new UpdateDownload.UpdateException("UPDATE_CACHE_ERROR");
        File[] files=root.listFiles();if(files==null)return;
        for(File file:files){
            if(!file.getName().matches("autopilot-update-[0-9]+\\.apk"))continue;
            File canonical=file.getCanonicalFile();
            if(!root.equals(canonical.getParentFile())||canonical.equals(retained))continue;
            Files.deleteIfExists(file.toPath());
        }
    }

    static void discard(File directory, File file) throws IOException {
        File root=directory.getCanonicalFile(),canonical=file.getCanonicalFile();
        if(!root.equals(canonical.getParentFile())||!canonical.getName().matches("autopilot-update-[0-9]+\\.apk"))
            throw new UpdateDownload.UpdateException("UPDATE_CACHE_ERROR");
        Files.deleteIfExists(canonical.toPath());
    }
}
