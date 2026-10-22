package app.autopilot.android;

import java.util.*;
import org.json.*;

/** Fixed-code observations only. Native log text and response error strings are never retained. */
public final class HealthDiagnostics {
    private HealthDiagnostics() {}

    public enum LatencyCode {
        NOT_TESTED, LATENCY_OK, LATENCY_FAILED, CORE_ACTION_FAILED, CORE_RESPONSE_INVALID, CHECK_TIMEOUT, CORE_RESPONSE_FAILED
    }
    public static final class Latency {
        public final int millis;
        public final LatencyCode code;
        public final Integer nativeCode;
        private Latency(int millis,LatencyCode code,Integer nativeCode){this.millis=millis;this.code=code;this.nativeCode=nativeCode;}
    }
    public static Latency failed(LatencyCode code){
        if(code!=LatencyCode.CHECK_TIMEOUT&&code!=LatencyCode.CORE_RESPONSE_FAILED)throw new IllegalArgumentException("Invalid failure code");
        return new Latency(-1,code,null);
    }
    /**
     * Pinned bridge action.go returns code=0 and an integer delay; proxies.go
     * deliberately collapses URLTest errors, deadlines and missing proxies to -1.
     * A -1 therefore cannot identify a DNS, provider or transport failure.
     */
    public static Latency latency(JSONObject response){
        Integer code=integer(response==null?null:response.opt("code"));
        if(code==null)return new Latency(-1,LatencyCode.CORE_RESPONSE_INVALID,null);
        if(code!=0)return new Latency(-1,LatencyCode.CORE_ACTION_FAILED,code);
        Integer value=integer(response.opt("data"));
        if(value==null||value< -1||value>65535)return new Latency(-1,LatencyCode.CORE_RESPONSE_INVALID,code);
        return value>0?new Latency(value,LatencyCode.LATENCY_OK,code):new Latency(-1,LatencyCode.LATENCY_FAILED,code);
    }
    private static Integer integer(Object value){
        if(!(value instanceof Number))return null;
        double number=((Number)value).doubleValue();
        return Double.isFinite(number)&&number==((Number)value).intValue()?((Number)value).intValue():null;
    }

    public enum NetworkCode { NATIVE_DNS_TIMEOUT, NATIVE_DNS_FAILURE, NATIVE_NETWORK_UNREACHABLE, NATIVE_CONNECTION_REFUSED, NATIVE_DIAL_TIMEOUT }
    /** Classify only warning/error dial observations; matches are not proof of any particular node failing. */
    public static NetworkCode network(String severity,String payload){
        if(!Set.of("warning","warn","error").contains(severity==null?"":severity.toLowerCase(Locale.ROOT))||payload==null||payload.length()>16384)return null;
        String text=payload.toLowerCase(Locale.ROOT);
        boolean timeout=text.contains("timeout")||text.contains("timed out")||text.contains("deadline exceeded");
        boolean dns=text.contains("dns resolve failed")||text.contains("dns request failed")||text.contains("all dns requests failed")||
            text.contains("no ip address")||text.contains("no such host")||text.contains("dns exchange")||text.contains("failed to resolve");
        if(dns)return timeout?NetworkCode.NATIVE_DNS_TIMEOUT:NetworkCode.NATIVE_DNS_FAILURE;
        if(!text.startsWith("[tcp]")&&!text.startsWith("[udp]"))return null;
        if(text.contains("network is unreachable")||text.contains("no route to host"))return NetworkCode.NATIVE_NETWORK_UNREACHABLE;
        if(text.contains("connection refused"))return NetworkCode.NATIVE_CONNECTION_REFUSED;
        return timeout?NetworkCode.NATIVE_DIAL_TIMEOUT:null;
    }
    /** Session-local counters retain only enum keys, counts and a timestamp. */
    public static final class NetworkSignals {
        private final EnumMap<NetworkCode,Long> counts=new EnumMap<>(NetworkCode.class);
        private NetworkCode last;
        private long at;
        public synchronized boolean observe(String severity,String payload,long now){
            NetworkCode code=network(severity,payload);if(code==null)return false;
            counts.put(code,Math.min(Integer.MAX_VALUE,counts.getOrDefault(code,0L)+1));last=code;at=now;return true;
        }
        public synchronized JSONObject json(){
            JSONObject result=new JSONObject(),totals=new JSONObject();
            try{for(var entry:counts.entrySet())totals.put(entry.getKey().name(),entry.getValue());
                result.put("counts",totals);if(last!=null)result.put("lastCode",last.name()).put("at",at);
            }catch(JSONException ignored){}
            return result;
        }
    }
    public static JSONArray providers(JSONArray rows){
        JSONArray result=new JSONArray();
        for(String provider:List.of("onevpn","octohide","import","other")){
            int total=0,tested=0,passed=0,failed=0,stale=0,qualified=0,pending=0,serviceFailed=0;
            EnumMap<LatencyCode,Integer> codes=new EnumMap<>(LatencyCode.class);
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.optJSONObject(i);if(row==null)continue;
                String source=row.optString("provider");if(!Set.of("onevpn","octohide","import").contains(source))source="other";
                if(!provider.equals(source))continue;total++;
                if(row.optLong("latencyAt")>0){tested++;if(row.optInt("latency",-1)>0){passed++;if(!row.optBoolean("latencyFresh"))stale++;}else failed++;}
                if(row.optBoolean("qualified"))qualified++;
                else if(row.optInt("latency",-1)>0){
                    if(row.optLong("servicesAt")==0)pending++;else serviceFailed++;
                }
                try{LatencyCode code=LatencyCode.valueOf(row.optString("latencyCode","NOT_TESTED"));codes.put(code,codes.getOrDefault(code,0)+1);}catch(IllegalArgumentException ignored){}
            }
            if(total==0&&!Set.of("onevpn","octohide").contains(provider))continue;
            try{JSONObject failures=new JSONObject();for(var entry:codes.entrySet())failures.put(entry.getKey().name(),entry.getValue());
                result.put(new JSONObject().put("provider",provider).put("loaded",total).put("tested",tested).put("notTested",total-tested)
                    .put("latencyPassed",passed).put("latencyFailed",failed).put("latencyStale",stale).put("serviceVerified",qualified)
                    .put("serviceChecksPending",pending).put("serviceNotQualified",serviceFailed).put("latencyCodes",failures));
            }catch(JSONException ignored){}
        }
        return result;
    }
    public static String latencyMessage(String code){
        final LatencyCode known;try{known=LatencyCode.valueOf(code);}catch(IllegalArgumentException|NullPointerException ignored){return "";}
        return switch(known){
            case NOT_TESTED->"Waiting for its first check";
            case LATENCY_OK->"";
            case LATENCY_FAILED->"Connection check failed";
            case CHECK_TIMEOUT->"Connection check timed out";
            case CORE_ACTION_FAILED->"The VPN core could not run this check";
            case CORE_RESPONSE_INVALID,CORE_RESPONSE_FAILED->"The VPN core returned no valid check result";
        };
    }
}
