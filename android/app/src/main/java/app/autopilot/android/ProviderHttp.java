package app.autopilot.android;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Fixed HTTPS origins, bounded JSON, no redirects and no arbitrary response errors. */
public class ProviderHttp {
    static final String ONE_ORIGIN = "https://api.101proxy.top";
    static final String OCTO_URL = "https://ohgp2.serviseapi.com/api/v2/";
    private static final String[] ONE_PATHS = { "/api/auth/login", "/api/auth/refresh", "/api/user/client-info", "/api/user/subscription/encrypted" };

    public JSONObject one(String path, JSONObject body, String accessToken, String stage) throws ProviderException {
        boolean found = false;
        for (String allowed : ONE_PATHS) if (allowed.equals(path)) found = true;
        if (!found) throw new ProviderException("INVALID_INPUT", stage);
        return request("onevpn", ONE_ORIGIN + path, body == null ? null : body.toString(), "application/json", accessToken, stage, 8 * 1024 * 1024);
    }

    public JSONObject octo(Map<String, String> form, String stage) throws ProviderException {
        try {
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> entry : form.entrySet()) {
                if (body.length() != 0) body.append('&');
                body.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=')
                        .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            }
            return request("octohide", OCTO_URL, body.toString(), "application/x-www-form-urlencoded", null, stage, 1024 * 1024);
        } catch (ProviderException error) { throw error; }
        catch (Exception ignored) { throw new ProviderException("INVALID_INPUT", stage); }
    }

    private JSONObject request(String provider, String url, String body, String contentType, String token, String stage, int limit) throws ProviderException {
        HttpURLConnection connection = null;
        long started = System.nanoTime();
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(20000); connection.setReadTimeout(20000);
            connection.setRequestMethod(body == null ? "GET" : "POST");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Autopilot/" + (provider.equals("onevpn") ? "OneVPN" : "Octohide"));
            if (token != null) {
                if (token.length() > 8192 || token.matches(".*[\\x00-\\x20\\x7f].*")) throw new ProviderException("AUTH_REQUIRED", stage);
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", contentType);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
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
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > limit) throw new ProviderException("INVALID_RESPONSE", stage);
                    if (System.nanoTime() - started > 30000000000L) throw new ProviderException("TIMEOUT", stage);
                    output.write(buffer, 0, count);
                }
                try { return new JSONObject(utf8(output.toByteArray())); }
                catch (Exception ignored) { throw new ProviderException("INVALID_RESPONSE", stage); }
            }
        } catch (ProviderException error) { throw error; }
        catch (SocketTimeoutException ignored) { throw new ProviderException("TIMEOUT", stage); }
        catch (Exception ignored) { throw new ProviderException("NETWORK_ERROR", stage); }
        finally { if (connection != null) connection.disconnect(); }
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
