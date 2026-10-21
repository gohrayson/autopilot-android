package app.autopilot.android;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Single provider-executor writer. UI readers see immutable published snapshots,
 * never a monitor held during HTTP, crypto, storage, or Octohide's export gap.
 */
public final class ProviderRepository {
    interface Clock { long now(); }
    interface Sleeper { void sleep(long millis) throws InterruptedException; }
    private static final long MIN_OCTO_GAP = 60000, FAILURE_GAP = 300000, METADATA_GAP = 300000, DEFAULT_TTL = 900000;
    private final SecureStore store;
    private final ProviderHttp http;
    private final Clock clock;
    private final Sleeper sleeper;
    private final State one = new State("onevpn"), octo = new State("octohide"), imported = new State("import");
    private OctoClient octoClient;
    private final Map<String, PendingWrite> pendingWrites = new HashMap<>();
    private volatile List<Node> publishedNodes = Collections.emptyList();
    private volatile Map<String, String> publishedStatus = Collections.emptyMap();
    private volatile Map<String, String> publishedOrigins = Collections.emptyMap();
    public volatile Runnable onChanged;
    /** Published by App with each VPN snapshot; provider work never calls back into the engine. */
    public volatile String currentNodeName = "";

    private static final class State {
        final String provider;
        JSONObject session, pendingSession, account, diagnostic;
        List<Node> nodes = new ArrayList<>();
        JSONArray regions = new JSONArray(), exports = new JSONArray();
        final Set<Integer> failedRegions = new HashSet<>();
        final Map<Integer, Long> exportAttempts = new HashMap<>();
        long retryAt, nextRefreshAt, lastRefresh, metadataAt, lastExportAt, ttl = DEFAULT_TTL;
        String phase = "logged-out", code, error, retryCode;
        State(String provider) { this.provider = provider; }
    }
    private static final class PendingWrite {
        final JSONObject data;
        int attempts;
        long retryAt;
        PendingWrite(JSONObject data, long retryAt) { this.data = data; this.retryAt = retryAt; }
    }

    public ProviderRepository(Context context) { this(new SecureStore(context), new ProviderHttp(), System::currentTimeMillis, Thread::sleep); }

    ProviderRepository(SecureStore store, ProviderHttp http, Clock clock, Sleeper sleeper) {
        this.store = store; this.http = http; this.clock = clock; this.sleeper = sleeper;
        for (State state : Arrays.asList(one, octo, imported)) {
            try { restore(state, store.load(state.provider)); }
            catch (Exception ignored) {
                state.session = null; state.pendingSession = null; state.account = null; state.nodes.clear();
                state.regions = new JSONArray(); state.exports = new JSONArray();
                failState(state, new ProviderException("CREDENTIAL_STORE", "restore"), "restore");
            }
        }
        publish();
    }

