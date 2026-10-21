package app.autopilot.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real semantic parser as the production ProfileValidation installer; no TUN/listener starts. */
public final class ConfigParserValidationProbe {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) throw new IllegalArgumentException("Native checker and workspace required");
        Path nativeBinary = Path.of(arguments[0]).toAbsolutePath();
        Path workspace = Path.of(arguments[1]).toAbsolutePath();
        JSONArray cases = new JSONArray(Files.readString(workspace.resolve("manifest.json"), StandardCharsets.UTF_8));
        JSONArray results = new JSONArray();
        int totalCalls = 0;
        for (int row = 0; row < cases.length(); row++) {
            JSONObject definition = cases.getJSONObject(row);
            String id = definition.getString("id");
            if (!id.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Unexpected fixture ID");
            JSONObject config = new JSONObject(Files.readString(Path.of(definition.getString("file")), StandardCharsets.UTF_8));
            JSONArray proxies = config.getJSONArray("proxies");
            List<Node> source = proxies.isEmpty() ? List.of() : NodeParser.proxies(proxies, "fixture");
            Node selected = source.isEmpty() ? null : source.get(0);
            if (!definition.getBoolean("expectedAccepted")) {
                for (Node node : source) if (node.config.optString("cipher").startsWith("fixture-nonexistent-cipher")) selected = node;
            }
            final String selectedId = selected == null ? "" : selected.id;
            Path directory = workspace.resolve("validation-" + id);
            Files.createDirectories(directory);
            int[] calls = {0};
            List<Integer> nativeIndexes = new ArrayList<>();
            List<Integer> sourceIndexes = new ArrayList<>();
            ProfileValidation.Result result = ProfileValidation.validate(source, selectedId, (candidates, selectedName) -> {
                Node expected = null;
                for (Node node : candidates) if (node.id.equals(selectedId)) expected = node;
                String expectedName = expected == null ? "REJECT" : expected.name;
                if (!expectedName.equals(selectedName)) throw new AssertionError("Installer selection mismatch for " + id);
                Path attempt = directory.resolve("attempt-" + (++calls[0]));
                Files.createDirectories(attempt);
                Path file = attempt.resolve("configuration.json");
                JSONObject exact = VpnEngine.configuration(candidates, new LinkedHashMap<>());
                Files.writeString(file, exact.toString(2), StandardCharsets.UTF_8);
                Process parser = new ProcessBuilder(nativeBinary.toString(), "-t", "-d", attempt.toString(), "-f", file.toString())
                    .redirectErrorStream(true).start();
                CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                    try { return new String(parser.getInputStream().readAllBytes(), StandardCharsets.UTF_8); }
                    catch (IOException error) { throw new RuntimeException("Native output read failed"); }
                });
                if (!parser.waitFor(30, TimeUnit.SECONDS)) { parser.destroyForcibly(); throw new IOException("Native parser timeout"); }
                String text = output.get(5, TimeUnit.SECONDS);
                // The raw native error never reaches stdout or a log file, including private input cases.
                return parser.exitValue() == 0 ? "" : ConfigParserFixtures.nativeReason(text);
            }, (diagnostic, skipped) -> {
                nativeIndexes.add(diagnostic.proxyIndex);
                sourceIndexes.clear();
                for (ProfileValidation.Skipped item : skipped) sourceIndexes.add(item.sourceIndex);
                System.out.println("NATIVE_REJECTION case=" + id + " diagnostic=" + diagnostic.json() + " skipped=" + skipped.size());
            });
            int expectedSkipped = definition.getBoolean("expectedAccepted") ? 0 : id.equals("invalid-shifted-indexes") ? 2 : 1;
            int expectedNodes = source.size() - expectedSkipped;
            boolean selectedRetained = result.selected != null;
            boolean expectedSelected = !selectedId.isEmpty() && definition.getBoolean("expectedAccepted");
            boolean matched = result.accepted && !result.limitReached && result.nodes.size() == expectedNodes
                && result.skipped.size() == expectedSkipped && selectedRetained == expectedSelected;
            if (id.equals("invalid-shifted-indexes")) matched &= nativeIndexes.equals(List.of(1, 2)) && sourceIndexes.equals(List.of(1, 3));
            JSONObject record = new JSONObject().put("id", id).put("sourceNodes", source.size()).put("accepted", result.accepted)
                .put("remainingNodes", result.nodes.size()).put("skipped", result.skipped.size()).put("selectedRetained", selectedRetained)
                .put("nativeCalls", calls[0]).put("nativeProxyIndexes", nativeIndexes).put("sourceIndexes", sourceIndexes).put("matched", matched);
            results.put(record); totalCalls += calls[0];
            Files.writeString(workspace.resolve("validation-results.json"), results.toString(2), StandardCharsets.UTF_8);
            System.out.println("NATIVE_VALIDATION " + record);
            if (!matched) throw new AssertionError("Native validation mismatch for " + id);
        }
        System.out.println("NATIVE_VALIDATION_SUMMARY matched=" + results.length() + "/" + cases.length() + " nativeCalls=" + totalCalls);
    }
}
