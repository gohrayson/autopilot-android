package app.autopilot.android;

import org.json.JSONObject;
import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

/** Exercises the adapter's full manifest + installed package + parsed archive identity contract. */
public class UpdateIdentityTest {
    private static final String A="a".repeat(64), B="b".repeat(64);
    private static UpdateManifest manifest() throws Exception {
        return UpdateManifest.parse(new JSONObject().put("schemaVersion",1).put("packageName",UpdateIdentity.PACKAGE_NAME)
                .put("versionCode",4).put("versionName","0.1.3").put("minSdk",26)
                .put("apkUrl","https://github.com/gohrayson/autopilot-android/releases/download/v0.1.3/Autopilot.apk")
                .put("sha256",A).put("sizeBytes",123).toString(),
                "https://github.com/gohrayson/autopilot-android/releases/latest/download/latest.json",UpdateIdentity.PACKAGE_NAME);
    }
    private static void verify(String installedPackage,long installedVersion,String archivePackage,long archiveVersion,
                               String versionName,int minSdk,int deviceSdk,Set<String> current,Set<String> archive) throws Exception {
        UpdateIdentity.verify(manifest(),installedPackage,installedVersion,archivePackage,archiveVersion,versionName,minSdk,deviceSdk,current,archive);
    }
    private static void fails(String code,Checked work) throws Exception {
        try{work.run();fail("Expected "+code);}catch(UpdateDownload.UpdateException error){assertEquals(code,error.code);}
    }
    private interface Checked{void run()throws Exception;}
    @Test public void sameIdentityUpgradeAccepted() throws Exception {
        verify(UpdateIdentity.PACKAGE_NAME,3,UpdateIdentity.PACKAGE_NAME,4,"0.1.3",26,36,Set.of(A),Set.of(A));
    }
    @Test public void debugOrForeignPackageRejected() throws Exception {
        fails("UPDATE_PACKAGE_MISMATCH",()->verify(UpdateIdentity.PACKAGE_NAME+".debug",3,UpdateIdentity.PACKAGE_NAME,4,"0.1.3",26,36,Set.of(A),Set.of(A)));
        fails("UPDATE_PACKAGE_MISMATCH",()->verify(UpdateIdentity.PACKAGE_NAME,3,"com.example.other",4,"0.1.3",26,36,Set.of(A),Set.of(A)));
    }
    @Test public void downgradeAndMetadataDisagreementRejected() throws Exception {
        fails("UPDATE_VERSION_MISMATCH",()->verify(UpdateIdentity.PACKAGE_NAME,4,UpdateIdentity.PACKAGE_NAME,4,"0.1.3",26,36,Set.of(A),Set.of(A)));
        fails("UPDATE_VERSION_MISMATCH",()->verify(UpdateIdentity.PACKAGE_NAME,3,UpdateIdentity.PACKAGE_NAME,5,"0.1.3",26,36,Set.of(A),Set.of(A)));
        fails("UPDATE_VERSION_MISMATCH",()->verify(UpdateIdentity.PACKAGE_NAME,3,UpdateIdentity.PACKAGE_NAME,4,"0.1.4",26,36,Set.of(A),Set.of(A)));
    }
    @Test public void androidCompatibilityAndDeclaredMinimumChecked() throws Exception {
        fails("UPDATE_ANDROID_UNSUPPORTED",()->verify(UpdateIdentity.PACKAGE_NAME,3,UpdateIdentity.PACKAGE_NAME,4,"0.1.3",26,25,Set.of(A),Set.of(A)));
        fails("UPDATE_ANDROID_UNSUPPORTED",()->verify(UpdateIdentity.PACKAGE_NAME,3,UpdateIdentity.PACKAGE_NAME,4,"0.1.3",27,36,Set.of(A),Set.of(A)));
    }
    @Test public void signerEqualityIsExactNotIntersectionOrHistorical() throws Exception {
        fails("UPDATE_SIGNER_MISMATCH",()->verify(UpdateIdentity.PACKAGE_NAME,3,UpdateIdentity.PACKAGE_NAME,4,"0.1.3",26,36,Set.of(A),Set.of(B)));
        fails("UPDATE_SIGNER_MISMATCH",()->verify(UpdateIdentity.PACKAGE_NAME,3,UpdateIdentity.PACKAGE_NAME,4,"0.1.3",26,36,Set.of(A),Set.of(A,B)));
        fails("UPDATE_SIGNER_MISMATCH",()->verify(UpdateIdentity.PACKAGE_NAME,3,UpdateIdentity.PACKAGE_NAME,4,"0.1.3",26,36,Set.of(A,B),Set.of(A)));
        verify(UpdateIdentity.PACKAGE_NAME,3,UpdateIdentity.PACKAGE_NAME,4,"0.1.3",26,36,Set.of(A,B),Set.of(B,A));
    }
    @Test public void unsignedMissingOrMalformedCertificatesRejected() throws Exception {
        fails("UPDATE_SIGNER_MISMATCH",()->verify(UpdateIdentity.PACKAGE_NAME,3,UpdateIdentity.PACKAGE_NAME,4,"0.1.3",26,36,Set.of(A),Set.of()));
        fails("UPDATE_SIGNER_MISMATCH",()->verify(UpdateIdentity.PACKAGE_NAME,3,UpdateIdentity.PACKAGE_NAME,4,"0.1.3",26,36,null,Set.of(A)));
        fails("UPDATE_SIGNER_MISMATCH",()->verify(UpdateIdentity.PACKAGE_NAME,3,UpdateIdentity.PACKAGE_NAME,4,"0.1.3",26,36,Set.of("bad"),Set.of("bad")));
    }
}
