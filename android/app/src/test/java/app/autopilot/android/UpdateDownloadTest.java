package app.autopilot.android;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class UpdateDownloadTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final byte[] APK = "APK fixture bytes: account data must remain untouched".getBytes(StandardCharsets.UTF_8);
    private static String hash(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder value = new StringBuilder();
        for (byte part : digest) value.append(String.format("%02x", part & 255));
        return value.toString();
    }
    private static UpdateManifest manifest(byte[] bytes) throws Exception {
        return UpdateManifestTest.parse(UpdateManifestTest.json().put("sizeBytes", bytes.length).put("sha256", hash(bytes)));
    }
    private static UpdateDownload.Response response(int status, long length, InputStream input, AtomicBoolean closed) {
        return new UpdateDownload.Response() {
            public int status() { return status; }
            public long contentLength() { return length; }
            public InputStream body() { return input; }
            public void close() { closed.set(true); }
        };
    }
    private static UpdateDownload downloader(int status, long length, byte[] body, AtomicBoolean closed) {
        return new UpdateDownload(uri -> response(status, length, new ByteArrayInputStream(body), closed));
    }
    private static UpdateDownload.Response redirect(String location, AtomicBoolean closed) {
        return new UpdateDownload.Response() {
            public int status() { return 302; }
            public long contentLength() { return 0; }
            public String location() { return location; }
            public InputStream body() { fail("Redirect body must not be consumed"); return null; }
            public void close() { closed.set(true); }
        };
    }
    private static void rejects(String code, Action action) throws Exception {
        try { action.run(); fail("Expected " + code); }
        catch (UpdateDownload.UpdateException error) { assertEquals(code, error.code); assertEquals(code, error.getMessage()); }
    }
    private interface Action { void run() throws Exception; }
    private static void assertNoPart(File directory) {
        File[] parts = directory.listFiles((dir, name) -> name.endsWith(".part"));
        assertNotNull(parts); assertEquals(0, parts.length);
    }

    @Test public void actualBytesAreHashedAndAtomicallyPublishedOnlyWhenComplete() throws Exception {
        File cache = temporary.newFolder(); AtomicBoolean closed = new AtomicBoolean(); AtomicInteger requests = new AtomicInteger();
        UpdateDownload download = new UpdateDownload(uri -> {
            requests.incrementAndGet(); assertEquals("https", uri.getScheme());
            assertFalse(new File(cache, "autopilot-update-3.apk").exists());
            return response(200, APK.length, new ByteArrayInputStream(APK), closed);
        });
        File result = download.download(manifest(APK), cache);
        assertEquals("autopilot-update-3.apk", result.getName());
        assertArrayEquals(APK, Files.readAllBytes(result.toPath())); assertTrue(closed.get());
        assertEquals(1, requests.get()); assertNoPart(cache);
    }
    @Test public void omittedContentLengthStillRequiresExactActualSize() throws Exception {
        File cache = temporary.newFolder();
        File result = downloader(200, -1, APK, new AtomicBoolean()).download(manifest(APK), cache);
        assertArrayEquals(APK, Files.readAllBytes(result.toPath())); assertNoPart(cache);
    }
    @Test public void truncatedBodyNeverBecomesApkAndPartialIsDeleted() throws Exception {
        File cache = temporary.newFolder(); AtomicBoolean closed = new AtomicBoolean();
        rejects("UPDATE_RESPONSE_SIZE", () -> downloader(200, -1, Arrays.copyOf(APK, APK.length - 1), closed).download(manifest(APK), cache));
        assertTrue(closed.get()); assertEquals(0, cache.list().length);
    }
    @Test public void oversizedBodyIsStoppedAtExpectedSizeAndCleanedUp() throws Exception {
        File cache = temporary.newFolder();
        rejects("UPDATE_RESPONSE_SIZE", () -> downloader(200, -1, Arrays.copyOf(APK, APK.length + 1), new AtomicBoolean()).download(manifest(APK), cache));
        assertEquals(0, cache.list().length);
    }
    @Test public void wrongHashPreservesPreviouslyVerifiedFileAndCleansPartial() throws Exception {
        File cache = temporary.newFolder(); File existing = new File(cache, "autopilot-update-3.apk");
        byte[] old = "existing verified apk".getBytes(StandardCharsets.UTF_8); Files.write(existing.toPath(), old);
        byte[] altered = APK.clone(); altered[0] ^= 1;
        rejects("UPDATE_HASH_MISMATCH", () -> downloader(200, APK.length, altered, new AtomicBoolean()).download(manifest(APK), cache));
        assertArrayEquals(old, Files.readAllBytes(existing.toPath())); assertNoPart(cache);
    }
    @Test public void incorrectDeclaredLengthIsRejectedWithoutReadingBody() throws Exception {
        File cache = temporary.newFolder(); AtomicBoolean read = new AtomicBoolean();
        InputStream input = new InputStream() { public int read() { read.set(true); return -1; } };
        UpdateDownload download = new UpdateDownload(uri -> response(200, APK.length + 1, input, new AtomicBoolean()));
        rejects("UPDATE_RESPONSE_SIZE", () -> download.download(manifest(APK), cache));
        assertFalse(read.get()); assertEquals(0, cache.list().length);
    }
    @Test public void everyRedirectAndHttpErrorIsRejectedWithoutFollowingOrReading() throws Exception {
        for (int status : new int[]{301, 302, 303, 307, 308, 401, 404, 429, 500}) {
            File cache = temporary.newFolder(); AtomicInteger requests = new AtomicInteger(); AtomicBoolean closed = new AtomicBoolean();
            UpdateDownload download = new UpdateDownload(uri -> {
                requests.incrementAndGet();
                return new UpdateDownload.Response() {
                    public int status() { return status; }
                    public long contentLength() { return APK.length; }
                    public InputStream body() { fail("Rejected HTTP body must not be read"); return null; }
                    public void close() { closed.set(true); }
                };
            });
            rejects("UPDATE_HTTP_ERROR", () -> download.download(manifest(APK), cache));
            assertEquals(1, requests.get()); assertTrue(closed.get()); assertEquals(0, cache.list().length);
        }
    }
    @Test public void interruptionBeforeRequestDoesNotConnectOrLeaveFile() throws Exception {
        File cache = temporary.newFolder(); AtomicBoolean connected = new AtomicBoolean();
        UpdateDownload download = new UpdateDownload(uri -> { connected.set(true); throw new IOException(); });
        Thread.currentThread().interrupt();
        try {
            rejects("UPDATE_CANCELLED", () -> download.download(manifest(APK), cache));
            assertTrue(Thread.currentThread().isInterrupted()); assertFalse(connected.get()); assertEquals(0, cache.list().length);
        } finally { Thread.interrupted(); }
    }
    @Test public void interruptionWhileStreamingRemovesPartAndClosesResponse() throws Exception {
        File cache = temporary.newFolder(); AtomicBoolean closed = new AtomicBoolean();
        ByteArrayInputStream input = new ByteArrayInputStream(APK) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                int value = super.read(bytes, offset, length); Thread.currentThread().interrupt(); return value;
            }
        };
        UpdateDownload download = new UpdateDownload(uri -> response(200, -1, input, closed));
        try {
            rejects("UPDATE_CANCELLED", () -> download.download(manifest(APK), cache));
            assertTrue(closed.get()); assertEquals(0, cache.list().length);
        } finally { Thread.interrupted(); }
    }
    @Test public void networkExceptionsExposeOnlyFixedCodesAndDeletePart() throws Exception {
        File cache = temporary.newFolder();
        UpdateDownload network = new UpdateDownload(uri -> { throw new IOException("https://secret.example/?token=never-log"); });
        rejects("UPDATE_NETWORK_ERROR", () -> network.download(manifest(APK), cache));
        assertEquals(0, cache.list().length);
        UpdateDownload timeout = new UpdateDownload(uri -> { throw new SocketTimeoutException("private-url"); });
        rejects("UPDATE_TIMEOUT", () -> timeout.download(manifest(APK), cache));
        assertEquals(0, cache.list().length);
    }
    @Test public void manifestFetchUsesActualJsonBytesAndExactPackage() throws Exception {
        byte[] json = UpdateManifestTest.json().toString().getBytes(StandardCharsets.UTF_8); AtomicBoolean closed = new AtomicBoolean();
        UpdateManifest parsed = downloader(200, json.length, json, closed).fetchManifest(UpdateManifestTest.SOURCE, UpdateManifestTest.PACKAGE);
        assertEquals(3, parsed.versionCode); assertTrue(closed.get());
        rejects("UPDATE_INVALID_MANIFEST", () -> downloader(200, -1, json, new AtomicBoolean()).fetchManifest(UpdateManifestTest.SOURCE, "wrong.package"));
    }
    @Test public void manifestFetchRejectsLengthMismatchOversizeBadUtf8AndRedirect() throws Exception {
        byte[] json = UpdateManifestTest.json().toString().getBytes(StandardCharsets.UTF_8);
        rejects("UPDATE_RESPONSE_SIZE", () -> downloader(200, json.length + 1, json, new AtomicBoolean()).fetchManifest(UpdateManifestTest.SOURCE, UpdateManifestTest.PACKAGE));
        rejects("UPDATE_RESPONSE_SIZE", () -> downloader(200, -1, new byte[UpdateManifest.MAX_MANIFEST_BYTES + 1], new AtomicBoolean()).fetchManifest(UpdateManifestTest.SOURCE, UpdateManifestTest.PACKAGE));
        rejects("UPDATE_INVALID_MANIFEST", () -> downloader(200, -1, new byte[]{(byte) 0xc3, 0x28}, new AtomicBoolean()).fetchManifest(UpdateManifestTest.SOURCE, UpdateManifestTest.PACKAGE));
        rejects("UPDATE_HTTP_ERROR", () -> downloader(302, -1, json, new AtomicBoolean()).fetchManifest(UpdateManifestTest.SOURCE, UpdateManifestTest.PACKAGE));
    }
    @Test public void badManifestEndpointIsRejectedBeforeTransport() throws Exception {
        AtomicBoolean connected = new AtomicBoolean();
        UpdateDownload download = new UpdateDownload(uri -> { connected.set(true); throw new IOException(); });
        rejects("UPDATE_UNSAFE_URL", () -> download.fetchManifest("http://updates.example.test/latest.json", UpdateManifestTest.PACKAGE));
        assertFalse(connected.get());
    }
    @Test public void manifestDownloadThenApkDownloadFormsOneObservablePipeline() throws Exception {
        JSONObject metadata = UpdateManifestTest.json().put("sizeBytes", APK.length).put("sha256", hash(APK));
        AtomicInteger requests = new AtomicInteger();
        UpdateDownload download = new UpdateDownload(uri -> {
            requests.incrementAndGet();
            byte[] body = uri.getPath().endsWith("latest.json") ? metadata.toString().getBytes(StandardCharsets.UTF_8) : APK;
            return response(200, body.length, new ByteArrayInputStream(body), new AtomicBoolean());
        });
        UpdateManifest release = download.fetchManifest(UpdateManifestTest.SOURCE, UpdateManifestTest.PACKAGE);
        assertTrue(release.isNewerThan(2)); assertTrue(release.supportsSdk(36));
        File result = download.download(release, temporary.newFolder());
        assertEquals(hash(APK), hash(Files.readAllBytes(result.toPath()))); assertEquals(2, requests.get());
    }
    @Test public void sameOriginRedirectAllowsRelativeLocationAndTransientQueryOnly() throws Exception {
        AtomicInteger requests = new AtomicInteger(); AtomicBoolean redirectClosed = new AtomicBoolean();
        UpdateDownload download = new UpdateDownload(uri -> {
            if (requests.getAndIncrement() == 0) return redirect("/autopilot/moved.apk?temporary=download", redirectClosed);
            assertEquals("https://updates.example.test/autopilot/moved.apk?temporary=download", uri.toASCIIString());
            return response(200, APK.length, new ByteArrayInputStream(APK), new AtomicBoolean());
        });
        assertArrayEquals(APK, Files.readAllBytes(download.download(manifest(APK), temporary.newFolder()).toPath()));
        assertEquals(2, requests.get()); assertTrue(redirectClosed.get());
    }
    @Test public void githubReleaseRedirectsAllowOnlyItsExplicitHttpsAssetHosts() throws Exception {
        for (String host : new String[]{"release-assets.githubusercontent.com", "objects.githubusercontent.com"}) {
            JSONObject metadata = UpdateManifestTest.json().put("sizeBytes", APK.length).put("sha256", hash(APK))
                    .put("apkUrl", "https://github.com/gohrayson/autopilot-updates/releases/download/v0.1.2/app.apk");
            UpdateManifest release = UpdateManifest.parse(metadata.toString(), "https://github.com/gohrayson/autopilot-updates/releases/latest/download/latest.json", UpdateManifestTest.PACKAGE);
            AtomicInteger requests = new AtomicInteger();
            UpdateDownload download = new UpdateDownload(uri -> requests.getAndIncrement() == 0
                    ? redirect("https://" + host + "/asset.apk?temporarySignature=private-never-log", new AtomicBoolean())
                    : response(200, APK.length, new ByteArrayInputStream(APK), new AtomicBoolean()));
            assertArrayEquals(APK, Files.readAllBytes(download.download(release, temporary.newFolder()).toPath()));
            assertEquals(2, requests.get());
        }
    }
    @Test public void redirectCannotDowngradeLeakCredentialsUseOtherOriginOrEscapeGithubHosts() throws Exception {
        String[] invalid = {"http://updates.example.test/app.apk", "https://evil.example/app.apk",
                "https://release-assets.githubusercontent.com/app.apk", "https://user:secret@updates.example.test/app.apk",
                "https://updates.example.test/app.apk#fragment", "https://updates.example.test:8443/app.apk"};
        for (String target : invalid) {
            File cache = temporary.newFolder(); AtomicInteger requests = new AtomicInteger(); AtomicBoolean closed = new AtomicBoolean();
            UpdateDownload download = new UpdateDownload(uri -> { requests.incrementAndGet(); return redirect(target, closed); });
            rejects("UPDATE_UNSAFE_URL", () -> download.download(manifest(APK), cache));
            assertEquals(1, requests.get()); assertTrue(closed.get()); assertEquals(0, cache.list().length);
        }
        JSONObject metadata = UpdateManifestTest.json().put("sizeBytes", APK.length).put("sha256", hash(APK))
                .put("apkUrl", "https://github.com/gohrayson/autopilot-updates/releases/download/v0.1.2/app.apk");
        UpdateManifest release = UpdateManifest.parse(metadata.toString(), "https://github.com/gohrayson/autopilot-updates/releases/latest/download/latest.json", UpdateManifestTest.PACKAGE);
        for (String target : new String[]{"https://release-assets.githubusercontent.com.evil.test/asset", "https://api.github.com/asset",
                "https://release-assets.githubusercontent.com:8443/asset", "http://objects.githubusercontent.com/asset"}) {
            rejects("UPDATE_UNSAFE_URL", () -> new UpdateDownload(uri -> redirect(target, new AtomicBoolean())).download(release, temporary.newFolder()));
        }
    }
    @Test public void redirectLoopHasFixedRequestBudgetAndCleansEveryResponse() throws Exception {
        File cache = temporary.newFolder(); AtomicInteger requests = new AtomicInteger(); AtomicInteger closes = new AtomicInteger();
        UpdateDownload download = new UpdateDownload(uri -> {
            requests.incrementAndGet();
            return new UpdateDownload.Response() {
                public int status() { return 307; }
                public long contentLength() { return 0; }
                public String location() { return "/redirect-loop.apk"; }
                public InputStream body() { fail("No redirect body must be read"); return null; }
                public void close() { closes.incrementAndGet(); }
            };
        });
        rejects("UPDATE_REDIRECT_LIMIT", () -> download.download(manifest(APK), cache));
        assertEquals(UpdateDownload.MAX_REDIRECTS + 1, requests.get()); assertEquals(requests.get(), closes.get());
        assertEquals(0, cache.list().length);
    }
}
