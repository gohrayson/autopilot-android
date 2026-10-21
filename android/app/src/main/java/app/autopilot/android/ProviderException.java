package app.autopilot.android;

import org.json.JSONObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Only catalogued diagnostics may cross the provider/UI boundary. */
public final class ProviderException extends Exception {
    private static final long serialVersionUID = 1L;
    private static final Map<String, String> MESSAGES;
    static {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("INVALID_INPUT", "Enter a valid email and password.");
        values.put("INVALID_RESPONSE", "The provider returned a response that could not be verified.");
        values.put("INVALID_SUBSCRIPTION", "The provider returned an invalid subscription.");
        values.put("INVALID_CREDENTIALS", "The provider rejected the email or password.");
        values.put("AUTH_REQUIRED", "Sign in to this provider again.");
        values.put("SESSION_EXPIRED", "The account session expired. Sign in again.");
        values.put("ACCOUNT_EXPIRED", "The subscription is expired or unavailable.");
        values.put("VIP_REQUIRED", "Octohide requires an active paid subscription for WireGuard exports.");
        values.put("ACCOUNT_ACTIVATION_REQUIRED", "Activate your Octohide account using its email verification.");
        values.put("DEVICE_LIMIT", "The provider's device limit was reached. Remove an unused device in your account.");
        values.put("BAD_REPLY_ID", "Octohide response verification failed.");
        values.put("MAINTENANCE", "The provider is undergoing maintenance. Cached nodes were kept.");
        values.put("RATE_LIMITED", "The provider temporarily limited requests. Autopilot will retry after the displayed time.");
        values.put("REQUEST_BLOCKED", "The provider refused the request. Check its status and try later.");
        values.put("PROVIDER_ERROR", "The provider could not complete the request. Cached nodes were kept.");
        values.put("HTTP_ERROR", "The provider could not complete the HTTP request. Cached nodes were kept.");
        values.put("NETWORK_ERROR", "Could not reach the provider. Cached nodes were kept.");
        values.put("TIMEOUT", "The provider request timed out. Cached nodes were kept.");
        values.put("ABORTED", "The provider request was cancelled.");
        values.put("ANDROID_VERIFICATION_REQUIRED", "Octohide requires verification in its own Android app.");
        values.put("CREDENTIAL_STORE", "Android could not save the account securely. Unlock this device and retry.");
        values.put("IMPORT_INVALID", "Choose a valid Clash JSON/YAML subscription or WireGuard configuration without command hooks.");
        values.put("UNKNOWN_PROVIDER", "Choose OneVPN or Octohide.");
        MESSAGES = Collections.unmodifiableMap(values);
    }

    public final String code;
    public final String stage;
    public final int httpStatus;
    public final long retryAt;
    public final String providerCode;

    public ProviderException(String code, String stage) { this(code, stage, 0, 0, null); }
    public ProviderException(String code, String stage, int httpStatus, long retryAt, String providerCode) {
        super(MESSAGES.getOrDefault(code, MESSAGES.get("PROVIDER_ERROR")));
        this.code = MESSAGES.containsKey(code) ? code : "PROVIDER_ERROR";
        this.stage = stage != null && stage.matches("[a-z-]{1,40}") ? stage : "request";
        this.httpStatus = httpStatus >= 100 && httpStatus <= 599 ? httpStatus : 0;
        this.retryAt = Math.max(0, retryAt);
        this.providerCode = providerCode != null && providerCode.matches("[A-Z_]{1,50}") ? providerCode : null;
    }

    public JSONObject diagnostic(String provider, String operation, long at) {
        JSONObject result = new JSONObject();
        try {
            result.put("provider", provider).put("operation", operation).put("stage", stage)
                    .put("outcome", "failed").put("code", code).put("at", at);
            if (httpStatus > 0) result.put("httpStatus", httpStatus);
            if (retryAt > 0) result.put("retryAt", retryAt);
            if (providerCode != null) result.put("providerCode", providerCode);
        } catch (Exception ignored) { }
        return result;
    }
}
