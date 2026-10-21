package app.autopilot.android;

import org.json.JSONArray;
import org.json.JSONObject;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.IDN;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses configuration data only. Never executes wg-quick hooks or imports root config directives. */
public final class NodeParser {
    private static final int MAX_TEXT = 8 * 1024 * 1024;
    private static final Set<String> RESERVED = new HashSet<>(java.util.Arrays.asList(
            "COMPATIBLE", "DIRECT", "GLOBAL", "PASS", "PASS-RULE", "REJECT", "REJECT-DROP", "AUTOPILOT"));
    private static final Pattern JUNK = Pattern.compile("www\\.101pr\\.cc|更新订阅|请升级|剩余流量|到期|过期|官网|expire|traffic", Pattern.CASE_INSENSITIVE);
    private NodeParser() { }

    public static List<Node> parse(String text, String provider) throws ProviderException {
        try {
            if (text == null || text.length() == 0 || text.length() > MAX_TEXT || text.indexOf('\0') >= 0) throw new Exception();
            text = text.replaceFirst("^\\uFEFF", "");
            String firstSetting = "";
            for (String line : text.split("\\r?\\n")) {
                String clean = line.replaceFirst("\\s*[#;].*$", "").trim();
                if (!clean.isEmpty()) { firstSetting = clean; break; }
            }
            if (firstSetting.equalsIgnoreCase("[Interface]"))
                return Collections.singletonList(wireguard(text, "Imported WireGuard", null, provider));
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(50);
            options.setNestingDepthLimit(50); options.setCodePointLimit(MAX_TEXT);
            Object raw = new Yaml(new SafeConstructor(options)).load(text);
            Object normalized = jsonValue(raw, 0);
            JSONArray list = normalized instanceof JSONArray ? (JSONArray) normalized :
                    normalized instanceof JSONObject ? ((JSONObject) normalized).optJSONArray("proxies") : null;
            return proxies(list, provider);
        } catch (ProviderException error) { throw error; }
        catch (Exception ignored) { throw new ProviderException("IMPORT_INVALID", "subscription-parse"); }
    }

