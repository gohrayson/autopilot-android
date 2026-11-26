package app.autopilot.android;

import org.json.*;
import org.junit.Test;
import java.net.URI;
import java.util.*;
import static org.junit.Assert.*;

public final class CoreDnsConfigTest {
    private static List<String> strings(JSONArray array)throws JSONException{
        ArrayList<String> values=new ArrayList<>();for(int i=0;i<array.length();i++)values.add(array.getString(i));return values;
    }
    @Test public void productionBootstrapDoesNotDependOnSelectingAProxy()throws Exception{
        Node node=new Node(new JSONObject().put("name","Hostname endpoint").put("type","socks5")
            .put("server","proxy-fixture.autopilot.test").put("port",29480),"import","JP");
        JSONObject config=VpnEngine.configuration(List.of(node),new HashMap<>()),dns=config.getJSONObject("dns");
        assertFalse(config.getBoolean("ipv6"));assertFalse(dns.getBoolean("ipv6"));
        assertTrue(dns.getBoolean("enable"));assertEquals("127.0.0.1:0",dns.getString("listen"));
        assertEquals("fake-ip",dns.getString("enhanced-mode"));assertEquals("198.18.0.1/16",dns.getString("fake-ip-range"));
        // Resolving encrypted resolver hostnames has literal-IP bootstrap servers,
        // so neither resolving proxy endpoints nor probing them requires a node
        // already selected in AUTOPILOT. Exact desktop parity has its own probe.
        List<String> bootstrap=strings(dns.getJSONArray("default-nameserver"));assertEquals(List.of("223.5.5.5","119.29.29.29","114.114.114.114"),bootstrap);
        for(String resolver:bootstrap)assertTrue(resolver,resolver.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"));
        for(String key:List.of("nameserver","proxy-server-nameserver")){
            List<String> resolvers=strings(dns.getJSONArray(key));assertEquals(CoreDnsConfig.DEFAULT_RESOLVERS,resolvers);
            assertEquals(bootstrap,resolvers.subList(0,bootstrap.size()));
            assertEquals(CoreDnsConfig.ENCRYPTED_NAMESERVERS,resolvers.subList(bootstrap.size(),resolvers.size()));
            for(String resolver:resolvers){
                assertFalse("DNS must not wait for an AUTOPILOT selection",resolver.contains("#"));
                if(resolver.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))continue;
                URI uri=URI.create(resolver);assertEquals("https",uri.getScheme());
                assertNotNull(uri.getHost());assertNull(uri.getFragment());
            }
        }
        assertEquals("REJECT",config.getJSONArray("proxy-groups").getJSONObject(0).getJSONArray("proxies").getString(0));
    }
    @Test public void isolatedResolverFlowsThroughProductionBuilderWithoutChangingTheDefault()throws Exception{
        ArrayList<String> bootstrap=new ArrayList<>(List.of("10.0.2.2:29553"));
        ArrayList<String> resolvers=new ArrayList<>(List.of("udp://10.0.2.2:29553"));
        CoreDnsConfig policy=new CoreDnsConfig(bootstrap,resolvers);bootstrap.clear();resolvers.clear();
        JSONObject config=VpnEngine.configuration(List.of(),new HashMap<>(),policy),dns=config.getJSONObject("dns");
        assertEquals(List.of("10.0.2.2:29553"),strings(dns.getJSONArray("default-nameserver")));
        assertEquals(List.of("udp://10.0.2.2:29553"),strings(dns.getJSONArray("nameserver")));
        assertEquals(dns.getJSONArray("nameserver").toString(),dns.getJSONArray("proxy-server-nameserver").toString());
        dns.getJSONArray("nameserver").put("192.0.2.1");
        assertEquals(List.of("udp://10.0.2.2:29553"),strings(policy.build().getJSONArray("nameserver")));
        assertEquals(CoreDnsConfig.DEFAULT_RESOLVERS,strings(CoreDnsConfig.DEFAULT.build().getJSONArray("nameserver")));
        assertEquals("resilient-alidns-tencent-ipv4",CoreDnsConfig.POLICY_ID);
        for(List<String> fixed:List.of(CoreDnsConfig.BOOTSTRAP_NAMESERVERS,CoreDnsConfig.ENCRYPTED_NAMESERVERS,CoreDnsConfig.DEFAULT_RESOLVERS))
            assertThrows(UnsupportedOperationException.class,()->fixed.add("192.0.2.1"));
        JSONObject defaultCopy=CoreDnsConfig.DEFAULT.build();defaultCopy.getJSONArray("proxy-server-nameserver").put("192.0.2.1");
        assertEquals(CoreDnsConfig.DEFAULT_RESOLVERS,strings(CoreDnsConfig.DEFAULT.build().getJSONArray("proxy-server-nameserver")));
    }
    @Test public void emptyOrBlankResolverPolicyIsRejected(){
        for(List<String> invalid:List.of(List.<String>of(),List.of(" "))){
            assertThrows(IllegalArgumentException.class,()->new CoreDnsConfig(invalid,List.of("223.5.5.5")));
            assertThrows(IllegalArgumentException.class,()->new CoreDnsConfig(List.of("223.5.5.5"),invalid));
        }
    }
}
