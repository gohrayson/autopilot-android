# Android 0.1.1 build and delivery receipt

Observed on 2026-10-21. The user's Android 16 / ARM64 report showed OneVPN refresh success and `CORE_CONFIG_REJECTED` followed by `STOPPED`, before any TUN start. Android 0.1.0 discarded the native rejection text. The exact offending setting cannot be recovered from that report. The original phone failure remains unverified as fixed.

This update validates an empty REJECT baseline, isolates only nodes identified by a trusted native numeric proxy index, preserves safe rejection categories/stages, and keeps the retained Android interface blocking during rejected reloads. Arbitrary errors cannot authorize removal. The original rejection survives successful empty-profile recovery. Octohide errors from its actual APK catalog retain their provider code without exposing arbitrary upstream strings; export cooldowns remain in effect.

Matching native parser and production validation-loop runtime evidence: [ANDROID_CONFIG_FIX_RECEIPTS.md](ANDROID_CONFIG_FIX_RECEIPTS.md). Provider diagnostic fixture evidence: [OCTO_ERROR_FIX_RECEIPTS.md](OCTO_ERROR_FIX_RECEIPTS.md). These host and fixture checks do not establish Android JNI/TUN execution on the phone.

## Build

From `D:\Github\onevpn-autopilot`:

```powershell
$ErrorActionPreference='Stop'
& android/tools/build.ps1 -Configuration Release 2>&1 | Tee-Object -FilePath 'D:\Github\onevpn-autopilot\.data\android-v011-build.log'
```

The script invokes `:app:testDebugUnitTest :app:lintDebug :app:assembleRelease` explicitly. The log path is absolute because the build script changes its working directory while invoking Gradle.

Verbatim final build tail:

```text
BUILD SUCCESSFUL in 5s
79 actionable tasks: 2 executed, 77 up-to-date
APK: D:\Github\onevpn-autopilot\dist\android\Autopilot-Android-0.1.1.apk
SHA256: 30c42e1ecd636e105b69f50d3c137b2d7304ca776103f020bfbb049deb441735
```

## JUnit runner results and source inventory

```powershell
$total=0
$suiteFiles=@(Get-ChildItem android/app/build/test-results/testDebugUnitTest -Filter '*.xml')
foreach ($report in $suiteFiles) {
    [xml]$suiteXml=Get-Content -LiteralPath $report.FullName
    if ([int]$suiteXml.testsuite.failures -ne 0 -or [int]$suiteXml.testsuite.errors -ne 0 -or [int]$suiteXml.testsuite.skipped -ne 0) { throw 'JVM suite did not pass completely' }
    $total += [int]$suiteXml.testsuite.tests
    '{0}: tests={1} failures={2} errors={3} skipped={4}' -f $suiteXml.testsuite.name,$suiteXml.testsuite.tests,$suiteXml.testsuite.failures,$suiteXml.testsuite.errors,$suiteXml.testsuite.skipped
}
$declared=@(rg -o '@Test\b' android/app/src/test/java).Count
if ($total -ne $declared) { throw "Test inventory mismatch: declared=$declared executed=$total" }
"JVM_INVENTORY_PASS declared=$declared executed=$total suites=$($suiteFiles.Count)"
```

Verbatim runner XML summary:

```text
app.autopilot.android.CoreDiagnosticsTest: tests=11 failures=0 errors=0 skipped=0
app.autopilot.android.EngineConfigurationTest: tests=3 failures=0 errors=0 skipped=0
app.autopilot.android.ProfileTransitionTest: tests=4 failures=0 errors=0 skipped=0
app.autopilot.android.ProfileValidationTest: tests=9 failures=0 errors=0 skipped=0
app.autopilot.android.ProviderRepositoryTest: tests=23 failures=0 errors=0 skipped=0
app.autopilot.android.SelectionPolicyTest: tests=6 failures=0 errors=0 skipped=0
app.autopilot.android.ServiceChecksTest: tests=4 failures=0 errors=0 skipped=0
JVM_INVENTORY_PASS declared=60 executed=60 suites=7
```

One JVM runner (JUnit4). New cases cover shifted native indices, rejected active selection, strict setup success, global rejection, bounded removal, empty-profile recovery, safe diagnostic persistence, secret exclusion, and recognized provider errors/cooldowns.

```powershell
Get-Content android/app/build/reports/lint-results-debug.txt | Select-Object -Last 1
```

```text
0 errors, 4 warnings
```

Warnings: pinned Gradle/test dependency versions and concatenated English node-summary text. No lint baseline or check suppression was added.

## Signed APK

```powershell
& android/tools/verify-apk.ps1
```

Relevant verbatim verification output:

```text
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Signer #1 certificate SHA-256 digest: 6c6831265f4d5593b499b2bfc1bbcaf93f3eb4d143f49fef192d811d4b6060ab
package: name='app.autopilot.android' versionCode='2' versionName='0.1.1' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
Verified APK contains no account vault, signing key, diagnostics log, or probe runtime fixture.
Verified APK ZIP alignment for 16 KB pages.
```

The signer certificate matches Android 0.1.0 (see `ANDROID_RECEIPTS.md`), the application ID is unchanged and versionCode increases to 2. Install as an update over the existing app, without uninstalling or clearing storage. Static ARM64/x86_64 ELF LOAD alignment and actual RELRO page protection checks also pass, as recorded in `.data/android-v011-verification.log`.

Delivered APK: `C:\Users\rayso\Downloads\Autopilot-Android-0.1.1.apk`, 47,737,490 bytes. Its SHA-256 equals the built artifact: `30c42e1ecd636e105b69f50d3c137b2d7304ca776103f020bfbb049deb441735`.

## Remaining device verification

The user must retry **Start VPN** on HyperOS after updating. If rejected, **⋯ → Diagnostics → Copy** now includes the baseline result, precise fixed error code, baseline/profile/recovery stage, and safe skipped-node metadata. Do not require a provider refresh or new sign-in merely to retry existing cached nodes. Android tunnel traffic and live Octohide export success remain unverified; the host's emulator cannot start, as recorded in the original receipt.

## Windows connection observation

No Windows VPN, proxy, route or installed application control was executed by this Android work. The native parser probes' local before/after guards passed.

The broader build-period `protectedState()` comparison did **not** pass: the existing desktop mihomo PID changed from 24640 to 12280, created at 20:11:45. Autopilot/OneVPN helper process identities and system proxy settings remained the same. Read-only desktop logs record country-priority changes at 20:11:36, `mihomo exited` / `Stopped` at 20:11:40, and `mihomo started` at 20:11:46. The actor/cause of that desktop stop/start is not established. It must not be described as an unchanged process identity throughout this entire turn. The final snapshot still has the proxy enabled and listening at `127.0.0.1:7890`.

Private read-only snapshots: `.data/android-v011-windows-before.json` and `.data/android-v011-windows-after.json`. These observations do not authorize a desktop restart, and none was attempted.
