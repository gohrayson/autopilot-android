package app.autopilot.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import javax.net.ssl.HttpsURLConnection;

/** Bounded public HTTPS downloads. Redirects stay within the source origin or GitHub's release hosts. */
public final class UpdateDownload {
    public static final int MAX_REDIRECTS = 3;
    public static final class UpdateException extends IOException {
        public final String code;
        public UpdateException(String code) { super(code); this.code = code; }
    }
    public interface Response extends AutoCloseable {
        int status() throws IOException;
        long contentLength();
        InputStream body() throws IOException;
        default String location() { return null; }
        @Override void close() throws IOException;
    }
    public interface Transport { Response open(URI uri) throws IOException; }
    private final Transport transport;

    public UpdateDownload() { this(new HttpsTransport()); }
    public UpdateDownload(Transport transport) {
        if (transport == null) throw new IllegalArgumentException("transport");
        this.transport = transport;
    }

    public UpdateManifest fetchManifest(String manifestUrl, String expectedPackage) throws UpdateException {
        URI source = UpdateManifest.publicHttpsUri(manifestUrl);
        long deadline = System.nanoTime() + 30_000_000_000L;
        try {
            checkCancelled();
            try (Response response = openResponse(source, deadline)) {
                requireOk(response);
                if (response.contentLength() > UpdateManifest.MAX_MANIFEST_BYTES) throw error("UPDATE_RESPONSE_SIZE");
                byte[] bytes;
                try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        checkProgress(deadline);
                        if ((long) output.size() + read > UpdateManifest.MAX_MANIFEST_BYTES) throw error("UPDATE_RESPONSE_SIZE");
                        output.write(buffer, 0, read);
                    }
                    checkProgress(deadline);
                    bytes = output.toByteArray();
                }
                if (response.contentLength() >= 0 && response.contentLength() != bytes.length) throw error("UPDATE_RESPONSE_SIZE");
                String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                return UpdateManifest.parse(json, source.toASCIIString(), expectedPackage);
            }
        } catch (UpdateException failure) { throw failure; }
        catch (SocketTimeoutException failure) { throw error("UPDATE_TIMEOUT"); }
        catch (InterruptedIOException failure) { throw error("UPDATE_CANCELLED"); }
        catch (java.nio.charset.CharacterCodingException failure) { throw error("UPDATE_INVALID_MANIFEST"); }
        catch (IOException failure) { throw error("UPDATE_NETWORK_ERROR"); }
    }

    /** The caller must supply its private cache directory and verify the returned APK's Android signer. */
    public File download(UpdateManifest manifest, File privateCacheDirectory) throws UpdateException {
        if (manifest == null) throw error("UPDATE_INVALID_MANIFEST");
        // Recheck the immutable object's origin immediately before connecting.
        URI source = UpdateManifest.publicHttpsUri(manifest.manifestUrl);
        URI apk = UpdateManifest.publicHttpsUri(manifest.apkUrl);
        if (!UpdateManifest.sameOrigin(source, apk)) throw error("UPDATE_UNSAFE_URL");
        File partial = null;
        long deadline = System.nanoTime() + 600_000_000_000L;
        try {
            checkCancelled();
            if (privateCacheDirectory == null || (!privateCacheDirectory.isDirectory() && !privateCacheDirectory.mkdirs()))
                throw error("UPDATE_CACHE_ERROR");
            try { partial = File.createTempFile("autopilot-update-", ".part", privateCacheDirectory); }
            catch (IOException failure) { throw error("UPDATE_CACHE_ERROR"); }
            try (Response response = openResponse(apk, deadline)) {
                requireOk(response);
                long declared = response.contentLength();
                if (declared >= 0 && declared != manifest.sizeBytes) throw error("UPDATE_RESPONSE_SIZE");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long total = 0;
                try (InputStream input = response.body(); FileOutputStream output = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[32 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        checkProgress(deadline);
                        total += read;
                        if (total > manifest.sizeBytes || total > UpdateManifest.MAX_APK_BYTES) throw error("UPDATE_RESPONSE_SIZE");
                        output.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                    }
                    checkProgress(deadline);
                    if (total != manifest.sizeBytes) throw error("UPDATE_RESPONSE_SIZE");
                    if (!MessageDigest.isEqual(digest.digest(), hexBytes(manifest.sha256))) throw error("UPDATE_HASH_MISMATCH");
                    output.getFD().sync();
                }
            }
            checkProgress(deadline);
            File complete = new File(privateCacheDirectory, "autopilot-update-" + manifest.versionCode + ".apk");
            try {
                Files.move(partial.toPath(), complete.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) { throw error("UPDATE_CACHE_ERROR"); }
            partial = null;
            return complete;
        } catch (UpdateException failure) { throw failure; }
        catch (SocketTimeoutException failure) { throw error("UPDATE_TIMEOUT"); }
        catch (InterruptedIOException failure) { throw error("UPDATE_CANCELLED"); }
        catch (IOException failure) { throw error("UPDATE_NETWORK_ERROR"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw error("UPDATE_HASH_UNAVAILABLE"); }
        finally { if (partial != null) { try { Files.deleteIfExists(partial.toPath()); } catch (IOException ignored) { } } }
    }

    private static void requireOk(Response response) throws IOException {
        if (response.status() != 200) throw error("UPDATE_HTTP_ERROR");
    }
    private Response openResponse(URI initial, long deadline) throws IOException {
        URI current = initial;
        for (int redirects = 0; ; redirects++) {
            checkProgress(deadline);
            Response response = transport.open(current);
            boolean keep = false;
            try {
                int status = response.status();
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                    keep = true;
                    return response;
                }
                if (redirects >= MAX_REDIRECTS) throw error("UPDATE_REDIRECT_LIMIT");
                String location = response.location();
                if (location == null || location.isEmpty()) throw error("UPDATE_HTTP_ERROR");
                current = redirectUri(initial, current, location);
            } finally { if (!keep) response.close(); }
        }
    }
    private static URI redirectUri(URI initial, URI current, String location) throws UpdateException {
        try {
            if (location.length() > 8192 || location.indexOf('\\') >= 0) throw error("UPDATE_UNSAFE_URL");
            URI next = current.resolve(new URI(location));
            if (!"https".equalsIgnoreCase(next.getScheme()) || next.getHost() == null
                    || next.getRawUserInfo() != null || next.getRawFragment() != null
                    || next.getPort() == 0 || next.getPort() < -1 || next.getPort() > 65535
                    || next.isOpaque()) throw error("UPDATE_UNSAFE_URL");
            if (UpdateManifest.sameOrigin(initial, next)) return next;
            boolean githubRelease = "github.com".equalsIgnoreCase(initial.getHost())
                    && (initial.getPort() == -1 || initial.getPort() == 443)
                    && (next.getPort() == -1 || next.getPort() == 443)
                    && ("github.com".equalsIgnoreCase(next.getHost())
                    || "release-assets.githubusercontent.com".equalsIgnoreCase(next.getHost())
                    || "objects.githubusercontent.com".equalsIgnoreCase(next.getHost()));
            if (!githubRelease) throw error("UPDATE_UNSAFE_URL");
            return next;
        } catch (UpdateException failure) { throw failure; }
        catch (Exception ignored) { throw error("UPDATE_UNSAFE_URL"); }
    }
    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("UPDATE_CANCELLED");
    }
    private static void checkProgress(long deadline) throws IOException {
        checkCancelled();
        if (System.nanoTime() > deadline) throw error("UPDATE_TIMEOUT");
    }
    private static UpdateException error(String code) { return new UpdateException(code); }
    private static byte[] hexBytes(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    private static final class HttpsTransport implements Transport {
        public Response open(URI uri) throws IOException {
            HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(20_000);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json, application/vnd.android.package-archive, application/octet-stream");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "Autopilot-Android-Updater/1");
            // Provider/session headers are never passed into this independent public transport.
            return new Response() {
                public int status() throws IOException { return connection.getResponseCode(); }
                public long contentLength() { return connection.getContentLengthLong(); }
                public InputStream body() throws IOException { return connection.getInputStream(); }
                public String location() { return connection.getHeaderField("Location"); }
                public void close() { connection.disconnect(); }
            };
        }
    }
}
