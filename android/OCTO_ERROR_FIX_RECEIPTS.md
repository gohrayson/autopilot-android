# Android Octohide error-code preservation

The Android 0.1.0 report showed `PROVIDER_ERROR` at `config-export`, without the original server error. The earlier catalog discarded recognized Octohide errors that were not listed locally. This change preserves the exact allowlisted error names from the downloaded Android APK's `getErrorMessage` switch (`.data/octohide-analysis/jadx/sources/defpackage/dh0.java`, lines 214–330). Errors without a verified recovery meaning retain the generic `PROVIDER_ERROR` behavior and safe cached exports.

`INVALID_REPLY_ID` now reaches the existing bounded `BAD_REPLY_ID` recovery path. `MAX_ACTIVATED_LIMIT` maps to `DEVICE_LIMIT`. Request-limit variants map to `RATE_LIMITED`. Only the APK's documented relative-seconds cases (`REQUEST_LIMIT_LONG`, `REQUEST_LIMIT_SEND_ACTIVATION_CODE`, and `REQUEST_LIMIT_ACTIVATE_ACCOUNT`) interpret the numeric `code` field as a retry delay. Unknown strings and nonstring errors remain excluded from diagnostics, including unknown uppercase strings that would pass a character-only filter.

The fixture integration probe drives an export failure through the real repository and checks the observable shown in the user's diagnostics: `lastDiagnostic.providerCode == NO_SERVERS`, with the original `config-export` stage. It also checks first-failure stop, retention of previous partial exports, the minimum cooldown, and persistence across a repository restart. This is a local transport probe, not a live Octohide API or Android-device verification. No live account, saved vault, Windows VPN, or proxy was accessed or mutated.

From `D:\Github\onevpn-autopilot`, using the existing locally bootstrapped toolchain and fixture libraries:

```powershell
. .data/android-tools/env.ps1
$providerFiles = @('Node','NodeParser','ProviderException','ProviderHttp','ProviderRepository','SecureStore') | ForEach-Object { "android/app/src/main/java/app/autopilot/android/$_.java" }
$octoFixClasses = '.data/android-provider-tests/octo-error-fix-classes'
New-Item -ItemType Directory -Force $octoFixClasses | Out-Null
& "$env:JAVA_HOME/bin/javac.exe" --release 17 -encoding UTF-8 -Xlint:all -cp '.data/android-tools/sdk/platforms/android-35/android.jar;.data/android-provider-tests/lib/snakeyaml.jar;.data/android-provider-tests/lib/junit.jar;.data/android-provider-tests/lib/hamcrest.jar' -d $octoFixClasses $providerFiles android/app/src/test/java/app/autopilot/android/ProviderRepositoryTest.java
if ($LASTEXITCODE -ne 0) { throw 'Android API compile failed' }
& "$env:JAVA_HOME/bin/java.exe" -cp "$octoFixClasses;.data/android-provider-tests/lib/*;.data/android-tools/sdk/platforms/android-35/android.jar" org.junit.runner.JUnitCore app.autopilot.android.ProviderRepositoryTest
if ($LASTEXITCODE -ne 0) { throw 'Fixture tests failed' }
```

Verbatim runner tail, 2026-10-21:

```text
JUnit version 4.13.2
.......................
Time: 0.217

OK (23 tests)
```

The successful fixture run proves preservation and classification of documented codes. It does not establish which code caused the user's earlier export failure, because that original code was discarded by Android 0.1.0, or that a live provider export now succeeds. APK packaging and the broader application checks are performed by the parent task after all Android fixes are integrated.