    public List<Node> nodes() { return new ArrayList<>(publishedNodes); }
    public JSONObject status(String provider) {
        try { return new JSONObject(publishedStatus.getOrDefault(provider, "{}")); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    public void login(String provider, String email, String password, List<String> preferred) throws ProviderException {
        State state = state(provider);
        if (state == imported) throw new ProviderException("UNKNOWN_PROVIDER", "login-request");
        int max = provider.equals("onevpn") ? 1024 : 4096;
        if (NodeParser.strictText(email, 320) == null || password == null || password.isEmpty() || password.length() > max || password.indexOf('\0') >= 0)
            throw new ProviderException("INVALID_INPUT", "login-request");
        if (state.retryAt > clock.now()) throw cooldown(state);
        state.phase = "signing-in"; state.error = null; state.code = null; publish();
        try {
            if (state == one) {
                JSONObject body = object("email", email.trim(), "password", password, "appId", "windows_app");
                // windows_app is the recovered, verified client contract; an Android app ID is not invented.
                JSONObject session = oneSession(http.one("/api/auth/login", body, null, "login-request"));
                state.pendingSession = session;
                state.phase = "subscription-pending";
                save(state); // Save authenticated session before any subscription request. Password is never retained.
                publish();
                refreshOne(true);
            } else {
                OctoClient candidate = new OctoClient(null);
                candidate.init(); candidate.login(email.trim(), password);
                if (candidate.session.optString("sid").isEmpty()) throw new ProviderException("AUTH_REQUIRED", "login-response");
                state.session = cloneJson(candidate.session); state.account = object("username", email.trim(), "paid", false);
                octoClient = candidate;
                state.nodes = new ArrayList<>(); state.regions = new JSONArray(); state.exports = new JSONArray();
                state.failedRegions.clear(); state.exportAttempts.clear();
                state.metadataAt = 0; state.nextRefreshAt = 0; state.retryAt = 0; state.retryCode = null;
                state.phase = "subscription-pending";
                save(state); publish();
                refreshOcto(preferred, candidate);
            }
        } catch (ProviderException error) {
            failState(state, error, "login");
            if (state == one) {
                if (terminalOne(error) && state.pendingSession != null) state.pendingSession = null;
                defer(state, error, 60000);
            } else defer(state, error, FAILURE_GAP);
            persistFailure(state, error); publish(); throw error;
        } catch (Exception ignored) {
            ProviderException error = new ProviderException("INVALID_RESPONSE", "login-response");
            failState(state, error, "login"); defer(state, error, state == octo ? FAILURE_GAP : 60000);
            persistFailure(state, error); publish(); throw error;
        }
    }

    public void refresh(String provider, List<String> preferred) throws ProviderException {
        State state = state(provider);
        retryWrite(state, true); // Never reuse a rotated token before its failed secure write has recovered.
        if (state.retryAt > clock.now() || (state == octo && state.nextRefreshAt > clock.now())) { publish(); return; }
        if (state.session == null && state.pendingSession == null) return;
        if (state == octo && state.session.optString("sid").isEmpty()) return; // Requires explicit sign-in, not anonymous retries.
        if (state == one) refreshOne(false); else refreshOcto(preferred, null);
    }

    /** One failing account never prevents the other provider's independent due refresh. */
    public void refreshDue(List<String> preferred) throws ProviderException {
        ProviderException first = null;
        for (State state : Arrays.asList(one, octo, imported)) {
            try { retryWrite(state, false); }
            catch (ProviderException error) { if (first == null) first = error; }
        }
        for (State state : Arrays.asList(one, octo)) {
            if (pendingWrites.containsKey(state.provider)) continue;
            if ((state.session == null && state.pendingSession == null) || state.retryAt > clock.now() || state.nextRefreshAt > clock.now()) continue;
            if (state == octo && state.session.optString("sid").isEmpty()) continue;
            try { refresh(state.provider, preferred); }
            catch (ProviderException error) { if (first == null) first = error; }
        }
        if (first != null) throw first;
    }

    /** Queue only failed Octohide exports; a sweep must never force-renew the whole healthy pool. */
    public void refreshFailedNodes(List<String> nodeNames, List<String> preferred) throws ProviderException {
        if (nodeNames == null || nodeNames.isEmpty() || octo.session == null || octo.session.optString("sid").isEmpty()) return;
        Set<String> originalIds = new HashSet<>();
        Map<String, String> origins = publishedOrigins;
        for (String name : nodeNames) if (origins.containsKey(name)) originalIds.add(origins.get(name));
        boolean changed = false;
        for (JSONObject entry : exportEntries(octo.exports)) {
            try {
                JSONObject region = entry.getJSONObject("region");
                Node node = NodeParser.wireguard(entry.getString("text"), region.getString("name") + " #" + region.getInt("id"), region.getString("country"), "octohide");
                if (originalIds.contains(node.id)) changed |= octo.failedRegions.add(region.getInt("id"));
            } catch (Exception ignored) { }
        }
        if (changed) save(octo);
        if (octo.failedRegions.isEmpty()) return;
        // Targets survive both provider cooldown and successful-refresh spacing, for the next due pass.
        if (octo.retryAt > clock.now() || octo.nextRefreshAt > clock.now()) { publish(); return; }
        refreshOcto(preferred, null);
    }

    public void logout(String provider) throws ProviderException {
        State state = state(provider);
        state.session = null; state.pendingSession = null; state.account = null;
        state.nodes = new ArrayList<>(); state.exports = new JSONArray(); state.regions = new JSONArray();
        state.failedRegions.clear(); state.exportAttempts.clear();
        if (state == octo) octoClient = null;
        state.lastRefresh = 0; state.metadataAt = 0; state.lastExportAt = 0; state.nextRefreshAt = 0;
        state.phase = "logged-out"; state.error = null; state.code = null;
        if (state == octo && state.retryAt > clock.now()) {
            ProviderException cooldown = cooldown(state); state.error = cooldown.getMessage(); state.code = cooldown.code;
        } else { state.retryAt = 0; state.retryCode = null; }
        state.diagnostic = success(state.provider, "logout");
        publish(); // Disconnect locally even if the storage operation fails; report failure honestly.
        try { save(state); }
        catch (ProviderException error) {
            preventResurrection(state); failState(state, error, "logout"); publish(); throw error;
        }
        publish();
    }

    public void importConfig(String text) throws ProviderException {
        List<Node> parsed = NodeParser.parse(text, "import");
        State next = new State("import"); next.nodes = parsed; next.phase = "ready"; next.lastRefresh = clock.now();
        next.diagnostic = success("import", "import");
        save(next); // Failed import/storage leaves the previous source intact.
        imported.nodes = parsed; imported.phase = "ready"; imported.lastRefresh = next.lastRefresh; imported.diagnostic = next.diagnostic;
        imported.code = null; imported.error = null; publish();
    }

    private void refreshOne(boolean fromLogin) throws ProviderException {
        State state = one;
        boolean candidate = state.pendingSession != null;
        JSONObject session = candidate ? state.pendingSession : state.session;
        if (session == null) return;
        state.phase = candidate ? "subscription-pending" : "refreshing"; state.error = null; state.code = null; publish();
        try {
            boolean renewed = false;
            if (session.optLong("expiresAt", 0) <= clock.now() + 60000) {
                session = rotateOne(state, session, candidate); renewed = true;
            }
            JSONObject account;
            try { account = oneAccount(http.one("/api/user/client-info", null, session.getString("accessToken"), "account")); }
            catch (ProviderException error) {
                if (!renewed && error.code.equals("AUTH_REQUIRED")) {
                    session = rotateOne(state, session, candidate);
                    account = oneAccount(http.one("/api/user/client-info", null, session.getString("accessToken"), "account"));
                } else throw error;
            }
            JSONObject subscription = http.one("/api/user/subscription/encrypted", null, session.getString("accessToken"), "subscription");
            List<Node> nodes = NodeParser.decryptOneVpn(subscription);
            State committed = new State("onevpn"); committed.session = cloneJson(session); committed.account = account; committed.nodes = nodes;
            committed.lastRefresh = clock.now(); committed.nextRefreshAt = oneNextRefresh(session); committed.phase = "ready";
            committed.diagnostic = success("onevpn", fromLogin ? "login" : "refresh");
            save(committed); // Source takeover only after account, non-empty subscription and secure commit all succeed.
            state.session = committed.session; state.pendingSession = null; state.account = account; state.nodes = nodes;
            state.lastRefresh = committed.lastRefresh; state.nextRefreshAt = committed.nextRefreshAt;
            state.retryAt = 0; state.retryCode = null; state.phase = "ready"; state.error = null; state.code = null; state.diagnostic = committed.diagnostic;
            publish();
        } catch (ProviderException error) {
            if (terminalOne(error)) {
                if (candidate) state.pendingSession = null;
                else { state.session = null; state.account = null; state.nodes = new ArrayList<>(); }
            }
            failState(state, error, fromLogin ? "login" : "refresh");
            defer(state, error, 60000); persistFailure(state, error); publish(); throw error;
        } catch (Exception ignored) {
            ProviderException error = new ProviderException("INVALID_RESPONSE", "subscription");
            failState(state, error, "refresh"); defer(state, error, 60000); persistFailure(state, error); publish(); throw error;
        }
    }

    private JSONObject rotateOne(State state, JSONObject session, boolean candidate) throws ProviderException {
        try {
            JSONObject rotated = oneSession(http.one("/api/auth/refresh", object("refreshToken", session.getString("refreshToken")), null, "session-refresh"));
            if (candidate) state.pendingSession = rotated; else state.session = rotated;
            save(state); // Rotated refresh token is durable before it is used for a protected request.
            return rotated;
        } catch (ProviderException error) { throw error; }
        catch (Exception ignored) { throw new ProviderException("INVALID_RESPONSE", "session-refresh"); }
    }

    static JSONObject oneSession(JSONObject raw, long now) throws ProviderException {
        try {
            String access = requiredText(raw.opt("accessToken"), 8192), refresh = requiredText(raw.opt("refreshToken"), 8192), device = requiredText(raw.opt("deviceId"), 128);
            long seconds = raw.isNull("expiresIn") ? 900 : integer(raw.opt("expiresIn"), 1, 365L * 86400);
            long expiry = now + seconds * 1000;
            try {
                String[] parts = access.split("\\.");
                if (parts.length == 3) {
                    JSONObject payload = new JSONObject(ProviderHttp.utf8(Base64.getUrlDecoder().decode(parts[1])));
                    long jwtExp = integer(payload.opt("exp"), 1, Long.MAX_VALUE / 1000);
                    expiry = Math.min(expiry, jwtExp * 1000);
                }
            } catch (Exception ignored) { }
            return object("accessToken", access, "refreshToken", refresh, "deviceId", device, "expiresAt", expiry);
        } catch (Exception ignored) { throw new ProviderException("INVALID_RESPONSE", "login-response"); }
    }
    private JSONObject oneSession(JSONObject raw) throws ProviderException { return oneSession(raw, clock.now()); }

    JSONObject oneAccount(JSONObject raw) throws ProviderException {
        try {
            JSONObject user = raw.getJSONObject("user");
            if (!"success".equals(raw.optString("status")) || !(user.opt("isBanned") instanceof Boolean)) throw new Exception();
            String email = requiredText(user.opt("email"), 320);
            Long end = user.isNull("expireTime") ? null : integer(user.opt("expireTime"), 0, Long.MAX_VALUE);
            if (user.getBoolean("isBanned") || end == null || end <= clock.now()) throw new ProviderException("ACCOUNT_EXPIRED", "account");
            return object("username", email, "subscriptionEnd", end, "paid", true);
        } catch (ProviderException error) { throw error; }
        catch (Exception ignored) { throw new ProviderException("INVALID_RESPONSE", "account"); }
    }

    private long oneNextRefresh(JSONObject session) { return Math.max(clock.now() + 60000, Math.min(clock.now() + 900000, session.optLong("expiresAt") - 60000)); }
    private static boolean terminalOne(ProviderException error) { return Arrays.asList("AUTH_REQUIRED", "SESSION_EXPIRED", "ACCOUNT_EXPIRED").contains(error.code); }

    private void refreshOcto(List<String> preferred, OctoClient existing) throws ProviderException {
        State state = octo;
        if (pendingWrites.containsKey(state.provider)) return;
        if (state.retryAt > clock.now() || state.nextRefreshAt > clock.now() || state.session == null) return;
        boolean initialize = existing == null && octoClient == null;
        OctoClient client = existing != null ? existing : octoClient != null ? octoClient : new OctoClient(state.session);
        octoClient = client;
        state.phase = "refreshing"; state.error = null; state.code = null;
        state.nextRefreshAt = clock.now() + MIN_OCTO_GAP; publish();
        String stage = "account";
        try {
            if (initialize) client.init(); // Re-establish verified timestamp state once after process restart.
            if (state.metadataAt + METADATA_GAP <= clock.now() || state.account == null || state.regions.length() == 0) {
                JSONObject info = client.info();
                if (!Boolean.TRUE.equals(info.opt("vip"))) {
                    state.nodes = new ArrayList<>(); state.exports = new JSONArray();
                    state.account = object("username", info.optString("username"), "subscriptionEnd", info.optLong("subscription_end") * 1000, "paid", false);
                    throw new ProviderException("VIP_REQUIRED", "account");
                }
                state.account = object("username", info.optString("username"), "subscriptionEnd", info.optLong("subscription_end") * 1000, "paid", true);
                state.ttl = exportTtl(info);
                stage = "regions";
                state.regions = normalizeRegions(client.regions().getJSONArray("list"));
                state.metadataAt = clock.now();
                state.session = cloneJson(client.session); save(state); publish();
            }
            List<JSONObject> todo = exportCandidates(state, preferred, clock.now());
            int attempts = 0;
            for (JSONObject region : todo) {
                if (++attempts > 3) break;
                long delay = Math.max(0, state.lastExportAt + 1500 - clock.now());
                if (delay > 0) sleeper.sleep(delay);
                state.lastExportAt = clock.now(); stage = "config-export";
                state.exportAttempts.put(region.getInt("id"), state.lastExportAt);
                state.session = cloneJson(client.session); save(state); // Persist the attempt before spending another export request.
                JSONObject exported = client.export(region.getInt("id"));
                Node node;
                try { node = NodeParser.wireguard(exported.getString("text"), region.getString("name") + " #" + region.getInt("id"), region.getString("country"), "octohide"); }
                catch (ProviderException ignored) { throw new ProviderException("INVALID_RESPONSE", "subscription-parse"); }
                JSONObject entry = object("region", region, "text", exported.getString("text"), "at", clock.now());
                List<JSONObject> entries = exportEntries(state.exports);
                entries.removeIf(row -> row.optJSONObject("region").optInt("id", -1) == region.optInt("id", -2));
                entries.add(entry);
                state.exports = trimExports(entries, preferred, currentOctoRegion(state));
                state.failedRegions.remove(region.getInt("id"));
                state.nodes = exportNodes(state.exports); state.session = cloneJson(client.session);
                save(state); publish(); // Partial successful exports remain durable if the next region fails.
            }
            if (state.nodes.isEmpty()) throw new ProviderException("INVALID_RESPONSE", "config-export");
            state.session = cloneJson(client.session); state.phase = "ready"; state.error = null; state.code = null;
            state.retryAt = 0; state.retryCode = null; state.lastRefresh = clock.now();
            state.nextRefreshAt = Math.max(state.nextRefreshAt, clock.now() + MIN_OCTO_GAP);
            state.diagnostic = success("octohide", "refresh"); save(state); publish();
        } catch (ProviderException error) {
            state.session = cloneJson(client.session);
            if (error.code.equals("AUTH_REQUIRED") && state.session != null) { put(state.session, "sid", ""); put(client.session, "sid", ""); }
            if (error.code.equals("VIP_REQUIRED")) { state.nodes = new ArrayList<>(); state.exports = new JSONArray(); state.failedRegions.clear(); }
            failState(state, error, "refresh"); defer(state, error, FAILURE_GAP); persistFailure(state, error); publish(); throw error;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            ProviderException error = new ProviderException("ABORTED", stage);
            state.session = cloneJson(client.session); failState(state, error, "refresh"); defer(state, error, FAILURE_GAP); persistFailure(state, error); publish(); throw error;
        } catch (Exception ignored) {
            ProviderException error = new ProviderException("INVALID_RESPONSE", stage);
            state.session = cloneJson(client.session); failState(state, error, "refresh"); defer(state, error, FAILURE_GAP); persistFailure(state, error); publish(); throw error;
        }
    }

    static long exportTtl(JSONObject info) {
        long ttl = DEFAULT_TTL;
        JSONObject cfg = info.optJSONObject("cfg");
        if (cfg != null) for (String name : Arrays.asList("export_expire_unused", "export_expire_inactive")) {
            Object value = cfg.opt(name);
            if (value instanceof Number) {
                long seconds = ((Number) value).longValue();
                if (seconds > 0 && seconds <= 365L * 86400) ttl = Math.min(ttl, seconds * 800);
            }
        }
        return Math.max(1000, ttl);
    }

    private static JSONArray normalizeRegions(JSONArray rows) throws Exception {
        JSONArray result = new JSONArray(); Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < rows.length() && result.length() < 200; i++) {
            JSONObject row = rows.getJSONObject(i); int id = row.getInt("id");
            if (row.has("android") && row.getInt("android") != 1) continue;
            if (row.has("f") && row.has("v") && ((row.getInt("f") | row.getInt("v")) & 1) == 0) continue;
            String country = row.optString("country_code").toUpperCase(Locale.ROOT), name = NodeParser.strictText(row.opt("name"), 128);
            if (!country.matches("[A-Z]{2}") || Arrays.asList("CN", "HK", "MO").contains(country) || name == null || !seen.add(id)) continue;
            result.put(object("id", id, "name", name.trim(), "country", country));
        }
        return result;
    }

    private static List<String> preferences(List<String> raw) {
        List<String> result = new ArrayList<>();
        if (raw != null) for (String value : raw) if (value != null && value.matches("[A-Z]{2}") && !result.contains(value) && result.size() < 32) result.add(value);
        return result;
    }

    private static int rank(String country, List<String> priorities) { int n = priorities.indexOf(country); return n < 0 ? 1000 : n; }

    private List<JSONObject> exportCandidates(State state, List<String> rawPreferred, long now) throws Exception {
        List<String> preferred = preferences(rawPreferred);
        List<JSONObject> regions = new ArrayList<>();
        for (int i = 0; i < state.regions.length(); i++) regions.add(state.regions.getJSONObject(i));
        List<JSONObject> entries = exportEntries(state.exports);
        Map<Integer, JSONObject> cached = new HashMap<>(); Map<String, JSONObject> cachedCountry = new HashMap<>();
        for (JSONObject entry : entries) {
            JSONObject region = entry.getJSONObject("region"); cached.put(region.getInt("id"), entry);
            cachedCountry.putIfAbsent(region.getString("country"), region);
        }
        List<JSONObject> targets = new ArrayList<>(); Set<String> countries = new HashSet<>(); Set<Integer> selected = new HashSet<>();
        int current = currentOctoRegion(state);
        if (cached.containsKey(current)) {
            JSONObject region = cached.get(current).getJSONObject("region");
            targets.add(region); countries.add(region.getString("country")); selected.add(current);
        }
        // Compute the three desired slots first. Lower preferred tiers must not cause exports
        // that would immediately be discarded from a pool already holding the top three tiers.
        for (String country : preferred) {
            JSONObject choice = cachedCountry.get(country);
            if (choice == null) for (JSONObject region : regions) if (country.equals(region.optString("country"))) { choice = region; break; }
            if (choice != null && countries.add(country)) { targets.add(choice); selected.add(choice.getInt("id")); }
            if (targets.size() == 3) break;
        }
        for (JSONObject entry : entries) {
            if (targets.size() == 3) break;
            JSONObject region = entry.getJSONObject("region");
            if (countries.add(region.getString("country"))) { targets.add(region); selected.add(region.getInt("id")); }
        }
        for (int pass = 0; pass < 2 && targets.size() < 3; pass++) for (JSONObject region : regions) {
            if (targets.size() == 3) break;
            int id = region.getInt("id"); String country = region.getString("country");
            if (selected.contains(id) || (pass == 0 && countries.contains(country))) continue;
            targets.add(region); selected.add(id); countries.add(country);
        }
        List<JSONObject> todo = new ArrayList<>();
        Set<Integer> scheduled = new HashSet<>();
        for (JSONObject entry : entries) {
            JSONObject region = entry.getJSONObject("region"); int id = region.getInt("id");
            if (selected.contains(id) && state.failedRegions.contains(id) && now - state.exportAttempts.getOrDefault(id, 0L) >= 120000) {
                todo.add(region); scheduled.add(id); if (todo.size() == 3) return todo;
            }
        }
        for (JSONObject target : targets) {
            int id = target.getInt("id");
            if (state.failedRegions.contains(id) && now - state.exportAttempts.getOrDefault(id, 0L) < 120000) continue;
            JSONObject entry = cached.get(id);
            if ((entry == null || now - entry.getLong("at") >= state.ttl) && scheduled.add(target.getInt("id"))) todo.add(target);
            if (todo.size() == 3) break;
        }
        return todo;
    }

    private static JSONArray trimExports(List<JSONObject> entries, List<String> preferredRaw, int currentRegion) {
        List<String> preferred = preferences(preferredRaw);
        // Stable sort retains existing exports at equal priority. Country diversity prevents one region monopolising the pool.
        entries.sort(Comparator.comparingInt(entry -> rank(entry.optJSONObject("region").optString("country"), preferred)));
        List<JSONObject> chosen = new ArrayList<>(); Set<String> countries = new HashSet<>();
        for (JSONObject entry : entries) if (entry.optJSONObject("region").optInt("id", -1) == currentRegion) {
            chosen.add(entry); countries.add(entry.optJSONObject("region").optString("country")); break;
        }
        for (JSONObject entry : entries) if (countries.add(entry.optJSONObject("region").optString("country"))) {
            chosen.add(entry); if (chosen.size() == 3) break;
        }
        if (chosen.size() < 3) for (JSONObject entry : entries) if (!chosen.contains(entry)) { chosen.add(entry); if (chosen.size() == 3) break; }
        chosen.sort(Comparator.comparingInt(entry -> rank(entry.optJSONObject("region").optString("country"), preferred)));
        JSONArray result = new JSONArray(); for (JSONObject entry : chosen) result.put(entry); return result;
    }

    private int currentOctoRegion(State state) {
        String original = publishedOrigins.get(currentNodeName);
        if (original == null) return -1;
        for (JSONObject entry : exportEntries(state.exports)) try {
            JSONObject region = entry.getJSONObject("region");
            Node node = NodeParser.wireguard(entry.getString("text"), region.getString("name") + " #" + region.getInt("id"), region.getString("country"), "octohide");
            if (original.equals(node.id)) return region.getInt("id");
        } catch (Exception ignored) { }
        return -1;
    }

    private static List<JSONObject> exportEntries(JSONArray rows) {
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) if (rows.optJSONObject(i) != null && rows.optJSONObject(i).optJSONObject("region") != null) result.add(rows.optJSONObject(i));
        return result;
    }
    private static List<Node> exportNodes(JSONArray rows) throws Exception {
        List<Node> result = new ArrayList<>();
        for (JSONObject entry : exportEntries(rows)) {
            JSONObject region = entry.getJSONObject("region");
            result.add(NodeParser.wireguard(entry.getString("text"), region.getString("name") + " #" + region.getInt("id"), region.getString("country"), "octohide"));
        }
        return result;
    }

