package app.autopilot.android;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** One immutable provider outbound; credentials never appear in public UI state. */
public final class Node {
    public static final Set<String> SUPPORTED_TYPES=Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        "vless","vmess","ss","ssr","trojan","hysteria","hysteria2","tuic","wireguard","socks5","http","snell","anytls")));
    public final String name, country, provider, id;
    public final JSONObject config;
    public Node(JSONObject outbound, String provider, String country) throws Exception {
        config = new JSONObject(outbound.toString());
        name = config.getString("name");
        if (name.isBlank() || name.length() > 256 || name.chars().anyMatch(c -> c < 32) ||
            Set.of("DIRECT", "REJECT", "GLOBAL", "AUTOPILOT", "COMPATIBLE", "PASS").contains(name.toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("Invalid node name");
        String type = config.getString("type").toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(type))
            throw new IllegalArgumentException("Unsupported node type");
        String server = config.getString("server"); int port = config.getInt("port");
        if (server.isBlank() || server.length() > 253 || server.chars().anyMatch(c -> c <= 32) || port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid node endpoint");
        this.provider = provider;
        String region = country == null ? regionOf(name) : country;
        this.country = region != null && region.matches("[A-Z]{2}") ? region : "";
        if (Set.of("CN", "HK", "MO").contains(this.country)) throw new IllegalArgumentException("Unsupported service region");
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical(config).getBytes(StandardCharsets.UTF_8));
        StringBuilder h = new StringBuilder(); for (byte b : hash) h.append(String.format(Locale.ROOT, "%02x", b & 255)); id = h.toString();
    }
    private static String canonical(Object value) throws JSONException {
        if (value instanceof JSONObject j) {
            ArrayList<String> keys = new ArrayList<>(); j.keys().forEachRemaining(keys::add); Collections.sort(keys);
            StringJoiner result = new StringJoiner(",", "{", "}");
            for (String k : keys) result.add(JSONObject.quote(k) + ":" + canonical(j.get(k))); return result.toString();
        }
        if (value instanceof JSONArray a) { StringJoiner r = new StringJoiner(",", "[", "]"); for (int i=0;i<a.length();i++) r.add(canonical(a.get(i))); return r.toString(); }
        return value instanceof String s ? JSONObject.quote(s) : String.valueOf(value);
    }
    public static String regionOf(String label) {
        int[] chars = label.codePoints().toArray();
        for (int i=0;i+1<chars.length;i++) if (chars[i]>=0x1f1e6 && chars[i]<=0x1f1ff && chars[i+1]>=0x1f1e6 && chars[i+1]<=0x1f1ff)
            return "" + (char)('A'+chars[i]-0x1f1e6) + (char)('A'+chars[i+1]-0x1f1e6);
        String lower = label.toLowerCase(Locale.ROOT);
        String[][] places = {{"澳大利亚","AU"},{"马来西亚","MY"},{"俄罗斯","RU"},{"土耳其","TR"},{"加拿大","CA"},{"新加坡","SG"},{"菲律宾","PH"},{"西班牙","ES"},{"香港","HK"},{"澳门","MO"},{"台湾","TW"},{"日本","JP"},{"韩国","KR"},{"印尼","ID"},{"印度","IN"},{"泰国","TH"},{"越南","VN"},{"英国","GB"},{"法国","FR"},{"德国","DE"},{"荷兰","NL"},{"瑞典","SE"},{"巴西","BR"},{"美国","US"},{"美西","US"},{"美东","US"},{"hong kong","HK"},{"singapore","SG"},{"taiwan","TW"},{"japan","JP"},{"korea","KR"},{"united states","US"},{"malaysia","MY"},{"australia","AU"},{"germany","DE"},{"united kingdom","GB"}};
        for (String[] p : places) if (lower.contains(p[0])) return p[1]; return "";
    }
}
