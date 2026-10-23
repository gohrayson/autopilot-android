# Android responsive discovery receipts

2026-10-23. Runtime source frozen after the selector-failure fallback correction. No phone, live desktop VPN, provider account, installer, signing key or release was touched by this worker. Physical traffic and native mixed-pool acceptance belong to the separate integration receipt; JVM fixtures do not prove device traffic.

## Exact full verification

Working directory: `D:/Github/onevpn-autopilot`. PowerShell invocation:

```powershell
. .data/android-tools/env.ps1
gradle -p android :app:testDebugUnitTest :app:lintDebug --console=plain *> .data/android-discovery-jvm-lint.log
$discoveryExit=$LASTEXITCODE
Get-Content .data/android-discovery-jvm-lint.log -Tail 35
if($discoveryExit -eq 0){python android/tools/check-unit-receipts.py android; $discoveryExit=$LASTEXITCODE}
exit $discoveryExit
```

Verbatim runner tail:

```text

> Task :app:compileDebugJavaWithJavac

Note: D:\Github\onevpn-autopilot\android\app\src\main\java\app\autopilot\android\UpdateManager.java uses or overrides a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
> Task :app:preDebugUnitTestBuild
> Task :app:javaPreCompileDebugUnitTest UP-TO-DATE
> Task :app:processDebugJavaRes NO-SOURCE
> Task :app:processDebugUnitTestJavaRes UP-TO-DATE
> Task :app:preDebugAndroidTestBuild SKIPPED
> Task :app:generateDebugAndroidTestResValues UP-TO-DATE
> Task :app:extractProguardFiles UP-TO-DATE
> Task :app:bundleDebugClassesToCompileJar
> Task :app:bundleDebugClassesToRuntimeJar
> Task :app:generateDebugLintReportModel
> Task :app:compileDebugUnitTestJavaWithJavac
> Task :app:testDebugUnitTest
> Task :app:generateDebugAndroidTestLintModel
> Task :app:generateDebugUnitTestLintModel
> Task :app:lintAnalyzeDebugAndroidTest
> Task :app:lintAnalyzeDebugUnitTest
> Task :app:lintAnalyzeDebug
> Task :app:lintReportDebug UP-TO-DATE
> Task :app:lintDebug

[Incubating] Problems report is available at: file:///D:/Github/onevpn-autopilot/android/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.13/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 20s
33 actionable tasks: 13 executed, 20 up-to-date
ANDROID_UNIT_XML_RECEIPT tests=199 expected=199 failures=0 errors=0 skipped=0 missing=0 duplicates=0 unexpected=0
```

The source inventory contains 24 JVM test classes. The audit enumerates every `@Test` declaration and every runner XML testcase, rejecting missing, duplicated, unexpected, failed, errored or skipped methods. Focused coordinator XML from this same full run:

```text
ANDROID_DISCOVERY_XML_RECEIPT tests=19 failures=0 errors=0 skipped=0
```

## Meaningful pipeline checks

`DiscoveryCoordinatorTest` drives the actual production coordinator with deterministic asynchronous native/HTTPS/service completions and the real `SelectionPolicy.Health` ledger. Cases cover a healthy result ahead of slow input order; a healthy node at the tail of a 52-node pool before the first 60-second budget expires; active watchdog work during discovery; a retained route across more than 90 seconds with fresh HTTPS and required-service proof; normal startup versus explicit forced retest; ordinary threshold comparison at a partial budget boundary; one forced final comparison; resume after a budget cuts final proof; fair cursors; coalesced repeated requests; cancellation of same-ID profile/priority results; late service cancellation; failed transport/services; selector rejection followed by a different verified winner; measured HTTPS fallback while native RTT remains failed; held/paused routes; stop; and nonoverlapping forced passes in a small pool.

The coordinator has at most eight native work records, two separately owned HTTPS fallback lanes, one separately owned proof lane and one pending controller pump. The engine adds a global native semaphore plus a hard-expiry timer so direct selected-node checks share the same native ceiling. Canceled generation work keeps its slots until settlement; no late result can mutate the new generation. Listener UI posts coalesce independently of immediate controller snapshots/updater guards.

Startup and provider profile reload use one discovery pass. Only public Re-test requests three passes. Partial slices may make one normal comparison, under existing dwell/improvement/country rules; force is applied only at the logical forced scan's final comparison. A service operation started before a soft budget deadline may finish and promote a freshly proved route after that deadline if the generation remains valid.

`discovery.lastAppliedRetestId` advances only after a queued public retest reaches `discovery.request` on the real controller. Diagnostics contain fixed discovery codes and numeric counters; they do not export configurations, credentials, endpoint IPs or arbitrary exception text. The launcher `openDiagnostics` boolean opens the existing safe report dialog and consumes the extra; repeated reports replace the prior dialog.

## Limitations and corrected verification failures

An initial compile found Java lambda capture errors; they were corrected before successful runs. Lint then identified `CompletableFuture.failedFuture` as API 31 while minSdk is 26; runtime calls use a compatible local future helper. A final-proof deadline regression exposed a lost attempted-candidate marker, corrected before this receipt. Root review exposed failed selector winners hiding fallback candidates; the final source marks only promotion failures for cooldown and ranks the next eligible unattempted candidate while preserving the selected route as the ranking anchor. Existing SDK XML/deprecation warnings remain; lint passed with no errors.

## Frozen source identities

```json
{
  "android/app/src/main/java/app/autopilot/android/DiscoveryCoordinator.java": "115359182930c8d95d2cf1bba548d2e4ff0eda45acce3805b7a1d26af99c60b0",
  "android/app/src/main/java/app/autopilot/android/VpnEngine.java": "b30f1ab9c316dcdcd89895bb36ca909bc10160b1058699879a25733c94935621",
  "android/app/src/main/java/app/autopilot/android/SelectionPolicy.java": "cffdcc9e814abe103074817ee6c9d1021e2fb71fc618fcc6907801b948e621b4",
  "android/app/src/main/java/app/autopilot/android/AutopilotApp.java": "e39c1219af21d357466013c932b8cca4c2cd8f219a4286cc76e7e078949418a7",
  "android/app/src/main/java/app/autopilot/android/MainActivity.java": "d3c0d3ac7bbedb13609e783a34bfb9576693382c9b962db048ed22e7a0bdc08d",
  "android/app/src/test/java/app/autopilot/android/DiscoveryCoordinatorTest.java": "f6efc8d2fcf6ddc35a9b93b1dbe2852440dd0d4955438e8786f5d43c0fc3fc8e",
  "android/app/src/test/java/app/autopilot/android/SelectionPolicyTest.java": "f9eee315d55257103479cc18a0b21c0c4893cc8f8631a09110cfe09cd4bd4dc1"
}
```
