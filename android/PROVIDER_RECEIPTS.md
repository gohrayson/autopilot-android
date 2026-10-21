# Android provider verification

This checks the real provider/parser/storage source against Android API 35 and runs fixture-only account tests. No existing Windows profile, provider account, VPN, proxy, or external provider API is used. The test transport is in memory. AndroidKeyStore encryption requires the separate device/emulator runtime probe; compilation and a memory-store fixture do not establish that runtime behaviour.

From `D:\Github\onevpn-autopilot`, after the local Android tool bootstrap:

```powershell
. .data/android-tools/env.ps1
$probeLib = '.data/android-provider-tests/lib'
New-Item -ItemType Directory -Force $probeLib | Out-Null
curl.exe -fL --retry 2 --output "$probeLib/json.jar" https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar
curl.exe -fL --retry 2 --output "$probeLib/snakeyaml.jar" https://repo.maven.apache.org/maven2/org/yaml/snakeyaml/2.4/snakeyaml-2.4.jar
curl.exe -fL --retry 2 --output "$probeLib/junit.jar" https://repo.maven.apache.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar
curl.exe -fL --retry 2 --output "$probeLib/hamcrest.jar" https://repo.maven.apache.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
$providerFiles = @('Node','NodeParser','ProviderException','ProviderHttp','ProviderRepository','SecureStore') | ForEach-Object { "android/app/src/main/java/app/autopilot/android/$_.java" }
$apiClasses = '.data/android-provider-tests/android-api-classes'
New-Item -ItemType Directory -Force $apiClasses | Out-Null
& "$env:JAVA_HOME/bin/javac.exe" --release 17 -encoding UTF-8 -Xlint:all -cp '.data/android-tools/sdk/platforms/android-35/android.jar;.data/android-provider-tests/lib/snakeyaml.jar;.data/android-provider-tests/lib/junit.jar;.data/android-provider-tests/lib/hamcrest.jar' -d $apiClasses $providerFiles android/app/src/test/java/app/autopilot/android/ProviderRepositoryTest.java
if ($LASTEXITCODE -ne 0) { throw 'Android API compile failed' }
& "$env:JAVA_HOME/bin/java.exe" -cp "$apiClasses;.data/android-provider-tests/lib/*;.data/android-tools/sdk/platforms/android-35/android.jar" org.junit.runner.JUnitCore app.autopilot.android.ProviderRepositoryTest
if ($LASTEXITCODE -ne 0) { throw 'Fixture tests failed' }
```

Verbatim runner tail, 2026-10-21:

```text
JUnit version 4.13.2
....................
Time: 0.227

OK (20 tests)
```

Covered observables: exact desktop signature vectors and subscription AES-CBC decoding; strict live response shapes and millisecond account dates; WireGuard validation and rejected hooks; safe YAML; pending login durability, source preservation, password-free retry and rotated-token ordering; authoritative entitlement removal; independent provider refresh; Octohide decimal region response, three-slot pool, sequential request spacing, healthy-export reuse, country priority changes, active/pinned export preservation, first-error stop, partial success, persisted cooldown across logout/restart, targeted failed-export renewal with a two-minute attempt gate; unique merged labels; readable UI snapshots during blocked HTTP; bounded safe diagnostics and Retry-After; failed secure-write retries and verified deletion fallback preventing revoked/disconnected accounts from reappearing from an obsolete vault.
