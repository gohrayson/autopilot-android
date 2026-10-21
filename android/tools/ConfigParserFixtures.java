package app.autopilot.android;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Emits production configuration() output with public, synthetic fixture credentials only. */
public final class ConfigParserFixtures {
    private static Node imported(JSONObject proxy) throws Exception {
        String profile = new JSONObject().put("proxies", new JSONArray().put(proxy)).toString();
        return NodeParser.parse(profile, "fixture").get(0);
    }

    private static String key(int seed) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (seed + i);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static Node wireguard(boolean dualStack) throws Exception {
        String text = "[Interface]\nPrivateKey = " + key(1)
            + "\nAddress = 10.77.0.2/32" + (dualStack ? ", fd77::2/128" : "")
            + "\nDNS = 1.1.1.1" + (dualStack ? ", 2606:4700:4700::1111" : "")
            + "\nMTU = 1280\n[Peer]\nPublicKey = " + key(65)
            + "\nPresharedKey = " + key(129)
            + "\nEndpoint = " + (dualStack ? "[2001:db8::10]:51820" : "192.0.2.10:51820")
            + "\nAllowedIPs = 0.0.0.0/0" + (dualStack ? ", ::/0" : "")
            + "\nPersistentKeepalive = 25\n";
        return NodeParser.wireguard(text, dualStack ? "Fixture dual-stack" : "Fixture IPv4", "SG", "octohide");
    }

    private static void emit(Path directory, JSONArray manifest, String id, List<Node> nodes, boolean expectedAccepted) throws Exception {
        Map<String, Integer> ports = new LinkedHashMap<>();
        JSONObject configuration = VpnEngine.configuration(nodes, ports);
        Path output = directory.resolve(id + ".json");
        Files.write(output, CoreConfigEncoding.encode(configuration));
        manifest.put(new JSONObject().put("id", id).put("file", output.toAbsolutePath().toString())
            .put("nodes", nodes.size()).put("expectedAccepted", expectedAccepted));
        System.out.println("EMIT " + id + " nodes=" + nodes.size() + " expected=" + (expectedAccepted ? "accept" : "reject"));
    }

    /** Unwraps the CLI log envelope into the same parser error string returned by quickSetup. */
    static String nativeReason(String output) {
        for (String line : output.split("\\R")) {
            if (!line.contains("level=error") && !line.contains("level=fatal")) continue;
            int offset = line.indexOf(" msg=");
            if (offset < 0) continue;
            String encoded = line.substring(offset + 5).trim();
            try {
                Object value = new org.json.JSONTokener(encoded).nextValue();
                if (value instanceof String) return (String) value;
            } catch (Exception ignored) { }
        }
        return output;
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 2 && "--classify".equals(arguments[0])) {
            JSONObject configuration = new JSONObject(Files.readString(Path.of(arguments[1]), StandardCharsets.UTF_8));
            List<String> types = new ArrayList<>();
            JSONArray proxies = configuration.getJSONArray("proxies");
            for (int i = 0; i < proxies.length(); i++) types.add(proxies.getJSONObject(i).getString("type"));
            String raw = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            System.out.println(CoreDiagnostics.classify(nativeReason(raw), types).json().toString());
            return;
        }
        if (arguments.length < 1 || arguments.length > 2) throw new IllegalArgumentException("Output directory and optional private profile required");
        Path directory = Path.of(arguments[0]).toAbsolutePath();
        Files.createDirectories(directory);
        if (arguments.length == 2) {
            try {
                List<Node> input = NodeParser.parse(Files.readString(Path.of(arguments[1]), StandardCharsets.UTF_8), "fixture");
                JSONArray manifest = new JSONArray();
                emit(directory, manifest, "input-profile", input, true);
                Files.writeString(directory.resolve("manifest.json"), manifest.toString(2), StandardCharsets.UTF_8);
                return;
            } catch (Exception ignored) {
                System.err.println("INPUT_PROFILE_EXPORT_FAILED category=SOURCE_PARSE_REJECTED");
                System.exit(2);
            }
        }
        Node socks = imported(new JSONObject().put("name", "Fixture SOCKS5 SG").put("type", "socks5")
            .put("server", "192.0.2.20").put("port", 1080).put("udp", true));
        Node vless = imported(new JSONObject().put("name", "Fixture VLESS JP").put("type", "vless")
            .put("server", "192.0.2.21").put("port", 443).put("uuid", "00000000-0000-4000-8000-000000000001")
            .put("tls", true).put("servername", "fixture.invalid").put("client-fingerprint", "chrome").put("udp", true));
        Node wg4 = wireguard(false), wgDual = wireguard(true);
        // Import validation does not know the core's cipher catalog; native parsing must reject this.
        Node invalid = imported(new JSONObject().put("name", "Fixture INVALID SS CIPHER").put("type", "ss")
            .put("server", "192.0.2.22").put("port", 443).put("cipher", "fixture-nonexistent-cipher").put("password", "public-fixture-password"));
        List<Node> allValid = List.of(socks, vless, wg4, wgDual);
        List<Node> allWithInvalid = new ArrayList<>(allValid); allWithInvalid.add(invalid);
        JSONArray manifest = new JSONArray();
        emit(directory, manifest, "empty", List.of(), true);
        emit(directory, manifest, "socks5", List.of(socks), true);
        emit(directory, manifest, "vless", List.of(vless), true);
        emit(directory, manifest, "wireguard-ipv4", List.of(wg4), true);
        emit(directory, manifest, "wireguard-dual-stack", List.of(wgDual), true);
        emit(directory, manifest, "all-valid", allValid, true);
        emit(directory, manifest, "invalid-shadowsocks-cipher", List.of(invalid), false);
        emit(directory, manifest, "invalid-with-wireguards", List.of(invalid, wg4, wgDual), false);
        emit(directory, manifest, "invalid-with-all", allWithInvalid, false);
        Node invalidSecond = imported(new JSONObject().put("name", "Fixture SECOND INVALID SS").put("type", "ss")
            .put("server", "192.0.2.23").put("port", 443).put("cipher", "fixture-nonexistent-cipher-2").put("password", "public-fixture-password-2"));
        emit(directory, manifest, "invalid-shifted-indexes", List.of(socks, invalid, vless, invalidSecond, wg4, wgDual), false);
        Files.writeString(directory.resolve("manifest.json"), manifest.toString(2), StandardCharsets.UTF_8);
    }
}
