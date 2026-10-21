package app.autopilot.android;

import android.content.Context;
import android.os.Build;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.github.oviron.libmihomo.Clash;
import io.github.oviron.libmihomo.InvokeInterface;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Actual Android JNI/parser probe. Fixed empty profile; no VPN routes or provider requests. */
@RunWith(AndroidJUnit4.class)
public final class NativeCoreConfigTest {
    @Test(timeout=90000) public void emptyProductionBaselineIsAcceptedByActualAndroidCore()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNull("This isolated native probe must not replace an active engine",((AutopilotApp)context.getApplicationContext()).engine);
        File home=new File(context.getFilesDir(),"native-core-config-probe-"+Long.toUnsignedString(System.nanoTime()));
        assertTrue("Create isolated private native home",home.mkdir());
        File profile=new File(home,"config.yaml");boolean loaded=false;
        try{
            JSONObject config=VpnEngine.configuration(List.of(),new HashMap<>());
            assertEquals(0,config.getJSONArray("proxies").length());assertEquals(0,config.getJSONArray("listeners").length());
            assertEquals("[\"REJECT\"]",config.getJSONArray("proxy-groups").getJSONObject(0).getJSONArray("proxies").toString());
            assertEquals("[\"MATCH,AUTOPILOT\"]",config.getJSONArray("rules").toString());assertFalse(config.getJSONObject("tun").getBoolean("enable"));
            byte[] bytes=config.toString().getBytes(StandardCharsets.UTF_8);
            try(FileOutputStream stream=new FileOutputStream(profile)){stream.write(bytes);stream.getFD().sync();}
            assertTrue(profile.isFile());assertEquals(bytes.length,profile.length());assertArrayEquals(bytes,Files.readAllBytes(profile.toPath()));
            JSONObject init=new JSONObject().put("home-dir",home.getAbsolutePath()).put("version",Build.VERSION.SDK_INT);
            JSONObject setup=new JSONObject().put("selected-map",new JSONObject().put("AUTOPILOT","REJECT"));
            assertEquals(2,init.length());assertTrue(init.get("version") instanceof Integer);
            assertEquals("REJECT",setup.getJSONObject("selected-map").getString("AUTOPILOT"));
            StringBuilder digest=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))digest.append(String.format(Locale.ROOT,"%02x",b&255));
            String evidence="SDK="+Build.VERSION.SDK_INT+" ABI="+Build.SUPPORTED_ABIS[0]+" bytes="+bytes.length+" sha256="+digest+"\ninit="+init+"\nsetup="+setup+"\ncredential-free-baseline="+config;
            System.out.println("AUTOPILOT_NATIVE_BASELINE_BEGIN "+evidence);
            Clash.INSTANCE.load(context.getApplicationInfo().nativeLibraryDir);Clash.INSTANCE.assertReady();loaded=true;
            CompletableFuture<String> completion=new CompletableFuture<>();
            Clash.INSTANCE.quickSetup(init.toString(),setup.toString(),(InvokeInterface)completion::complete);
            String rejection=completion.get(45,TimeUnit.SECONDS);
            // Exact raw native text is safe here: the submitted profile contains ZERO
            // provider data, credentials, account sessions or user-supplied values.
            assertEquals("Actual Android native baseline rejection (unredacted fixed fixture).\n"+evidence+"\nRAW_NATIVE_RESULT="+String.valueOf(rejection),"",rejection);
            CompletableFuture<String> proxyCompletion=new CompletableFuture<>();
            JSONObject command=new JSONObject().put("id","native-config-probe").put("method","getProxies").put("data",JSONObject.NULL);
            Clash.INSTANCE.invokeAction(command.toString(),(InvokeInterface)proxyCompletion::complete);
            String rawResponse=proxyCompletion.get(10,TimeUnit.SECONDS);JSONObject response=new JSONObject(rawResponse);
            assertEquals(rawResponse,0,response.getInt("code"));assertEquals("getProxies",response.getString("method"));
            Object data=response.get("data");JSONObject document=data instanceof JSONObject?(JSONObject)data:new JSONObject(String.valueOf(data));
            JSONObject group=document.getJSONObject("proxies").getJSONObject("AUTOPILOT");assertEquals(rawResponse,"REJECT",group.getString("now"));
            System.out.println("AUTOPILOT_NATIVE_BASELINE_ACCEPTED selector="+group.getString("now")+" SDK="+Build.VERSION.SDK_INT+" ABI="+Build.SUPPORTED_ABIS[0]);
        }finally{
            if(loaded)Clash.INSTANCE.stopTun();
            // This directory is created by this test directly inside private filesDir.
            assertEquals(context.getFilesDir().getCanonicalFile(),home.getCanonicalFile().getParentFile());
            File[] files=home.listFiles();if(files!=null)for(File file:files)if(file.isFile())file.delete();
            home.delete();
        }
    }
}
