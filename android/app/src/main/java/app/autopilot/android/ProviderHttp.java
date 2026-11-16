package app.autopilot.android;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.io.IOException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/** Fixed HTTPS origins, bounded JSON, no redirects and no arbitrary response errors. */
public class ProviderHttp {
    // OneVPN 1.5.0 ApiHosts. Only these fixed HTTPS origins may receive account requests.
    private static final class OneOrigin {
        final String id, url;
        OneOrigin(String id, String url) { this.id = id; this.url = url; }
    }
    private static final List<OneOrigin> ONE_HOSTS = Collections.unmodifiableList(Arrays.asList(
            new OneOrigin("primary", "https://api.101proxy.top"),
            new OneOrigin("mirror-vi", "https://api.101vi.com"),
            new OneOrigin("mirror-p", "https://api.101p.win")));
    static final String ONE_ORIGIN = ONE_HOSTS.get(0).url;
    private static final List<String> ONE_ORIGINS = Collections.unmodifiableList(ONE_HOSTS.stream().map(origin -> origin.url).collect(Collectors.toList()));
    private static final List<String> ONE_ORIGIN_IDS = Collections.unmodifiableList(ONE_HOSTS.stream().map(origin -> origin.id).collect(Collectors.toList()));
    static final String OCTO_URL = "https://ohgp2.serviseapi.com/api/v2/";
    private static final String[] ONE_PATHS = { "/api/auth/login", "/api/auth/refresh", "/api/user/client-info", "/api/user/subscription/encrypted" };

    interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "autopilot-provider-deadlines"); thread.setDaemon(true); return thread;
    });
    private final ConnectionFactory connections;
    private final LongSupplier nanoTime;
    private final long oneTimeoutMs;
    private final AtomicLong cancellation = new AtomicLong();
    private final Set<Budget> active = ConcurrentHashMap.newKeySet();
    private volatile int preferredOneOrigin;
    private volatile String lastOneOrigin = ONE_ORIGIN_IDS.get(0);
    private volatile int lastOneAttempt;

    public ProviderHttp() { this(url -> (HttpURLConnection) url.openConnection(), System::nanoTime, 20000); }
    ProviderHttp(ConnectionFactory connections, LongSupplier nanoTime, long oneTimeoutMs) {
        if (connections == null || nanoTime == null || oneTimeoutMs < 1 || oneTimeoutMs > 20000) throw new IllegalArgumentException();
        this.connections = connections; this.nanoTime = nanoTime; this.oneTimeoutMs = oneTimeoutMs;
    }
    static List<String> oneOrigins() { return ONE_ORIGINS; }
    static List<String> oneOriginIds() { return ONE_ORIGIN_IDS; }
    public String oneOriginId() { return lastOneOrigin; }
    public int oneAttempt() { return lastOneAttempt; }
    public void abort() { cancellation.incrementAndGet(); for (Budget budget : active) budget.disconnect(); }

    public JSONObject one(String path, JSONObject body, String accessToken, String stage) throws ProviderException {
        boolean found = false;
        for (String allowed : ONE_PATHS) if (allowed.equals(path)) found = true;
        if (!found) throw new ProviderException("INVALID_INPUT", stage);
        int preferred = preferredOneOrigin;
        int[] order = new int[ONE_ORIGINS.size()]; order[0] = preferred;
        for (int i = 0, next = 1; i < order.length; i++) if (i != preferred) order[next++] = i;
        Budget budget = new Budget(oneTimeoutMs);
        String originId = ONE_ORIGIN_IDS.get(preferred); int attempts = 0;
        try {
            for (int attempt = 0; attempt < order.length; attempt++) {
                budget.check(stage);
                int origin = order[attempt]; lastOneOrigin = ONE_ORIGIN_IDS.get(origin); lastOneAttempt = attempt + 1;
                originId = lastOneOrigin; attempts = attempt + 1;
                try {
                    return request("onevpn", ONE_ORIGINS.get(origin) + path, body == null ? null : body.toString(),
                            "application/json", accessToken, stage, 8 * 1024 * 1024, budget, origin);
                } catch (AttemptFailure failure) {
                    budget.check(stage);
                    if (!failure.retryable || attempt == order.length - 1) throw failure.failure;
                }
            }
            throw new ProviderException("NETWORK_ERROR", stage);
        } catch (ProviderException error) { throw error.withOneTransport(originId, attempts); }
        finally { budget.close(); }
    }

    public JSONObject octo(Map<String, String> form, String stage) throws ProviderException {
        try {
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> entry : form.entrySet()) {
                if (body.length() != 0) body.append('&');
                body.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=')
                        .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            }
            Budget budget = new Budget(30000);
            try { return request("octohide", OCTO_URL, body.toString(), "application/x-www-form-urlencoded", null, stage, 1024 * 1024, budget, -1); }
            catch (AttemptFailure failure) { throw failure.failure; }
            finally { budget.close(); }
        } catch (ProviderException error) { throw error; }
        catch (Exception ignored) { throw new ProviderException("INVALID_INPUT", stage); }
    }

    private final class Budget {
        final long started = nanoTime.getAsLong();
        final long duration, epoch = cancellation.get();
        final AtomicReference<HttpURLConnection> connection = new AtomicReference<>();
        final ScheduledFuture<?> timer;
        volatile boolean expired;
        Budget(long timeoutMs) {
            duration = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            active.add(this);
            timer = DEADLINES.schedule(() -> { expired = true; disconnect(); }, timeoutMs, TimeUnit.MILLISECONDS);
        }
        void check(String stage) throws ProviderException {
            if (Thread.currentThread().isInterrupted() || cancellation.get() != epoch) throw new ProviderException("ABORTED", stage);
            if (expired || nanoTime.getAsLong() - started >= duration) throw new ProviderException("TIMEOUT", stage);
        }
        int remaining(String stage) throws ProviderException {
            check(stage);
            return (int) Math.min(Integer.MAX_VALUE, Math.max(1, TimeUnit.NANOSECONDS.toMillis(duration - (nanoTime.getAsLong() - started))));
        }
        void disconnect() { HttpURLConnection current = connection.get(); if (current != null) current.disconnect(); }
        void close() { timer.cancel(false); active.remove(this); }
    }
    private static final class AttemptFailure extends Exception {
        final ProviderException failure; final boolean retryable;
        AttemptFailure(String code, String stage, boolean retryable) { failure = new ProviderException(code, stage); this.retryable = retryable; }
    }
    private static boolean mayFailOver(IOException error, boolean hasBody, boolean bodyStarted, boolean connecting) {
        if (error instanceof SocketTimeoutException) return connecting;
        if (error instanceof SSLHandshakeException || error instanceof SSLPeerUnverifiedException) return connecting && !bodyStarted;
        if (error instanceof UnknownHostException || error instanceof ConnectException || error instanceof NoRouteToHostException)
            return !hasBody || (connecting && !bodyStarted);
        // Official unknown SocketException retries are safe for read-only requests;
        // an authentication POST may already have been processed, so never replay it.
        return !hasBody && error instanceof SocketException;
    }

    private JSONObject request(String provider, String url, String body, String contentType, String token, String stage, int limit,
                               Budget budget, int origin) throws ProviderException, AttemptFailure {
        HttpURLConnection connection = null;
        boolean connecting = true, bodyStarted = false, receivedResponse = false;
        try {
            budget.check(stage);
            connection = connections.open(new URL(url));
            if (!(connection instanceof HttpsURLConnection) || !url.equals(connection.getURL().toString())) throw new ProviderException("INVALID_RESPONSE", stage);
            budget.connection.set(connection); budget.check(stage);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setConnectTimeout(Math.min(origin < 0 ? 20000 : 5000, budget.remaining(stage)));
            connection.setReadTimeout(Math.min(20000, budget.remaining(stage)));
            connection.setRequestMethod(body == null ? "GET" : "POST");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Autopilot/" + (provider.equals("onevpn") ? "OneVPN" : "Octohide"));
            if (token != null) {
                if (token.isEmpty() || token.length() > 8192 || token.chars().anyMatch(value -> value <= 0x20 || value == 0x7f)) throw new ProviderException("AUTH_REQUIRED", stage);
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            byte[] bytes = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
            if (bytes != null) {
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", contentType);
                connection.setFixedLengthStreamingMode(bytes.length);
            }
            connection.connect(); connecting = false; budget.check(stage);
            if (bytes != null) {
                bodyStarted = true;
                try (OutputStream output = connection.getOutputStream()) { budget.check(stage); output.write(bytes); }
            }
            connection.setReadTimeout(Math.min(20000, budget.remaining(stage)));
            int status = connection.getResponseCode();
            receivedResponse = true; budget.check(stage);
            if (status < 100 || status > 599) throw new ProviderException("INVALID_RESPONSE", stage);
            if (origin >= 0) preferredOneOrigin = origin; // HTTP refusal is terminal, but proves this allowed host is reachable.
            if (status != 200) {
                String code = status == 429 ? "RATE_LIMITED" : status == 401 ?
                        (url.endsWith("/login") ? "INVALID_CREDENTIALS" : url.endsWith("/refresh") ? "SESSION_EXPIRED" : "AUTH_REQUIRED") :
                        status == 403 && url.endsWith("/login") ? "REQUEST_BLOCKED" : "HTTP_ERROR";
                long retryAt = status == 429 ? retryAt(connection.getHeaderField("Retry-After"), System.currentTimeMillis(), provider.equals("onevpn") ? 86400 : 365L * 86400) : 0;
                throw new ProviderException(code, stage, status, retryAt, null);
            }
            if (connection.getContentLengthLong() > limit) throw new ProviderException("INVALID_RESPONSE", stage);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while (true) {
                    connection.setReadTimeout(Math.min(20000, budget.remaining(stage)));
                    count = input.read(buffer);
                    budget.check(stage);
                    if (count == -1) break;
                    if (output.size() + count > limit) throw new ProviderException("INVALID_RESPONSE", stage);
                    output.write(buffer, 0, count);
                }
                JSONObject parsed;
                try { parsed = new JSONObject(utf8(output.toByteArray())); }
                catch (Exception ignored) { throw new ProviderException("INVALID_RESPONSE", stage); }
                budget.check(stage);
                return parsed;
            }
        } catch (ProviderException error) { throw error; }
        catch (IOException error) {
            budget.check(stage);
            throw new AttemptFailure(error instanceof SocketTimeoutException ? "TIMEOUT" : "NETWORK_ERROR", stage,
                    !receivedResponse && mayFailOver(error, body != null, bodyStarted, connecting));
        }
        catch (Exception ignored) { budget.check(stage); throw new ProviderException("NETWORK_ERROR", stage); }
        finally { if (connection != null) { budget.connection.compareAndSet(connection, null); connection.disconnect(); } }
    }

    static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    static long retryAt(String header, long now, long maxSeconds) {
        long seconds = 60;
        try {
            if (header != null && header.matches("[0-9]{1,10}")) seconds = Long.parseLong(header);
            else if (header != null) seconds = Math.max(1, (ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now + 999) / 1000);
        } catch (Exception ignored) { }
        return now + Math.min(Math.max(1, seconds), maxSeconds) * 1000;
    }
}
