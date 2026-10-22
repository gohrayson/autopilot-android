package app.autopilot.android;

import java.util.*;

/** Pure country, freshness and stability policy shared by every selection path. */
public final class SelectionPolicy {
    public static final long LATENCY_TTL = 90_000L, SERVICE_TTL = 300_000L, DWELL = 30_000L;
    private SelectionPolicy() {}
    public static final class Health {
        public final String id, name, country;
        public long latencyAt, servicesAt;
        public long appFailureUntil,appVerifiedAt,transportAt;
        public int appFailures;
        public int applicationLatency=-1;
        public boolean appRejected;
        public int latency = -1, failures;
        public String claude = "unknown", chatgpt = "unknown", gemini = "unknown";
        private final ArrayDeque<Integer> samples = new ArrayDeque<>();
        public Health(String id, String name, String country) { this.id=id; this.name=name; this.country=country; }
        public void latency(int ms, long now) {
            latencyAt=now; latency=ms;
            if(ms<=0) { failures=Math.min(10,failures+1); return; }
            failures=Math.max(0,failures-1); samples.addLast(ms); if(samples.size()>5)samples.removeFirst();
        }
        public int score() {
            if(latency<=0||transportAt-latencyAt>LATENCY_TTL)return applicationLatency<=0?Integer.MAX_VALUE:applicationLatency+failures*250;
            ArrayList<Integer> ordered=new ArrayList<>(samples); Collections.sort(ordered);
            int median=ordered.isEmpty()?latency:ordered.get(ordered.size()/2);
            int jitter=ordered.size()<2?0:ordered.get(ordered.size()-1)-ordered.get(0);
            int challenges=("challenged".equals(claude)?1:0)+("challenged".equals(chatgpt)?1:0)+("challenged".equals(gemini)?1:0);
            return median+jitter/3+failures*250+challenges*100;
        }
        public boolean latencyFresh(long now) { return latency>0 && now>=latencyAt && now-latencyAt<=LATENCY_TTL; }
        public boolean qualified(long now) {
            return !appRejected&&eligibleForProof(now);
        }
        public boolean connectionFresh(long now){return latencyFresh(now)||applicationLatency>0&&now>=transportAt&&now-transportAt<=LATENCY_TTL;}
        public boolean eligibleForProof(long now){return now>=appFailureUntil&&connectionFresh(now)&&now>=servicesAt&&now-servicesAt<=SERVICE_TTL&&usable(claude)&&usable(chatgpt)&&usable(gemini);}
        public void applicationFailed(long now){appRejected=true;appFailures=Math.min(4,appFailures+1);appFailureUntil=now+Math.min(300_000L,60_000L<<(appFailures-1));}
        /** An ordinary latency pass deliberately cannot clear this state. */
        public void applicationVerified(long now){appRejected=false;appFailureUntil=0;appVerifiedAt=now;}
        public void transportVerified(long now,int measuredMillis){if(measuredMillis>0){transportAt=now;applicationLatency=measuredMillis;}}
        public int displayLatency(){return latency>0&&transportAt-latencyAt<=LATENCY_TTL?latency:applicationLatency;}
        /** Only an already selected route may survive a site-wide service outage on recent independent transport proof. */
        public boolean retainSelectedTransport(long now){return !appRejected&&applicationLatency>0&&transportAt>0&&now>=transportAt&&now-transportAt<=45_000;}
    }
    public static boolean usable(String verdict) {return "ok".equals(verdict)||"challenged".equals(verdict);}
    public static int tier(String country,List<String> preferred) {int i=preferred.indexOf(country);return i<0?preferred.size():i;}
    public static Comparator<Health> order(List<String> preferred) {
        List<String> copy=List.copyOf(preferred);
        return Comparator.comparingInt((Health h)->tier(h.country,copy)).thenComparingInt(Health::score).thenComparing(h->h.name);
    }
    public static Health best(Collection<Health> all,List<String> preferred,long now) {
        return all.stream().filter(h->h.qualified(now)).min(order(preferred)).orElse(null);
    }
    public static boolean shouldSwitch(Health current,Health candidate,List<String> preferred,long now,long lastSwitch,boolean force) {
        if(candidate==null || !candidate.qualified(now) || current!=null && candidate.id.equals(current.id))return false;
        if(current==null || !current.qualified(now))return true;
        int candidateTier=tier(candidate.country,preferred),currentTier=tier(current.country,preferred);
        if(candidateTier<currentTier)return true;
        if(candidateTier>currentTier)return false;
        return now-lastSwitch>=DWELL && current.score()-candidate.score()>=20 && candidate.score()<=current.score()*0.9;
    }
}
