package app.autopilot.android;

import org.json.*;
import org.junit.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class ProfileValidationTest {
    private Node node(String name)throws Exception{return new Node(new JSONObject().put("name",name).put("type","ss").put("server","192.0.2.9").put("port",443).put("cipher","aes-128-gcm").put("password","never-log-password"),"onevpn","MY");}
    @Test public void validProfileAndExistingSelectionArePreserved()throws Exception{
        Node a=node("a"),b=node("b");AtomicInteger calls=new AtomicInteger();
        var r=ProfileValidation.validate(List.of(a,b),b.id,(nodes,selected)->{calls.incrementAndGet();assertEquals("b",selected);return "";},(d,s)->fail("Valid profile must not produce a rejection"));
        assertTrue(r.accepted);assertSame(b,r.selected);assertEquals(List.of(a,b),r.nodes);assertEquals(1,calls.get());assertNull(r.lastDiagnostic);
    }
    @Test public void deterministicRemovalTracksShiftedNativeAndOriginalIndexes()throws Exception{
        Node a=node("secret-a"),b=node("secret-b"),c=node("secret-c"),d=node("secret-d");Set<String> invalid=Set.of(a.id,c.id);
        List<String> selections=new ArrayList<>();List<Integer> reported=new ArrayList<>();
        var r=ProfileValidation.validate(List.of(a,b,c,d),c.id,(nodes,selected)->{
            selections.add(selected);for(int i=0;i<nodes.size();i++)if(invalid.contains(nodes.get(i).id))return "proxy "+i+": unsupported cipher: never-log-password";return "";
        },(diag,skipped)->reported.add(diag.proxyIndex));
        assertTrue(r.accepted);assertEquals(List.of(b,d),r.nodes);assertNull(r.selected);
        assertEquals(List.of(0,1),reported);assertEquals(List.of("secret-c","secret-c","REJECT"),selections);
        assertEquals(0,r.skipped.get(0).sourceIndex);assertEquals(2,r.skipped.get(1).sourceIndex);
        for(var skipped:r.skipped){String safe=skipped.json().toString();assertFalse(safe.contains("secret-"));assertFalse(safe.contains("password"));assertFalse(safe.contains("192.0.2.9"));assertEquals("onevpn",skipped.provider);assertEquals("ss",skipped.diagnostic.proxyType);}
    }
    @Test public void globalFailureNeverRemovesNodes()throws Exception{
        Node a=node("a");AtomicInteger calls=new AtomicInteger();
        var r=ProfileValidation.validate(List.of(a),a.id,(nodes,selected)->{calls.incrementAndGet();return "DNS NameServer[0] format error: https://secret.example/token";},(diag,skipped)->assertEquals(CoreDiagnostics.Kind.DNS_INVALID,diag.kind));
        assertFalse(r.accepted);assertFalse(r.limitReached);assertEquals(List.of(a),r.nodes);assertTrue(r.skipped.isEmpty());assertEquals(1,calls.get());assertNull(r.selected);
    }
    @Test public void unverifiedIndexesAndWrappedFailuresNeverRemoveNodes()throws Exception{
        for(String error:List.of("proxy 7: unsupported cipher","proxy -1: unsupported cipher","remote text proxy 0: unsupported cipher","Parse config error: proxy 0: unsupported cipher","unrecognized failure")){
            Node a=node("a");AtomicInteger calls=new AtomicInteger();var r=ProfileValidation.validate(List.of(a),"",(nodes,selected)->{calls.incrementAndGet();return error;},(diag,skipped)->{});
            assertFalse(error,r.accepted);assertTrue(r.skipped.isEmpty());assertEquals(1,calls.get());assertEquals(List.of(a),r.nodes);
        }
    }
    @Test public void onlyExactEmptyNativeResponseIsAccepted()throws Exception{
        Node a=node("a");for(String response:Arrays.asList(null," ","\n","false","{}")){
            var r=ProfileValidation.validate(List.of(a),"",(nodes,selected)->response,(diag,skipped)->{});assertFalse(r.accepted);assertTrue(r.skipped.isEmpty());
        }
    }
    @Test public void attemptBudgetStopsWithoutSilentlyAcceptingRemainingBadNodes()throws Exception{
        ArrayList<Node> nodes=new ArrayList<>();for(int i=0;i<26;i++)nodes.add(node("node-"+i));AtomicInteger calls=new AtomicInteger();
        var r=ProfileValidation.validate(nodes,"",(list,selected)->{calls.incrementAndGet();return "proxy 0: unsupported cipher";},(diag,skipped)->{});
        assertFalse(r.accepted);assertTrue(r.limitReached);assertEquals(ProfileValidation.MAX_DROPS,r.skipped.size());assertEquals(ProfileValidation.MAX_DROPS+1,calls.get());assertEquals(1,r.nodes.size());
    }
    @Test public void allRejectedNodesProduceValidatedEmptyRejectProfileAndRetainCause()throws Exception{
        Node a=node("a"),b=node("b");AtomicInteger calls=new AtomicInteger();
        var r=ProfileValidation.validate(List.of(a,b),a.id,(nodes,selected)->{calls.incrementAndGet();if(nodes.isEmpty()){assertEquals("REJECT",selected);return "";}return "proxy 0: unsupported cipher";},(diag,skipped)->{});
        assertTrue(r.accepted);assertTrue(r.nodes.isEmpty());assertEquals(2,r.skipped.size());assertEquals(3,calls.get());assertNull(r.selected);
        assertEquals(CoreDiagnostics.Kind.PROXY_CIPHER,r.lastDiagnostic.kind);
    }
    @Test public void rejectionIsObservedBeforeLaterSetupTransportFailure()throws Exception{
        Node a=node("a"),b=node("b");AtomicInteger calls=new AtomicInteger();AtomicReference<CoreDiagnostics.Diagnostic> last=new AtomicReference<>();
        try{ProfileValidation.validate(List.of(a,b),"",(nodes,selected)->{if(calls.getAndIncrement()==0)return "proxy 0: unsupported cipher";throw new IOException("CORE_TIMEOUT");},(diag,skipped)->{last.set(diag);assertEquals(1,skipped.size());});fail("Setup transport error must propagate");}catch(IOException expected){assertEquals("CORE_TIMEOUT",expected.getMessage());}
        assertEquals(CoreDiagnostics.Kind.PROXY_CIPHER,last.get().kind);
    }
    @Test public void realValidationGateFitsGuardedRecoveryWithoutAttachingDirectDefaults()throws Exception{
        Node a=node("a");AtomicBoolean attached=new AtomicBoolean(true),nativeDirect=new AtomicBoolean(false);AtomicReference<CoreDiagnostics.Diagnostic> retained=new AtomicReference<>();
        boolean accepted=VpnEngine.reloadSafely(new VpnEngine.ProfileTransition(){
            public void pause(){attached.set(false);}
            public boolean install(boolean fallback)throws Exception{
                assertFalse(attached.get());if(fallback){nativeDirect.set(false);return true;}
                var result=ProfileValidation.validate(List.of(a),a.id,(nodes,selected)->{assertFalse(attached.get());nativeDirect.set(true);return "DNS NameServer[0] format error: secret-url";},(diag,skipped)->retained.set(diag));return result.accepted;
            }
            public void attach(){assertFalse("The native DIRECT default must never receive packets",nativeDirect.get());attached.set(true);}
        });
        assertFalse(accepted);assertTrue(attached.get());assertEquals(CoreDiagnostics.Kind.DNS_INVALID,retained.get().kind);
    }
}
