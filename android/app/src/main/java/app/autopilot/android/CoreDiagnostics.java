package app.autopilot.android;

import org.json.JSONObject;
import java.util.*;
import java.util.regex.*;

/**
 * Converts a failed native quickSetup result into an allowlisted diagnostic.
 * Native errors can contain entire credentials, node labels, URLs and paths: no
 * input text, exception, matched substring or caller-supplied label is retained.
 * Call this only after quickSetup has failed (the native success result is "").
 */
public final class CoreDiagnostics {
    private static final int TEXT_LIMIT=16384,MAX_PROXIES=2000;
    private static final Pattern PROXY_INDEX=Pattern.compile("^proxy ([0-9]{1,4}):",Pattern.CASE_INSENSITIVE);
    public enum Stage {
        BASELINE("baseline"),PROFILE("profile"),RECOVERY("recovery");
        public final String value;
        Stage(String value){this.value=value;}
    }

    /** Runtime catalog: codes, categories and UI messages all originate here. */
    public enum Kind {
        PROXY_TYPE("CORE_PROXY_TYPE_UNSUPPORTED","proxy","A node uses a protocol this VPN core does not support. Refresh the provider or remove that node."),
        PROXY_CIPHER("CORE_PROXY_CIPHER_INVALID","proxy","A node uses an unsupported or invalid cipher. Refresh the provider configuration."),
        PROXY_KEY("CORE_PROXY_KEY_INVALID","proxy","A node contains an invalid cryptographic key. Refresh its provider configuration or import a fresh export."),
        PROXY_UUID("CORE_PROXY_UUID_INVALID","proxy","A node contains an invalid user ID. Refresh its provider configuration."),
        PROXY_PLUGIN("CORE_PROXY_PLUGIN_INVALID","proxy","A node has an unsupported or invalid plugin configuration. Refresh its provider configuration."),
        PROXY_TRANSPORT("CORE_PROXY_TRANSPORT_INVALID","proxy","A node has an unsupported or invalid transport configuration. Refresh its provider configuration."),
        PROXY_SCHEMA("CORE_PROXY_SCHEMA_INVALID","proxy","A node is missing a required setting or contains an invalid value. Refresh its provider configuration."),
        PROXY_DUPLICATE("CORE_PROXY_NAME_DUPLICATE","proxy","The generated configuration contains duplicate node names. Refresh the nodes and report this diagnostic if it repeats."),
        PROXY_INVALID("CORE_PROXY_INVALID","proxy","The VPN core rejected a node configuration. Refresh the provider or import a fresh configuration."),
        DNS_INVALID("CORE_DNS_CONFIG_INVALID","dns","The VPN core rejected the DNS configuration. Update Autopilot or report this diagnostic."),
        INIT_FAILED("CORE_INIT_FAILED","init","The VPN core could not initialize its private runtime folder. Restart Autopilot and report this diagnostic if it repeats."),
        RESPONSE_INVALID("CORE_SETUP_RESPONSE_INVALID","init","The VPN core returned no valid setup result. Restart Autopilot and report this diagnostic if it repeats."),
        GEODATA_FAILED("CORE_GEODATA_FAILED","geodata","The VPN core could not load geographic data required by the profile. Update Autopilot or import a profile without geographic rules."),
        CONFIG_MISSING("CORE_CONFIG_MISSING","storage","The VPN core could not find its generated configuration. Restart Autopilot and report this diagnostic if it repeats."),
        CONFIG_UNREADABLE("CORE_CONFIG_UNREADABLE","storage","The VPN core could not read its generated configuration. Restart Autopilot and report this diagnostic if it repeats."),
        CONFIG_SCHEMA("CORE_CONFIG_SCHEMA_INVALID","schema","The VPN core rejected the generated configuration format. Update Autopilot or report this diagnostic."),
        PROXY_LIMIT("CORE_PROXY_SKIP_LIMIT","proxy","The profile contains too many incompatible nodes. Update Autopilot or refresh the provider configuration."),
        PROXY_EMPTY("CORE_NO_COMPATIBLE_NODES","proxy","Every loaded node was rejected by the VPN core. Refresh the provider configuration or import another profile."),
        UNKNOWN("CORE_CONFIG_UNKNOWN","unknown","The VPN core rejected the configuration for an unrecognized reason. Copy diagnostics and report this code.");
        public final String code,category,message;
        Kind(String code,String category,String message){this.code=code;this.category=category;this.message=message;}
    }

