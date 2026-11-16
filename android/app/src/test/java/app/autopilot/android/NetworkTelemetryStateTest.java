package app.autopilot.android;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class NetworkTelemetryStateTest {
    private static NetworkTelemetryState.Route route(long generation,long epoch,String name,String country){return new NetworkTelemetryState.Route(generation,epoch,name,name,country,27001);}
    @Test public void oldExitNeverLabelsNewSelectionProfileOrStoppedVpn()throws Exception{
        NetworkTelemetryState state=new NetworkTelemetryState(null,true);
        state.target(route(1,1,"Japan","JP"),1,true);NetworkTelemetryState.Ticket old=state.beginExit();
        state.target(route(1,2,"Singapore","SG"),1,true);
        assertFalse(state.completeExit(old,NetworkTelemetryState.parseExit("ip=8.8.8.8\nloc=JP\n"),10));
        assertTrue(state.json().getJSONObject("exit").isNull("ip"));assertEquals("checking",state.json().getJSONObject("exit").getString("phase"));
        NetworkTelemetryState.Ticket active=state.beginExit();assertTrue(state.completeExit(active,NetworkTelemetryState.parseExit("ip=8.8.4.4\nloc=SG\n"),20));
        assertEquals("SG",state.json().getJSONObject("exit").getString("country"));
        state.target(route(2,2,"Singapore","SG"),2,true);assertTrue(state.json().getJSONObject("exit").isNull("country"));
        NetworkTelemetryState.Ticket stopped=state.beginExit();state.close();assertFalse(state.completeExit(stopped,NetworkTelemetryState.parseExit("ip=8.8.8.8\nloc=SG"),30));
        assertEquals("STOPPED",state.json().getJSONObject("exit").getString("code"));assertTrue(state.json().getJSONObject("exit").isNull("ip"));
    }
    @Test public void measuredCountryMismatchIsExplicitAndDoesNotChangeAdvertisedCountry()throws Exception{
        NetworkTelemetryState state=new NetworkTelemetryState(null,true);state.target(route(1,1,"Japan label","JP"),1,true);
        NetworkTelemetryState.Ticket ticket=state.beginExit();assertTrue(state.completeExit(ticket,NetworkTelemetryState.parseExit("ip=1.1.1.1\nloc=SG"),25));
        JSONObject exit=state.json().getJSONObject("exit");assertEquals("EXIT_COUNTRY_MISMATCH",exit.getString("code"));assertEquals("SG",exit.getString("country"));assertEquals("JP",exit.getString("expectedCountry"));assertFalse(exit.getBoolean("countryMatches"));
        NetworkTelemetryState.Ticket retry=state.beginExit();assertTrue(state.json().getJSONObject("exit").isNull("ip"));assertTrue(state.failExit(retry,40));assertEquals("unavailable",state.json().getJSONObject("exit").getString("phase"));
    }
    @Test public void exactNativeCountersTrackDeltasResetAndProfileBaselinesWithoutDoubleCounting()throws Exception{
        NetworkTelemetryState state=new NetworkTelemetryState(new NetworkTelemetryState.Pair(1000,2000),true);state.target(route(1,1,"first","JP"),1,true);
        state.counters(1,new NetworkTelemetryState.Pair(500,600),new NetworkTelemetryState.Pair(10,20),100,1000);
        JSONObject first=state.json().getJSONObject("traffic");assertEquals(0,first.getLong("sessionUpload"));assertTrue(first.isNull("uploadRate"));assertEquals(1000,first.getLong("totalUpload"));
        state.counters(1,new NetworkTelemetryState.Pair(550,680),new NetworkTelemetryState.Pair(25,40),2100,3000);
        JSONObject next=state.json().getJSONObject("traffic");assertEquals(50,next.getLong("sessionUpload"));assertEquals(80,next.getLong("sessionDownload"));assertEquals(1050,next.getLong("totalUpload"));assertEquals(25,next.getLong("uploadRate"));
        state.target(route(1,2,"next","SG"),1,true);state.counters(1,new NetworkTelemetryState.Pair(600,700),new NetworkTelemetryState.Pair(25,10),4100,5000);
        assertEquals(100,state.json().getJSONObject("traffic").getLong("sessionUpload"));
        state.target(route(2,3,"reload","SG"),2,true);state.counters(2,new NetworkTelemetryState.Pair(600,700),new NetworkTelemetryState.Pair(0,0),6100,7000);
        assertEquals(100,state.json().getJSONObject("traffic").getLong("sessionUpload"));assertTrue(state.json().getJSONObject("traffic").isNull("uploadRate"));
        state.counters(2,new NetworkTelemetryState.Pair(3,4),new NetworkTelemetryState.Pair(3,4),8100,9000);
        assertEquals(103,state.json().getJSONObject("traffic").getLong("sessionUpload"));assertEquals(1103,state.json().getJSONObject("traffic").getLong("totalUpload"));
        state.counters(1,new NetworkTelemetryState.Pair(9000,9000),new NetworkTelemetryState.Pair(9000,9000),10100,11000);
        assertEquals(103,state.json().getJSONObject("traffic").getLong("sessionUpload"));
    }
    @Test public void unavailableSamplesNeverBecomeHealthyZeroOrEraseKnownUsage()throws Exception{
        NetworkTelemetryState state=new NetworkTelemetryState(null,true);state.target(route(1,1,"first","JP"),1,true);
        state.counters(1,new NetworkTelemetryState.Pair(100,200),new NetworkTelemetryState.Pair(0,0),1,1);
        state.counters(1,new NetworkTelemetryState.Pair(150,250),new NetworkTelemetryState.Pair(25,25),2001,2001);state.trafficFailure(1);
        JSONObject status=state.json().getJSONObject("traffic");assertEquals("unavailable",status.getString("phase"));assertTrue(status.isNull("downloadRate"));assertEquals(50,status.getLong("sessionDownload"));
        state.persistenceFailed();assertTrue(state.json().getJSONObject("traffic").isNull("totalDownload"));assertEquals(50,state.json().getJSONObject("traffic").getLong("sessionDownload"));
        state.close();state.counters(1,new NetworkTelemetryState.Pair(900,1000),new NetworkTelemetryState.Pair(100,100),4001,4001);
        assertEquals(50,state.json().getJSONObject("traffic").getLong("sessionDownload"));assertEquals("STOPPED",state.json().getJSONObject("traffic").getString("code"));
    }
    @Test public void trafficAndTraceSchemasRejectMalformedOrUnboundedValues()throws Exception{
        for(String raw:List.of("{}","{\"up\":1}","{\"up\":-1,\"down\":1}","{\"up\":1.5,\"down\":1}","{\"up\":\"1\",\"down\":1}","{\"up\":9007199254740992,\"down\":1}"))try{NetworkTelemetryState.parsePair(raw);fail(raw);}catch(JSONException|IllegalArgumentException expected){}
        NetworkTelemetryState.Pair pair=NetworkTelemetryState.parsePair("{\"up\":123,\"down\":456}");assertEquals(123,pair.up);assertEquals(456,pair.down);
        for(String raw:List.of("ip=999.1.1.1\nloc=JP","ip=8.8.8\nloc=JP","ip=example.com\nloc=JP","ip=1.1.1.1\nloc=XX","ip=1.1.1.1\nloc=xx","ip=::g\nloc=SG","ip=01.1.1.1\nloc=JP","ip=127.0.0.1\nloc=JP","ip=192.168.1.1\nloc=JP","ip=100.64.0.1\nloc=JP","ip=203.0.113.1\nloc=JP","ip=::1\nloc=JP","ip=fc00::1\nloc=JP","ip=fe80::1\nloc=JP","ip=2001:db8::1\nloc=JP","ip=::ffff:192.168.1.1\nloc=JP"))try{NetworkTelemetryState.parseExit(raw);fail(raw);}catch(Exception expected){}
        assertEquals("JP",NetworkTelemetryState.parseExit("ip=2001:4860:4860::8888\nloc=JP").country);
    }
}
