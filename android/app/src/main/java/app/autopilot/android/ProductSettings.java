package app.autopilot.android;

import org.json.*;
import java.net.IDN;
import java.util.*;

/** Atomic common preferences. Reject an invalid patch before the caller writes anything. */
final class ProductSettings {
    private static final Set<String> LIST_KEYS=Set.of("bypassApps","onlyApps","directDomains","proxyDomains");
    static JSONObject merge(JSONObject previous,JSONObject patch){
        if(patch==null)throw new IllegalArgumentException("Settings must be an object");
        try{
            JSONObject base=ProductContract.mergeSettings(previous==null?new JSONObject():previous);
            JSONObject candidate=new JSONObject(base.toString());
            for(Iterator<String> it=patch.keys();it.hasNext();){String key=it.next();candidate.put(key,patch.get(key));}
            candidate=ProductContract.mergeSettings(candidate);
            JSONArray countries=candidate.getJSONArray("preferredRegions");Set<String> excluded=strings(ProductContract.policy().optJSONArray("excludedCountries")),known=new HashSet<>(Arrays.asList(Locale.getISOCountries()));
            for(int i=0;i<countries.length();i++){String code=countries.getString(i);if(!known.contains(code)||excluded.contains(code))throw new IllegalArgumentException("Invalid preferred country");}
            for(String key:LIST_KEYS){
                Object raw=candidate.opt(key);if(!(raw instanceof JSONArray))throw new IllegalArgumentException(key+" must be a list");
                JSONArray list=(JSONArray)raw;int limit=key.endsWith("Apps")?200:500;
                if(list.length()>limit)throw new IllegalArgumentException(key+" has too many entries");
                LinkedHashSet<String> normalized=new LinkedHashSet<>();
                for(int i=0;i<list.length();i++){
                    Object entry=list.opt(i);if(!(entry instanceof String))throw new IllegalArgumentException(key+" contains an invalid entry");
                    String value=key.endsWith("Apps")?packageName((String)entry):domain((String)entry);
                    if(value==null)throw new IllegalArgumentException(key+" contains an invalid "+(key.endsWith("Apps")?"Android package":"website"));
                    normalized.add(value);
                }
                candidate.put(key,new JSONArray(normalized));
            }
            Set<String> direct=strings(candidate.optJSONArray("directDomains"));
            for(String value:strings(candidate.optJSONArray("proxyDomains")))if(direct.contains(value))throw new IllegalArgumentException("A website cannot be both direct and through VPN");
            return candidate;
        }catch(JSONException error){throw new IllegalArgumentException("Invalid settings",error);}
    }
    static String packageName(String input){
        if(input==null||input.chars().anyMatch(c->c<32||c==127))return null;
        String value=input==null?"":input.trim();
        return value.length()<=255&&value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")?value:null;
    }
    static String domain(String input){
        if(input==null)return null;
        String value=input.trim().toLowerCase(Locale.ROOT).replaceFirst("^[a-z][a-z0-9+.-]*://","");
        value=value.split("[/?#]",2)[0];value=value.replaceFirst("^[^@]*@","");
        if(value.startsWith("[")||value.chars().filter(c->c==':').count()>1)return null;
        value=value.replaceFirst(":\\d*$","").replaceFirst("^\\*\\.","").replaceAll("^\\.+|\\.+$","");
        try{value=IDN.toASCII(value,IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);}catch(IllegalArgumentException error){return null;}
        if(value.length()>253)return null;
        String[] labels=value.split("\\.");if(labels.length<2||labels[labels.length-1].matches("[0-9]+"))return null;
        for(String label:labels)if(!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))return null;
        return value;
    }
    static Set<String> strings(JSONArray list){LinkedHashSet<String> out=new LinkedHashSet<>();if(list!=null)for(int i=0;i<list.length();i++)out.add(list.optString(i));return out;}
    static String routingIdentity(JSONObject settings){
        String mode=settings.optString("splitMode","off");
        Set<String> apps=strings(settings.optJSONArray(mode.equals("only")?"onlyApps":"bypassApps"));
        if(mode.equals("off")||apps.isEmpty())return "off";
        return mode+":"+String.join(",",new TreeSet<>(apps));
    }
    static JSONObject portable(JSONObject settings){
        try{
            JSONObject values=new JSONObject(),validated=merge(null,settings);
            for(Iterator<String> keys=ProductContract.settingsSchema().keys();keys.hasNext();){String key=keys.next();if(!Set.of("splitMode","bypassApps","onlyApps").contains(key))values.put(key,validated.get(key));}
            return new JSONObject().put("schemaVersion",1).put("product","Autopilot").put("settings",values);
        }catch(JSONException impossible){throw new IllegalStateException(impossible);}
    }
    static JSONObject portablePatch(JSONObject envelope){
        if(envelope==null||envelope.optInt("schemaVersion")!=1||!"Autopilot".equals(envelope.optString("product"))||!(envelope.opt("settings") instanceof JSONObject))throw new IllegalArgumentException("Invalid Autopilot preferences file");
        for(Iterator<String> keys=envelope.keys();keys.hasNext();)if(!Set.of("schemaVersion","product","settings").contains(keys.next()))throw new IllegalArgumentException("Preferences contain unsupported data");
        JSONObject settings=envelope.optJSONObject("settings");
        for(Iterator<String> keys=settings.keys();keys.hasNext();){String key=keys.next();if(Set.of("splitMode","bypassApps","onlyApps").contains(key)||!ProductContract.settingsSchema().has(key))throw new IllegalArgumentException("Preferences contain account or platform-specific data");}
        merge(null,settings);try{return new JSONObject(settings.toString());}catch(JSONException impossible){throw new IllegalStateException(impossible);}
    }
    private ProductSettings(){}
}
