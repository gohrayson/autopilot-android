# Android independent connection health receipts

22 October 2026. No physical phone was attached for this change. All local checks below are JVM tests or Android-test compilation. The new real Android CONNECT fixture is selected by `tools/ci-native-check.sh`; its runtime result must be recorded by the parent release verification before publication.

## Local unit suite and instrumentation compilation

From `D:\Github\onevpn-autopilot` in PowerShell:

```powershell
. .data/android-tools/env.ps1
gradle -p android --no-daemon --console=plain :app:testDebugUnitTest :app:compileDebugAndroidTestJavaWithJavac 2>&1 | Tee-Object -FilePath D:\Github\onevpn-autopilot\.data\android-smart-health-final-focused.log
exit $LASTEXITCODE
```

Runner tail:

```text
> Task :app:testDebugUnitTest
> Task :app:compileDebugAndroidTestJavaWithJavac

BUILD SUCCESSFUL in 22s
31 actionable tasks: 6 executed, 25 up-to-date
```

The SDK XML-version warning and existing Gradle/UpdateManager deprecation warnings remain. A cancellation-test synchronization race was found by the parent's broader build: its fake opener signaled readiness before registration in the active-request set. The test now waits at response-read entry, after registration. This run includes that correction.

## Exact focused test catalog audit

The catalog includes existing selection-policy methods as well as the new health methods; this is not a claim that every listed method is new.

```powershell
$catalog=@('app.autopilot.android.ConnectionWatchdogTest','app.autopilot.android.ReachabilityChecksTest','app.autopilot.android.SelectionPolicyTest')
$cases=@()
foreach($file in Get-ChildItem android/app/build/test-results/testDebugUnitTest/TEST-*.xml) {
    [xml]$report=Get-Content -LiteralPath $file.FullName
    if($catalog -contains $report.testsuite.name) {
        $cases+=@($report.testsuite.testcase)
        Write-Output ($report.testsuite.name+' tests='+$report.testsuite.tests+' failures='+$report.testsuite.failures+' errors='+$report.testsuite.errors+' skipped='+$report.testsuite.skipped)
    }
}
$failures=@($cases|Where-Object {$_.failure -or $_.error -or $_.skipped})
Write-Output ('ANDROID_SMART_HEALTH_UNIT_RECEIPT tests='+$cases.Count+' failures='+$failures.Count)
if($cases.Count -ne 20 -or $failures.Count -ne 0){exit 1}
```

Verbatim output:

```text
app.autopilot.android.ConnectionWatchdogTest tests=6 failures=0 errors=0 skipped=0
app.autopilot.android.ReachabilityChecksTest tests=4 failures=0 errors=0 skipped=0
app.autopilot.android.SelectionPolicyTest tests=10 failures=0 errors=0 skipped=0
ANDROID_SMART_HEALTH_UNIT_RECEIPT tests=20 failures=0
```

Coverage includes: Google responding while both application origins fail; confirmation on a separate executor while discovery is blocked; transient and isolated endpoint failures; comparative single-origin handling; snapshot identity changes; one hung first origin not erasing completed successes; explicit loopback routing; interruption cleanup; cooldown that Google success cannot clear; fallback selection; measured HTTPS timing when Google alone fails; and retention of an already selected transport-healthy route when service eligibility expires. The JVM transport uses controlled HTTPS connection objects; it does not claim a completed platform TLS handshake.

## Runtime gate added, not locally executed

On the disposable hosted Linux emulator, the existing command remains:

```bash
bash android/tools/ci-native-check.sh android
```

The selected catalog now also requires `ReachabilityRuntimeTest.independentWatchdogBoundsRealConnectsWhileControllerIsBusy`. It uses actual Android HTTPS CONNECT requests through a local nonresponding proxy, confirms broad failure in two bounded rounds while a controller job is blocked, verifies all request sockets close, and separately verifies interruption cancellation. Its fixed marker is `ANDROID_HEALTH_TRANSPORT_ACCEPTED`. It does not complete TLS against the nonresponding fixture and does not override certificate or hostname verification. Existing public-feed trusted-TLS and separate-UID native TUN gates remain unchanged.

Physical-device behavior and production Internet reachability still require the user's installed-device observation. Per-node mixed-listener checks continue to bypass Autopilot's own excluded UID; they are not proof of every other app's TUN path.
