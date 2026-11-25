package app.autopilot.android;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.*;

/** One drain for periodic checks, catalog recovery and targeted failed exports.
 * Thirty-second ticks never accumulate a queue of stale provider HTTP jobs. */
final class ProviderWorkQueue {
    interface Work {
        void due(BooleanSupplier active);
        void catalogs(String reason,BooleanSupplier active);
        void failed(List<String> names,BooleanSupplier active);
    }
    private final Executor executor;private final Supplier<Object> owner;private final Predicate<Object> allowed,recoveryAllowed;private final Work work;private final Runnable changed,onError;
    private boolean scheduled,due;private Object queuedOwner;private String reason;private final LinkedHashSet<String> failed=new LinkedHashSet<>();
    ProviderWorkQueue(Executor executor,Supplier<Object> owner,Predicate<Object> allowed,Predicate<Object> recoveryAllowed,Work work,Runnable changed,Runnable onError){this.executor=executor;this.owner=owner;this.allowed=allowed;this.recoveryAllowed=recoveryAllowed;this.work=work;this.changed=changed;this.onError=onError;}
    void due(){request(true,null,List.of());}
    void catalogs(String reason){new AccountRecoveryPolicy().reason(reason);request(false,reason,List.of());}
    void failed(List<String> names){request(false,null,names);}
    private synchronized void request(boolean periodic,String recovery,List<String> names){
        Object current=owner.get();if(current==null||!allowed.test(current))return;
        if(recovery!=null&&!recoveryAllowed.test(current))return;
        if(queuedOwner!=current){due=false;reason=null;failed.clear();queuedOwner=current;}
        due|=periodic;if(recovery!=null)reason=recovery;
        for(String name:names)if(name!=null&&name.length()<=1024&&failed.size()<2000)failed.add(name);
        if(scheduled)return;scheduled=true;
        try{executor.execute(this::drain);}catch(RuntimeException rejected){scheduled=false;due=false;reason=null;failed.clear();throw rejected;}
    }
    private void drain(){
        for(;;){
            Object captured;boolean periodic;String recovery;List<String> names;
            synchronized(this){
                if(!due&&reason==null&&failed.isEmpty()){scheduled=false;queuedOwner=null;return;}
                captured=queuedOwner;periodic=due;recovery=reason;names=List.copyOf(failed);due=false;reason=null;failed.clear();
            }
            BooleanSupplier active=()->owner.get()==captured&&allowed.test(captured);
            try{
                if(!active.getAsBoolean())continue;
                // Repair a positively failed export before metadata refresh can
                // consume Octohide's minimum request window.
                if(!names.isEmpty()&&active.getAsBoolean())work.failed(names,active);
                BooleanSupplier recoveryActive=()->active.getAsBoolean()&&recoveryAllowed.test(captured);
                if(recovery!=null&&recoveryActive.getAsBoolean())work.catalogs(recovery,recoveryActive);
                if(periodic&&active.getAsBoolean())work.due(active);
            }catch(RuntimeException failure){try{onError.run();}catch(RuntimeException ignored){}}
            finally{try{changed.run();}catch(RuntimeException ignored){}}
        }
    }
}
