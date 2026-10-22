# Android updater UI/controller compile receipt

Date: 2026-10-22. Workspace: `D:\Github\onevpn-autopilot`.

The controller now publishes its operation/phase immediately when it accepts a tap. The Activity renders an indeterminate indicator while checking, verifying, restoring cached files, or preparing an installer, and explains the disabled Download/Install actions. Timeout, HTTP, ordinary network, unavailable VPN route, and VPN-proxy failure messages are distinct and expose fixed diagnostic codes. Diagnostics include operation start/elapsed time and a sanitized feed hostname, excluding the feed path/query and exception text.

Automatic checks skip a verified ready release before acquiring updater single-flight ownership. Startup restoration still validates the local APK; if it is ready, startup does not chain a network check. Explicit manual checks remain available. A failed manual check retains the ready release and allows Install after the check finishes. This change does not stop a VPN or change automatic installation's existing VPN-idle requirement.

The two new `UpdateStatusUiTest` instrumentation methods exercise a real Activity Check click with the real manager/downloader and controlled HTTPS responses, plus a controller-seeded ready-state scheduling case. The ready-state case does not claim APK verification or installation coverage. Public construction still uses the fixed release identity; the package-private injected-transport constructor lets the account-free debug instrumentation use its own package identity without changing APK signature verification.

## Runnable compile check

PowerShell invocation:

```powershell
$ErrorActionPreference='Stop'
. .data/android-tools/env.ps1
gradle -p android --no-daemon --console=plain :app:compileDebugJavaWithJavac :app:compileDebugAndroidTestJavaWithJavac 2>&1 | Tee-Object -FilePath .data/android-update-ui-compile.log
exit $LASTEXITCODE
```

Runner tail, verbatim:

```text
> Task :app:compileDebugAndroidTestJavaWithJavac

[Incubating] Problems report is available at: file:///D:/Github/onevpn-autopilot/android/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.13/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 7s
27 actionable tasks: 3 executed, 24 up-to-date
```

This receipt verifies compilation only. Instrumentation has not been executed by this implementation agent; the orchestrating agent owns the combined build/lint/unit/instrumentation run and its runtime receipts. No phone, provider account, desktop VPN, proxy settings, or network routes were changed by this work.

## Cache identity and stale-ready recovery follow-up

The download layer returns a canonical cache file. The old manager cleanup compared that file against directory entries with `File.equals`, which compares path spellings. Different spellings of the same canonical file therefore allowed cleanup to delete its keep file. This is a confirmed source defect and a reproduced filesystem condition; it is not proof that aliases caused the reported phone file loss.

`UpdateCache` now compares canonical identities within the owned cache root and fails closed for an external keep file. Download completion publishes `ready` only after cleanup retains a file with the expected size. Diagnostics report local file presence/actual size/expected size separately from transfer progress. Pre-install missing or invalid files clear ready, progress, and stale owned install state while keeping the public release manifest, so Download becomes available immediately. The recovery message and fixed code appear next to the update buttons.

The new Android runtime fixtures are `UpdateCacheRuntimeTest.canonicalAliasCleanupKeepsCompleteApkAndLeavesResumeFiles` and `UpdateStatusUiTest.missingReadyApkInvalidatesCacheAndEnablesDownload`. The first runs the actual downloader/checksum/atomic file publication and cache cleanup on real Android files using different path spellings. It does not claim APK signer validation. The second seeds a ready controller with no local file, taps the real Install button, and asserts that verification rejects the missing file before any PackageInstaller session is created, clears progress, and enables Download. It temporarily permits unknown-source installation only for the fresh account-free debug fixture and restores the prior app-op mode.

Runnable PowerShell invocation:

```powershell
$ErrorActionPreference='Stop'
. .data/android-tools/env.ps1
gradle -p android --no-daemon --console=plain :app:compileDebugJavaWithJavac :app:compileDebugAndroidTestJavaWithJavac :app:testDebugUnitTest --tests app.autopilot.android.UpdateCacheTest 2>&1 | Tee-Object -FilePath .data/android-update-cache-compile-test.log
exit $LASTEXITCODE
```

Runner tail, verbatim:

```text
> Task :app:processDebugUnitTestJavaRes NO-SOURCE
> Task :app:testDebugUnitTest

[Incubating] Problems report is available at: file:///D:/Github/onevpn-autopilot/android/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.13/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 8s
31 actionable tasks: 6 executed, 25 up-to-date
```

Runnable test-count receipt against the retained runner XML (`.data/android-update-cache-junit.xml`, copied immediately from the Gradle result):

```powershell
[xml]$report=Get-Content -LiteralPath .data/android-update-cache-junit.xml -Raw
$suite=$report.testsuite
Write-Output ('ANDROID_UPDATE_CACHE_UNIT_RECEIPT tests='+$suite.tests+' failures='+$suite.failures+' errors='+$suite.errors+' skipped='+$suite.skipped)
```

Verbatim output:

```text
ANDROID_UPDATE_CACHE_UNIT_RECEIPT tests=1 failures=0 errors=0 skipped=0
```

Only compilation and the filesystem unit case were executed by this implementation agent. The added Android runtime tests remain for the orchestrator's combined instrumentation run. No release, installation, phone mutation, or VPN change was performed by this agent.
