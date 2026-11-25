package app.autopilot.android;

import java.util.*;
import org.json.*;

/** The shared runtime contract owns recovery timing and the public enum domain. */
final class AccountRecoveryPolicy {
    final long persistentDegradedMs, refreshMinGapMs;
    final Set<String> phases, reasons;
    AccountRecoveryPolicy(){this(ProductContract.policy().optJSONObject("accountRecovery"));}
    AccountRecoveryPolicy(JSONObject raw){
        try{
            if(raw==null)throw new IllegalArgumentException();
            persistentDegradedMs=duration(raw,"persistentDegradedMs",3600000);refreshMinGapMs=duration(raw,"refreshMinGapMs",86400000);
            if(refreshMinGapMs<persistentDegradedMs)throw new IllegalArgumentException();
            phases=domain(raw.getJSONArray("phases"));reasons=domain(raw.getJSONArray("reasons"));
        }catch(JSONException|IllegalArgumentException invalid){throw new IllegalStateException("ACCOUNT_RECOVERY_POLICY_INVALID",invalid);}
    }
    private static long duration(JSONObject raw,String key,long max)throws JSONException{
        Object value=raw.get(key);if(!(value instanceof Number))throw new IllegalArgumentException();
        Number n=(Number)value;long result=n.longValue();if(n.doubleValue()!=result||result<1000||result>max)throw new IllegalArgumentException();return result;
    }
    private static Set<String> domain(JSONArray array)throws JSONException{
        LinkedHashSet<String> values=new LinkedHashSet<>();if(array.length()==0||array.length()>16)throw new IllegalArgumentException();
        for(int i=0;i<array.length();i++){Object raw=array.get(i);if(!(raw instanceof String)||!((String)raw).matches("[a-z][a-z-]{0,47}")||!values.add((String)raw))throw new IllegalArgumentException();}
        return Collections.unmodifiableSet(values);
    }
    void reason(String value){if(!reasons.contains(value))throw new IllegalArgumentException("ACCOUNT_RECOVERY_REASON_INVALID");}
}
