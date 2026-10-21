package app.autopilot.android;

import org.json.JSONObject;
import org.junit.Test;
import java.net.URI;
import static org.junit.Assert.*;

public class UpdateManifestTest {
    static final String SOURCE = "https://updates.example.test/autopilot/latest.json";
    static final String PACKAGE = "app.autopilot.android";
    static JSONObject json() throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("packageName", PACKAGE)
                .put("versionCode", 3).put("versionName", "0.1.2").put("minSdk", 26)
                .put("apkUrl", "https://updates.example.test/autopilot/Autopilot-Android-0.1.2.apk")
                .put("sha256", "0".repeat(64)).put("sizeBytes", 1234)
                .put("notes", "Update details\nNo account data is removed.");
    }
    static UpdateManifest parse(JSONObject value) throws Exception {
        return UpdateManifest.parse(value.toString(), SOURCE, PACKAGE);
    }
    static void rejects(String code, JSONObject value) throws Exception {
        try { parse(value); fail("Expected " + code); }
        catch (UpdateDownload.UpdateException error) { assertEquals(code, error.code); assertEquals(code, error.getMessage()); }
    }

    @Test public void validManifestHasTypedFieldsAndCallerVersionSdkGates() throws Exception {
        UpdateManifest manifest = parse(json().put("publishedAt", "2026-10-21T12:00:00Z"));
        assertEquals(1, manifest.schemaVersion); assertEquals(PACKAGE, manifest.packageName);
        assertEquals(3, manifest.versionCode); assertEquals("0.1.2", manifest.versionName);
        assertEquals(26, manifest.minSdk); assertEquals(1234, manifest.sizeBytes);
        assertTrue(manifest.isNewerThan(2)); assertFalse(manifest.isNewerThan(3)); assertFalse(manifest.isNewerThan(4));
        assertTrue(manifest.supportsSdk(36)); assertFalse(manifest.supportsSdk(25));
        assertEquals("2026-10-21T12:00:00Z", manifest.publishedAt);
    }
    @Test public void optionalFieldsHaveSafeEmptyDefaults() throws Exception {
        JSONObject data = json(); data.remove("notes");
        UpdateManifest manifest = parse(data);
        assertEquals("", manifest.notes); assertEquals("", manifest.publishedAt);
    }
    @Test public void numericFieldsRejectStringsFloatingPointNullAndRangeErrors() throws Exception {
        for (String key : new String[]{"schemaVersion", "versionCode", "minSdk", "sizeBytes"}) {
            for (Object invalid : new Object[]{"3", 1.5, JSONObject.NULL, 0, -1, Long.MAX_VALUE})
                rejects("UPDATE_INVALID_MANIFEST", json().put(key, invalid));
            JSONObject missing = json(); missing.remove(key); rejects("UPDATE_INVALID_MANIFEST", missing);
        }
        String exponent = json().toString().replace("\"versionCode\":3", "\"versionCode\":3e0");
        try { UpdateManifest.parse(exponent, SOURCE, PACKAGE); fail("Exponent is not an integer JSON representation"); }
        catch (UpdateDownload.UpdateException expected) { assertEquals("UPDATE_INVALID_MANIFEST", expected.code); }
    }
    @Test public void schemaAndUnknownFieldsAreRejected() throws Exception {
        rejects("UPDATE_INVALID_MANIFEST", json().put("schemaVersion", 2));
        rejects("UPDATE_INVALID_MANIFEST", json().put("silentInstall", true));
        rejects("UPDATE_INVALID_MANIFEST", json().put("password", "never-record-this"));
        rejects("UPDATE_INVALID_MANIFEST", json().put("notes", JSONObject.NULL));
    }
    @Test public void packageAndSha256AreExactAndTyped() throws Exception {
        rejects("UPDATE_INVALID_MANIFEST", json().put("packageName", "another.android.app"));
        rejects("UPDATE_INVALID_MANIFEST", json().put("packageName", 123));
        for (Object invalid : new Object[]{"A".repeat(64), "0".repeat(63), "g".repeat(64), JSONObject.NULL})
            rejects("UPDATE_INVALID_MANIFEST", json().put("sha256", invalid));
    }
    @Test public void semanticVersionsAreBoundedAndNumericIdentifiersCanonical() throws Exception {
        for (String valid : new String[]{"1.0.0", "0.1.2-beta.1", "1.2.3-rc-1+android.01", "1.0.0+build-1"})
            assertEquals(valid, parse(json().put("versionName", valid)).versionName);
        for (String invalid : new String[]{"v1.2.3", "1.2", "01.2.3", "1.2.3-beta.01", "1.2.3-", "1.2.3+", "1.2.3\n", "1.2.3+" + "x".repeat(80)})
            rejects("UPDATE_INVALID_MANIFEST", json().put("versionName", invalid));
    }
    @Test public void endpointAndApkRequireCredentialFreeSameOriginHttps() throws Exception {
        for (String invalid : new String[]{"http://updates.example.test/app.apk", "https://other.example.test/app.apk",
                "https://updates.example.test:8443/app.apk", "https://user:secret@updates.example.test/app.apk",
                "https://updates.example.test/app.apk?token=secret", "https://updates.example.test/app.apk#fragment",
                "/relative.apk", "https://updates.example.test:0/app.apk", "https://updates.example.test:65536/app.apk"})
            rejects("UPDATE_UNSAFE_URL", json().put("apkUrl", invalid));
        assertEquals("updates.example.test", UpdateManifest.publicHttpsUri(SOURCE).getHost());
        assertTrue(UpdateManifest.sameOrigin(new URI(SOURCE), new URI("https://UPDATES.example.test:443/app.apk")));
        String securePortSource = "https://updates.example.test:8443/latest.json";
        UpdateManifest manifest = UpdateManifest.parse(json().put("apkUrl", "https://updates.example.test:8443/app.apk").toString(), securePortSource, PACKAGE);
        assertEquals("https://updates.example.test:8443/app.apk", manifest.apkUrl);
    }
    @Test public void unsafeManifestEndpointIsRejectedBeforeMetadata() throws Exception {
        for (String invalid : new String[]{"http://updates.example.test/latest.json", "https://a:password@updates.example.test/latest.json",
                "https://updates.example.test/latest.json?token=secret", "https://updates.example.test/latest.json#x", "javascript:alert(1)"}) {
            try { UpdateManifest.parse(json().toString(), invalid, PACKAGE); fail("Unsafe endpoint accepted"); }
            catch (UpdateDownload.UpdateException error) { assertEquals("UPDATE_UNSAFE_URL", error.code); }
        }
    }
    @Test public void notesAndTimestampAreBoundedPlainText() throws Exception {
        assertEquals("n".repeat(4096), parse(json().put("notes", "n".repeat(4096))).notes);
        for (String invalid : new String[]{"n".repeat(4097), "界".repeat(1400), "control\u0000character", "spoof\u202efile.apk"})
            rejects("UPDATE_INVALID_MANIFEST", json().put("notes", invalid));
        rejects("UPDATE_INVALID_MANIFEST", json().put("publishedAt", "tomorrow"));
    }
    @Test public void payloadCannotExceedCapOrAppendAnotherJsonObject() throws Exception {
        for (String invalid : new String[]{json() + "{}", "[]", "null", " ".repeat(UpdateManifest.MAX_MANIFEST_BYTES + 1)}) {
            try { UpdateManifest.parse(invalid, SOURCE, PACKAGE); fail("Invalid envelope accepted"); }
            catch (UpdateDownload.UpdateException error) { assertEquals("UPDATE_INVALID_MANIFEST", error.code); }
        }
        rejects("UPDATE_INVALID_MANIFEST", json().put("sizeBytes", UpdateManifest.MAX_APK_BYTES + 1));
    }
}