    public static final class Diagnostic {
        public final Kind kind;
        public final String code,category,message,proxyType;
        /** Zero-based native proxies[] index, or -1 when absent/unverified. */
        public final int proxyIndex;
        private Diagnostic(Kind kind,int proxyIndex,String proxyType){
            this.kind=kind;this.code=kind.code;this.category=kind.category;this.message=kind.message;
            this.proxyIndex=proxyIndex;this.proxyType=proxyType;
        }
        public Map<String,Object> fields(){
            LinkedHashMap<String,Object> fields=new LinkedHashMap<>();
            fields.put("code",code);fields.put("category",category);fields.put("message",message);
            if(proxyIndex>=0)fields.put("proxyIndex",proxyIndex);
            if(!proxyType.isEmpty())fields.put("proxyType",proxyType);
            return Collections.unmodifiableMap(fields);
        }
        public JSONObject json(){return new JSONObject(fields());}
        public boolean canIsolateProxy(){return proxyIndex>=0&&"proxy".equals(category);}
        /** Fits AutopilotApp.log's fixed-code alphabet and 96-character limit. */
        public String logCode(){return code+(proxyIndex<0?"":":proxy_"+proxyIndex)+(proxyType.isEmpty()?"":":"+proxyType);}
        @Override public String toString(){return logCode();}
    }

