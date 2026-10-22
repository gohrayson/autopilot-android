package app.autopilot.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.List;

/** Resolver policy shared by outbound bootstrapping and DNS arriving through the TUN. */
final class CoreDnsConfig {
    static final String POLICY_ID="desktop-alidns-tencent-ipv4";
    // Keep the production policy aligned with src/main/core.js. Resolving a proxy's
    // hostname must work before AUTOPILOT can select a working proxy.
    static final boolean IPV6_ENABLED=false;
    static final List<String> BOOTSTRAP_NAMESERVERS=List.of("223.5.5.5","119.29.29.29");
    static final List<String> ENCRYPTED_NAMESERVERS=List.of("https://dns.alidns.com/dns-query","https://doh.pub/dns-query");
    static final List<String> FAKE_IP_FILTER=List.of("*.lan","*.local","+.msftconnecttest.com","+.msftncsi.com","localhost.ptlogin2.qq.com");
    static final CoreDnsConfig DEFAULT=new CoreDnsConfig(BOOTSTRAP_NAMESERVERS,ENCRYPTED_NAMESERVERS);

    private final List<String> bootstrap,resolvers;

    // Package-private injection keeps native integration probes on the real builder
    // while directing their DNS to an isolated, deterministic resolver fixture.
    CoreDnsConfig(List<String> bootstrap,List<String> resolvers){
        this.bootstrap=validated(bootstrap);this.resolvers=validated(resolvers);
    }
    private static List<String> validated(List<String> values){
        List<String> copy=List.copyOf(values);
        if(copy.isEmpty()||copy.stream().anyMatch(String::isBlank))throw new IllegalArgumentException("DNS resolvers are required");
        return copy;
    }
    JSONObject build()throws JSONException{
        return new JSONObject().put("enable",true).put("listen","127.0.0.1:0").put("ipv6",IPV6_ENABLED)
            .put("enhanced-mode","fake-ip").put("fake-ip-range","198.18.0.1/16")
            .put("fake-ip-filter",new JSONArray(FAKE_IP_FILTER))
            .put("default-nameserver",new JSONArray(bootstrap))
            // These direct resolver sockets belong to the excluded/protected UID.
            .put("nameserver",new JSONArray(resolvers)).put("proxy-server-nameserver",new JSONArray(resolvers));
    }
}