    private State state(String provider) throws ProviderException {
        if ("onevpn".equals(provider)) return one;
        if ("octohide".equals(provider)) return octo;
        if ("import".equals(provider)) return imported;
        throw new ProviderException("UNKNOWN_PROVIDER", "request");
    }
    private void defer(State state, ProviderException error, long minimum) {
        state.retryAt = Math.max(state.retryAt, Math.max(clock.now() + minimum, error.retryAt));
        state.retryCode = error.code; state.nextRefreshAt = state.retryAt;
    }
    private ProviderException cooldown(State state) { return new ProviderException(state.retryCode == null ? "RATE_LIMITED" : state.retryCode, "cooldown", 0, state.retryAt, null); }
    private void failState(State state, ProviderException error, String operation) {
        state.phase = state.pendingSession != null ? "subscription-pending" : "error";
        state.code = error.code; state.error = error.getMessage(); state.diagnostic = error.diagnostic(state.provider, operation, clock.now());
    }
    private void persistFailure(State state, ProviderException original) throws ProviderException {
        try { save(state); }
        catch (ProviderException storage) {
            boolean revoked = state == one && terminalOne(original) && state.session == null && state.pendingSession == null;
            revoked |= state == octo && original.code.equals("VIP_REQUIRED");
            if (revoked) preventResurrection(state);
            failState(state, storage, "refresh"); publish(); throw storage;
        }
    }
    private void preventResurrection(State state) {
        // A Keystore encryption failure need not prevent deleting the obsolete vault.
        // SecureStore's null write verifies base/.bak/.new are absent, without opening the key.
        try { store.save(state.provider, null); } catch (ProviderException ignored) { }
    }
    private void retryWrite(State state, boolean manual) throws ProviderException {
        PendingWrite pending = pendingWrites.get(state.provider);
        if (pending == null || (!manual && (pending.attempts >= 3 || pending.retryAt > clock.now()))) return;
        try { store.save(state.provider, pending.data); pendingWrites.remove(state.provider); }
        catch (ProviderException error) {
            pending.attempts++; pending.retryAt = clock.now() + 30000;
            failState(state, error, "refresh"); publish(); throw error;
        }
    }
    private JSONObject success(String provider, String operation) { return object("provider", provider, "operation", operation, "stage", "completed", "outcome", "success", "code", "OK", "at", clock.now()); }

