package app.autopilot.android;

import org.json.*;
import java.util.*;

/** Deterministic native semantic gate. No listener/TUN attachment occurs in this class. */
public final class ProfileValidation {
    public static final int MAX_DROPS=25;
    public interface Installer {String setup(List<Node> candidates,String selectedName)throws Exception;}
    public interface Observer {void rejected(CoreDiagnostics.Diagnostic diagnostic,List<Skipped> skipped);}
    public static final class Skipped {
        public final CoreDiagnostics.Diagnostic diagnostic;
        public final String provider;
        /** Original index is stable even after subsequent native indices shift. */
        public final int sourceIndex;
        private Skipped(Node node,int sourceIndex,CoreDiagnostics.Diagnostic diagnostic){
            this.diagnostic=diagnostic;this.sourceIndex=sourceIndex;
            this.provider=node.provider!=null&&Set.of("onevpn","octohide","import").contains(node.provider)?node.provider:"unknown";
        }
        public JSONObject json(){JSONObject json=diagnostic.json();try{json.put("provider",provider).put("sourceIndex",sourceIndex);}catch(JSONException ignored){}return json;}
    }
    public static final class Result {
        public final boolean accepted,limitReached;
        public final List<Node> nodes;
        public final Node selected;
        public final List<Skipped> skipped;
        public final CoreDiagnostics.Diagnostic lastDiagnostic;
        private Result(boolean accepted,boolean limitReached,List<Node> nodes,Node selected,List<Skipped> skipped,CoreDiagnostics.Diagnostic last){
            this.accepted=accepted;this.limitReached=limitReached;this.nodes=List.copyOf(nodes);this.selected=selected;
            this.skipped=List.copyOf(skipped);this.lastDiagnostic=last;
        }
    }
    private ProfileValidation(){}
    public static Result validate(List<Node> source,String selectedId,Installer installer,Observer observer)throws Exception{
        Objects.requireNonNull(source);Objects.requireNonNull(installer);Objects.requireNonNull(observer);
        ArrayList<Node> candidates=new ArrayList<>(source);ArrayList<Integer> indexes=new ArrayList<>();
        for(int i=0;i<source.size();i++)indexes.add(i);
        ArrayList<Skipped> skipped=new ArrayList<>();CoreDiagnostics.Diagnostic last=null;
        while(true){
            Node selected=find(candidates,selectedId);
            String rejection=installer.setup(Collections.unmodifiableList(new ArrayList<>(candidates)),selected==null?"REJECT":selected.name);
            if("".equals(rejection))return new Result(true,false,candidates,selected,skipped,last);
            ArrayList<String> types=new ArrayList<>();for(Node node:candidates)types.add(node.config.optString("type",""));
            last=CoreDiagnostics.classify(rejection,types);
            if(!last.canIsolateProxy()){
                observer.rejected(last,List.copyOf(skipped));return new Result(false,false,candidates,null,skipped,last);
            }
            if(skipped.size()>=MAX_DROPS){
                observer.rejected(last,List.copyOf(skipped));return new Result(false,true,candidates,null,skipped,last);
            }
            int index=last.proxyIndex;Node bad=candidates.remove(index);int original=indexes.remove(index);
            skipped.add(new Skipped(bad,original,last));observer.rejected(last,List.copyOf(skipped));
        }
    }
    private static Node find(List<Node> nodes,String id){if(id==null||id.isEmpty())return null;for(Node node:nodes)if(node.id.equals(id))return node;return null;}
}
