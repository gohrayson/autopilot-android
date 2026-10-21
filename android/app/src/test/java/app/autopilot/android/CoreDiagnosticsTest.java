package app.autopilot.android;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class CoreDiagnosticsTest {
    private static final String TOKEN="secret-token-never-log-743849",UUID="f76912c6-7ef1-4618-b94d-c9ea9c16ba31",KEY="QmFzZTY0U2VjcmV0UHJpdmF0ZUtleU5ldmVyRXNjYXBl";
    private static final String SECRETS=" https://user:"+TOKEN+"@private.example/export?token="+TOKEN+" uuid="+UUID+" private-key="+KEY+" node=Personal-Malaysia server=198.51.100.77";
    private void assertSecretFree(CoreDiagnostics.Diagnostic diagnostic){
        String out=diagnostic.json()+" "+diagnostic.fields()+" "+diagnostic.logCode()+" "+diagnostic;
        for(String secret:List.of(TOKEN,UUID,KEY,"private.example","Personal-Malaysia","198.51.100.77","https://","user:"))assertFalse(out,out.contains(secret));
        assertTrue(diagnostic.logCode().matches("[A-Za-z0-9_.:-]{1,96}"));
        assertTrue(Set.of("code","category","message","proxyIndex","proxyType").containsAll(diagnostic.fields().keySet()));
    }
    @Test public void parsesNativeZeroBasedProxyErrorsAndOnlyCallerTypes(){
        var d=CoreDiagnostics.classify("proxy 1: unsupported cipher: secret-cipher"+SECRETS,List.of("wireguard","SS"));
        assertEquals(CoreDiagnostics.Kind.PROXY_CIPHER,d.kind);assertEquals(1,d.proxyIndex);assertEquals("ss",d.proxyType);assertSecretFree(d);
        d=CoreDiagnostics.classify("proxy 0: invalid UUID: "+UUID+SECRETS,List.of("vless"));
        assertEquals(CoreDiagnostics.Kind.PROXY_UUID,d.kind);
        assertEquals("vless",d.proxyType);assertSecretFree(d);
    }
    @Test public void recognizesActionableProxyCategories(){
        String[][] fixtures={{"unsupport proxy type: closed-plugin","PROXY_TYPE"},{"unsupported cipher: old-cipher","PROXY_CIPHER"},{"invalid public key: bad-base64","PROXY_KEY"},
            {"invalid UUID: not-a-uuid","PROXY_UUID"},{"plugin v2ray-plugin is not supported","PROXY_PLUGIN"},{"unsupported network transport: websocket","PROXY_TRANSPORT"},
            {"server is empty","PROXY_SCHEMA"},{"other native failure","PROXY_INVALID"}};
        for(String[] fixture:fixtures){var d=CoreDiagnostics.classify("proxy 0: "+fixture[0],List.of("vless"));assertEquals(fixture[1],d.kind.name());assertSecretFree(d);}
    }
    @Test public void recognizesMeasuredDnsRejectionsWithoutKeepingResolverUrls(){
        for(String error:List.of("DNS NameServer[0] format error:","DNS NameServer[0] unsupport scheme:","default nameserver should be pure IP",
            "if DNS configuration is turned on, NameServer cannot be empty","if “respect-rules” is turned on, “proxy-server-nameserver” cannot be empty",
            "disallow empty `proxy-server-nameserver` when `proxy-server-nameserver-policy` is set","dns.fake-ip-filter[0] error:")){
            var d=CoreDiagnostics.classify(error+SECRETS);assertEquals(CoreDiagnostics.Kind.DNS_INVALID,d.kind);assertEquals(-1,d.proxyIndex);assertSecretFree(d);
        }
    }
    @Test public void recognizesInitMissingStorageSchemaAndGeodata(){
        String[][] fixtures={{"init failed","INIT_FAILED"},{"open /data/user/0/app/files/core/config.yaml: no such file or directory","CONFIG_MISSING"},
            {"open /data/user/0/app/files/core/config.yaml: permission denied","CONFIG_UNREADABLE"},{"yaml: unmarshal errors: line 44 cannot unmarshal value","CONFIG_SCHEMA"},
            {"rules[0] [GEOSITE,bad,AUTOPILOT] error: failed to load geosite.dat","GEODATA_FAILED"},{"json: cannot unmarshal string into Go struct field","CONFIG_SCHEMA"},
            {"proxy group[0]: unknown proxy label","CONFIG_SCHEMA"},{"unrecognized upstream rejection","UNKNOWN"}};
        for(String[] fixture:fixtures){var d=CoreDiagnostics.classify(fixture[0]+SECRETS);assertEquals(fixture[1],d.kind.name());assertSecretFree(d);}
    }
    @Test public void indexesAreBoundedAndValidatedAgainstSubmittedProfile(){
        assertEquals(0,CoreDiagnostics.classify("proxy 0: failure").proxyIndex);
        assertEquals(1999,CoreDiagnostics.classify("proxy 1999: failure").proxyIndex);
        for(String error:List.of("proxy 2000: failure","proxy 9999: failure","proxy 999999999999999999999999999999: failure","proxy -1: failure","prefix secret proxy 0: failure")){
            var d=CoreDiagnostics.classify(error);assertEquals(-1,d.proxyIndex);assertSecretFree(d);}
        assertEquals(-1,CoreDiagnostics.classify("proxy 1: failure",List.of("ss")).proxyIndex);
        assertEquals(-1,CoreDiagnostics.classify("proxy 0: failure",List.of()).proxyIndex);
    }
    @Test public void knownWrapperCanExplainButCannotAuthorizeNodeIsolation(){
        var d=CoreDiagnostics.classify("Parse config error: proxy 0: unsupported cipher: secret-cipher"+SECRETS,List.of("ss"));
        assertEquals(CoreDiagnostics.Kind.PROXY_CIPHER,d.kind);assertEquals(-1,d.proxyIndex);assertEquals("",d.proxyType);assertFalse(d.canIsolateProxy());assertSecretFree(d);
        assertTrue(CoreDiagnostics.classify("proxy 0: unsupported cipher",List.of("ss")).canIsolateProxy());
        assertFalse(CoreDiagnostics.classify("proxy 1: unsupported cipher",List.of("ss")).canIsolateProxy());
        assertEquals(CoreDiagnostics.Kind.DNS_INVALID,CoreDiagnostics.classify("Parse config error: DNS NameServer[0] format error:"+SECRETS).kind);
    }
    @Test public void neverCopiesTypesOrNamesFromNativeTextOrUntrustedContext(){
        var d=CoreDiagnostics.classify("proxy 0: unsupported proxy type: vless "+SECRETS,List.of(TOKEN));
        assertEquals("",d.proxyType);assertSecretFree(d);
        d=CoreDiagnostics.classify("proxy Personal-Malaysia is the duplicate name"+SECRETS);
        assertEquals(CoreDiagnostics.Kind.PROXY_DUPLICATE,d.kind);assertEquals(-1,d.proxyIndex);assertSecretFree(d);
        d=CoreDiagnostics.classify("proxy 0: native generic error type=wireguard",Arrays.asList((String)null));assertEquals("",d.proxyType);
    }
    @Test public void malformedOrOversizedInputCannotEscape(){
        for(String error:Arrays.asList(null,"","  ","\u0000\r\n"+SECRETS,"x".repeat(20000)+"proxy 0: unsupported cipher"+SECRETS)){
            var d=CoreDiagnostics.classify(error);assertSecretFree(d);assertEquals(-1,d.proxyIndex);}
    }
    @Test public void catalogIsTheOnlySourceOfDiagnosticStrings(){
        Set<String> codes=new HashSet<>();
        for(CoreDiagnostics.Kind kind:CoreDiagnostics.Kind.values()){
            assertTrue(codes.add(kind.code));assertTrue(kind.code.matches("CORE_[A-Z_]{1,60}"));assertTrue(kind.category.matches("[a-z]{1,12}"));
            assertFalse(kind.message.isBlank());assertFalse(kind.message.contains("%s"));
        }
        try{CoreDiagnostics.classify("unknown").fields().put("raw",TOKEN);fail("Fields must be immutable");}catch(UnsupportedOperationException expected){}
    }
    @Test public void persistedDiagnosticsAreRebuiltFromTheCatalog()throws Exception{
        org.json.JSONObject saved=CoreDiagnostics.classify("proxy 0: unsupported cipher",List.of("ss")).json();
        saved.put("message",SECRETS).put("category",SECRETS).put("raw",SECRETS).put("stage","profile").put("at",1780000000000L).put("skippedCount",1);
        org.json.JSONObject restored=CoreDiagnostics.restore(saved);assertNotNull(restored);
        assertEquals(CoreDiagnostics.Kind.PROXY_CIPHER.message,restored.getString("message"));assertFalse(restored.has("raw"));assertEquals("profile",restored.getString("stage"));assertEquals(1,restored.getInt("skippedCount"));
        for(String secret:List.of(TOKEN,UUID,KEY,"private.example"))assertFalse(restored.toString().contains(secret));
        saved.put("stage",TOKEN).put("proxyType",TOKEN).put("proxyIndex",2000).put("skippedCount",999).put("at",Long.MAX_VALUE);
        restored=CoreDiagnostics.restore(saved);for(String field:List.of("stage","proxyType","proxyIndex","skippedCount","at"))assertFalse(restored.has(field));
        saved.put("code",TOKEN);assertNull(CoreDiagnostics.restore(saved));
    }
    @Test public void supportedTypeCatalogCannotDriftBetweenNodesAndDiagnostics()throws Exception{
        for(String type:Node.SUPPORTED_TYPES)assertEquals(type,CoreDiagnostics.classify("proxy 0: generic failure",List.of(type)).proxyType);
        try{Node.SUPPORTED_TYPES.add("invented");fail("Supported types must be immutable");}catch(UnsupportedOperationException expected){}
        for(CoreDiagnostics.Stage stage:CoreDiagnostics.Stage.values())assertTrue(Set.of("baseline","profile","recovery").contains(stage.value));
    }
}