    private void save(State state) throws ProviderException {
        JSONArray nodes = new JSONArray();
        for (Node node : state.nodes) nodes.put(object("config", node.config, "country", node.country));
        JSONArray failed = new JSONArray(), attempts = new JSONArray();
        for (Integer id : state.failedRegions) failed.put(id);
        for (Map.Entry<Integer, Long> entry : state.exportAttempts.entrySet()) attempts.put(new JSONArray().put(entry.getKey()).put(entry.getValue()));
        JSONObject data = object("version", 1, "session", state.session, "pendingSession", state.pendingSession,
                "account", state.account, "nodes", nodes, "regions", state.regions, "exports", state.exports,
                "retryAt", state.retryAt, "retryCode", state.retryCode, "nextRefreshAt", state.nextRefreshAt,
                "lastRefresh", state.lastRefresh, "metadataAt", state.metadataAt, "lastExportAt", state.lastExportAt,
                "ttl", state.ttl, "lastDiagnostic", state.diagnostic, "failedRegions", failed, "exportAttempts", attempts);
        try { store.save(state.provider, data); pendingWrites.remove(state.provider); }
        catch (ProviderException error) {
            pendingWrites.put(state.provider, new PendingWrite(cloneJson(data), clock.now() + 30000));
            throw error;
        }
    }

