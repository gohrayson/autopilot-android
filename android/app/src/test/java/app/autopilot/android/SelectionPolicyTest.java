package app.autopilot.android;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class SelectionPolicyTest {
    private static final long NOW=1_000_000;
    private SelectionPolicy.Health node(String id,String country,int latency){
        SelectionPolicy.Health h=new SelectionPolicy.Health(id,id,country);h.latency(latency,NOW);h.servicesAt=NOW;
        h.claude=h.chatgpt=h.gemini="ok";return h;
    }
    @Test public void prioritizesFirstHealthyCountryAndFallsBack(){
        var sg=node("sg","SG",40);var my=node("my","MY",300);var jp=node("jp","JP",20);
        assertSame(my,SelectionPolicy.best(List.of(sg,my,jp),List.of("MY","SG"),NOW));
        my.gemini="blocked";assertSame(sg,SelectionPolicy.best(List.of(sg,my,jp),List.of("MY","SG"),NOW));
        sg.claude="error";assertSame(jp,SelectionPolicy.best(List.of(sg,my,jp),List.of("MY","SG"),NOW));
        assertSame(jp,SelectionPolicy.best(List.of(my,jp),List.of(),NOW));
    }
    @Test public void refusesStaleOrPartiallyCheckedNodes(){
        var h=node("healthy","JP",100);assertTrue(h.qualified(NOW));
        assertFalse(h.qualified(NOW+SelectionPolicy.LATENCY_TTL+1));
        h.latencyAt=NOW+SelectionPolicy.SERVICE_TTL;assertFalse(h.qualified(h.latencyAt+1));
        h.servicesAt=h.latencyAt;h.gemini="unknown";assertFalse(h.qualified(h.latencyAt));
        h.gemini="challenged";assertTrue(h.qualified(h.latencyAt));
        h.latency=-1;assertFalse(h.qualified(h.latencyAt));
    }
    @Test public void sameTierRequiresDwellAndBothImprovementThresholds(){
        var current=node("a","JP",100);var candidate=node("b","JP",75);
        assertFalse(SelectionPolicy.shouldSwitch(current,candidate,List.of(),NOW,NOW-10_000,false));
        assertTrue(SelectionPolicy.shouldSwitch(current,candidate,List.of(),NOW,NOW-31_000,false));
        candidate=node("b","JP",85);
        assertTrue(SelectionPolicy.shouldSwitch(current,candidate,List.of(),NOW,NOW-31_000,true));
        current=node("a","JP",1000);candidate=node("b","JP",950);
        assertFalse(SelectionPolicy.shouldSwitch(current,candidate,List.of(),NOW,NOW-31_000,false));
    }
    @Test public void priorityOverrideAndFailureRecoveryIgnoreDwell(){
        var current=node("sg","SG",40);var my=node("my","MY",300);
        assertTrue(SelectionPolicy.shouldSwitch(current,my,List.of("MY","SG"),NOW,NOW,false));
        assertTrue(SelectionPolicy.shouldSwitch(my,current,List.of("MY","SG"),NOW,NOW-31_000,true));
        current.claude="blocked";assertTrue(SelectionPolicy.shouldSwitch(current,my,List.of(),NOW,NOW,false));
        assertFalse(SelectionPolicy.shouldSwitch(my,my,List.of(),NOW,NOW-31_000,true));
    }
    @Test public void stabilityAndChallengesAffectRanking(){
        var stable=node("a","JP",100);var jittery=node("b","JP",90);jittery.latency(600,NOW);jittery.latency(300,NOW);
        assertTrue(stable.score()<jittery.score());
        stable.chatgpt="challenged";assertEquals(400,stable.score());
        stable.latency(-1,NOW);assertEquals(Integer.MAX_VALUE,stable.score());
        stable.latency(100,NOW);assertTrue(stable.qualified(NOW));
    }
    @Test public void rejectsFutureTimestamp(){var h=node("a","JP",100);assertFalse(h.qualified(NOW-1));}
    @Test public void applicationCooldownSurvivesGoogleSuccessAndRequiresFreshProof(){
        var h=node("a","JP",100);h.applicationFailed(NOW);h.latency(40,NOW+1);assertFalse(h.qualified(NOW+1));
        assertFalse(h.eligibleForProof(NOW+59_999));h.latency(50,NOW+60_001);h.servicesAt=NOW+60_001;
        assertTrue(h.eligibleForProof(NOW+60_001));assertFalse(h.qualified(NOW+60_001));h.applicationVerified(NOW+60_001);assertTrue(h.qualified(NOW+60_001));
        h.applicationFailed(NOW+60_002);assertEquals(NOW+180_002,h.appFailureUntil);
    }
    @Test public void failedBestCandidateLeavesNextQualifiedCandidateAvailable(){
        var fastest=node("fastest","JP",30);var fallback=node("fallback","SG",90);fastest.applicationFailed(NOW);
        assertSame(fallback,SelectionPolicy.best(List.of(fastest,fallback),List.of("JP","SG"),NOW));
    }
    @Test public void finalProofSkipsAttemptedWinnerButKeepsSelectedRouteAsRankingAnchor(){
        var fastest=node("fastest","JP",30);var current=node("selected","JP",50);var slower=node("slower","JP",90);
        assertSame(current,SelectionPolicy.bestForProof(List.of(fastest,current,slower),List.of(),NOW,Set.of("fastest","selected"),"selected"));
        assertSame(slower,SelectionPolicy.bestForProof(List.of(fastest,slower),List.of(),NOW,Set.of("fastest"),""));
    }
    @Test public void measuredApplicationTransportCanSustainGoogleOnlyOutageWithoutInventedRtt(){
        var h=node("a","JP",100);h.latency(-1,NOW+1);h.transportVerified(NOW+2,317);
        assertEquals(-1,h.latency);assertEquals(317,h.applicationLatency);assertEquals(317,h.displayLatency());assertTrue(h.qualified(NOW+2));
        assertFalse(h.qualified(NOW+SelectionPolicy.LATENCY_TTL+3));
    }
    @Test public void selectedRouteSurvivesExpiredServiceCheckButCandidatesStillNeedProof(){
        var h=node("active","JP",100);long later=NOW+SelectionPolicy.SERVICE_TTL+1;h.transportVerified(later,222);
        assertFalse(h.qualified(later));assertFalse(h.eligibleForProof(later));assertTrue(h.retainSelectedTransport(later));
        h.servicesAt=later;h.chatgpt="unknown";assertFalse(h.qualified(later));assertTrue(h.retainSelectedTransport(later));
        assertFalse(h.retainSelectedTransport(later+45_001));h.applicationFailed(later);assertFalse(h.retainSelectedTransport(later));
    }
    @Test public void selectedTransportRetentionStillRequiresMeasuredLatencyBelowConfiguredCeiling(){
        var h=node("active","JP",100);h.transportVerified(NOW+1,3000);assertFalse(h.retainSelectedTransport(NOW+1));
        h.transportVerified(NOW+2,2999);assertTrue(h.retainSelectedTransport(NOW+2));
        h.applicationFailed(NOW+3);assertFalse(h.retainSelectedTransport(NOW+3));
    }
    @Test public void candidateProofRetainsEscalationUntilSelectedHealthIsSustained(){
        var h=node("a","JP",100);h.applicationFailed(NOW);h.applicationVerified(NOW+60_001);assertEquals(1,h.appFailures);
        for(long at=NOW+60_001;at<=NOW+165_001;at+=15_000)h.selectedHealthy(at);
        assertEquals(1,h.appFailures);h.selectedUncertain();h.selectedHealthy(NOW+180_001);assertEquals(1,h.appFailures);
        for(long at=NOW+195_001;at<=NOW+300_001;at+=15_000)h.selectedHealthy(at);assertEquals(0,h.appFailures);
        h.applicationFailed(NOW+300_002);assertEquals(NOW+360_002,h.appFailureUntil);
    }
}
