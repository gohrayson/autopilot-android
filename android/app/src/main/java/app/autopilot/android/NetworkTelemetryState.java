package app.autopilot.android;

import org.json.*;
import java.net.*;
import java.util.*;

/** Validated, synchronized telemetry model. It never changes VPN selection. */
final class NetworkTelemetryState {
    static final long MAX_COUNTER=9_007_199_254_740_991L;
    static final Set<String> COUNTRIES=Set.of(Locale.getISOCountries());
    static final class Pair {
        final long up,down;
        Pair(long up,long down){if(!valid(up)||!valid(down))throw new IllegalArgumentException("TRAFFIC_INVALID");this.up=up;this.down=down;}
    }
    static final class Route {
        final long profileGeneration,selectionEpoch;final String nodeId,node,country;final int port;
        Route(long generation,long epoch,String id,String node,String country,int port){
            if(id==null||id.isEmpty()||node==null||node.isEmpty()||port<1||port>65535)throw new IllegalArgumentException("EXIT_ROUTE_INVALID");
            profileGeneration=generation;selectionEpoch=epoch;nodeId=id;this.node=node;this.country=country!=null&&COUNTRIES.contains(country)?country:"";this.port=port;
        }
        @Override public boolean equals(Object other){if(!(other instanceof Route))return false;Route r=(Route)other;return profileGeneration==r.profileGeneration&&selectionEpoch==r.selectionEpoch&&port==r.port&&nodeId.equals(r.nodeId)&&node.equals(r.node)&&country.equals(r.country);}
        @Override public int hashCode(){return Objects.hash(profileGeneration,selectionEpoch,nodeId,node,country,port);}
    }
    static final class Exit {final String ip,country;Exit(String ip,String country){this.ip=ip;this.country=country;}}
    static final class Ticket {final Route route;final long revision;Ticket(Route route,long revision){this.route=route;this.revision=revision;}}
    private Route route;private long revision,generation=Long.MIN_VALUE;
    private boolean active,closed,persistent=true,counterLimit;
    private Pair previous;private long previousElapsed,sessionUp,sessionDown,totalUp,totalDown,trafficAt;
    private Long upRate,downRate;
    private String trafficPhase="idle",trafficCode="STOPPED",exitPhase="idle",exitCode="STOPPED";
    private Exit exit;private long exitAt;
    NetworkTelemetryState(Pair stored,boolean persistent){if(stored!=null){totalUp=stored.up;totalDown=stored.down;}this.persistent=persistent;}
    static boolean valid(long value){return value>=0&&value<=MAX_COUNTER;}
    static Pair parsePair(String raw)throws JSONException{
        if(raw==null||raw.length()>1024)throw new JSONException("TRAFFIC_INVALID");JSONObject value=new JSONObject(raw);
        return new Pair(integer(value.get("up")),integer(value.get("down")));
    }
    private static long integer(Object value)throws JSONException{
        if(!(value instanceof Integer)&&!(value instanceof Long))throw new JSONException("TRAFFIC_INVALID");long number=((Number)value).longValue();if(!valid(number))throw new JSONException("TRAFFIC_INVALID");return number;
    }
    static Exit parseExit(String raw)throws Exception{
        if(raw==null||raw.length()>8192)throw new IllegalArgumentException("EXIT_INVALID");Map<String,String> fields=new HashMap<>();
        for(String line:raw.split("\\r?\\n")){int at=line.indexOf('=');if(at>0)fields.put(line.substring(0,at),line.substring(at+1).trim());}
        String ip=fields.get("ip"),country=fields.get("loc");
        if(ip==null||ip.length()>45||!COUNTRIES.contains(country))throw new IllegalArgumentException("EXIT_INVALID");
        if(ip.indexOf(':')>=0){if(!ip.matches("[0-9a-fA-F:.]+"))throw new IllegalArgumentException("EXIT_INVALID");}
        else{String[] octets=ip.split("\\.",-1);if(octets.length!=4)throw new IllegalArgumentException("EXIT_INVALID");for(String octet:octets)if(!octet.matches("0|[1-9][0-9]{0,2}")||Integer.parseInt(octet)>255)throw new IllegalArgumentException("EXIT_INVALID");}
        // Numeric-literal checks above prevent these validation reads from doing DNS.
        InetAddress address=InetAddress.getByName(ip);byte[] bytes=address.getAddress();
        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())throw new IllegalArgumentException("EXIT_INVALID");
        if(bytes.length==4){int a=bytes[0]&255,b=bytes[1]&255,c=bytes[2]&255;
            if(a==0||a>=224||a==100&&b>=64&&b<=127||a==192&&b==0&&(c==0||c==2)||a==198&&(b==18||b==19||b==51&&c==100)||a==203&&b==0&&c==113)throw new IllegalArgumentException("EXIT_INVALID");
        }else if((bytes[0]&254)==252||bytes[0]==0x20&&bytes[1]==0x01&&(bytes[2]&255)==0x0d&&(bytes[3]&255)==0xb8)throw new IllegalArgumentException("EXIT_INVALID");
        return new Exit(ip,country);
    }
    synchronized boolean target(Route next,long nativeGeneration,boolean nativeAvailable){
        if(closed)return false;boolean changed=!Objects.equals(route,next);
        if(changed){route=next;revision++;exit=null;exitAt=0;exitPhase=next==null?"idle":"checking";exitCode=next==null?"STOPPED":"CHECKING";}
        if(generation!=nativeGeneration){generation=nativeGeneration;previous=null;upRate=downRate=null;}
        active=nativeAvailable;
        if(!active){previous=null;upRate=downRate=null;trafficPhase="idle";trafficCode="STOPPED";}
        return changed;
    }
    synchronized Route route(){return closed?null:route;}
    synchronized long generation(){return generation;}
    synchronized boolean active(){return active&&!closed;}
    synchronized long exitAt(){return exitAt;}
    synchronized Ticket beginExit(){if(closed||route==null)return null;exit=null;exitPhase="checking";exitCode="CHECKING";return new Ticket(route,++revision);}
    private boolean current(Ticket ticket){return !closed&&ticket!=null&&revision==ticket.revision&&Objects.equals(route,ticket.route);}
    synchronized boolean completeExit(Ticket ticket,Exit value,long at){
        if(!current(ticket))return false;exit=value;exitAt=at;boolean mismatch=!route.country.isEmpty()&&!route.country.equals(value.country);
        exitPhase=mismatch?"mismatch":"verified";exitCode=mismatch?"EXIT_COUNTRY_MISMATCH":"OK";return true;
    }
    synchronized boolean failExit(Ticket ticket,long at){if(!current(ticket))return false;exit=null;exitAt=at;exitPhase="unavailable";exitCode="EXIT_UNAVAILABLE";return true;}
    synchronized void counters(long nativeGeneration,Pair totals,Pair rates,long elapsed,long at){
        if(!active()||generation!=nativeGeneration||counterLimit)return;
        if(previous!=null){
            boolean reset=totals.up<previous.up||totals.down<previous.down;
            long up=reset?totals.up:totals.up-previous.up,down=reset?totals.down:totals.down-previous.down;
            if(sessionUp>MAX_COUNTER-up||sessionDown>MAX_COUNTER-down||totalUp>MAX_COUNTER-up||totalDown>MAX_COUNTER-down){counterLimit=true;trafficPhase="unavailable";trafficCode="TRAFFIC_COUNTER_LIMIT";upRate=downRate=null;return;}
            sessionUp=add(sessionUp,up);sessionDown=add(sessionDown,down);totalUp=add(totalUp,up);totalDown=add(totalDown,down);
            if(elapsed>previousElapsed){upRate=rates.up;downRate=rates.down;}else upRate=downRate=null;
        }else upRate=downRate=null;
        previous=totals;previousElapsed=elapsed;trafficAt=at;trafficPhase="available";trafficCode="OK";
    }
    synchronized void trafficFailure(long nativeGeneration){if(!active()||generation!=nativeGeneration)return;trafficPhase="unavailable";trafficCode="TRAFFIC_UNAVAILABLE";upRate=downRate=null;}
    private static long add(long a,long b){return a>MAX_COUNTER-b?MAX_COUNTER:a+b;}
    synchronized Pair totals(){return new Pair(totalUp,totalDown);}
    synchronized boolean persistent(){return persistent;}
    synchronized void persistenceFailed(){persistent=false;}
    synchronized void close(){closed=true;active=false;route=null;revision++;exit=null;exitAt=0;exitPhase="idle";exitCode="STOPPED";trafficPhase="idle";trafficCode="STOPPED";upRate=downRate=null;previous=null;}
    synchronized JSONObject json(){try{
        JSONObject e=new JSONObject().put("phase",exitPhase).put("code",exitCode).put("ip",exit==null?JSONObject.NULL:exit.ip).put("country",exit==null?JSONObject.NULL:exit.country)
            .put("node",route==null?JSONObject.NULL:route.node).put("expectedCountry",route==null||route.country.isEmpty()?JSONObject.NULL:route.country)
            .put("countryMatches",exit==null||route==null||route.country.isEmpty()?JSONObject.NULL:route.country.equals(exit.country)).put("checkedAt",exitAt==0?JSONObject.NULL:exitAt).put("source","Cloudflare trace");
        JSONObject t=new JSONObject().put("phase",trafficPhase).put("code",trafficCode).put("uploadRate",upRate==null?JSONObject.NULL:upRate).put("downloadRate",downRate==null?JSONObject.NULL:downRate)
            .put("sessionUpload",counterLimit?JSONObject.NULL:sessionUp).put("sessionDownload",counterLimit?JSONObject.NULL:sessionDown).put("totalUpload",persistent&&!counterLimit?totalUp:JSONObject.NULL).put("totalDownload",persistent&&!counterLimit?totalDown:JSONObject.NULL)
            .put("persistence",persistent?"available":"unavailable").put("checkedAt",trafficAt==0?JSONObject.NULL:trafficAt);
        return new JSONObject().put("exit",e).put("traffic",t);
    }catch(JSONException impossible){throw new IllegalStateException(impossible);}}
}
