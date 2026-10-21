package app.autopilot.android;

import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

/** Models the native defect: rejected semantic configs actively install a DIRECT fallback. */
public class ProfileTransitionTest {
    private static final class NativeFixture implements VpnEngine.ProfileTransition{
        final List<String> calls=new ArrayList<>();
        final boolean mainValid,fallbackValid;
        boolean routeGuard=true,attached=true,directFallback;
        int attachedDefaults;
        NativeFixture(boolean mainValid,boolean fallbackValid){this.mainValid=mainValid;this.fallbackValid=fallbackValid;}
        public void pause(){calls.add("pause");attached=false;assertTrue("Android routes stay installed during native replacement",routeGuard);}
        public boolean install(boolean fallback){
            calls.add(fallback?"install-reject":"install-provider");assertFalse("Never apply a profile to a live packet consumer",attached);
            boolean valid=fallback?fallbackValid:mainValid;
            directFallback=!valid; // Exact dangerous native behavior, rather than an inert validation failure.
            assertTrue(routeGuard);return valid;
        }
        public void attach(){calls.add("attach");if(directFallback)attachedDefaults++;attached=true;assertTrue(routeGuard);}
    }
    @Test public void validProviderAttachesOnlyAfterSuccessfulSemanticSetup()throws Exception{
        NativeFixture nativeCore=new NativeFixture(true,true);
        assertTrue(VpnEngine.reloadSafely(nativeCore));
        assertEquals(List.of("pause","install-provider","attach"),nativeCore.calls);
        assertEquals(0,nativeCore.attachedDefaults);assertTrue(nativeCore.routeGuard);
    }
    @Test public void invalidProviderNeverExposesNativeDirectFallback()throws Exception{
        NativeFixture nativeCore=new NativeFixture(false,true);
        assertFalse(VpnEngine.reloadSafely(nativeCore));
        assertEquals(List.of("pause","install-provider","pause","install-reject","attach"),nativeCore.calls);
        assertFalse(nativeCore.directFallback);assertEquals(0,nativeCore.attachedDefaults);assertTrue(nativeCore.attached);
    }
    @Test public void evenRejectedSafeProfileLeavesRoutesGuardedWithoutNativeAttachment()throws Exception{
        NativeFixture nativeCore=new NativeFixture(false,false);
        try{VpnEngine.reloadSafely(nativeCore);fail("Rejected safe profile must not attach");}catch(IOException ex){assertEquals("CORE_CONFIG_REJECTED",ex.getMessage());}
        assertEquals(List.of("pause","install-provider","pause","install-reject"),nativeCore.calls);
        assertFalse(nativeCore.attached);assertTrue(nativeCore.routeGuard);assertEquals(0,nativeCore.attachedDefaults);
    }
    @Test public void setupTransportFailureNeverAttachesOrReleasesTheInterface()throws Exception{
        NativeFixture nativeCore=new NativeFixture(true,true);
        try{VpnEngine.reloadSafely(new VpnEngine.ProfileTransition(){
            public void pause(){nativeCore.pause();}
            public boolean install(boolean safe)throws Exception{throw new IOException("CORE_TIMEOUT");}
            public void attach(){nativeCore.attach();}
        });fail("Transport failure must propagate");}catch(IOException ex){assertEquals("CORE_TIMEOUT",ex.getMessage());}
        assertEquals(List.of("pause"),nativeCore.calls);assertFalse(nativeCore.attached);assertTrue(nativeCore.routeGuard);
    }
}
