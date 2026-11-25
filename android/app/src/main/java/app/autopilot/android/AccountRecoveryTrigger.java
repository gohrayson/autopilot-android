package app.autopilot.android;

/** Serialized engine evidence only. "degraded" means a bounded replacement check
 * already failed; provider requests remain off the controller and rate limited. */
final class AccountRecoveryTrigger {
    private final AccountRecoveryPolicy policy;
    private long degradedAt=-1, nextSignalAt;
    private volatile long pendingUntil;
    AccountRecoveryTrigger(){this(new AccountRecoveryPolicy());}
    AccountRecoveryTrigger(AccountRecoveryPolicy policy){this.policy=policy;}
    String observe(String signal,long now){
        if(now<0)throw new IllegalArgumentException("Invalid recovery time");
        if(!"degraded".equals(signal)&&!"no-replacement".equals(signal)){
            if(!java.util.Set.of("healthy","paused","stopped").contains(signal))throw new IllegalArgumentException("Invalid recovery signal");
            degradedAt=-1;pendingUntil=0;return null;
        }
        if("degraded".equals(signal)){
            if(degradedAt<0||now<degradedAt)degradedAt=now;
            pendingUntil=now-degradedAt<policy.persistentDegradedMs?degradedAt+policy.persistentDegradedMs:0;
            if(now-degradedAt<policy.persistentDegradedMs)return null;
        }
        if(now<nextSignalAt)return null;
        nextSignalAt=now+policy.refreshMinGapMs;
        pendingUntil=0;
        return "degraded".equals(signal)?"persistent-service-degraded":"no-verified-replacement";
    }
    long pendingUntil(){return pendingUntil;}
}
