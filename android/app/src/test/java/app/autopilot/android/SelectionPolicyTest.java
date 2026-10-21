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
        assertFalse(SelectionPolicy.shouldSwitch(current,candidate,List.of(),NOW,NOW-31_000,true));
        current=node("a","JP",1000);candidate=node("b","JP",950);
        assertFalse(SelectionPolicy.shouldSwitch(current,candidate,List.of(),NOW,NOW-31_000,false));
    }
    @Test public void priorityOverrideAndFailureRecoveryIgnoreDwell(){
        var current=node("sg","SG",40);var my=node("my","MY",300);
        assertTrue(SelectionPolicy.shouldSwitch(current,my,List.of("MY","SG"),NOW,NOW,false));
        assertFalse(SelectionPolicy.shouldSwitch(my,current,List.of("MY","SG"),NOW,NOW-31_000,true));
        current.claude="blocked";assertTrue(SelectionPolicy.shouldSwitch(current,my,List.of(),NOW,NOW,false));
        assertFalse(SelectionPolicy.shouldSwitch(my,my,List.of(),NOW,NOW-31_000,true));
    }
    @Test public void stabilityAndChallengesAffectRanking(){
        var stable=node("a","JP",100);var jittery=node("b","JP",90);jittery.latency(600,NOW);jittery.latency(90,NOW);
        assertTrue(stable.score()<jittery.score());
        stable.chatgpt="challenged";assertEquals(200,stable.score());
        stable.latency(-1,NOW);assertEquals(Integer.MAX_VALUE,stable.score());
        stable.latency(100,NOW);assertTrue(stable.qualified(NOW));
    }
    @Test public void rejectsFutureTimestamp(){var h=node("a","JP",100);assertFalse(h.qualified(NOW-1));}
}
