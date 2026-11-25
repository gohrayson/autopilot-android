package app.autopilot.android;

import java.util.*;
import org.json.*;

/** Narrow public contract: no provider error text, names, addresses or account data. */
final class AccountRecoveryStatus {
    private AccountRecoveryStatus(){}
    static JSONObject validate(JSONObject raw){
        try{
            AccountRecoveryPolicy policy=new AccountRecoveryPolicy();
            exact(raw,Set.of("phase","reason","lastAttemptAt","nextAttemptAt","providers"));
            phase(raw,policy);Object reason=raw.get("reason");if(reason!=JSONObject.NULL&&(!(reason instanceof String)||!policy.reasons.contains(reason)))throw new IllegalArgumentException();
            time(raw,"lastAttemptAt");time(raw,"nextAttemptAt");JSONArray providers=raw.getJSONArray("providers");if(providers.length()>2)throw new IllegalArgumentException();Set<String> seen=new HashSet<>();
            for(int i=0;i<providers.length();i++){JSONObject item=providers.getJSONObject(i);exact(item,Set.of("id","phase","lastAttemptAt","nextAttemptAt"));Object id=item.get("id");if(!Set.of("onevpn","octohide").contains(id)||!seen.add((String)id))throw new IllegalArgumentException();phase(item,policy);time(item,"lastAttemptAt");time(item,"nextAttemptAt");}
            return new JSONObject(raw.toString());
        }catch(JSONException|IllegalArgumentException invalid){throw new IllegalArgumentException("ACCOUNT_RECOVERY_STATUS_INVALID",invalid);}
    }
    private static void phase(JSONObject raw,AccountRecoveryPolicy policy)throws JSONException{Object phase=raw.get("phase");if(!(phase instanceof String)||!policy.phases.contains(phase))throw new IllegalArgumentException();}
    private static void exact(JSONObject raw,Set<String> expected){if(raw==null)throw new IllegalArgumentException();Set<String> keys=new HashSet<>();for(Iterator<String> iterator=raw.keys();iterator.hasNext();)keys.add(iterator.next());if(!keys.equals(expected))throw new IllegalArgumentException();}
    private static void time(JSONObject raw,String key)throws JSONException{Object value=raw.get(key);if(value==JSONObject.NULL)return;if(!(value instanceof Number))throw new IllegalArgumentException();Number n=(Number)value;long at=n.longValue();if(n.doubleValue()!=at||at<0||at>9007199254740991L)throw new IllegalArgumentException();}
    static String message(JSONObject raw){
        JSONObject safe=safe(raw);
        return switch(safe.optString("phase")){
            case "pending"->"Preparing an account server refresh.";
            case "refreshing"->"Refreshing account servers in the background.";
            case "cooldown"->"Account refresh is waiting for its retry window"+(safe.isNull("nextAttemptAt")?".":" · next attempt "+java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(new Date(safe.optLong("nextAttemptAt")))+".");
            case "retesting"->"Account servers changed. Checking new nodes before selecting a replacement.";
            case "unchanged"->"Account servers were refreshed; cached nodes are current. Connection checks continue.";
            case "error"->"Account recovery needs attention. Cached nodes are retained when valid; see the account status and diagnostics.";
            default->"";
        };
    }
    static JSONObject safe(JSONObject raw){
        if(raw!=null)try{return validate(raw);}catch(IllegalArgumentException invalid){/* Never render unknown enum values or fields. */}
        return ProviderRepository.object("phase",raw==null?"idle":"error","reason",null,"lastAttemptAt",null,"nextAttemptAt",null,"providers",new JSONArray());
    }
    /** Current UI phase follows actual lifecycle; attempt/cooldown times remain
     * available as historical evidence after a successful connection or Stop. */
    static JSONObject lifecycle(JSONObject raw,boolean active,boolean healthy,long pendingUntil,long now){
        JSONObject result;
        if(raw==null)result=safe(null);else try{result=validate(raw);}catch(IllegalArgumentException invalid){return safe(raw);}
        try{
            if(!active||healthy){
                result.put("phase","idle").put("reason",JSONObject.NULL);JSONArray items=result.getJSONArray("providers");for(int i=0;i<items.length();i++)items.getJSONObject(i).put("phase","idle");
            }else if(pendingUntil>now&&pendingUntil<=now+new AccountRecoveryPolicy().persistentDegradedMs&&!"refreshing".equals(result.optString("phase"))){
                result.put("phase","pending").put("reason","persistent-service-degraded").put("nextAttemptAt",pendingUntil);
            }
            return validate(result);
        }catch(JSONException invalid){return safe(new JSONObject());}
    }
}
