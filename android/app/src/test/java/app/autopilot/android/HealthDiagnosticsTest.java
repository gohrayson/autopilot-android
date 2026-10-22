package app.autopilot.android;

import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class HealthDiagnosticsTest {
    private static final String SECRET="https://user:password-secret@private.example:443/export?token=never-log-token private-key=private-key-secret uuid=secret-user-id";
    private JSONObject response(Object code,Object data)throws Exception{return new JSONObject().put("code",code).put("data",data);}
    @Test public void measuredNativeContractKeepsMinusOneUnclassified()throws Exception{
        HealthDiagnostics.Latency success=HealthDiagnostics.latency(response(0,217));
        assertEquals(217,success.millis);assertEquals(HealthDiagnostics.LatencyCode.LATENCY_OK,success.code);assertEquals(Integer.valueOf(0),success.nativeCode);
        HealthDiagnostics.Latency failed=HealthDiagnostics.latency(response(0,-1));
        assertEquals(-1,failed.millis);assertEquals(HealthDiagnostics.LatencyCode.LATENCY_FAILED,failed.code);assertEquals(Integer.valueOf(0),failed.nativeCode);
        for(int code:List.of(-1,17)){
            failed=HealthDiagnostics.latency(response(code,SECRET));assertEquals(-1,failed.millis);
            assertEquals(HealthDiagnostics.LatencyCode.CORE_ACTION_FAILED,failed.code);assertEquals(Integer.valueOf(code),failed.nativeCode);
        }
    }
    @Test public void stringsFractionsObjectsAndOversizedValuesCannotBecomeDelays()throws Exception{
        for(Object data:List.of("217",SECRET,new JSONObject().put("delay",217),true,1.5,-2,65536,Long.MAX_VALUE)){
            var parsed=HealthDiagnostics.latency(response(0,data));assertEquals(-1,parsed.millis);assertEquals(HealthDiagnostics.LatencyCode.CORE_RESPONSE_INVALID,parsed.code);
        }
        for(Object code:List.of("0",0.5,true,SECRET,Long.MAX_VALUE))assertEquals(HealthDiagnostics.LatencyCode.CORE_RESPONSE_INVALID,HealthDiagnostics.latency(response(code,217)).code);
        assertEquals(HealthDiagnostics.LatencyCode.CORE_RESPONSE_INVALID,HealthDiagnostics.latency(null).code);
        assertEquals(HealthDiagnostics.LatencyCode.CORE_RESPONSE_INVALID,HealthDiagnostics.latency(new JSONObject()).code);
    }
    @Test public void watchdogAndNativeNegativeResultsAreSeparateObservations(){
        assertEquals(HealthDiagnostics.LatencyCode.CHECK_TIMEOUT,HealthDiagnostics.failed(HealthDiagnostics.LatencyCode.CHECK_TIMEOUT).code);
        assertNull(HealthDiagnostics.failed(HealthDiagnostics.LatencyCode.CORE_RESPONSE_FAILED).nativeCode);
        try{HealthDiagnostics.failed(HealthDiagnostics.LatencyCode.LATENCY_FAILED);fail("Only watchdog or bridge failures use this path");}catch(IllegalArgumentException expected){}
    }
    @Test public void warningEventsClassifyOnlyFixedNetworkSignals(){
        assertEquals(HealthDiagnostics.NetworkCode.NATIVE_DNS_TIMEOUT,HealthDiagnostics.network("warning","[TCP] dial AUTOPILOT: dns resolve failed: all DNS requests failed: context deadline exceeded "+SECRET));
        assertEquals(HealthDiagnostics.NetworkCode.NATIVE_DNS_FAILURE,HealthDiagnostics.network("error","[UDP] dial: no ip address "+SECRET));
        assertEquals(HealthDiagnostics.NetworkCode.NATIVE_NETWORK_UNREACHABLE,HealthDiagnostics.network("warning","[TCP] dial: network is unreachable "+SECRET));
        assertEquals(HealthDiagnostics.NetworkCode.NATIVE_CONNECTION_REFUSED,HealthDiagnostics.network("warning","[TCP] dial: connection refused "+SECRET));
        assertEquals(HealthDiagnostics.NetworkCode.NATIVE_DIAL_TIMEOUT,HealthDiagnostics.network("warning","[UDP] dial: i/o timeout "+SECRET));
        // A generic timeout is not enough to assert DNS failed, or even a dial failed.
        assertNull(HealthDiagnostics.network("warning","background job deadline exceeded "+SECRET));
        assertNull(HealthDiagnostics.network("info","[TCP] dial: dns resolve failed "+SECRET));
        assertNull(HealthDiagnostics.network("warning","x".repeat(16385)+" dns resolve failed"));
    }
    @Test public void safeCountersRetainNoRawInputAndAreDetachedSnapshots()throws Exception{
        var signals=new HealthDiagnostics.NetworkSignals();
        assertTrue(signals.observe("warning","[TCP] dial: dns resolve failed: no such host "+SECRET,1234));
        assertTrue(signals.observe("warning","[TCP] dial: dns resolve failed "+SECRET,1235));
        assertFalse(signals.observe("info",SECRET,1236));
        JSONObject json=signals.json();assertEquals("NATIVE_DNS_FAILURE",json.getString("lastCode"));assertEquals(1235,json.getLong("at"));
        assertEquals(2,json.getJSONObject("counts").getInt("NATIVE_DNS_FAILURE"));
        for(String secret:List.of("password-secret","private.example","never-log-token","private-key-secret","secret-user-id","https://"))assertFalse(json.toString(),json.toString().contains(secret));
        json.getJSONObject("counts").put("NATIVE_DNS_FAILURE",999);assertEquals(2,signals.json().getJSONObject("counts").getInt("NATIVE_DNS_FAILURE"));
    }
    private JSONObject row(String provider,int latency,long latencyAt,String code)throws Exception{
        return new JSONObject().put("provider",provider).put("latency",latency).put("latencyAt",latencyAt).put("latencyCode",code)
            .put("name",SECRET).put("config",SECRET);
    }
    @Test public void providerSummarySeparatesUntestedFailedStaleAndServiceChecks()throws Exception{
        JSONArray rows=new JSONArray().put(row("onevpn",-1,0,"NOT_TESTED")).put(row("onevpn",-1,12,"LATENCY_FAILED"))
            .put(row("onevpn",42,13,"LATENCY_OK").put("latencyFresh",true))
            .put(row("onevpn",54,14,"LATENCY_OK").put("latencyFresh",true).put("servicesAt",14).put("qualified",true))
            .put(row("octohide",88,15,"LATENCY_OK").put("latencyFresh",false).put("servicesAt",15))
            .put(row(SECRET,-1,16,SECRET));
        JSONArray summary=HealthDiagnostics.providers(rows);JSONObject one=summary.getJSONObject(0),octo=summary.getJSONObject(1),other=summary.getJSONObject(2);
        assertEquals("onevpn",one.getString("provider"));assertEquals(4,one.getInt("loaded"));assertEquals(3,one.getInt("tested"));assertEquals(1,one.getInt("notTested"));
        assertEquals(2,one.getInt("latencyPassed"));assertEquals(1,one.getInt("latencyFailed"));assertEquals(1,one.getInt("serviceVerified"));assertEquals(1,one.getInt("serviceChecksPending"));
        assertEquals(1,octo.getInt("latencyStale"));assertEquals(1,octo.getInt("serviceNotQualified"));
        assertEquals("other",other.getString("provider"));assertFalse(summary.toString().contains("secret"));assertFalse(summary.toString().contains("name"));assertFalse(summary.toString().contains("config"));
    }
    @Test public void userMessagesComeOnlyFromTheRuntimeCodeCatalog(){
        for(var code:HealthDiagnostics.LatencyCode.values())assertNotNull(HealthDiagnostics.latencyMessage(code.name()));
        assertEquals("",HealthDiagnostics.latencyMessage(SECRET));assertEquals("",HealthDiagnostics.latencyMessage(null));
    }
    @Test public void successfulNativeDelayAboveUserLimitIsReportedAsSlow()throws Exception{
        HealthDiagnostics.Latency measured=HealthDiagnostics.latency(new JSONObject().put("code",0).put("data",4000));
        HealthDiagnostics.Latency limited=HealthDiagnostics.threshold(measured,3000);assertEquals(4000,limited.millis);assertEquals(HealthDiagnostics.LatencyCode.LATENCY_SLOW,limited.code);
        assertEquals(HealthDiagnostics.LatencyCode.LATENCY_OK,HealthDiagnostics.threshold(measured,5000).code);assertFalse(HealthDiagnostics.latencyMessage(limited.code.name()).isEmpty());
    }
}