    private CoreDiagnostics(){}
    public static String messageForCode(String code){for(Kind kind:Kind.values())if(kind.code.equals(code))return kind.message;return null;}
    public static Diagnostic forKind(Kind kind){return result(Objects.requireNonNull(kind),-1,null);}
    /** Reconstruct persisted diagnostics from the catalog; arbitrary stored fields/text are discarded. */
    public static JSONObject restore(JSONObject raw){
        if(raw==null)return null;Kind selected=null;
        for(Kind kind:Kind.values())if(kind.code.equals(raw.optString("code")))selected=kind;
        if(selected==null)return null;
        int index=-1;Object value=raw.opt("proxyIndex");
        if(value instanceof Number&&((Number)value).doubleValue()==((Number)value).intValue()&&((Number)value).intValue()>=0&&((Number)value).intValue()<MAX_PROXIES&&"proxy".equals(selected.category))index=((Number)value).intValue();
        String type=raw.optString("proxyType","");if(index<0||!Node.SUPPORTED_TYPES.contains(type))type="";
        JSONObject result=new Diagnostic(selected,index,type).json();
        try{
            for(Stage stage:Stage.values())if(stage.value.equals(raw.optString("stage")))result.put("stage",stage.value);
            value=raw.opt("at");if(value instanceof Number&&((Number)value).longValue()>0&&((Number)value).longValue()<=4102444800000L)result.put("at",((Number)value).longValue());
            value=raw.opt("skippedCount");if(value instanceof Number&&((Number)value).doubleValue()==((Number)value).intValue()&&((Number)value).intValue()>=0&&((Number)value).intValue()<=ProfileValidation.MAX_DROPS)result.put("skippedCount",((Number)value).intValue());
        }catch(Exception ignored){}
        return result;
    }
    public static Diagnostic classify(String rejection){return classify(rejection,null);}
    /**
     * proxyTypes must follow the exact proxies[] order submitted to quickSetup.
     * Supplying context validates the native index against the actual profile.
     * Types come from caller context, never from native error text.
     */
    public static Diagnostic classify(String rejection,List<String> proxyTypes){
        if(rejection==null)return result(Kind.RESPONSE_INVALID,-1,proxyTypes);
        String text=rejection.substring(0,Math.min(TEXT_LIMIT,rejection.length())).trim().toLowerCase(Locale.ROOT);
        if(text.isEmpty())return result(Kind.RESPONSE_INVALID,-1,proxyTypes);
        Matcher proxy=PROXY_INDEX.matcher(text);boolean exactProxy=proxy.find();
        // This known wrapper may help explain an error, but only the exact native
        // top-level numeric prefix is trusted for destructive node isolation.
        String diagnosticText=text.startsWith("parse config error:")?text.substring("parse config error:".length()).trim():text;
        Matcher wrapped=PROXY_INDEX.matcher(diagnosticText);
        if(exactProxy||wrapped.find()){
            int index=exactProxy?Integer.parseInt(proxy.group(1)):-1;if(index>=MAX_PROXIES)index=-1;
            String reason=exactProxy?text.substring(proxy.end()):diagnosticText.substring(wrapped.end());
            Kind kind;
            if(has(reason,"unsupported proxy type","unsupport proxy type","unknown proxy type","proxy type is not supported"))kind=Kind.PROXY_TYPE;
            else if(has(reason,"cipher","unknown method","unsupported method"))kind=Kind.PROXY_CIPHER;
            else if(has(reason,"uuid","invalid user id"))kind=Kind.PROXY_UUID;
            else if(has(reason,"private-key","public-key","pre-shared-key","presharedkey","private key","public key","pre-shared key","invalid key","bad key length"))kind=Kind.PROXY_KEY;
            else if(has(reason,"plugin"))kind=Kind.PROXY_PLUGIN;
            else if(has(reason,"transport","ws-opts","grpc-opts","unsupported network","network is not supported"))kind=Kind.PROXY_TRANSPORT;
            else if(has(reason,"missing","required","cannot decode","cannot unmarshal","invalid port","empty server","server is empty","expected a map"))kind=Kind.PROXY_SCHEMA;
            else kind=Kind.PROXY_INVALID;
            return result(kind,index,proxyTypes);
        }
        text=diagnosticText;
        if(text.startsWith("proxy ")&&text.contains(" is the duplicate name"))return result(Kind.PROXY_DUPLICATE,-1,proxyTypes);
        if(text.startsWith("init failed")||text.startsWith("clash not loaded")||text.startsWith("native initialization failed"))return result(Kind.INIT_FAILED,-1,proxyTypes);
        if(has(text,"geosite","geoip","geodata","geo data","country.mmdb","asn.mmdb"))return result(Kind.GEODATA_FAILED,-1,proxyTypes);
        if(text.startsWith("dns ")||text.startsWith("dns:")||text.startsWith("dns.")||text.startsWith("default nameserver")||text.startsWith("if dns configuration")||
           text.startsWith("if “respect-rules”")||text.startsWith("if \"respect-rules\"")||text.startsWith("disallow empty `proxy-server-nameserver`"))
            return result(Kind.DNS_INVALID,-1,proxyTypes);
        if(text.startsWith("open ")||text.startsWith("read ")||text.startsWith("stat ")){
            if(has(text,"config.yaml","config.yml"))return result(has(text,"no such file","does not exist","not found","enoent")?Kind.CONFIG_MISSING:Kind.CONFIG_UNREADABLE,-1,proxyTypes);
        }
        if(text.startsWith("yaml:")||text.startsWith("json:")||text.startsWith("proxy group")||text.startsWith("rules[")||
           has(text,"cannot unmarshal","unmarshal errors","error unmarshaling","error decoding config","invalid config schema"))return result(Kind.CONFIG_SCHEMA,-1,proxyTypes);
        return result(Kind.UNKNOWN,-1,proxyTypes);
    }
    private static Diagnostic result(Kind kind,int index,List<String> types){
        if(index>=0&&types!=null&&index>=types.size())index=-1;
        String type="";
        if(index>=0&&types!=null){String input=types.get(index);
            if(input!=null&&input.length()<=16){String canonical=input.toLowerCase(Locale.ROOT);if(Node.SUPPORTED_TYPES.contains(canonical))type=canonical;}}
        return new Diagnostic(kind,index,type);
    }
    private static boolean has(String text,String... phrases){for(String phrase:phrases)if(text.contains(phrase))return true;return false;}
}
