package app.autopilot.android;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class ProductSettingsTest {
    @Test public void invalidPatchCannotMutateEarlierValidatedSettings()throws Exception{
        JSONObject current=ProductSettings.merge(null,new JSONObject().put("required",new JSONArray(List.of("claude"))).put("directDomains",new JSONArray(List.of("old.example"))));
        String before=current.toString();
        try{ProductSettings.merge(current,new JSONObject().put("tickMs",1).put("directDomains",new JSONArray(List.of("new.example"))));fail();}catch(IllegalArgumentException expected){}
        assertEquals(before,current.toString());
        try{ProductSettings.merge(current,new JSONObject().put("required",new JSONArray()));fail();}catch(IllegalArgumentException expected){}
        assertEquals(before,current.toString());
    }
    @Test public void normalizedWebsitesMatchDesktopDomainSemantics()throws Exception{
        assertEquals("xn--bcher-kva.example",ProductSettings.domain("https://*.Bücher.Example:8443/path?q=yes"));
        assertEquals("example.com",ProductSettings.domain("user@example.com."));
        for(String input:List.of("127.0.0.1","[::1]","bad,rule.example","bad_name.example","single","example.123","foo..example"))assertNull(input,ProductSettings.domain(input));
        JSONObject normalized=ProductSettings.merge(null,new JSONObject().put("proxyDomains",new JSONArray(List.of("https://*.EXAMPLE.com/path"))));
        assertEquals("[\"example.com\"]",normalized.getJSONArray("proxyDomains").toString());
    }
    @Test public void normalizedConflictsAndAndroidRuleInjectionAreRejected()throws Exception{
        try{ProductSettings.merge(null,new JSONObject().put("directDomains",new JSONArray(List.of("Example.com"))).put("proxyDomains",new JSONArray(List.of("https://example.com/"))));fail();}catch(IllegalArgumentException expected){}
        for(String invalid:List.of("app,REJECT","C:\\test.exe","package","com.bad-thing.app","com.app\n"))assertNull(invalid,ProductSettings.packageName(invalid));
        assertEquals("com.example_app.client",ProductSettings.packageName("com.example_app.client"));
    }
    @Test public void countryServicesAndNumericBoundsComeFromTheRuntimeContract()throws Exception{
        JSONObject defaults=ProductSettings.merge(null,new JSONObject());assertEquals(ProductContract.defaults().getLong("tickMs"),defaults.getLong("tickMs"));
        for(JSONObject patch:List.of(new JSONObject().put("required",new JSONArray(List.of("unknown"))),new JSONObject().put("preferredRegions",new JSONArray(List.of("my"))),new JSONObject().put("preferredRegions",new JSONArray(List.of("HK"))),new JSONObject().put("failLimit",1.5),new JSONObject().put("globalMode","true"),new JSONObject().put("unknown",true))){try{ProductSettings.merge(defaults,patch);fail(patch.toString());}catch(IllegalArgumentException expected){}}
    }
    @Test public void portablePreferencesExcludePlatformIdentityAndRejectSecrets()throws Exception{
        JSONObject original=ProductSettings.merge(null,new JSONObject().put("bypassApps",new JSONArray(List.of("com.example.app"))).put("splitMode","bypass").put("preferredRegions",new JSONArray(List.of("MY","JP"))).put("directDomains",new JSONArray(List.of("test.example"))));
        JSONObject exported=ProductSettings.portable(original),values=exported.getJSONObject("settings");
        for(String key:List.of("bypassApps","onlyApps","splitMode","account","session","password"))assertFalse(key,values.has(key));
        JSONObject patch=ProductSettings.portablePatch(exported);assertEquals("[\"MY\",\"JP\"]",patch.getJSONArray("preferredRegions").toString());
        assertEquals("bypass",ProductSettings.merge(original,patch).getString("splitMode"));
        for(String forbidden:List.of("password","session","bypassApps","splitMode")){JSONObject corrupt=new JSONObject(exported.toString());corrupt.getJSONObject("settings").put(forbidden,"secret");try{ProductSettings.portablePatch(corrupt);fail(forbidden);}catch(IllegalArgumentException expected){}}
    }
    @Test public void onlyActualAppRouteChangesNeedRestart()throws Exception{
        JSONObject first=ProductSettings.merge(null,new JSONObject().put("splitMode","bypass").put("bypassApps",new JSONArray(List.of("com.example.one","com.example.two"))));
        JSONObject websites=ProductSettings.merge(first,new JSONObject().put("directDomains",new JSONArray(List.of("test.example"))).put("bypassApps",new JSONArray(List.of("com.example.two","com.example.one"))));
        assertEquals(ProductSettings.routingIdentity(first),ProductSettings.routingIdentity(websites));
        JSONObject only=ProductSettings.merge(first,new JSONObject().put("splitMode","only").put("onlyApps",new JSONArray(List.of("com.example.one"))));
        assertNotEquals(ProductSettings.routingIdentity(first),ProductSettings.routingIdentity(only));
        assertEquals("off",ProductSettings.routingIdentity(ProductContract.defaults()));
    }
}
