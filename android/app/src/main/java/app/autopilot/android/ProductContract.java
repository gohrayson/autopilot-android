package app.autopilot.android;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Runtime source of shared product behavior, supplied as the same JSON on both platforms. */
public final class ProductContract {
    private static volatile JSONObject installed=classpath();
    private ProductContract(){}
    private static JSONObject classpath(){
        try(InputStream input=ProductContract.class.getResourceAsStream("/product-contract.json")){return input==null?null:read(input);}
        catch(Exception failure){throw new IllegalStateException("PRODUCT_CONTRACT_INVALID",failure);}
    }
    private static JSONObject read(InputStream input)throws IOException,JSONException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[4096];int count;
        while((count=input.read(block))!=-1){if(bytes.size()+count>65536)throw new IOException("PRODUCT_CONTRACT_SIZE");bytes.write(block,0,count);}
        JSONObject value=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
        if(value.optInt("schemaVersion")!=1||value.optJSONObject("settings")==null||value.optJSONObject("policy")==null||value.optJSONArray("services")==null)throw new IOException("PRODUCT_CONTRACT_INVALID");
        return value;
    }
    public static synchronized void install(InputStream input)throws IOException,JSONException{try(InputStream owned=input){installed=read(owned);}}
    private static JSONObject value(){JSONObject result=installed;if(result==null)throw new IllegalStateException("PRODUCT_CONTRACT_NOT_INITIALIZED");return result;}
    public static JSONObject snapshot(){return copy(value());}
    public static JSONObject policy(){return copy(value().optJSONObject("policy"));}
    public static JSONObject settingsSchema(){return copy(value().optJSONObject("settings"));}
    public static int policyInt(String key){return value().optJSONObject("policy").optInt(key);}
    public static long policyLong(String key){return value().optJSONObject("policy").optLong(key);}
    public static double policyDouble(String key){return value().optJSONObject("policy").optDouble(key);}
    public static List<String> services(){JSONArray array=value().optJSONArray("services");ArrayList<String> result=new ArrayList<>();for(int i=0;i<array.length();i++)result.add(array.optString(i));return List.copyOf(result);}
    public static JSONObject defaults(){JSONObject result=new JSONObject(),schemas=value().optJSONObject("settings");try{for(Iterator<String> keys=schemas.keys();keys.hasNext();){String key=keys.next();result.put(key,schemas.getJSONObject(key).get("default"));}return copy(result);}catch(JSONException failure){throw new IllegalStateException("PRODUCT_CONTRACT_INVALID",failure);}}
    public static JSONObject mergeSettings(JSONObject overrides){
        JSONObject result=defaults(),schemas=settingsSchema();
        try{
            if(overrides!=null)for(Iterator<String> keys=overrides.keys();keys.hasNext();){String key=keys.next();if(!schemas.has(key))throw new IllegalArgumentException("Unknown setting: "+key);result.put(key,overrides.get(key));}
            for(Iterator<String> keys=schemas.keys();keys.hasNext();){String key=keys.next();JSONObject schema=schemas.getJSONObject(key);Object candidate=result.get(key);String type=schema.getString("type");
                boolean valid=switch(type){case "boolean"->candidate instanceof Boolean;case "integer"->candidate instanceof Number&&Double.isFinite(((Number)candidate).doubleValue())&&((Number)candidate).doubleValue()==((Number)candidate).longValue();case "number"->candidate instanceof Number&&Double.isFinite(((Number)candidate).doubleValue());case "string"->candidate instanceof String;case "array"->candidate instanceof JSONArray;default->false;};
                if(!valid)throw new IllegalArgumentException("Invalid setting: "+key);
                if(candidate instanceof Number){double n=((Number)candidate).doubleValue();if(schema.has("min")&&n<schema.getDouble("min")||schema.has("max")&&n>schema.getDouble("max"))throw new IllegalArgumentException("Setting out of range: "+key);}
                JSONArray choices=schema.optJSONArray("enum");if(choices!=null){boolean found=false;for(int i=0;i<choices.length();i++)if(choices.get(i).equals(candidate))found=true;if(!found)throw new IllegalArgumentException("Invalid choice: "+key);}
                if(candidate instanceof JSONArray){JSONArray array=(JSONArray)candidate;if(array.length()<schema.optInt("minItems")||array.length()>schema.optInt("maxItems"))throw new IllegalArgumentException("Invalid list size: "+key);HashSet<String> unique=new HashSet<>();for(int i=0;i<array.length();i++){Object item=array.get(i);if(!(item instanceof String)||((String)item).isEmpty()||((String)item).length()>1024||((String)item).chars().anyMatch(c->c<32||c==127)||!unique.add((String)item))throw new IllegalArgumentException("Invalid list: "+key);if("service".equals(schema.optString("items"))&&!services().contains(item))throw new IllegalArgumentException("Invalid service: "+key);}}
            }
            return copy(result);
        }catch(JSONException failure){throw new IllegalArgumentException("Invalid settings",failure);}
    }
    private static JSONObject copy(JSONObject source){try{return new JSONObject(source.toString());}catch(JSONException failure){throw new IllegalStateException("PRODUCT_CONTRACT_INVALID",failure);}}
}