    static List<Node> proxies(JSONArray list, String provider) throws ProviderException {
        if (list == null || list.length() > 2000) throw new ProviderException("INVALID_SUBSCRIPTION", "subscription-parse");
        List<Node> result = new ArrayList<>(); Set<String> names = new HashSet<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject proxy = list.optJSONObject(i);
            if (proxy == null) continue;
            String name = strictText(proxy.opt("name"), 256), type = strictText(proxy.opt("type"), 40), server = strictText(proxy.opt("server"), 253);
            Object port = proxy.opt("port");
            if (name == null || type == null || server == null || RESERVED.contains(name) || JUNK.matcher(name).find() ||
                    !(port instanceof Number) || ((Number) port).doubleValue() != ((Number) port).intValue() ||
                    ((Number) port).intValue() < 1 || ((Number) port).intValue() > 65535 || !names.add(name)) continue;
            try { result.add(new Node(new JSONObject(proxy.toString()), provider, null)); }
            catch (Exception ignored) { }
        }
        if (result.isEmpty()) throw new ProviderException("INVALID_SUBSCRIPTION", "subscription-parse");
        return result;
    }

    static List<Node> decryptOneVpn(JSONObject raw) throws ProviderException {
        try {
            byte[] iv = canonicalBase64(raw.opt("iv"), 16), data = canonicalBase64(raw.opt("data"), MAX_TEXT);
            if (iv.length != 16 || data.length == 0 || data.length % 16 != 0) throw new Exception();
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec("x9Kp2mNv7QwTr4Yb8FjHs6DcAeLg0UiZ".getBytes(StandardCharsets.UTF_8), "AES"), new IvParameterSpec(iv));
            return parse(ProviderHttp.utf8(cipher.doFinal(data)), "onevpn");
        } catch (Exception ignored) { throw new ProviderException("INVALID_SUBSCRIPTION", "subscription-parse"); }
    }

    private static Object jsonValue(Object value, int depth) throws Exception {
        if (depth > 50) throw new Exception();
        if (value == null) return JSONObject.NULL;
        if (value instanceof Map) {
            JSONObject result = new JSONObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new Exception();
                result.put((String) entry.getKey(), jsonValue(entry.getValue(), depth + 1));
            }
            return result;
        }
        if (value instanceof List) {
            JSONArray result = new JSONArray();
            for (Object row : (List<?>) value) result.put(jsonValue(row, depth + 1));
            return result;
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Number) return value;
        throw new Exception();
    }

    static byte[] canonicalBase64(Object value, int max) throws Exception {
        if (!(value instanceof String)) throw new Exception();
        String text = (String) value;
        if (text.length() > ((long) max + 2) / 3 * 4 || text.length() % 4 != 0) throw new Exception();
        // Avoid Java's recursive regex engine on multi-megabyte encrypted subscriptions.
        int padding = text.endsWith("==") ? 2 : text.endsWith("=") ? 1 : 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (i >= text.length() - padding) { if (c != '=') throw new Exception(); }
            else if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/')) throw new Exception();
        }
        byte[] result = Base64.getDecoder().decode(text);
        if (result.length > max || !Base64.getEncoder().encodeToString(result).equals(text)) throw new Exception();
        return result;
    }

    static String strictText(Object value, int max) {
        if (!(value instanceof String)) return null;
        String text = (String) value;
        return text.trim().isEmpty() || text.length() > max || Pattern.compile("[\\x00-\\x1f\\x7f]").matcher(text).find() ? null : text;
    }

    public static Node wireguard(String text, String name, String country, String provider) throws ProviderException {
        try {
            if (text == null || text.length() > 65536 || text.indexOf('\0') >= 0 || strictText(name, 256) == null) throw new Exception();
            Map<String, String> iface = null, peer = null, current = null;
            for (String raw : text.replaceFirst("^\\uFEFF", "").split("\\r?\\n")) {
                String line = raw.replaceFirst("\\s*[#;].*$", "").trim();
                if (line.isEmpty()) continue;
                if (line.equalsIgnoreCase("[Interface]")) { if (iface != null) throw new Exception(); current = iface = new LinkedHashMap<>(); continue; }
                if (line.equalsIgnoreCase("[Peer]")) { if (peer != null) throw new Exception(); current = peer = new LinkedHashMap<>(); continue; }
                Matcher entry = Pattern.compile("^([A-Za-z][A-Za-z0-9]*)\\s*=\\s*(.+)$").matcher(line);
                if (current == null || !entry.matches()) throw new Exception();
                String key = entry.group(1).toLowerCase(Locale.ROOT);
                if (current.put(key, entry.group(2).trim()) != null) throw new Exception();
            }
            if (iface == null || peer == null) throw new Exception();
            for (String key : iface.keySet()) if (!java.util.Arrays.asList("privatekey", "address", "dns", "mtu", "listenport").contains(key)) throw new Exception();
            for (String key : peer.keySet()) if (!java.util.Arrays.asList("publickey", "presharedkey", "endpoint", "allowedips", "persistentkeepalive").contains(key)) throw new Exception();
            JSONObject config = new JSONObject();
            String flag = country != null && country.matches("[A-Z]{2}") ? new String(Character.toChars(0x1f1e6 + country.charAt(0) - 'A')) + new String(Character.toChars(0x1f1e6 + country.charAt(1) - 'A')) + " " : "";
            config.put("name", ("octohide".equals(provider) ? "Octohide" : "import") + " | " + flag + name.trim())
                    .put("type", "wireguard").put("private-key", key32(iface.get("privatekey")))
                    .put("public-key", key32(peer.get("publickey"))).put("udp", true);
            Matcher endpoint = Pattern.compile("^(?:\\[([^\\]]+)\\]|([^:\\s]+)):(\\d+)$").matcher(peer.getOrDefault("endpoint", ""));
            if (!endpoint.matches()) throw new Exception();
            String host = endpoint.group(1) == null ? endpoint.group(2) : endpoint.group(1);
            int family = ipFamily(host);
            if (endpoint.group(1) != null && family != 6) throw new Exception();
            if (family == 0) {
                host = IDN.toASCII(host).toLowerCase(Locale.ROOT);
                if (host.length() > 253 || host.isEmpty()) throw new Exception();
                for (String part : host.split("\\.", -1)) if (!part.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")) throw new Exception();
            }
            config.put("server", host).put("port", boundedInteger(endpoint.group(3), 1, 65535));
            Set<Integer> families = new HashSet<>();
            for (String address : iface.getOrDefault("address", "").split(",", -1)) {
                String normalized = cidr(address.trim()); String ip = normalized.substring(0, normalized.indexOf('/'));
                int addressFamily = ipFamily(ip);
                if (!families.add(addressFamily)) throw new Exception();
                config.put(addressFamily == 4 ? "ip" : "ipv6", ip);
            }
            JSONArray allowed = new JSONArray();
            for (String item : peer.getOrDefault("allowedips", "").split(",", -1)) allowed.put(cidr(item.trim()));
            config.put("allowed-ips", allowed);
            if (peer.containsKey("presharedkey")) config.put("pre-shared-key", key32(peer.get("presharedkey")));
            if (peer.containsKey("persistentkeepalive")) config.put("persistent-keepalive", boundedInteger(peer.get("persistentkeepalive"), 0, 65535));
            if (iface.containsKey("mtu")) config.put("mtu", boundedInteger(iface.get("mtu"), 576, 9000));
            if (iface.containsKey("listenport")) boundedInteger(iface.get("listenport"), 0, 65535);
            if (iface.containsKey("dns")) {
                JSONArray dns = new JSONArray();
                for (String value : iface.get("dns").split(",", -1)) { if (ipFamily(value.trim()) == 0) throw new Exception(); dns.put(value.trim()); }
                config.put("dns", dns).put("remote-dns-resolve", true);
            }
            return new Node(config, provider, country);
        } catch (Exception ignored) { throw new ProviderException("IMPORT_INVALID", "subscription-parse"); }
    }

    private static String key32(String value) throws Exception {
        if (value == null || value.length() != 44 || canonicalBase64(value, 32).length != 32) throw new Exception();
        return value;
    }
    private static int boundedInteger(String value, int min, int max) throws Exception {
        if (value == null || !value.matches("[0-9]{1,10}")) throw new Exception();
        long n = Long.parseLong(value);
        if (n < min || n > max) throw new Exception();
        return (int) n;
    }
    private static String cidr(String value) throws Exception {
        String[] parts = value.split("/", -1);
        int family = ipFamily(parts[0]);
        if (family == 0 || parts.length > 2) throw new Exception();
        int prefix = parts.length == 1 ? (family == 4 ? 32 : 128) : boundedInteger(parts[1], 0, family == 4 ? 32 : 128);
        return parts[0] + "/" + prefix;
    }
    static int ipFamily(String value) {
        if (value == null || value.isEmpty()) return 0;
        if (value.indexOf(':') >= 0) {
            if (!value.matches("[0-9a-fA-F:.]+")) return 0;
            try { InetAddress.getByName(value); return 6; } catch (Exception ignored) { return 0; }
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return 0;
        for (String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) return 0;
        }
        return 4;
    }
}
