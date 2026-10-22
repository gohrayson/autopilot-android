package app.autopilot.android;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class AndroidRoutingTest {
    @Test public void websiteOverridesPrecedeRegionalRuleAndVpnFallback()throws Exception{
        JSONObject settings=ProductSettings.merge(null,new JSONObject().put("directDomains",new JSONArray(List.of("direct.example"))).put("proxyDomains",new JSONArray(List.of("proxy.example"))));
        JSONArray rules=AndroidRouting.buildRules(settings,true);
        assertEquals("DOMAIN-SUFFIX,direct.example,DIRECT",rules.getString(0));assertEquals("DOMAIN-SUFFIX,proxy.example,AUTOPILOT",rules.getString(1));
        assertEquals("GEOIP,CN,DIRECT",rules.getString(rules.length()-2));assertEquals("MATCH,AUTOPILOT",rules.getString(rules.length()-1));
        JSONArray forced=ProductContract.policy().getJSONArray("forceDomains");for(int i=0;i<forced.length();i++)assertEquals("DOMAIN-SUFFIX,"+forced.getString(i)+",AUTOPILOT",rules.getString(i+2));
    }
    @Test public void globalModePreservesWebsiteOverridesAndNeverAddsRegionalDirect()throws Exception{
        JSONObject settings=ProductSettings.merge(null,new JSONObject().put("globalMode",true).put("directDomains",new JSONArray(List.of("test.example"))));
        assertEquals("[\"DOMAIN-SUFFIX,test.example,DIRECT\",\"MATCH,AUTOPILOT\"]",AndroidRouting.buildRules(settings,true).toString());
        assertFalse(AndroidRouting.buildRules(ProductContract.defaults(),false).toString().contains("GEOIP"));
    }
    @Test public void appRoutesNeverAllowTheCoreUidBackIntoVpn()throws Exception{
        String own="app.autopilot.android";
        AndroidRouting.AppPlan off=AndroidRouting.appPlan(ProductContract.defaults(),own);assertFalse(off.includeOnly);assertEquals(Set.of(own),off.packages);
        JSONObject bypass=ProductSettings.merge(null,new JSONObject().put("splitMode","bypass").put("bypassApps",new JSONArray(List.of("com.example.app",own))));
        AndroidRouting.AppPlan excluded=AndroidRouting.appPlan(bypass,own);assertFalse(excluded.includeOnly);assertEquals(Set.of(own,"com.example.app"),excluded.packages);
        JSONObject only=ProductSettings.merge(null,new JSONObject().put("splitMode","only").put("onlyApps",new JSONArray(List.of("com.example.app",own))));
        AndroidRouting.AppPlan included=AndroidRouting.appPlan(only,own);assertTrue(included.includeOnly);assertEquals(Set.of("com.example.app"),included.packages);
    }
    @Test public void emptyOnlyListUsesDesktopAllAppsDefaultButOwnOnlyCannotCaptureCore()throws Exception{
        JSONObject empty=ProductSettings.merge(null,new JSONObject().put("splitMode","only"));assertFalse(AndroidRouting.appPlan(empty,"app.autopilot.android").includeOnly);
        JSONObject own=ProductSettings.merge(empty,new JSONObject().put("onlyApps",new JSONArray(List.of("app.autopilot.android"))));
        AndroidRouting.AppPlan invalid=AndroidRouting.appPlan(own,"app.autopilot.android");assertTrue(invalid.includeOnly);assertTrue(invalid.packages.isEmpty());
    }
}
