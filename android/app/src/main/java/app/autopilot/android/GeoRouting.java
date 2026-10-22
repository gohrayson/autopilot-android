package app.autopilot.android;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.Locale;

/** Installs the signed APK's pinned regional database; no startup network request. */
final class GeoRouting {
    private static File verified;
    private static long verifiedSize,verifiedModified;
    static synchronized boolean ensure(Context context){
        verified=null;
        try(InputStream manifest=context.getAssets().open("geoip-manifest.json");InputStream asset=context.getAssets().open("geoip.metadb")){
            File installed=install(asset,manifest,new File(context.getFilesDir(),"core"));
            verified=installed.getCanonicalFile();verifiedSize=installed.length();verifiedModified=installed.lastModified();return true;
        }catch(Exception failed){((AutopilotApp)context.getApplicationContext()).log("routing","GEODATA_UNAVAILABLE");return false;}
    }
    static synchronized boolean available(Context context){
        try{File file=new File(context.getFilesDir(),"core/geoip.metadb").getCanonicalFile();return verified!=null&&verified.equals(file)&&file.isFile()&&file.length()==verifiedSize&&file.lastModified()==verifiedModified;}
        catch(IOException invalid){return false;}
    }
    static File install(InputStream asset,InputStream manifest,File home)throws Exception{
        ByteArrayOutputStream raw=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
        while((count=manifest.read(buffer))!=-1){if(raw.size()+count>8192)throw new IOException("GEODATA_MANIFEST_SIZE");raw.write(buffer,0,count);}
        JSONObject expected=new JSONObject(raw.toString(StandardCharsets.UTF_8.name()));Object size=expected.opt("size");
        if(!"geoip.metadb".equals(expected.optString("name"))||!(size instanceof Number)||((Number)size).doubleValue()!=((Number)size).longValue())throw new IOException("GEODATA_MANIFEST_INVALID");
        long bytes=((Number)size).longValue();String digest=expected.optString("sha256");
        if(bytes<=0||bytes>32L*1024*1024||!digest.matches("[a-f0-9]{64}"))throw new IOException("GEODATA_MANIFEST_INVALID");
        if(!home.isDirectory()&&!home.mkdirs())throw new IOException("GEODATA_STORAGE");
        File destination=new File(home,"geoip.metadb");
        if(valid(destination,bytes,digest))return destination;
        File pending=File.createTempFile("geoip-",".pending",home);
        try{
            MessageDigest sha=MessageDigest.getInstance("SHA-256");long copied=0;
            try(FileOutputStream output=new FileOutputStream(pending)){
                while((count=asset.read(buffer))!=-1){copied+=count;if(copied>bytes)throw new IOException("GEODATA_SIZE");sha.update(buffer,0,count);output.write(buffer,0,count);}
                output.getFD().sync();
            }
            if(copied!=bytes||!digest.equals(hex(sha.digest())))throw new IOException("GEODATA_INTEGRITY");
            Files.move(pending.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            if(!valid(destination,bytes,digest))throw new IOException("GEODATA_INTEGRITY");return destination;
        }finally{if(pending.exists())pending.delete();}
    }
    private static boolean valid(File file,long bytes,String digest)throws Exception{
        if(!file.isFile()||file.length()!=bytes)return false;
        MessageDigest sha=MessageDigest.getInstance("SHA-256");try(InputStream input=new FileInputStream(file)){byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)sha.update(buffer,0,count);}
        return digest.equals(hex(sha.digest()));
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte value:bytes)out.append(String.format(Locale.ROOT,"%02x",value&255));return out.toString();}
    private GeoRouting(){}
}
