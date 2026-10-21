package app.autopilot.android;

import org.json.*;
import org.junit.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class CoreConfigEncodingTest {
    private Object yaml(String json){return new Yaml(new SafeConstructor(new LoaderOptions())).load(json);}
    @Test public void androidStyleBaselineSlashesBecomeYamlCompatibleWithoutChangingData()throws Exception{
        String android="{\"dns\":{\"fake-ip-range\":\"198.18.0.1\\/16\",\"nameserver\":[\"https:\\/\\/1.1.1.1\\/dns-query\"]},\"rules\":[\"MATCH,AUTOPILOT\"]}";
        String encoded=CoreConfigEncoding.normalizeOptionalSlashes(android);
        JSONObject parsed=new JSONObject(encoded),original=new JSONObject(android);
        assertEquals(original.getJSONObject("dns").getString("fake-ip-range"),parsed.getJSONObject("dns").getString("fake-ip-range"));
        assertEquals("https://1.1.1.1/dns-query",parsed.getJSONObject("dns").getJSONArray("nameserver").getString(0));
        Map<?,?> y=(Map<?,?>)yaml(encoded),dns=(Map<?,?>)y.get("dns");
        assertEquals("198.18.0.1/16",dns.get("fake-ip-range"));assertEquals(List.of("https://1.1.1.1/dns-query"),dns.get("nameserver"));
    }
    @Test public void consumesEscapedBackslashesAtomicallyForBothJsonSerializers()throws Exception{
        // Enumerate JSON spellings of adjacent backslashes/slashes, including
        // the exact host case that a global string replacement would corrupt.
        for(int slashes=0;slashes<8;slashes++){
            String value="before"+"\\".repeat(slashes)+"/after";
            String host=new JSONObject().put("password",value).toString();
            assertEquals(value,new JSONObject(CoreConfigEncoding.normalizeOptionalSlashes(host)).getString("password"));
            String android="{\"password\":\"before"+"\\\\".repeat(slashes)+"\\/after\"}";
            String normalized=CoreConfigEncoding.normalizeOptionalSlashes(android);
            assertEquals(value,new JSONObject(normalized).getString("password"));
            assertEquals(value,((Map<?,?>)yaml(normalized)).get("password"));
            assertEquals(normalized,CoreConfigEncoding.normalizeOptionalSlashes(normalized));
        }
    }
    @Test public void preservesQuotesUnicodeControlEscapesAndNetworkPaths()throws Exception{
        List<String> values=List.of("quoted \"value\" / end","line\nnext\tcolumn\rreturn","马来西亚 / 日本 😀","\\\\server\\share/leaf","literal \\u002f and \\n /","ending backslash\\");
        for(String value:values){
            JSONObject original=new JSONObject().put("field",value);String encoded=new String(CoreConfigEncoding.encode(original),StandardCharsets.UTF_8);
            assertEquals(value,new JSONObject(encoded).getString("field"));assertEquals(value,((Map<?,?>)yaml(encoded)).get("field"));
        }
        String unicodeEscape="{\"field\":\"\\u005c\\/\\u002f\\\"\\n\"}";
        String normalized=CoreConfigEncoding.normalizeOptionalSlashes(unicodeEscape);
        assertEquals(new JSONObject(unicodeEscape).getString("field"),new JSONObject(normalized).getString("field"));
        assertEquals(new JSONObject(unicodeEscape).getString("field"),((Map<?,?>)yaml(normalized)).get("field"));
    }
    @Test public void actualProductionProfileKeepsNodeCredentialsAndAllGlobalValues()throws Exception{
        String password="secret\\/literal \\"+"/ slash\n\"quotes\" 中文";
        Node node=new Node(new JSONObject().put("name","Encoding fixture").put("type","ss").put("server","192.0.2.5").put("port",443).put("cipher","aes-128-gcm").put("password",password),"import","MY");
        JSONObject profile=VpnEngine.configuration(List.of(node),new HashMap<>());
        String encoded=new String(CoreConfigEncoding.encode(profile),StandardCharsets.UTF_8);JSONObject parsed=new JSONObject(encoded);
        assertEquals(password,parsed.getJSONArray("proxies").getJSONObject(0).getString("password"));
        Map<?,?> y=(Map<?,?>)yaml(encoded);Map<?,?> outbound=(Map<?,?>)((List<?>)y.get("proxies")).get(0);
        assertEquals(password,outbound.get("password"));assertEquals("ss",outbound.get("type"));
        assertEquals("198.18.0.1/16",((Map<?,?>)y.get("dns")).get("fake-ip-range"));assertEquals(List.of("MATCH,AUTOPILOT"),y.get("rules"));
        assertEquals(parsed.getJSONArray("listeners").getJSONObject(0).getString("proxy"),node.name);
    }
    @Test public void encodedEmptyBaselineIsJsonAndYamlWithTheSameMeaning()throws Exception{
        JSONObject profile=VpnEngine.configuration(List.of(),new HashMap<>());
        String encoded=new String(CoreConfigEncoding.encode(profile),StandardCharsets.UTF_8);
        JSONObject json=new JSONObject(encoded);Map<?,?> y=(Map<?,?>)yaml(encoded);
        assertEquals("rule",json.getString("mode"));assertEquals("rule",y.get("mode"));assertFalse((Boolean)((Map<?,?>)y.get("tun")).get("enable"));
        assertTrue(((List<?>)y.get("proxies")).isEmpty());assertEquals("REJECT",((List<?>)((Map<?,?>)((List<?>)y.get("proxy-groups")).get(0)).get("proxies")).get(0));
    }
}
