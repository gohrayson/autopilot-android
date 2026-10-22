package app.autopilot.android;

import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;

public final class GeoRoutingTest {
    private static byte[] manifest(byte[] bytes)throws Exception{StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))sha.append(String.format(Locale.ROOT,"%02x",b&255));return new JSONObject().put("name","geoip.metadb").put("size",bytes.length).put("sha256",sha.toString()).toString().getBytes(StandardCharsets.UTF_8);}
    @Test public void verifiedInstallUsesCacheAndRepairsCorruptCacheAtomically()throws Exception{
        File home=Files.createTempDirectory("autopilot-geo-test-").toFile();byte[] data="fixed-credential-free-geodata-fixture".getBytes(StandardCharsets.UTF_8),metadata=manifest(data);
        try{
            File installed=GeoRouting.install(new ByteArrayInputStream(data),new ByteArrayInputStream(metadata),home);assertArrayEquals(data,Files.readAllBytes(installed.toPath()));
            InputStream forbidden=new InputStream(){public int read()throws IOException{throw new IOException("Cache should not reread asset");}};
            assertEquals(installed,GeoRouting.install(forbidden,new ByteArrayInputStream(metadata),home));
            Files.write(installed.toPath(),new byte[data.length]);GeoRouting.install(new ByteArrayInputStream(data),new ByteArrayInputStream(metadata),home);assertArrayEquals(data,Files.readAllBytes(installed.toPath()));
            assertEquals(1,Objects.requireNonNull(home.listFiles()).length);
        }finally{for(File file:Objects.requireNonNull(home.listFiles()))Files.delete(file.toPath());Files.delete(home.toPath());}
    }
    @Test public void truncatedOrMismatchedAssetNeverReplacesPriorFile()throws Exception{
        File home=Files.createTempDirectory("autopilot-geo-reject-").toFile();byte[] data="fixture".getBytes(StandardCharsets.UTF_8),prior="prior".getBytes(StandardCharsets.UTF_8),metadata=manifest(data);File destination=new File(home,"geoip.metadb");Files.write(destination.toPath(),prior);
        try{
            for(byte[] invalid:List.of(new byte[]{1},new byte[data.length],new byte[data.length+1])){try{GeoRouting.install(new ByteArrayInputStream(invalid),new ByteArrayInputStream(metadata),home);fail();}catch(IOException expected){}assertArrayEquals(prior,Files.readAllBytes(destination.toPath()));assertEquals(1,Objects.requireNonNull(home.listFiles()).length);}
            JSONObject bad=new JSONObject(new String(metadata,StandardCharsets.UTF_8)).put("name","../outside.metadb");try{GeoRouting.install(new ByteArrayInputStream(data),new ByteArrayInputStream(bad.toString().getBytes(StandardCharsets.UTF_8)),home);fail();}catch(IOException expected){}
        }finally{for(File file:Objects.requireNonNull(home.listFiles()))Files.delete(file.toPath());Files.delete(home.toPath());}
    }
}
