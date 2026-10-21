# Android engine policy, service parsers and cancellation

Run from `D:\Github\onevpn-autopilot` in PowerShell:

```powershell
.\android\app\src\test\run-engine-tests.ps1
```

Observed 2026-10-21 (Java 17.0.20.1, JUnit 4.13.2 bundled with Gradle 8.13):

```text
JUnit version 4.13.2
..........
Time: 0.108

OK (10 tests)
```

The cancellation test accepts the real three HTTPS CONNECT requests on an isolated loopback proxy, deliberately stalls their responses, interrupts the caller, and requires completion within two seconds. No external network requests, Android routes or Windows VPN settings are used. These are JVM checks, not evidence of a device-wide Android tunnel; the APK runtime probe is recorded separately by the release builder.

## Full Android project compilation and JVM suite

Run from the same repository root:

```powershell
. .\.data\android-tools\env.ps1
gradle -p android testDebugUnitTest compileDebugJavaWithJavac --console=plain --no-daemon
Get-ChildItem android/app/build/test-results/testDebugUnitTest/TEST-*.xml | ForEach-Object { [xml]$test = Get-Content -LiteralPath $_.FullName; $suite = $test.testsuite; [pscustomobject]@{Suite=$suite.name;Tests=$suite.tests;Failures=$suite.failures;Errors=$suite.errors;Skipped=$suite.skipped} } | Format-Table -AutoSize
```

Observed 2026-10-21, final runner tail:

```text
> Task :app:compileDebugJavaWithJavac
> Task :app:preDebugUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompileDebugUnitTest UP-TO-DATE
> Task :app:processDebugJavaRes NO-SOURCE
> Task :app:processDebugUnitTestJavaRes NO-SOURCE
> Task :app:bundleDebugClassesToCompileJar
> Task :app:bundleDebugClassesToRuntimeJar
> Task :app:compileDebugUnitTestJavaWithJavac
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 7s
39 actionable tasks: 5 executed, 34 up-to-date
```

Runner XML summary (30 tests, no failures/errors/skips):

```text
Suite                                         Tests Failures Errors Skipped
-----                                         ----- -------- ------ -------
app.autopilot.android.EngineConfigurationTest 3     0        0      0
app.autopilot.android.ProviderRepositoryTest  17    0        0      0
app.autopilot.android.SelectionPolicyTest     6     0        0      0
app.autopilot.android.ServiceChecksTest       4     0        0      0
```

The initial compile was blocked by the provider's then-in-flight `refreshFailedNodes` method; the second was blocked by checked `JSONException` in provider test fixtures. Both were resolved in the provider worker's owned files before the successful invocation above. SDK XML-version and Gradle deprecation warnings remain; the Android compiler and test tasks pass.

## Guarded native profile replacement

The pinned native `common.go` applies a default profile after semantic parse failure, and its `validateConfig` action does not perform semantic validation. Production reload now detaches/stops the native TUN consumer before calling `quickSetup`; Java retains an independent `ParcelFileDescriptor` for the Android VPN interface. The native consumer receives only duplicates. A rejected profile is replaced with a known empty REJECT configuration, and the selector is verified before native attachment. If replacement or attachment fails, Java keeps the interface/routes and traffic stays paused until retry or explicit Stop. Cleanup closes the Java guard after stopping the core.

The `ProfileTransitionTest` fixture deliberately models the dangerous native behavior (invalid semantic input installs DIRECT). It checks successful replacement, rejected-provider recovery to REJECT, failure of the safe fallback, and transport failure. It verifies no native consumer is attached while DIRECT defaults are installed. This is a transition/JVM proof; actual Android PFD routing and JNI behavior still need the physical-device/runtime probe.

Rerun the same full-project command and XML summary command above. Latest observed runner tail, 2026-10-21:

```text
> Task :app:compileDebugJavaWithJavac
> Task :app:preDebugUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompileDebugUnitTest UP-TO-DATE
> Task :app:processDebugJavaRes NO-SOURCE
> Task :app:processDebugUnitTestJavaRes NO-SOURCE
> Task :app:bundleDebugClassesToCompileJar
> Task :app:bundleDebugClassesToRuntimeJar
> Task :app:compileDebugUnitTestJavaWithJavac UP-TO-DATE
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 11s
39 actionable tasks: 4 executed, 35 up-to-date
```

Latest XML summary (37 tests, no failures/errors/skips):

```text
Suite                                         Tests Failures Errors Skipped
-----                                         ----- -------- ------ -------
app.autopilot.android.EngineConfigurationTest 3     0        0      0
app.autopilot.android.ProfileTransitionTest   4     0        0      0
app.autopilot.android.ProviderRepositoryTest  20    0        0      0
app.autopilot.android.SelectionPolicyTest     6     0        0      0
app.autopilot.android.ServiceChecksTest       4     0        0      0
```
