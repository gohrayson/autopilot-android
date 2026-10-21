package app.autopilot.android;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Fixture transport only: never touches a Windows account, external API, proxy or VPN. */
public class ProviderRepositoryTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static String wg() { return "[Interface]\nPrivateKey=" + KEY + "\nAddress=10.0.0.2/32,fd00::2/128\nDNS=1.1.1.1\nMTU=1280\n[Peer]\nPublicKey=" + KEY + "\nEndpoint=example.test:51820\nAllowedIPs=0.0.0.0/0,::/0\nPersistentKeepalive=25\n"; }
    private static String proxy(String label) { return "proxies:\n  - name: \"" + label + "\"\n    type: vless\n    server: fixture.invalid\n    port: 443\n    uuid: fixture\n"; }
    private static JSONObject encrypted(String yaml) throws Exception {
        byte[] iv = new byte[16];
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec("x9Kp2mNv7QwTr4Yb8FjHs6DcAeLg0UiZ".getBytes(StandardCharsets.UTF_8), "AES"), new IvParameterSpec(iv));
        return ProviderRepository.object("iv", Base64.getEncoder().encodeToString(iv), "data", Base64.getEncoder().encodeToString(cipher.doFinal(yaml.getBytes(StandardCharsets.UTF_8))));
    }
    private static void code(String expected, Throwing job) throws Exception {
        try { job.run(); fail("Expected " + expected); } catch (ProviderException error) { assertEquals(expected, error.code); }
    }
    interface Throwing { void run() throws Exception; }
    static final class MemoryStore extends SecureStore {
        final Map<String, JSONObject> saved = new HashMap<>();
        boolean failNonNullWrites;
        int failedWrites, deletes;
        MemoryStore() { super(); }
        @Override public JSONObject load(String name) throws ProviderException {
            try { JSONObject value = saved.get(name); return value == null ? null : new JSONObject(value.toString()); }
            catch (Exception ignored) { throw new ProviderException("CREDENTIAL_STORE", "restore"); }
        }
        @Override public void save(String name, JSONObject value) throws ProviderException {
            if (value != null && failNonNullWrites) { failedWrites++; throw new ProviderException("CREDENTIAL_STORE", "session-save"); }
            if (value == null) deletes++;
            try { if (value == null) saved.remove(name); else saved.put(name, new JSONObject(value.toString())); }
            catch (Exception ignored) { throw new ProviderException("CREDENTIAL_STORE", "session-save"); }
        }
    }
    static final class Time implements ProviderRepository.Clock {
        long now = 1700000000000L;
        @Override public long now() { return now; }
        void advance(long ms) { now += ms; }
    }
    static final class FixtureHttp extends ProviderHttp {
        final Time time; final MemoryStore store;
        int oneLogins, oneRefreshes, oneCalls, octoCalls, exports, failExportAt;
        String label = "Japan fixture", failOneCode, failExportCode = "REQUEST_LIMIT_LONG";
        boolean expired, octoPaid = true, octoAuth = true, rotateImmediately, assertRotationSaved;
        CountDownLatch blockEntered, release;
        final List<Long> exportTimes = new ArrayList<>();
        final Map<String, Long> initTimes = new HashMap<>(), regionTimes = new HashMap<>();
        FixtureHttp(Time time, MemoryStore store) { this.time = time; this.store = store; }
        @Override public JSONObject one(String path, JSONObject body, String token, String stage) throws ProviderException {
            oneCalls++;
            try {
                if (path.endsWith("/login")) {
                    oneLogins++; assertEquals("windows_app", body.getString("appId")); assertFalse(body.getString("password").isEmpty());
                    return ProviderRepository.object("accessToken", "access-" + oneLogins, "refreshToken", "refresh-" + oneLogins, "deviceId", "fixture-device", "expiresIn", rotateImmediately ? 1 : 900);
                }
                if (path.endsWith("/refresh")) {
                    oneRefreshes++;
                    return ProviderRepository.object("accessToken", "rotated-access", "refreshToken", "rotated-refresh", "deviceId", "fixture-device", "expiresIn", 900);
                }
                if (path.endsWith("client-info")) {
                    if (blockEntered != null) { blockEntered.countDown(); if (!release.await(3, TimeUnit.SECONDS)) throw new Exception(); }
                    if (assertRotationSaved) assertEquals("rotated-refresh", store.saved.get("onevpn").getJSONObject("pendingSession").getString("refreshToken"));
                    if (failOneCode != null) throw new ProviderException(failOneCode, stage);
                    return ProviderRepository.object("status", "success", "user", ProviderRepository.object("email", "fixture@example.invalid", "isBanned", false, "expireTime", expired ? time.now - 1 : time.now + 86400000));
                }
                return encrypted(proxy(label));
            } catch (ProviderException error) { throw error; }
            catch (Exception ignored) { throw new ProviderException("INVALID_RESPONSE", stage); }
        }
        @Override public JSONObject octo(Map<String, String> form, String stage) throws ProviderException {
            octoCalls++;
            try {
                String action = form.containsKey("action") ? form.get("action") : form.get("action[0]");
                String device = form.get("id"); long timestamp = time.now / 1000;
                assertEquals("v2.250", form.get("client_version")); assertEquals("Android", form.get("os"));
                assertEquals(16, form.get("requestid").length());
                JSONObject result = ProviderRepository.object("timestamp", timestamp, "authenticated", octoAuth ? 1 : 0, "replyid", "2");
                switch (action) {
                    case "init": result.put("profile", 1).put("init", 1); initTimes.put(device, timestamp); break;
                    case "login": result.put("sid", "fixture-sid").put("profile_id", 1).put("shared", 0).put("authenticated", 1); break;
                    case "info": result.put("vip", octoPaid).put("subscription_end", time.now / 1000 + 86400).put("profile_id", 1).put("username", "fixture@example.invalid").put("shared", 0).put("activated", true)
                            .put("cfg", ProviderRepository.object("export_expire_unused", 1800, "export_expire_inactive", 1800)); break;
                    case "regions":
                        JSONArray rows = new JSONArray(); String[] countries = {"MY", "SG", "JP", "DE", "HK", "US"};
                        for (int i = 0; i < countries.length; i++) rows.put(ProviderRepository.object("id", i + 1, "name", "Region " + countries[i], "country_code", countries[i], "android", 1, "f", 0, "v", 1));
                        result.put("replyid", ProviderRepository.replyId(device, timestamp)).put("list", rows); regionTimes.put(device, timestamp); break;
                    case "configexport":
                        exports++; exportTimes.add(time.now);
                        if (exports == failExportAt) return ProviderRepository.object("error", failExportCode, "code", 1800);
                        assertEquals(ProviderRepository.platformId(initTimes.get(device)), form.get("platformid"));
                        assertEquals(ProviderRepository.configId(device, initTimes.get(device), regionTimes.get(device)), form.get("configid"));
                        result.put("region", form.get("region")).put("address", "10.0.0.2").put("text", wg()); break;
                    default: throw new Exception();
                }
                return ProviderRepository.object(action, result);
            } catch (ProviderException error) { throw error; }
            catch (Exception ignored) { throw new ProviderException("INVALID_RESPONSE", stage); }
        }
    }
    static final class Fixture {
        final MemoryStore store = new MemoryStore(); final Time time = new Time();
        final FixtureHttp http = new FixtureHttp(time, store);
        final ProviderRepository repo = new ProviderRepository(store, http, time, time::advance);
        void one() throws Exception { repo.login("onevpn", "fixture@example.invalid", "fixture-password", List.of()); }
        void octo() throws Exception { repo.login("octohide", "fixture@example.invalid", "fixture-password", List.of("MY", "SG", "JP", "DE")); }
    }

    @Test public void officialCryptoAndSignatureVectorsMatchDesktop() throws Exception {
        assertEquals("0f4fd01a", ProviderRepository.replyId("0123456789abcdef", 1700000000));
        assertEquals("f95baa", ProviderRepository.platformId(1700000000));
        assertEquals("5292e0b2", ProviderRepository.configId("0123456789abcdef", 1700000000, 1700000001));
        assertTrue(ProviderRepository.evenReply("7ffffffffffffffe")); assertFalse(ProviderRepository.evenReply("8000000000000000")); assertFalse(ProviderRepository.evenReply("3"));
        assertEquals("Japan fixture", NodeParser.decryptOneVpn(encrypted(proxy("Japan fixture"))).get(0).name);
        code("INVALID_SUBSCRIPTION", () -> NodeParser.decryptOneVpn(ProviderRepository.object("iv", "bad", "data", "bad")));
    }

    @Test public void oneVpnSessionUsesTopLevelStrictFieldsAndMilliseconds() throws Exception {
        JSONObject session = ProviderRepository.oneSession(ProviderRepository.object("accessToken", "a", "refreshToken", "r", "deviceId", "d"), 1700000000000L);
        assertEquals(1700000900000L, session.getLong("expiresAt"));
        code("INVALID_RESPONSE", () -> ProviderRepository.oneSession(ProviderRepository.object("data", session), 1700000000000L));
        code("INVALID_RESPONSE", () -> ProviderRepository.oneSession(ProviderRepository.object("accessToken", "a", "refreshToken", "r", "deviceId", "d", "expiresIn", "900"), 1700000000000L));
    }

    @Test public void wireGuardImportValidatesKeysEndpointsAddressAndRejectsHooks() throws Exception {
        Node node = NodeParser.wireguard(wg(), "Fixture", "MY", "octohide");
        assertEquals("MY", node.country); assertEquals("example.test", node.config.getString("server")); assertEquals("fd00::2", node.config.getString("ipv6"));
        assertEquals(2, node.config.getJSONArray("allowed-ips").length());
        code("IMPORT_INVALID", () -> NodeParser.wireguard(wg() + "PostUp=execute\n", "Fixture", null, "import"));
        code("IMPORT_INVALID", () -> NodeParser.wireguard(wg().replace("10.0.0.2/32", "010.0.0.2/32"), "Fixture", null, "import"));
        code("IMPORT_INVALID", () -> NodeParser.wireguard(wg().replace("51820", "70000"), "Fixture", null, "import"));
    }

    @Test public void importsDoNotApplyRootDirectivesAndRejectUnsafeYaml() throws Exception {
        Fixture f = new Fixture();
        f.repo.importConfig("mixed-port: 1\nrules: [MATCH,DIRECT]\n" + proxy("Japan fixture"));
        assertEquals(1, f.repo.nodes().size()); assertFalse(f.repo.nodes().get(0).config.has("rules"));
        code("IMPORT_INVALID", () -> f.repo.importConfig("!!java.lang.Runtime {}"));
        assertEquals(1, f.repo.nodes().size());
        f.repo.importConfig("# Exported client configuration\n\n" + wg().replace("[Interface]", "[interface]"));
        assertEquals("wireguard", f.repo.nodes().get(0).config.getString("type"));
    }

    @Test public void oneVpnRotatedTokenIsSavedBeforeProtectedRequest() throws Exception {
        Fixture f = new Fixture(); f.http.rotateImmediately = true; f.http.assertRotationSaved = true; f.one();
        assertEquals(1, f.http.oneRefreshes);
        assertEquals("rotated-refresh", f.store.saved.get("onevpn").getJSONObject("session").getString("refreshToken"));
        assertFalse(f.store.saved.get("onevpn").toString().contains("fixture-password"));
        assertEquals("ready", f.repo.status("onevpn").getString("phase"));
    }

    @Test public void pendingOneVpnLoginSurvivesRestartAndKeepsPreviousSource() throws Exception {
        Fixture f = new Fixture(); f.one(); String original = f.repo.nodes().get(0).id;
        f.http.label = "Singapore replacement"; f.http.failOneCode = "NETWORK_ERROR";
        code("NETWORK_ERROR", f::one);
        assertTrue(f.repo.status("onevpn").getBoolean("pendingSignin")); assertEquals(original, f.repo.nodes().get(0).id);
        int calls = f.http.oneCalls;
        ProviderRepository restored = new ProviderRepository(f.store, f.http, f.time, f.time::advance);
        assertEquals(calls, f.http.oneCalls); assertTrue(restored.status("onevpn").getBoolean("pendingSignin"));
        f.http.failOneCode = null; f.time.advance(61000); restored.refresh("onevpn", List.of());
        assertFalse(restored.status("onevpn").getBoolean("pendingSignin")); assertEquals("Singapore replacement", restored.nodes().get(0).name);
        assertEquals(2, f.http.oneLogins); // Password-free retry uses the persisted candidate session.
    }

    @Test public void authoritativeOneVpnRevocationRemovesOnlyItsOwnNodes() throws Exception {
        Fixture f = new Fixture(); f.one(); f.repo.importConfig(proxy("Singapore import"));
        f.http.expired = true; code("ACCOUNT_EXPIRED", () -> f.repo.refresh("onevpn", List.of()));
        assertEquals(0, f.repo.status("onevpn").getInt("nodeCount"));
        assertEquals(1, f.repo.nodes().size()); assertEquals("import", f.repo.nodes().get(0).provider);
        ProviderRepository restored = new ProviderRepository(f.store, f.http, f.time, f.time::advance);
        assertEquals(1, restored.nodes().size());
    }

    @Test public void aRejectedCandidateDoesNotRevokeWorkingOneVpnAccount() throws Exception {
        Fixture f = new Fixture(); f.one(); String old = f.repo.nodes().get(0).id; f.http.expired = true;
        code("ACCOUNT_EXPIRED", f::one);
        assertEquals(old, f.repo.nodes().get(0).id); assertFalse(f.repo.status("onevpn").getBoolean("pendingSignin"));
        assertEquals("access-1", f.store.saved.get("onevpn").getJSONObject("session").getString("accessToken"));
    }

    @Test public void octohideAcceptsDecimalRegionAndCanonicalReplyChecks() throws Exception {
        JSONObject export = ProviderRepository.object("timestamp", 1700000000, "authenticated", 1, "replyid", "2", "region", "36", "address", "10.0.0.2", "text", wg());
        assertEquals(36, ProviderRepository.parseOctoSection("configexport", export).getInt("region"));
        export.put("region", "036"); code("INVALID_RESPONSE", () -> ProviderRepository.parseOctoSection("configexport", export));
        export.put("region", 36).put("authenticated", "1"); code("INVALID_RESPONSE", () -> ProviderRepository.parseOctoSection("configexport", export));
    }

    @Test public void octohideKeepsThreeStableExportsAndHonoursGapAndPriorityChanges() throws Exception {
        Fixture f = new Fixture(); f.octo();
        assertEquals(3, f.http.exports); assertEquals(3, f.repo.nodes().size());
        assertTrue(f.http.exportTimes.get(1) - f.http.exportTimes.get(0) >= 1500);
        assertTrue(f.http.exportTimes.get(2) - f.http.exportTimes.get(1) >= 1500);
        int calls = f.http.octoCalls; f.repo.refresh("octohide", List.of("MY", "SG", "JP", "DE")); assertEquals(calls, f.http.octoCalls);
        f.time.advance(61000); f.repo.refresh("octohide", List.of("MY", "SG", "JP", "DE")); assertEquals(3, f.http.exports);
        f.time.advance(61000); f.repo.refresh("octohide", List.of("DE", "MY", "SG"));
        assertEquals(4, f.http.exports); assertEquals(List.of("DE", "MY", "SG"), f.repo.nodes().stream().map(n -> n.country).toList());
        assertFalse(f.store.saved.get("octohide").toString().contains("fixture-password"));
    }

    @Test public void octohideStopsOnFirstFailureKeepsPartialsAndPersistsCooldownAcrossLogout() throws Exception {
        Fixture f = new Fixture(); f.http.failExportAt = 2; code("RATE_LIMITED", f::octo);
        assertEquals(2, f.http.exports); assertEquals(1, f.repo.nodes().size());
        long deadline = f.repo.status("octohide").getLong("retryAt"); assertTrue(deadline >= f.time.now + 1700000);
        int calls = f.http.octoCalls;
        f.repo.refresh("octohide", List.of("MY")); assertEquals(calls, f.http.octoCalls);
        f.repo.logout("octohide"); assertEquals(0, f.repo.nodes().size());
        ProviderRepository restored = new ProviderRepository(f.store, f.http, f.time, f.time::advance);
        code("RATE_LIMITED", () -> restored.login("octohide", "fixture@example.invalid", "new-password", List.of()));
        assertEquals(calls, f.http.octoCalls); assertFalse(f.store.saved.get("octohide").toString().contains("fixture-sid"));
        assertEquals(deadline, restored.status("octohide").getLong("retryAt"));
    }

    @Test public void octohideRevokedEntitlementClearsExportsWhileExpiredSessionKeepsValidCachedExports() throws Exception {
        Fixture f = new Fixture(); f.octo(); f.http.octoAuth = false; f.time.advance(301000);
        code("AUTH_REQUIRED", () -> f.repo.refresh("octohide", List.of()));
        assertEquals(3, f.repo.nodes().size()); assertFalse(f.repo.status("octohide").getBoolean("connected"));
        int calls = f.http.octoCalls; f.time.advance(301000); f.repo.refreshDue(List.of()); assertEquals(calls, f.http.octoCalls);
        Fixture revoked = new Fixture(); revoked.octo(); revoked.http.octoPaid = false; revoked.time.advance(301000);
        code("VIP_REQUIRED", () -> revoked.repo.refresh("octohide", List.of())); assertEquals(0, revoked.repo.nodes().size());
    }

    @Test public void dueRefreshContinuesOtherProviderAfterOneVpnFailure() throws Exception {
        Fixture f = new Fixture(); f.one(); f.octo();
        f.time.advance(901000); f.http.failOneCode = "NETWORK_ERROR";
        int oldExports = f.http.exports;
        code("NETWORK_ERROR", () -> f.repo.refreshDue(List.of("MY", "SG", "JP")));
        assertEquals(oldExports + 3, f.http.exports); assertEquals("ready", f.repo.status("octohide").getString("phase"));
    }

    @Test public void uiSnapshotsRemainReadableWhileLoginNetworkIsBlocked() throws Exception {
        Fixture f = new Fixture(); f.http.blockEntered = new CountDownLatch(1); f.http.release = new CountDownLatch(1);
        Thread worker = new Thread(() -> { try { f.one(); } catch (Exception ignored) { } }); worker.start();
        assertTrue(f.http.blockEntered.await(2, TimeUnit.SECONDS));
        try {
            assertTrue(f.repo.status("onevpn").getBoolean("pendingSignin")); assertTrue(f.repo.nodes().isEmpty());
            assertEquals("subscription-pending", f.repo.status("onevpn").getString("phase"));
        } finally { f.http.release.countDown(); worker.join(2000); }
        assertFalse(worker.isAlive()); assertEquals(1, f.repo.nodes().size());
    }

    @Test public void diagnosticsDropUnknownUpstreamTextAndParseRetryAfter() throws Exception {
        for (Object unknown : Arrays.asList("secret-server-token-xyz", "SECRET_SERVER_TOKEN", "NO_SERVERS\nsecret", "no_servers", 12345,
                ProviderRepository.object("token", "secret-server-token-xyz"))) {
            try { ProviderRepository.checkOctoError(ProviderRepository.object("error", unknown, "code", 50), "account", 1700000000000L); fail(); }
            catch (ProviderException error) {
                assertEquals("PROVIDER_ERROR", error.code); assertNull(error.providerCode); assertEquals(0, error.retryAt);
                JSONObject diagnostic = error.diagnostic("octohide", "refresh", 1);
                assertFalse(diagnostic.has("providerCode")); assertFalse(diagnostic.toString().contains("secret-server-token"));
            }
        }
        assertEquals(1700000120000L, ProviderHttp.retryAt("120", 1700000000000L, 86400));
        assertEquals(1700086400000L, ProviderHttp.retryAt("9999999999", 1700000000000L, 86400));
    }

    @Test public void officialOctohideErrorsKeepExactSafeCodesWithoutInventingRemediesOrDeadlines() throws Exception {
        for (String providerCode : Arrays.asList("SERVICE_UNAVAILABLE", "API_RESPONSE_NULL", "PASSWORD_TOO_SIMPLE", "TOKEN_APPLE_SUBSCRIPTION",
                "TOKEN_UNLIMITED_SUBSCRIPTION", "CODE_EXPIRED_ACTIVATE_ACCOUNT", "CODE_EXPIRED_CHANGE_USERNAME", "ALREADY_REGISTERED",
                "VPN_START_ERROR", "SERVICE_NOT_FOUND", "INVALID_SERVICE", "NOPUBLICIP", "SYSTEM_ERROR", "CLIENT_CERTIFICATE_ERROR",
                "INTEGRITY_CHECK_ERROR", "FAILED_TO_CREATE", "IP_SEARCH_FAILED", "CREATE_ERROR", "INVALID_ID_FORMAT", "CLIENT_NOT_FOUND",
                "INVALID_CREDENTIALS_FORMAT", "TOKEN_EXPIRED", "USERNAME_TOO_LONG", "USERNAME_NOT_EMAIL", "REGION_NOT_FOUND", "UNKNOWN_ACTION",
                "NO_AGREEMENT", "PASSWORD_TOO_WEAK", "ERROR_VPN_CONNECTION_LOST", "APP_NO_NETWORK", "NOT_REGISTERED", "PASSWORD_TOO_SHORT",
                "TOKEN_UNKNOWN", "EMPTY_RESPONSE", "FORBIDDEN", "LOGIN_LIMIT", "USERNAME_NOT_SET", "NEW_USERNAME_NOT_EMAIL", "TOKEN_USED",
                "UNSUPPORTED_SERVICE", "CODE_EXPIRED", "UNKNOWN_REDEEM_TYPE", "SERVER_ERROR", "BAD_ACTION", "INVALID_DEACTIVATION_ID",
                "USERNAME_TAKEN", "INVALID_USERNAME_FORMAT", "NO_SERVERS", "ERROR_GETTING_CONFIGURATION", "MISSING_ACTION", "TOKEN_LIMIT",
                "TOKEN_INVALID")) {
            try { ProviderRepository.checkOctoError(ProviderRepository.object("error", providerCode, "code", 1800), "config-export", 1700000000000L); fail(providerCode); }
            catch (ProviderException error) {
                assertEquals(providerCode, "PROVIDER_ERROR", error.code); assertEquals(providerCode, error.providerCode);
                assertEquals(providerCode, 0, error.retryAt); assertEquals("config-export", error.stage);
                assertEquals(providerCode, error.diagnostic("octohide", "refresh", 1).getString("providerCode"));
            }
        }
    }

    @Test public void officialOctohideRecoveryAndRequestLimitCodesUseExistingSafeActions() throws Exception {
        for (String[] mapping : new String[][] {
                {"INVALID_REPLY_ID", "BAD_REPLY_ID"}, {"MAX_ACTIVATED_LIMIT", "DEVICE_LIMIT"},
                {"REQUEST_LIMIT_SEND_ACTIVATION_CODE", "RATE_LIMITED"}, {"REQUEST_LIMIT_ACTIVATE_ACCOUNT", "RATE_LIMITED"},
                {"REQUEST_LIMIT_LONG", "RATE_LIMITED"}, {"REQUEST_LIMIT", "RATE_LIMITED"}, {"REQUEST_LIMIT_SHORT", "RATE_LIMITED"}}) {
            try { ProviderRepository.checkOctoError(ProviderRepository.object("error", mapping[0], "code", 1800), "config-export", 1700000000000L); fail(mapping[0]); }
            catch (ProviderException error) {
                assertEquals(mapping[0], mapping[1], error.code); assertEquals(mapping[0], error.providerCode);
                boolean documentedWait = Arrays.asList("REQUEST_LIMIT_LONG", "REQUEST_LIMIT_SEND_ACTIVATION_CODE", "REQUEST_LIMIT_ACTIVATE_ACCOUNT").contains(mapping[0]);
                assertEquals(mapping[0], documentedWait ? 1700001800000L : 0, error.retryAt);
            }
        }
        for (Object invalidWait : Arrays.asList(-1, 0, "1800", 1.5, 365L * 86400 + 1, JSONObject.NULL)) {
            try { ProviderRepository.checkOctoError(ProviderRepository.object("error", "REQUEST_LIMIT_ACTIVATE_ACCOUNT", "code", invalidWait), "account", 1700000000000L); fail(); }
            catch (ProviderException error) { assertEquals("RATE_LIMITED", error.code); assertEquals(0, error.retryAt); }
        }
    }

    @Test public void rejectedOctohideExportPublishesOriginalProviderCodeAndStillStopsAndCoolsDown() throws Exception {
        Fixture f = new Fixture(); f.http.failExportAt = 2; f.http.failExportCode = "NO_SERVERS";
        code("PROVIDER_ERROR", f::octo);
        assertEquals(2, f.http.exports); assertEquals(1, f.repo.nodes().size());
        JSONObject status = f.repo.status("octohide");
        assertEquals("NO_SERVERS", status.getJSONObject("lastDiagnostic").getString("providerCode"));
        assertEquals("config-export", status.getJSONObject("lastDiagnostic").getString("stage"));
        assertTrue(status.getLong("retryAt") >= f.time.now + 300000);
        int calls = f.http.octoCalls; f.repo.refresh("octohide", List.of("MY")); assertEquals(calls, f.http.octoCalls);
        ProviderRepository restored = new ProviderRepository(f.store, f.http, f.time, f.time::advance);
        assertEquals("NO_SERVERS", restored.status("octohide").getJSONObject("lastDiagnostic").getString("providerCode"));
        restored.refresh("octohide", List.of("MY")); assertEquals(calls, f.http.octoCalls);
    }

    @Test public void failedOctohideRenewalTargetsOneExportAndSurvivesCooldownRestart() throws Exception {
        Fixture f = new Fixture(); f.octo();
        String failed = f.repo.nodes().stream().filter(n -> n.country.equals("MY")).findFirst().orElseThrow().name;
        f.time.advance(61000); f.repo.refreshFailedNodes(List.of(failed), List.of("MY", "SG", "JP"));
        assertEquals(3, f.http.exports); // Latest successful export attempt is less than two minutes old.
        assertEquals(1, f.store.saved.get("octohide").getJSONArray("failedRegions").length());
        ProviderRepository restored = new ProviderRepository(f.store, f.http, f.time, f.time::advance);
        f.time.advance(61000); restored.refreshDue(List.of("MY", "SG", "JP"));
        assertEquals(4, f.http.exports);
        assertEquals(0, f.store.saved.get("octohide").getJSONArray("failedRegions").length());
        restored.refreshFailedNodes(List.of(failed), List.of("MY", "SG", "JP"));
        assertEquals(4, f.http.exports);
        f.time.advance(121000); restored.refreshDue(List.of("MY", "SG", "JP"));
        assertEquals(5, f.http.exports); // The other healthy, fresh regions were not renewed.
    }

    @Test public void combinedPublishedNamesAreUniqueAndRestoreDeterministically() throws Exception {
        Fixture f = new Fixture(); f.one(); f.repo.importConfig(proxy("Japan fixture"));
        List<Node> nodes = f.repo.nodes();
        assertEquals(2, nodes.size()); assertEquals("Japan fixture", nodes.get(0).name);
        assertEquals("Japan fixture [import]", nodes.get(1).name);
        assertEquals("import", nodes.get(1).provider); assertEquals("JP", nodes.get(1).country);
        assertEquals("Japan fixture [import]", nodes.get(1).config.getString("name"));
        assertEquals("Japan fixture", f.store.saved.get("import").getJSONArray("nodes").getJSONObject(0).getJSONObject("config").getString("name"));
        ProviderRepository restored = new ProviderRepository(f.store, f.http, f.time, f.time::advance);
        assertEquals(nodes.stream().map(n -> n.name).toList(), restored.nodes().stream().map(n -> n.name).toList());
    }

    @Test public void revokedAccountCannotReappearWhenSecureTombstoneWriteFails() throws Exception {
        Fixture f = new Fixture(); f.one(); f.http.expired = true; f.store.failNonNullWrites = true;
        code("CREDENTIAL_STORE", () -> f.repo.refresh("onevpn", List.of()));
        assertTrue(f.repo.nodes().isEmpty()); assertFalse(f.store.saved.containsKey("onevpn")); assertTrue(f.store.deletes > 0);
        ProviderRepository restarted = new ProviderRepository(f.store, f.http, f.time, f.time::advance);
        assertTrue(restarted.nodes().isEmpty());
        int calls = f.http.oneCalls;
        for (int i = 0; i < 5; i++) {
            f.time.advance(31000);
            try { f.repo.refreshDue(List.of()); } catch (ProviderException error) { assertEquals("CREDENTIAL_STORE", error.code); }
        }
        assertEquals(calls, f.http.oneCalls); // Storage retries never submit an account request.
        int attempts = f.store.failedWrites;
        f.time.advance(31000); f.repo.refreshDue(List.of()); assertEquals(attempts, f.store.failedWrites); // Bounded automatic retries.
        f.store.failNonNullWrites = false; f.repo.refresh("onevpn", List.of());
        assertTrue(f.store.saved.get("onevpn").isNull("session"));
    }

    @Test public void logoutDeletesObsoleteVaultIfKeystoreWriteFails() throws Exception {
        Fixture f = new Fixture(); f.one(); f.store.failNonNullWrites = true;
        code("CREDENTIAL_STORE", () -> f.repo.logout("onevpn"));
        assertTrue(f.repo.nodes().isEmpty()); assertFalse(f.store.saved.containsKey("onevpn"));
        assertTrue(new ProviderRepository(f.store, f.http, f.time, f.time::advance).nodes().isEmpty());
    }

    @Test public void changingCountriesProtectsActiveExportAndNeverExportsAImmediatelyDiscardedFourthTier() throws Exception {
        Fixture f = new Fixture(); f.octo();
        f.repo.currentNodeName = f.repo.nodes().stream().filter(n -> n.country.equals("JP")).findFirst().orElseThrow().name;
        List<String> preferred = List.of("DE", "US", "MY", "SG", "JP");
        f.time.advance(61000); f.repo.refresh("octohide", preferred);
        assertEquals(5, f.http.exports); assertEquals(List.of("DE", "US", "JP"), f.repo.nodes().stream().map(n -> n.country).toList());
        f.time.advance(61000); f.repo.refresh("octohide", preferred); assertEquals(5, f.http.exports);
        f.repo.currentNodeName = f.repo.nodes().stream().filter(n -> n.country.equals("DE")).findFirst().orElseThrow().name;
        f.time.advance(61000); f.repo.refresh("octohide", preferred);
        assertEquals(6, f.http.exports); assertEquals(List.of("DE", "US", "MY"), f.repo.nodes().stream().map(n -> n.country).toList());
    }
}