    private void restore(State state, JSONObject data) throws Exception {
        if (data == null) return;
        if (data.optInt("version") != 1) throw new Exception();
        state.retryAt = Math.min(Math.max(0, data.optLong("retryAt")), clock.now() + 365L * 86400000);
        state.retryCode = data.optString("retryCode", "RATE_LIMITED");
        state.nextRefreshAt = Math.min(Math.max(0, data.optLong("nextRefreshAt")), clock.now() + 365L * 86400000);
        state.lastRefresh = Math.min(Math.max(0, data.optLong("lastRefresh")), clock.now());
        state.metadataAt = Math.min(Math.max(0, data.optLong("metadataAt")), clock.now());
        state.lastExportAt = Math.min(Math.max(0, data.optLong("lastExportAt")), clock.now());
        state.ttl = Math.max(1000, Math.min(DEFAULT_TTL, data.optLong("ttl", DEFAULT_TTL)));
        state.session = data.optJSONObject("session"); state.pendingSession = data.optJSONObject("pendingSession"); state.account = data.optJSONObject("account");
        if (state == one) {
            validateOneSaved(state.session); validateOneSaved(state.pendingSession);
        } else if (state == octo && state.session != null) validateOctoSession(state.session);
        if (state == octo) {
            if (data.optJSONArray("regions") != null && data.optJSONArray("regions").length() <= 200) state.regions = data.getJSONArray("regions");
            if (data.optJSONArray("exports") != null && data.optJSONArray("exports").length() <= 3 && state.account != null && state.account.optBoolean("paid")) {
                state.exports = data.getJSONArray("exports"); state.nodes = exportNodes(state.exports);
            }
            Set<Integer> cachedIds = new HashSet<>();
            for (JSONObject entry : exportEntries(state.exports)) cachedIds.add(entry.getJSONObject("region").getInt("id"));
            JSONArray failed = data.optJSONArray("failedRegions"), attempts = data.optJSONArray("exportAttempts");
            if (failed != null) for (int i = 0; i < Math.min(200, failed.length()); i++) {
                int id = (int) integer(failed.opt(i), 0, 2147483647); if (cachedIds.contains(id)) state.failedRegions.add(id);
            }
            if (attempts != null) for (int i = 0; i < Math.min(200, attempts.length()); i++) {
                JSONArray row = attempts.getJSONArray(i); if (row.length() != 2) continue;
                int id = (int) integer(row.opt(0), 0, 2147483647); long at = integer(row.opt(1), 0, clock.now());
                state.exportAttempts.put(id, at);
            }
        } else if (data.optJSONArray("nodes") != null) {
            JSONArray rows = data.getJSONArray("nodes");
            if (rows.length() > 2000) throw new Exception();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                state.nodes.add(new Node(row.getJSONObject("config"), state.provider, row.isNull("country") ? null : row.getString("country")));
            }
            if (state == one && (state.session == null || state.account == null)) state.nodes.clear();
        }
        state.diagnostic = safeDiagnostic(data.optJSONObject("lastDiagnostic"), state.provider);
        state.phase = state.pendingSession != null ? "subscription-pending" : !state.nodes.isEmpty() ? "ready" : state.session != null ? "subscription-pending" : "logged-out";
        if (state == octo && state.session != null && state.session.optString("sid").isEmpty()) {
            ProviderException error = new ProviderException("AUTH_REQUIRED", "restore");
            state.phase = "error"; state.error = error.getMessage(); state.code = error.code;
        }
        if (state.retryAt > clock.now()) { ProviderException error = cooldown(state); state.error = error.getMessage(); state.code = error.code; }
    }

    private static JSONObject safeDiagnostic(JSONObject raw, String provider) {
        if (raw == null || !provider.equals(raw.optString("provider"))) return null;
        JSONObject result = object("provider", provider);
        for (String name : Arrays.asList("operation", "stage", "outcome", "code", "providerCode")) {
            String value = raw.optString(name);
            if (value.matches("[A-Za-z_-]{1,50}")) put(result, name, value);
        }
        for (String name : Arrays.asList("at", "httpStatus", "retryAt")) if (raw.opt(name) instanceof Number) put(result, name, raw.optLong(name));
        return result;
    }

    private void publish() {
        List<Node> nodes = new ArrayList<>(); Map<String, String> statuses = new HashMap<>(), origins = new HashMap<>(); Set<String> usedNames = new HashSet<>();
        for (State state : Arrays.asList(one, octo, imported)) {
            for (Node original : state.nodes) {
                Node visible = original;
                if (!usedNames.add(original.name)) {
                    String base = original.name.substring(0, Math.min(220, original.name.length()));
                    String name = base + " [" + original.provider + "]"; int number = 2;
                    while (!usedNames.add(name)) name = base + " [" + original.provider + " " + number++ + "]";
                    try { JSONObject config = cloneJson(original.config); config.put("name", name); visible = new Node(config, original.provider, original.country); }
                    catch (Exception ignored) { continue; }
                }
                nodes.add(visible); origins.put(visible.name, original.id);
            }
            JSONObject account = state.account == null ? new JSONObject() : state.account;
            JSONObject status = object("provider", state.provider, "username", account.optString("username", ""),
                    "subscriptionEnd", account.opt("subscriptionEnd"), "paid", account.optBoolean("paid"),
                    "connected", state.session != null && (!state.provider.equals("octohide") || !state.session.optString("sid").isEmpty()),
                    "phase", state.phase, "error", state.error == null ? "" : state.error, "code", state.code == null ? "" : state.code, "retryAt", state.retryAt > clock.now() ? state.retryAt : 0,
                    "nextRefreshAt", state.nextRefreshAt > clock.now() ? state.nextRefreshAt : 0,
                    "nodeCount", state.nodes.size(), "pendingSignin", state.pendingSession != null,
                    "lastRefresh", state.lastRefresh, "lastDiagnostic", state.diagnostic);
            statuses.put(state.provider, status.toString());
        }
        publishedNodes = Collections.unmodifiableList(nodes); publishedStatus = Collections.unmodifiableMap(statuses); publishedOrigins = Collections.unmodifiableMap(origins);
        Runnable listener = onChanged;
        if (listener != null) try { listener.run(); } catch (Exception ignored) { }
    }

    static JSONObject object(Object... pairs) {
        JSONObject result = new JSONObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) put(result, (String) pairs[i], pairs[i + 1]);
        return result;
    }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value == null ? JSONObject.NULL : value); } catch (Exception ignored) { } }
    private static JSONObject cloneJson(JSONObject raw) { try { return raw == null ? null : new JSONObject(raw.toString()); } catch (Exception ignored) { return null; } }
    private static String requiredText(Object raw, int max) throws Exception { String value = NodeParser.strictText(raw, max); if (value == null) throw new Exception(); return value; }
    private static long integer(Object raw, long min, long max) throws Exception {
        if (!(raw instanceof Number)) throw new Exception();
        Number number = (Number) raw;
        long value = number.longValue();
        if (number.doubleValue() != (double) value || value < min || value > max) throw new Exception();
        return value;
    }
    private static void validateOneSaved(JSONObject session) throws Exception {
        if (session == null) return;
        requiredText(session.opt("accessToken"), 8192); requiredText(session.opt("refreshToken"), 8192); requiredText(session.opt("deviceId"), 128);
        integer(session.opt("expiresAt"), 1, Long.MAX_VALUE);
    }
    private static void validateOctoSession(JSONObject session) throws Exception {
        if (!session.optString("deviceId").matches("[a-f0-9]{16}")) throw new Exception();
        String sid = session.optString("sid");
        if (sid.length() > 4096 || PatternControl(sid)) throw new Exception();
        for (String key : Arrays.asList("tsv1", "tsv2")) { long value = integer(session.opt(key), 0, 9999999999L); if (value != 0 && value < 1000000000) throw new Exception(); }
    }
    private static boolean PatternControl(String value) { return java.util.regex.Pattern.compile("[\\x00-\\x1f\\x7f]").matcher(value).find(); }

    // The official paid configexport flow only. Normal Android connection/integrity flows are not bypassed.
    final class OctoClient {
        JSONObject session;
        boolean initial;
        OctoClient(JSONObject previous) throws ProviderException {
            try {
                session = previous == null ? object("deviceId", randomHex(8), "sid", "", "tsv1", 0, "tsv2", 0) : cloneJson(previous);
                validateOctoSession(session); initial = session.optLong("tsv1") != 0;
            } catch (Exception ignored) { throw new ProviderException("INVALID_INPUT", "session"); }
        }
        JSONObject init() throws ProviderException {
            try { return initOnce(); }
            catch (ProviderException error) {
                if (!error.code.equals("BAD_REPLY_ID")) throw error;
                put(session, "tsv1", 0); put(session, "tsv2", 0); return initOnce();
            }
        }
        private JSONObject initOnce() throws ProviderException {
            Map<String, String> fields = form("action[0]", "init"); if (!initial) fields.put("initialstate", "1");
            JSONObject value = section("init", fields, false); initial = true; return value;
        }
        JSONObject info() throws ProviderException { return recover(() -> { ensureInit(); return section("info", form("action[0]", "info"), false); }, false); }
        JSONObject regions() throws ProviderException { return recover(this::regionsOnce, false); }
        private JSONObject regionsOnce() throws ProviderException { ensureInit(); return section("regions", form("action[0]", "regions"), false); }
        JSONObject login(String username, String password) throws ProviderException { return recover(() -> { ensureInit(); return section("login", form("action", "login", "username", username, "password", password), false); }, false); }
        JSONObject export(int region) throws ProviderException {
            return recover(() -> {
                try {
                    if (session.optString("sid").isEmpty()) throw new ProviderException("AUTH_REQUIRED", "config-export");
                    ensureInit(); if (session.optLong("tsv2") == 0) regionsOnce();
                    if (session.optString("sid").isEmpty()) throw new ProviderException("AUTH_REQUIRED", "config-export");
                    JSONObject value = section("configexport", form("action", "configexport", "service", "1", "region", String.valueOf(region),
                            "platformid", platformId(session.optLong("tsv1")), "configid", configId(session.optString("deviceId"), session.optLong("tsv1"), session.optLong("tsv2"))), true);
                    if (value.optInt("authenticated") != 1) throw new ProviderException("AUTH_REQUIRED", "config-export");
                    if (value.optInt("region", -1) != region) throw new ProviderException("INVALID_RESPONSE", "config-export");
                    return value;
                } catch (ProviderException error) { throw error; }
                catch (Exception ignored) { throw new ProviderException("INVALID_RESPONSE", "config-export"); }
            }, true);
        }
        private void ensureInit() throws ProviderException { if (session.optLong("tsv1") == 0) initOnce(); }
        interface Operation { JSONObject run() throws ProviderException; }
        private JSONObject recover(Operation operation, boolean rebuildRegions) throws ProviderException {
            try { return operation.run(); }
            catch (ProviderException error) {
                if (!error.code.equals("BAD_REPLY_ID")) throw error;
                put(session, "tsv1", 0); put(session, "tsv2", 0); initOnce(); if (rebuildRegions) regionsOnce();
                return operation.run(); // Exactly one recovery, no infinite reply or request retry.
            }
        }
        private JSONObject section(String action, Map<String, String> extra, boolean mode4) throws ProviderException {
            String stage = action.equals("configexport") ? "config-export" : action.equals("login") ? "login-request" : action.equals("info") ? "account" : action;
            try {
                long seconds = clock.now() / 1000; timestamp(seconds);
                String device = session.getString("deviceId");
                Map<String, String> fields = form("id", device, "client_version", "v2.250", "install_source", "", "locale", "en-US", "device_locale", "en-US",
                        "device_type", "phone", "os", "Android", "cid", clientId(device, clock.now()), "timestamp", String.valueOf(seconds),
                        "requestid", requestId(device, seconds, mode4));
                if (!session.optString("sid").isEmpty()) fields.put("auth", session.getString("sid"));
                fields.putAll(extra);
                JSONObject data = http.octo(fields, stage); checkOctoError(data, stage, clock.now());
                JSONObject raw = data.optJSONObject(action);
                if (raw == null) throw new ProviderException("INVALID_RESPONSE", stage);
                checkOctoError(raw, stage, clock.now());
                JSONObject parsed = parseOctoSection(action, raw);
                String reply = parsed.getString("replyid");
                boolean good = action.equals("regions") ? reply.equals(replyId(device, parsed.getLong("timestamp"))) : evenReply(reply);
                if (!good) { put(session, "tsv2", 0); throw new ProviderException("BAD_REPLY_ID", stage); }
                if (parsed.getInt("authenticated") == 0) put(session, "sid", "");
                if (action.equals("init")) put(session, "tsv1", parsed.getLong("timestamp"));
                if (action.equals("regions")) put(session, "tsv2", parsed.getLong("timestamp"));
                if (action.equals("login")) put(session, "sid", parsed.getString("sid"));
                if (Arrays.asList("info", "regions").contains(action) && parsed.getInt("authenticated") != 1) throw new ProviderException("AUTH_REQUIRED", stage);
                return parsed;
            } catch (ProviderException error) { throw error; }
            catch (Exception ignored) { throw new ProviderException("INVALID_RESPONSE", stage); }
        }
    }

    static JSONObject parseOctoSection(String action, JSONObject raw) throws ProviderException {
        String stage = action.equals("configexport") ? "config-export" : action;
        try {
            long time = integer(raw.opt("timestamp"), 1000000000, 9999999999L);
            long authenticated = integer(raw.opt("authenticated"), 0, 1);
            String reply = (String) raw.get("replyid"); if (reply.length() > 32) throw new Exception();
            JSONObject result = object("timestamp", time, "authenticated", authenticated, "replyid", reply);
            if (action.equals("init")) {
                put(result, "profile", integer(raw.opt("profile"), 0, 1));
                if (raw.has("init")) put(result, "init", integer(raw.opt("init"), 0, Long.MAX_VALUE));
            } else if (action.equals("login")) {
                if (authenticated != 1) throw new ProviderException("AUTH_REQUIRED", "login-response");
                put(result, "sid", requiredText(raw.opt("sid"), 4096));
                put(result, "profile_id", integer(raw.opt("profile_id"), 0, Long.MAX_VALUE));
                put(result, "shared", integer(raw.opt("shared"), 0, Long.MAX_VALUE));
            } else if (action.equals("info")) {
                if (!(raw.opt("vip") instanceof Boolean)) throw new Exception();
                put(result, "vip", raw.getBoolean("vip"));
                put(result, "subscription_end", integer(raw.opt("subscription_end"), 0, Long.MAX_VALUE / 1000));
                put(result, "profile_id", integer(raw.opt("profile_id"), 0, Long.MAX_VALUE));
                put(result, "shared", integer(raw.opt("shared"), 0, Long.MAX_VALUE));
                String username = raw.getString("username"); if (username.length() > 512 || PatternControl(username)) throw new Exception(); put(result, "username", username);
                Object activated = raw.opt("activated"); if (!(activated instanceof Boolean)) integer(activated, 0, 1);
                put(result, "activated", activated);
                JSONObject cfg = raw.getJSONObject("cfg"), parsedCfg = new JSONObject();
                for (String key : Arrays.asList("export_expire_unused", "export_expire_inactive", "con_test_int")) if (cfg.has(key)) put(parsedCfg, key, integer(cfg.opt(key), 0, Long.MAX_VALUE));
                put(result, "cfg", parsedCfg);
            } else if (action.equals("regions")) {
                JSONArray rows = raw.getJSONArray("list"), list = new JSONArray(); if (rows.length() > 1000) throw new Exception();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    JSONObject parsed = object("id", integer(row.opt("id"), 0, 2147483647), "name", requiredText(row.opt("name"), 256));
                    String country = row.optString("country_code"); put(parsed, "country_code", country.matches("(?i)[a-z]{2}") ? country.toUpperCase(Locale.ROOT) : null);
                    for (String key : Arrays.asList("free", "vip", "android", "ios", "vo", "t", "i6", "f", "v", "wireguard", "free_wireguard", "vip_wireguard"))
                        if (row.has(key)) put(parsed, key, integer(row.opt(key), 0, 2147483647));
                    list.put(parsed);
                }
                put(result, "list", list);
            } else if (action.equals("configexport")) {
                Object rawRegion = raw.opt("region");
                if (rawRegion instanceof String && ((String) rawRegion).matches("0|[1-9][0-9]{0,9}")) rawRegion = Long.parseLong((String) rawRegion);
                long region = integer(rawRegion, 0, 2147483647);
                String address = raw.getString("address"), text = raw.getString("text");
                if (address.length() > 512 || PatternControl(address) || text.isEmpty() || text.length() > 128 * 1024 || text.indexOf('\0') >= 0) throw new Exception();
                put(result, "region", region); put(result, "address", address); put(result, "text", text);
            } else throw new Exception();
            return result;
        } catch (ProviderException error) { throw error; }
        catch (Exception ignored) { throw new ProviderException("INVALID_RESPONSE", stage); }
    }

    private static final Map<String, String> OCTO_ERRORS;
    static {
        Map<String, String> errors = new HashMap<>();
        // Exact error names from the provider's Android getErrorMessage catalog. Do not
        // expose arbitrary server text or infer account/integrity remedies from a name.
        for (String name : Arrays.asList("SERVICE_UNAVAILABLE", "API_RESPONSE_NULL", "PASSWORD_TOO_SIMPLE", "TOKEN_APPLE_SUBSCRIPTION",
                "TOKEN_UNLIMITED_SUBSCRIPTION", "CODE_EXPIRED_ACTIVATE_ACCOUNT", "CODE_EXPIRED_CHANGE_USERNAME", "ALREADY_REGISTERED",
                "VPN_START_ERROR", "SERVICE_NOT_FOUND", "INVALID_SERVICE", "NOPUBLICIP", "SYSTEM_ERROR", "CLIENT_CERTIFICATE_ERROR",
                "INTEGRITY_CHECK_ERROR", "FAILED_TO_CREATE", "IP_SEARCH_FAILED", "CREATE_ERROR", "INVALID_ID_FORMAT", "CLIENT_NOT_FOUND",
                "INVALID_CREDENTIALS_FORMAT", "TOKEN_EXPIRED", "USERNAME_TOO_LONG", "USERNAME_NOT_EMAIL", "REGION_NOT_FOUND", "UNKNOWN_ACTION",
                "NO_AGREEMENT", "PASSWORD_TOO_WEAK", "ERROR_VPN_CONNECTION_LOST", "APP_NO_NETWORK", "NOT_REGISTERED", "PASSWORD_TOO_SHORT",
                "TOKEN_UNKNOWN", "EMPTY_RESPONSE", "FORBIDDEN", "LOGIN_LIMIT", "USERNAME_NOT_SET", "NEW_USERNAME_NOT_EMAIL", "TOKEN_USED",
                "UNSUPPORTED_SERVICE", "CODE_EXPIRED", "UNKNOWN_REDEEM_TYPE", "SERVER_ERROR", "BAD_ACTION", "INVALID_DEACTIVATION_ID",
                "USERNAME_TAKEN", "INVALID_USERNAME_FORMAT", "NO_SERVERS", "ERROR_GETTING_CONFIGURATION", "MISSING_ACTION", "TOKEN_LIMIT",
                "TOKEN_INVALID")) errors.put(name, "PROVIDER_ERROR");
        for (String name : Arrays.asList("BAD_REPLY_ID", "MAINTENANCE", "AUTH_REQUIRED", "INVALID_CREDENTIALS", "VIP_REQUIRED", "DEVICE_LIMIT", "RATE_LIMITED")) errors.put(name, name);
        errors.put("INVALID_REPLY_ID", "BAD_REPLY_ID");
        for (String name : Arrays.asList("UNAUTHORIZED", "NOT_AUTHENTICATED")) errors.put(name, "AUTH_REQUIRED");
        for (String name : Arrays.asList("AUTH_FAILED", "WRONG_PASSWORD", "INVALID_PASSWORD", "USER_NOT_FOUND")) errors.put(name, "INVALID_CREDENTIALS");
        for (String name : Arrays.asList("NOT_VIP", "SUBSCRIPTION_EXPIRED", "VIP_SUBSCRIPTION_REQUIRED", "VIP_ACCESS_NOT_FOUND")) errors.put(name, "VIP_REQUIRED");
        for (String name : Arrays.asList("DEVICE_LIMIT_REACHED", "TOO_MANY_DEVICES", "MAX_ACTIVATED_LIMIT")) errors.put(name, "DEVICE_LIMIT");
        for (String name : Arrays.asList("NEEDS_ACTIVATION", "ACCOUNT_NOT_ACTIVATED")) errors.put(name, "ACCOUNT_ACTIVATION_REQUIRED");
        for (String name : Arrays.asList("TOO_MANY_REQUESTS", "REQUEST_LIMIT", "REQUEST_LIMIT_SHORT", "REQUEST_LIMIT_LONG",
                "REQUEST_LIMIT_SEND_ACTIVATION_CODE", "REQUEST_LIMIT_ACTIVATE_ACCOUNT")) errors.put(name, "RATE_LIMITED");
        OCTO_ERRORS = Collections.unmodifiableMap(errors);
    }
    static void checkOctoError(JSONObject raw, String stage, long now) throws ProviderException {
        Object value = raw.opt("error"); if (value == null || value == JSONObject.NULL || "".equals(value)) return;
        String provider = value instanceof String && OCTO_ERRORS.containsKey(value) ? (String) value : null;
        String code = provider == null ? "PROVIDER_ERROR" : OCTO_ERRORS.get(provider);
        long retryAt = 0;
        // Only these provider codes document `code` as a relative wait in seconds.
        if ("REQUEST_LIMIT_LONG".equals(provider) || "REQUEST_LIMIT_SEND_ACTIVATION_CODE".equals(provider)
                || "REQUEST_LIMIT_ACTIVATE_ACCOUNT".equals(provider))
            try { retryAt = now + integer(raw.opt("code"), 1, 365L * 86400) * 1000; } catch (Exception ignored) { }
        throw new ProviderException(code, stage, 0, retryAt, provider);
    }
    static String pickHash(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return hex(new byte[] { digest[4], digest[6], digest[11], digest[14] });
    }
    static String requestId(String device, long time, boolean mode4) throws Exception { timestamp(time); return pickHash((mode4 ? "octohidvpn" : "octohidevpn") + device + time) + randomHex(4); }
    static String replyId(String device, long time) throws Exception { timestamp(time); return pickHash("octohidevpn" + time + device); }
    static String platformId(long tsv1) throws Exception { timestamp(tsv1); long n = (tsv1 ^ 0xaaaaaa) & 0xffffff; return String.format(Locale.ROOT, "%06x", n); }
    static String configId(String device, long tsv1, long tsv2) throws Exception { timestamp(tsv1); timestamp(tsv2); return pickHash("octohidevpn" + tsv1 + tsv2 + device); }
    static boolean evenReply(String value) {
        if (value == null || !value.matches("(?i)[0-9a-f]{1,16}")) return false;
        BigInteger n = new BigInteger(value, 16);
        return n.bitLength() <= 63 && !n.testBit(0);
    }
    private static void timestamp(long value) throws Exception { if (value < 1000000000 || value >= 10000000000L) throw new Exception(); }
    private static String randomHex(int bytes) { byte[] value = new byte[bytes]; new SecureRandom().nextBytes(value); return hex(value); }
    private static String clientId(String device, long millis) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(device.getBytes(StandardCharsets.UTF_8)); digest.update(String.valueOf(millis).getBytes(StandardCharsets.UTF_8));
        byte[] random = new byte[16]; new SecureRandom().nextBytes(random); digest.update(random);
        return hex(digest.digest()).substring(0, 32);
    }
    private static String hex(byte[] bytes) { StringBuilder value = new StringBuilder(); for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 255)); return value.toString(); }
    private static Map<String, String> form(String... pairs) { Map<String, String> result = new LinkedHashMap<>(); for (int i = 0; i + 1 < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]); return result; }
}
