package app.autopilot.android;

import java.util.List;
import java.util.function.Consumer;

/** Selected-node evidence only. The provider's existing serialized scheduler owns
 * request pacing, failed-region persistence and all retry/cooldown decisions. */
final class SelectedProviderRecovery {
    private final Consumer<List<String>> enqueue;
    private final Runnable onError;
    private ConnectionWatchdog.Snapshot lastRequested;
    SelectedProviderRecovery(Consumer<List<String>> enqueue,Runnable onError){this.enqueue=enqueue;this.onError=onError;}

    /** Controller only. A partial origin incident needs a freshly proved replacement
     * before it can be attributed to this node rather than the application service. */
    boolean confirmed(ConnectionWatchdog.Snapshot snapshot,Node failed,ConnectionWatchdog.Code code,boolean comparativeReplacementVerified){
        if(snapshot==null||failed==null||!"octohide".equals(failed.provider)||!snapshot.nodeId.equals(failed.id))return false;
        if(code!=ConnectionWatchdog.Code.CONFIRMED_FAILED&&!(code==ConnectionWatchdog.Code.CONFIRMED_PARTIAL_FAILED&&comparativeReplacementVerified))return false;
        if(snapshot.equals(lastRequested))return false;
        lastRequested=snapshot;
        try{enqueue.accept(List.of(failed.name));return true;}
        catch(RuntimeException failure){try{onError.run();}catch(RuntimeException ignored){}return false;}
    }
}
