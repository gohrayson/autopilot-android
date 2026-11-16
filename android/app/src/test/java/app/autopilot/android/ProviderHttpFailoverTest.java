package app.autopilot.android;

import org.json.JSONObject;
import org.junit.Test;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Production ProviderHttp through owned in-memory HTTPS connections; never contacts a provider. */
public final class ProviderHttpFailoverTest {
    private static final String PATH = "/api/user/client-info";
    private static class Connection extends HttpsURLConnection {
        int status = 200, disconnects, connects;
        byte[] bytes = "{\"fixture\":true}".getBytes(StandardCharsets.UTF_8);
        IOException connectFailure, responseFailure, bodyFailure;
        final ByteArrayOutputStream posted = new ByteArrayOutputStream();
        CountDownLatch entered, released;
        Connection(URL url) { super(url); }
        @Override public void connect() throws IOException { connects++; if (connectFailure != null) throw connectFailure; }
        @Override public int getResponseCode() throws IOException {
            if (entered != null) {
                entered.countDown();
                try { if (!released.await(2, TimeUnit.SECONDS)) throw new IOException("fixture release timed out"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            }
            if (responseFailure != null) throw responseFailure;
            return status;
        }
        @Override public OutputStream getOutputStream() { return posted; }
        @Override public InputStream getInputStream() throws IOException {
            if (bodyFailure != null) throw bodyFailure;
            return new ByteArrayInputStream(bytes);
        }
        @Override public long getContentLengthLong() { return bytes.length; }
        @Override public String getHeaderField(String key) { return key.equalsIgnoreCase("Retry-After") ? "60" : null; }
        @Override public void disconnect() { disconnects++; if (released != null) released.countDown(); }
        @Override public boolean usingProxy() { return false; }
        @Override public String getCipherSuite() { return "fixture"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return null; }
    }
    private static class Fixture {
        final List<Connection> opened = new ArrayList<>();
        final List<URL> urls = new ArrayList<>();
        final Queue<java.util.function.Consumer<Connection>> steps = new ArrayDeque<>();
        final AtomicLong time = new AtomicLong(1000000000L);
        ProviderHttp http;
        Fixture() { this(20000); }
        Fixture(long timeoutMs) {
            http = new ProviderHttp(url -> {
                urls.add(url); Connection connection = new Connection(url); opened.add(connection);
                if (!steps.isEmpty()) steps.remove().accept(connection);
                return connection;
            }, time::get, timeoutMs);
        }
        void step(java.util.function.Consumer<Connection> action) { steps.add(action); }
        JSONObject get() throws ProviderException { return http.one(PATH, null, "synthetic-access", "account"); }
    }
    private interface Job { void run() throws Exception; }
    private static ProviderException failure(String code, Job job) throws Exception {
        try { job.run(); fail("Expected " + code); return null; }
        catch (ProviderException error) { assertEquals(code, error.code); return error; }
    }
    @Test public void runtimeCatalogIsFixedHttpsAndImmutable() {
        assertEquals(Arrays.asList("https://api.101proxy.top", "https://api.101vi.com", "https://api.101p.win"), ProviderHttp.oneOrigins());
        assertEquals(Arrays.asList("primary", "mirror-vi", "mirror-p"), ProviderHttp.oneOriginIds());
        try { ProviderHttp.oneOrigins().set(0, "https://untrusted.invalid"); fail(); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void onlyKnownEndpointPathsCanReachTransport() throws Exception {
        Fixture f = new Fixture();
        for (String path : Arrays.asList("https://other.invalid/api/auth/login", "/api/user/client-info?token=x", "/api/auth/logout", "//other.invalid/"))
            failure("INVALID_INPUT", () -> f.http.one(path, null, "synthetic-access", "account"));
        assertTrue(f.opened.isEmpty());
    }
    @Test public void failedDnsMovesGetToMirrorAndKeepsItForNextRequest() throws Exception {
        Fixture f = new Fixture(); f.step(c -> c.connectFailure = new UnknownHostException("private transport material"));
        assertTrue(f.get().getBoolean("fixture")); assertTrue(f.get().getBoolean("fixture"));
        assertEquals(Arrays.asList("api.101proxy.top", "api.101vi.com", "api.101vi.com"), Arrays.asList(f.urls.get(0).getHost(), f.urls.get(1).getHost(), f.urls.get(2).getHost()));
        assertEquals("mirror-vi", f.http.oneOriginId());
        for (Connection c : f.opened) { assertEquals("Bearer synthetic-access", c.getRequestProperty("Authorization")); assertEquals(1, c.disconnects); }
    }
    @Test public void allOriginsAreVisitedAtMostOnceAndErrorDiagnosticsUseFixedIds() throws Exception {
        Fixture f = new Fixture(); for (int i = 0; i < 3; i++) f.step(c -> c.connectFailure = new ConnectException("private material"));
        ProviderException error = failure("NETWORK_ERROR", f::get);
        assertEquals(3, f.opened.size()); assertEquals("mirror-p", error.origin); assertEquals(3, error.attempt);
        JSONObject diagnostic = error.diagnostic("onevpn", "refresh", 1700000000000L);
        assertEquals("mirror-p", diagnostic.getString("origin")); assertEquals(3, diagnostic.getInt("attempt"));
        assertFalse(diagnostic.toString().contains("private")); assertFalse(diagnostic.toString().contains("https://"));
    }
    @Test public void tlsFailureBeforeSendCanFailOverWithoutDisablingVerification() throws Exception {
        Fixture f = new Fixture(); f.step(c -> c.connectFailure = new SSLHandshakeException("synthetic invalid certificate"));
        f.get();
        for (Connection c : f.opened) {
            assertSame(HttpsURLConnection.getDefaultSSLSocketFactory(), c.getSSLSocketFactory());
            assertSame(HttpsURLConnection.getDefaultHostnameVerifier(), c.getHostnameVerifier());
            assertFalse(c.getInstanceFollowRedirects());
        }
        assertEquals(2, f.opened.size());
    }
    @Test public void unsentLoginMovesHostsAndRetainsExactJsonForm() throws Exception {
        Fixture f = new Fixture(); f.step(c -> c.connectFailure = new ConnectException("fixture refused before send"));
        JSONObject login = new JSONObject().put("email", "fixture@example.invalid").put("password", "synthetic-password").put("appId", "windows_app");
        f.http.one("/api/auth/login", login, null, "login-request");
        assertEquals(2, f.opened.size()); assertEquals(0, f.opened.get(0).posted.size());
        assertEquals(login.toString(), f.opened.get(1).posted.toString("UTF-8"));
        assertEquals("POST", f.opened.get(1).getRequestMethod());
        assertEquals("application/json", f.opened.get(1).getRequestProperty("Content-Type"));
        assertNull(f.opened.get(1).getRequestProperty("Authorization"));
    }
    @Test public void ambiguousPostedLoginAndRotationAreNeverReplayed() throws Exception {
        for (String path : Arrays.asList("/api/auth/login", "/api/auth/refresh")) {
            Fixture f = new Fixture(); f.step(c -> c.responseFailure = new SocketException("response lost after processing"));
            failure("NETWORK_ERROR", () -> f.http.one(path, new JSONObject().put("refreshToken", "synthetic"), null, "session-refresh"));
            assertEquals(1, f.opened.size()); assertTrue(f.opened.get(0).posted.size() > 0);
        }
    }
    @Test public void receiveTimeoutDoesNotReplayEvenReadOnlyGet() throws Exception {
        Fixture f = new Fixture(); f.step(c -> c.responseFailure = new SocketTimeoutException("receive timeout"));
        failure("TIMEOUT", f::get); assertEquals(1, f.opened.size());
    }
    @Test public void connectTimeoutCanFailOverWithinTheSharedBudget() throws Exception {
        Fixture f = new Fixture(); f.step(c -> c.connectFailure = new SocketTimeoutException("connect timeout"));
        f.get(); assertEquals(2, f.opened.size());
        for (Connection c : f.opened) assertTrue(c.getConnectTimeout() <= 5000);
    }
    @Test public void actualHttpRefusalsRedirectsAndGatewayErrorsNeverMoveHosts() throws Exception {
        for (int status : new int[]{301, 302, 400, 401, 403, 404, 408, 429, 500, 502, 503, 504}) {
            Fixture f = new Fixture(); f.step(c -> c.status = status);
            try { f.get(); fail(); } catch (ProviderException error) { assertEquals(status, error.httpStatus); }
            assertEquals(1, f.opened.size()); assertFalse(f.opened.get(0).getInstanceFollowRedirects());
        }
    }
    @Test public void responseBodyFailureCannotTriggerCrossHostReplay() throws Exception {
        Fixture f = new Fixture(); f.step(c -> c.bodyFailure = new SocketException("body lost after HTTP200"));
        failure("NETWORK_ERROR", f::get); assertEquals(1, f.opened.size());
    }
    @Test public void malformedJsonAndUtf8AreTerminalContractErrors() throws Exception {
        for (byte[] bytes : new byte[][]{"not-json".getBytes(StandardCharsets.UTF_8), {(byte) 0xc3, 0x28}}) {
            Fixture f = new Fixture(); f.step(c -> c.bytes = bytes);
            failure("INVALID_RESPONSE", f::get); assertEquals(1, f.opened.size());
        }
    }
    @Test public void connectionTimeSpentOnFirstHostReducesTheNextHostsBudget() throws Exception {
        Fixture f = new Fixture(); f.step(c -> { f.time.addAndGet(TimeUnit.SECONDS.toNanos(19)); c.connectFailure = new UnknownHostException(); });
        f.get(); assertEquals(2, f.opened.size());
        assertTrue(f.opened.get(1).getConnectTimeout() <= 1000); assertTrue(f.opened.get(1).getReadTimeout() <= 1000);
    }
    @Test public void exhaustedBudgetCannotStartTheNextHost() throws Exception {
        Fixture f = new Fixture(); f.step(c -> { f.time.addAndGet(TimeUnit.SECONDS.toNanos(21)); c.connectFailure = new UnknownHostException(); });
        failure("TIMEOUT", f::get); assertEquals(1, f.opened.size());
    }
    @Test public void realDeadlineDisconnectsTheSinglePendingAttempt() throws Exception {
        Fixture f = new Fixture(70); f.step(c -> { c.entered = new CountDownLatch(1); c.released = new CountDownLatch(1); c.responseFailure = new SocketException("disconnected"); });
        long started = System.nanoTime(); failure("TIMEOUT", f::get);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000); assertEquals(1, f.opened.size());
    }
    @Test public void abortDisconnectsWithoutFailoverAndLaterExplicitRequestsStillWork() throws Exception {
        Fixture f = new Fixture(); CountDownLatch entered = new CountDownLatch(1);
        f.step(c -> { c.entered = entered; c.released = new CountDownLatch(1); c.responseFailure = new SocketException("cancelled"); });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<String> outcome = worker.submit(() -> { try { f.get(); return "unexpected success"; } catch (ProviderException error) { return error.code; } });
            assertTrue(entered.await(1, TimeUnit.SECONDS)); f.http.abort();
            assertEquals("ABORTED", outcome.get(1, TimeUnit.SECONDS)); assertEquals(1, f.opened.size());
            assertTrue(f.get().getBoolean("fixture")); assertEquals(2, f.opened.size());
        } finally { worker.shutdownNow(); }
    }
    @Test public void interruptedCallerCannotOpenAnyHost() throws Exception {
        Fixture f = new Fixture(); Thread.currentThread().interrupt();
        try { failure("ABORTED", f::get); assertTrue(f.opened.isEmpty()); }
        finally { Thread.interrupted(); }
    }
    @Test public void octohideKeepsItsOwnSingleOriginAndNeverUsesOneVpnFailover() throws Exception {
        Fixture f = new Fixture(); f.step(c -> c.connectFailure = new ConnectException("fixture"));
        failure("NETWORK_ERROR", () -> f.http.octo(Map.of("action", "init"), "account"));
        assertEquals(1, f.opened.size()); assertEquals(ProviderHttp.OCTO_URL, f.urls.get(0).toString());
    }
    @Test public void malformedAuthorizationIsRejectedBeforeConnectingWithoutFailover() throws Exception {
        for (String token : Arrays.asList("", "bad token", "first\nsecond\nthird", "bad\r\nheader\r\nvalue", "bad\u007ftoken")) {
            Fixture f = new Fixture();
            failure("AUTH_REQUIRED", () -> f.http.one(PATH, null, token, "account"));
            assertEquals(1, f.opened.size()); assertEquals(0, f.opened.get(0).connects);
        }
    }
    @Test public void diagnosticOriginCannotContainArbitraryTextOrCrossProviderMetadata() throws Exception {
        ProviderException invalid = new ProviderException("NETWORK_ERROR", "account").withOneTransport("https://private.invalid/token", 9);
        assertNull(invalid.origin); assertEquals(0, invalid.attempt);
        ProviderException known = invalid.withOneTransport("mirror-vi", 2);
        assertFalse(known.diagnostic("octohide", "refresh", 1700000000000L).has("origin"));
    }
}
