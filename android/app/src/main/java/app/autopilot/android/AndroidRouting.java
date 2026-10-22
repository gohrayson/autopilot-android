package app.autopilot.android;

import android.content.Context;
import org.json.*;
import java.io.File;
import java.util.*;

/** Website rules apply to traffic captured by Android's per-app VPN routes. */
final class AndroidRouting {
    static boolean geoAvailable(Context context){return GeoRouting.available(context);}
    static JSONArray buildRules(JSONObject raw,boolean geoAvailable)throws JSONException{
        JSONObject settings=ProductSettings.merge(null,raw);
        JSONArray rules=new JSONArray();
        for(String domain:ProductSettings.strings(settings.getJSONArray("directDomains")))rules.put("DOMAIN-SUFFIX,"+domain+",DIRECT");
        for(String domain:ProductSettings.strings(settings.getJSONArray("proxyDomains")))rules.put("DOMAIN-SUFFIX,"+domain+",AUTOPILOT");
        if(!settings.getBoolean("globalMode")){
            // Shared contract is also consumed by the desktop builder.
            JSONArray forced=ProductContract.policy().optJSONArray("forceDomains");
            if(forced!=null)for(int i=0;i<forced.length();i++)rules.put("DOMAIN-SUFFIX,"+forced.getString(i)+",AUTOPILOT");
            if(geoAvailable)rules.put("GEOIP,CN,DIRECT");
        }
        rules.put("MATCH,AUTOPILOT");return rules;
    }
    static final class AppPlan {
        final boolean includeOnly;final Set<String> packages;
        AppPlan(boolean only,Set<String> packages){this.includeOnly=only;this.packages=Collections.unmodifiableSet(packages);}
    }
    static AppPlan appPlan(JSONObject settings,String ownPackage){
        String mode=settings.optString("splitMode","off");
        Set<String> selected=ProductSettings.strings(settings.optJSONArray(mode.equals("only")?"onlyApps":"bypassApps"));boolean configured=!selected.isEmpty();selected.remove(ownPackage);
        if(mode.equals("only")&&configured)return new AppPlan(true,selected);
        Set<String> bypass=new LinkedHashSet<>();bypass.add(ownPackage);if(mode.equals("bypass"))bypass.addAll(selected);
        return new AppPlan(false,bypass);
    }
    private AndroidRouting(){}
}
