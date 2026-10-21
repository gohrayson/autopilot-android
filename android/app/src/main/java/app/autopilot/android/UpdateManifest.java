package app.autopilot.android;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/** Public, credential-free, same-origin HTTPS release metadata. No Android dependencies. */
public final class UpdateManifest {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_MANIFEST_BYTES = 64 * 1024;
    public static final long MAX_APK_BYTES = 200L * 1024 * 1024;
    public static final int MAX_NOTES_BYTES = 4 * 1024;
    private static final Pattern VERSION_NAME = Pattern.compile(
            "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");

    /** The only field catalog: required-field and unknown-field checks derive from it. */
    private enum Field {
        SCHEMA("schemaVersion", true), PACKAGE("packageName", true), CODE("versionCode", true),
        VERSION("versionName", true), SDK("minSdk", true), APK("apkUrl", true),
        HASH("sha256", true), SIZE("sizeBytes", true), NOTES("notes", false), PUBLISHED("publishedAt", false);
        final String key;
        final boolean required;
        Field(String key, boolean required) { this.key = key; this.required = required; }
    }
    private static final Map<String, Field> FIELDS = new HashMap<>();
    static { for (Field field : Field.values()) FIELDS.put(field.key, field); }

    public final int schemaVersion;
    public final String packageName;
    public final int versionCode;
    public final String versionName;
    public final int minSdk;
    public final String apkUrl;
    public final String sha256;
    public final long sizeBytes;
    public final String notes;
    public final String publishedAt;
    public final String manifestUrl;

    private UpdateManifest(JSONObject json, String manifestUrl, URI apk) throws Exception {
        this.schemaVersion = SCHEMA_VERSION;
        this.packageName = string(json, Field.PACKAGE);
        this.versionCode = (int) integer(json, Field.CODE, 1, Integer.MAX_VALUE);
        this.versionName = string(json, Field.VERSION);
        this.minSdk = (int) integer(json, Field.SDK, 1, Integer.MAX_VALUE);
        this.apkUrl = apk.toASCIIString();
        this.sha256 = string(json, Field.HASH);
        this.sizeBytes = integer(json, Field.SIZE, 1, MAX_APK_BYTES);
        this.notes = json.has(Field.NOTES.key) ? string(json, Field.NOTES) : "";
        this.publishedAt = json.has(Field.PUBLISHED.key) ? string(json, Field.PUBLISHED) : "";
        this.manifestUrl = manifestUrl;
    }

    public boolean isNewerThan(long installedVersionCode) { return versionCode > installedVersionCode; }
    public boolean supportsSdk(int sdk) { return sdk >= minSdk; }

    public static UpdateManifest parse(String json, String manifestUrl, String expectedPackage)
            throws UpdateDownload.UpdateException {
        URI source = publicHttpsUri(manifestUrl);
        try {
            if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES)
                throw invalid();
            JSONTokener tokener = new JSONTokener(json);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) throw invalid();
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) if (!FIELDS.containsKey(keys.next())) throw invalid();
            for (Field field : Field.values()) {
                if (field.required && (!object.has(field.key) || object.isNull(field.key))) throw invalid();
            }
            if (integer(object, Field.SCHEMA, 1, 1) != SCHEMA_VERSION) throw invalid();
            String packageName = string(object, Field.PACKAGE);
            if (expectedPackage == null || !packageName.equals(expectedPackage)
                    || !packageName.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")) throw invalid();
            String versionName = string(object, Field.VERSION);
            if (versionName.length() > 80 || !VERSION_NAME.matcher(versionName).matches()
                    || hasLeadingZeroPrerelease(versionName)) throw invalid();
            String hash = string(object, Field.HASH);
            if (!hash.matches("[0-9a-f]{64}")) throw invalid();
            URI apk = publicHttpsUri(string(object, Field.APK));
            if (!sameOrigin(source, apk)) throw new UpdateDownload.UpdateException("UPDATE_UNSAFE_URL");
            UpdateManifest manifest = new UpdateManifest(object, source.toASCIIString(), apk);
            if (manifest.notes.getBytes(StandardCharsets.UTF_8).length > MAX_NOTES_BYTES
                    || hasUnsafeText(manifest.notes)) throw invalid();
            if (!manifest.publishedAt.isEmpty()) {
                if (manifest.publishedAt.length() > 64) throw invalid();
                Instant.parse(manifest.publishedAt);
            }
            return manifest;
        } catch (UpdateDownload.UpdateException error) { throw error; }
        catch (Exception ignored) { throw invalid(); }
    }

    /** A public feed must not carry credentials, fragments or token-bearing query parameters. */
    public static URI publicHttpsUri(String url) throws UpdateDownload.UpdateException {
        try {
            if (url == null || url.length() > 2048 || url.indexOf('\\') >= 0) throw invalid();
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getRawQuery() != null
                    || uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65535
                    || !uri.isAbsolute() || uri.isOpaque()) throw invalid();
            return uri;
        } catch (Exception ignored) { throw new UpdateDownload.UpdateException("UPDATE_UNSAFE_URL"); }
    }

    static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }
    private static String string(JSONObject json, Field field) throws Exception {
        Object value = json.get(field.key);
        if (!(value instanceof String)) throw invalid();
        return (String) value;
    }
    private static long integer(JSONObject json, Field field, long minimum, long maximum) throws Exception {
        Object value = json.get(field.key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid();
        long number = ((Number) value).longValue();
        if (number < minimum || number > maximum) throw invalid();
        return number;
    }
    private static boolean hasUnsafeText(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')
                    || c == '\u202a' || c == '\u202b' || c == '\u202c' || c == '\u202d' || c == '\u202e'
                    || c == '\u2066' || c == '\u2067' || c == '\u2068' || c == '\u2069') return true;
        }
        return false;
    }
    private static boolean hasLeadingZeroPrerelease(String value) {
        int separator = value.indexOf('-');
        int metadata = value.indexOf('+');
        if (separator == -1 || (metadata != -1 && separator > metadata)) return false;
        String prerelease = value.substring(separator + 1, metadata == -1 ? value.length() : metadata);
        for (String identifier : prerelease.split("\\."))
            if (identifier.matches("0[0-9]+")) return true;
        return false;
    }
    private static UpdateDownload.UpdateException invalid() { return new UpdateDownload.UpdateException("UPDATE_INVALID_MANIFEST"); }
}
