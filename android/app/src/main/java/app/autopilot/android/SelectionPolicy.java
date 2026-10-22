package app.autopilot.android;

import java.util.*;

/** The same age-bounded health ledger and selection rules as the desktop runtime. */
public final class SelectionPolicy {
    public static final long LATENCY_TTL=ProductContract.policyLong("latencyTtlMs"),SERVICE_TTL=ProductContract.policyLong("serviceTtlMs"),DWELL=ProductContract.defaults().optLong("minDwellMs");
    private SelectionPolicy() {}
    public static final class Health {
        public final String id,name,country;
        public long latencyAt,servicesAt,appFailureUntil,appVerifiedAt,transportAt;
        public int appFailures,applicationLatency=-1,latency=-1,failures;
        public boolean appRejected;
        public String claude="unknown",chatgpt="unknown",gemini="unknown";
        private int maxLatency=ProductContract.defaults().optInt("maxLatencyMs");
        private List<String> required=ProductContract.services();
        private long healthySince,lastHealthyAt,scoreAt;
        private static final class Sample {final int ms;final long at;final boolean application;Sample(int ms,long at,boolean application){this.ms=ms;this.at=at;this.application=application;}}
        private final ArrayDeque<Sample> samples=new ArrayDeque<>();
        public Health(String id,String name,String country){this.id=id;this.name=name;this.country=country;}
        public void configure(int maxLatency,List<String> required){this.maxLatency=maxLatency;this.required=List.copyOf(required);}
        public void latency(int ms,long now){
            latencyAt=now;latency=ms>0&&ms<maxLatency?ms:-1;scoreAt=now;
            samples.addLast(new Sample(latency,now,false));while(samples.size()>ProductContract.policyInt("sampleCount"))samples.removeFirst();
            failures=latency>0?0:Math.min(10,failures+1);
        }
        private static double median(List<Double> values){Collections.sort(values);int mid=values.size()/2;return values.size()%2==1?values.get(mid):(values.get(mid-1)+values.get(mid))/2;}
        public double score(long now){return eligibleForProof(now)?connectionScore(now):Double.POSITIVE_INFINITY;}
        public double connectionScore(long now){
            if(!connectionFresh(now))return Double.POSITIVE_INFINITY;
            ArrayList<Double> passing=new ArrayList<>();int total=0,failed=0;
            for(Sample sample:samples)if(now>=sample.at&&now-sample.at<=ProductContract.policyLong("sampleHorizonMs")){total++;if(sample.ms>0)passing.add((double)sample.ms);else failed++;}
            // An application-path fallback is a measured HTTPS result, never a fabricated native RTT.
            if(passing.isEmpty())return Double.POSITIVE_INFINITY;
            double center=median(passing);ArrayList<Double> deviation=new ArrayList<>();for(double ms:passing)deviation.add(Math.abs(ms-center));
            int challenged=0;for(String service:required)if("challenged".equals(verdict(service)))challenged++;
            return center+median(deviation)*ProductContract.policyDouble("jitterWeight")+challenged*ProductContract.policyInt("challengePenaltyMs")+(total==0?0:ProductContract.policyDouble("failurePenaltyMs")*failed/total);
        }
        public int score(){double score=score(Math.max(scoreAt,transportAt));return Double.isFinite(score)?(int)Math.round(score):Integer.MAX_VALUE;}
        public String verdict(String service){return "claude".equals(service)?claude:"chatgpt".equals(service)?chatgpt:gemini;}
        public boolean latencyFresh(long now){return latency>0&&latency<maxLatency&&now>=latencyAt&&now-latencyAt<=LATENCY_TTL;}
        public boolean qualified(long now){return !appRejected&&eligibleForProof(now);}
        public boolean connectionFresh(long now){return latencyFresh(now)||applicationLatency>0&&applicationLatency<maxLatency&&now>=transportAt&&now-transportAt<=LATENCY_TTL;}
        public boolean eligibleForProof(long now){return !excluded(country)&&now>=appFailureUntil&&connectionFresh(now)&&now>=servicesAt&&now-servicesAt<=SERVICE_TTL&&required.stream().allMatch(s->usable(verdict(s)));}
        public void applicationFailed(long now){appRejected=true;appFailures=Math.min(4,appFailures+1);appFailureUntil=now+Math.min(ProductContract.policyLong("cooldownMaxMs"),ProductContract.policyLong("cooldownBaseMs")<<(appFailures-1));healthySince=lastHealthyAt=0;}
        /** Fresh candidate proof clears rejection, but retains the escalation history. */
        public void applicationVerified(long now){appRejected=false;appFailureUntil=0;appVerifiedAt=now;healthySince=lastHealthyAt=0;}
        public void transportVerified(long now,int measuredMillis){if(measuredMillis>0){
            transportAt=now;applicationLatency=measuredMillis;scoreAt=now;
            if(!latencyFresh(now)){
                Sample latest=samples.peekLast();if(latest!=null&&!latest.application&&latest.ms<=0&&now>=latest.at&&now-latest.at<=15_000)samples.removeLast();
                samples.addLast(new Sample(measuredMillis,now,true));while(samples.size()>ProductContract.policyInt("sampleCount"))samples.removeFirst();failures=0;
            }
        }}
        public int sampleCount(long now){int count=0;for(Sample sample:samples)if(sample.ms>0&&now>=sample.at&&now-sample.at<=ProductContract.policyLong("sampleHorizonMs"))count++;return count;}
        /** Only selected-route watchdog proof can reset strikes after two sustained healthy minutes. */
        public void selectedHealthy(long now){if(appRejected)return;if(lastHealthyAt==0||now<lastHealthyAt||now-lastHealthyAt>ProductContract.policyLong("transportRetentionMs"))healthySince=now;lastHealthyAt=now;if(now-healthySince>=ProductContract.policyLong("cooldownResetHealthyMs"))appFailures=0;}
        public void selectedUncertain(){healthySince=lastHealthyAt=0;}
        public int displayLatency(){return displayLatency(Math.max(scoreAt,transportAt));}
        public int displayLatency(long now){return latencyFresh(now)?latency:applicationLatency>0&&now>=transportAt&&now-transportAt<=LATENCY_TTL?applicationLatency:-1;}
        public boolean retainSelectedTransport(long now){return !appRejected&&applicationLatency>0&&transportAt>0&&now>=transportAt&&now-transportAt<=ProductContract.policyLong("transportRetentionMs");}
    }
    private static boolean excluded(String country){org.json.JSONArray countries=ProductContract.policy().optJSONArray("excludedCountries");for(int i=0;i<countries.length();i++)if(country.equals(countries.optString(i)))return true;return false;}
    public static boolean usable(String verdict){return "ok".equals(verdict)||"challenged".equals(verdict);}
    public static int tier(String country,List<String> preferred){int i=preferred.indexOf(country);return i<0?preferred.size():i;}
    public static Comparator<Health> order(List<String> preferred){return order(preferred,-1);}
    public static Comparator<Health> order(List<String> preferred,long now){List<String> copy=List.copyOf(preferred);return Comparator.comparingInt((Health h)->tier(h.country,copy)).thenComparingDouble(h->now<0?h.score():h.score(now)).thenComparing(h->h.name);}
    public static Health best(Collection<Health> all,List<String> preferred,long now){return all.stream().filter(h->h.qualified(now)).min(order(preferred,now)).orElse(null);}
    public static boolean shouldSwitch(Health current,Health candidate,List<String> preferred,long now,long lastSwitch,boolean force){return shouldSwitch(current,candidate,preferred,now,lastSwitch,force,DWELL,ProductContract.defaults().optDouble("switchRatio"));}
    public static boolean shouldSwitch(Health current,Health candidate,List<String> preferred,long now,long lastSwitch,boolean force,long dwell,double ratio){
        if(candidate==null||!candidate.qualified(now)||current!=null&&candidate.id.equals(current.id))return false;
        if(current==null||!current.qualified(now))return true;
        if(force)return true;
        int candidateTier=tier(candidate.country,preferred),currentTier=tier(current.country,preferred);
        if(candidateTier<currentTier)return true;if(candidateTier>currentTier)return false;
        return now-lastSwitch>=dwell&&current.score(now)-candidate.score(now)>=ProductContract.policyInt("minImprovementPoints")&&candidate.score(now)<=current.score(now)*ratio;
    }
}
