package app.autopilot.android;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

/** Test the exact configuration object passed to the bundled native core. */
public class EngineConfigurationTest {
    private Node proxy(String name,String server,String country)throws Exception{return new Node(new JSONObject().put("name",name).put("type","socks5").put("server",server).put("port",8443),"import",country);}
    @Test public void everyProbeIsLoopbackBoundAndSelectsItsOwnOutbound()throws Exception{
        Node my=proxy("MY test","192.0.2.1","MY"),sg=proxy("SG test","192.0.2.2","SG");Map<String,Integer> ports=new HashMap<>();
        JSONObject config=VpnEngine.configuration(List.of(my,sg),ports);
        assertFalse(config.getBoolean("allow-lan"));assertEquals("127.0.0.1",config.getString("bind-address"));
        assertFalse(config.getJSONObject("tun").getBoolean("enable"));
        JSONArray listeners=config.getJSONArray("listeners");assertEquals(2,listeners.length());
        for(int i=0;i<listeners.length();i++){
            JSONObject listener=listeners.getJSONObject(i);Node n=i==0?my:sg;
            assertEquals("127.0.0.1",listener.getString("listen"));assertEquals(n.name,listener.getString("proxy"));
            assertEquals(ports.get(n.id).intValue(),listener.getInt("port"));assertEquals("mixed",listener.getString("type"));
        }
        assertNotEquals(ports.get(my.id),ports.get(sg.id));
    }
    @Test public void noVerifiedSelectionFallsClosedAndDoesNotRequireGeodata()throws Exception{
        JSONObject config=VpnEngine.configuration(List.of(),new HashMap<>());
        assertEquals("[\"MATCH,AUTOPILOT\"]",config.getJSONArray("rules").toString());
        JSONArray names=config.getJSONArray("proxy-groups").getJSONObject(0).getJSONArray("proxies");
        assertEquals("[\"REJECT\"]",names.toString());assertFalse(config.has("external-controller"));
        assertFalse(config.has("geo-auto-update"));
    }
    @Test public void encryptedDnsCanBootstrapBeforeAnyNodeIsSelected()throws Exception{
        JSONObject dns=VpnEngine.configuration(List.of(),new HashMap<>()).getJSONObject("dns");
        assertTrue(dns.getBoolean("enable"));assertEquals("fake-ip",dns.getString("enhanced-mode"));
        for(String key:List.of("nameserver","proxy-server-nameserver")){
            JSONArray resolvers=dns.getJSONArray(key);assertTrue(resolvers.length()>0);
            for(int i=0;i<resolvers.length();i++){String resolver=resolvers.getString(i);assertTrue(resolver.startsWith("https://"));assertFalse(resolver.contains("#AUTOPILOT"));}
        }
    }
}
