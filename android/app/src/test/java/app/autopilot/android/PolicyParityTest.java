package app.autopilot.android;
import org.junit.Test;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Both platforms execute this exact packaged JSON scenario set against their runtime policy. */
public class PolicyParityTest {
    private JSONObject scenarios()throws Exception{try(InputStream input=getClass().getResourceAsStream("/policy-scenarios.json")){assertNotNull(input);return new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));}}
    private SelectionPolicy.Health node(String id,String country,int latency,long now){SelectionPolicy.Health h=new SelectionPolicy.Health(id,id,country);h.latency(latency,now);h.servicesAt=now;h.claude=h.chatgpt=h.gemini="ok";return h;}
    @Test public void scoreScenariosMatchRuntime()throws Exception{
        JSONObject fixtures=scenarios();long now=fixtures.getLong("now");JSONArray cases=fixtures.getJSONArray("scoreCases");
        for(int index=0;index<cases.length();index++){
            JSONObject scenario=cases.getJSONObject(index);SelectionPolicy.Health h=new SelectionPolicy.Health("fixture","fixture","JP");
            JSONArray required=scenario.optJSONArray("required");ArrayList<String> services=new ArrayList<>();if(required==null)services.addAll(ProductContract.services());else for(int i=0;i<required.length();i++)services.add(required.getString(i));h.configure(3000,services);
            JSONArray samples=scenario.getJSONArray("samples");for(int i=0;i<samples.length();i++){JSONObject sample=samples.getJSONObject(i);if("application-https".equals(sample.optString("signal"))){h.latency(sample.getInt("nativeMs"),now-sample.getLong("age"));h.transportVerified(now-sample.getLong("age"),sample.getInt("ms"));}else h.latency(sample.getInt("ms"),now-sample.getLong("age"));}
            JSONObject verdicts=scenario.optJSONObject("verdicts");h.claude=verdicts==null?"ok":verdicts.optString("claude","ok");h.chatgpt=verdicts==null?"ok":verdicts.optString("chatgpt","ok");h.gemini=verdicts==null?"ok":verdicts.optString("gemini","ok");h.servicesAt=now;
            double actual=h.qualified(now)?h.score(now):Double.POSITIVE_INFINITY;
            if(scenario.isNull("expected"))assertEquals(scenario.getString("id"),Double.POSITIVE_INFINITY,actual,0);else assertEquals(scenario.getString("id"),scenario.getDouble("expected"),actual,0.000000001);
        }
    }
    @Test public void switchingScenariosMatchRuntime()throws Exception{
        JSONObject fixtures=scenarios();long now=fixtures.getLong("now");JSONArray cases=fixtures.getJSONArray("switchCases");
        for(int i=0;i<cases.length();i++){JSONObject scenario=cases.getJSONObject(i);SelectionPolicy.Health current=node("current",scenario.optString("currentCountry","JP"),scenario.getInt("currentMs"),now),candidate=node("candidate",scenario.optString("candidateCountry","JP"),scenario.getInt("candidateMs"),now);JSONArray countries=scenario.optJSONArray("preferred");ArrayList<String> preferred=new ArrayList<>();if(countries!=null)for(int n=0;n<countries.length();n++)preferred.add(countries.getString(n));assertEquals(scenario.getString("id"),scenario.getBoolean("expected"),SelectionPolicy.shouldSwitch(current,candidate,preferred,now,now-scenario.getLong("dwellMs"),scenario.getBoolean("force")));}
    }
    @Test public void confirmationScenariosMatchRuntime()throws Exception{
        JSONArray cases=scenarios().getJSONArray("confirmationCases");for(int i=0;i<cases.length();i++){JSONObject scenario=cases.getJSONObject(i);assertEquals(scenario.getString("id"),scenario.getBoolean("expected"),ConnectionWatchdog.confirmsBroad(scenario.getInt("previousMask"),scenario.getInt("nextMask"),scenario.getLong("elapsedMs"),ProductContract.policyLong("watchdogConfirmMs")));}
    }
}
