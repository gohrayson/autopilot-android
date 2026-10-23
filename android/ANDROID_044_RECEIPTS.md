# Android 0.4.4 selected-provider renewal and build receipts

2026-10-23. Package `app.autopilot.android`, version `0.4.4`, code `14`, minimum SDK `26`. Runtime/tests and the built APK are frozen. These local receipts do not claim a real provider API renewal, physical-device result or hosted emulator result. Later CI evidence is recorded outside this source tree in `docs/ANDROID_044_CI_RECEIPTS.md`.

## Production behavior and pipeline regression

`VpnEngine.applyWatchdog` retains its current snapshot/current-node guard before consuming an observation. A confirmed broad HTTPS failure now notifies `SelectedProviderRecovery` before rejection clears the selected node, independently of the node's Google RTT. A partial-origin failure notifies only after a fresh comparative candidate has been verified and promoted successfully.

The helper accepts only an Octohide node matching the observed snapshot, deduplicates the same selection snapshot and queues its failed name through the existing application/repository scheduler. Queue and diagnostic exceptions are isolated from failover. The existing repository owns targeted region selection, export spacing, durable pending regions and provider cooldown; it is not replaced or bypassed.

`SelectedProviderRecoveryTest` runs real `ConnectionWatchdog` observations into the helper and the actual `ProviderRepository` using its memory store/transport fixture. Cases cover fresh native RTT `100` with repeated two-origin HTTPS failure, selection deduplication, targeted export renewal, existing two-minute spacing and restart persistence, provider rate-limit cooldown persistence, service-wide partial incidents versus fresh comparative proof, provider/node identity guards, queue/diagnostic exceptions, and stale watchdog completion after selection changes. No fixture invokes a real account or external provider API.

Working directory for all commands: `D:/Github/onevpn-autopilot`.

```powershell
. ./.data/android-tools/env.ps1
gradle -p android --no-daemon --console=plain :app:testDebugUnitTest --tests app.autopilot.android.SelectedProviderRecoveryTest --tests app.autopilot.android.ConnectionWatchdogTest --tests app.autopilot.android.ProviderRepositoryTest 2>&1 | Tee-Object -FilePath 'D:/Github/onevpn-autopilot/.data/android044-selected-provider-focused.log'
$selectedRecoveryFocusedExit=$LASTEXITCODE
if($selectedRecoveryFocusedExit -ne 0){exit $selectedRecoveryFocusedExit}
```

Verbatim tail:

```text
BUILD SUCCESSFUL in 19s
23 actionable tasks: 13 executed, 10 up-to-date
```

Exit code: `0`. This is a pipeline test of the actual watchdog/helper/repository components; it does not invoke an Android service instance on the JVM. The service notification ordering and stale-observation guard were reviewed in the production source.

## Full JVM catalog and lint

```powershell
. ./.data/android-tools/env.ps1
gradle -p android --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug 2>&1 | Tee-Object -FilePath 'D:/Github/onevpn-autopilot/.data/android044-selected-provider-full-jvm-lint.log'
$selectedRecoveryFullExit=$LASTEXITCODE
if($selectedRecoveryFullExit -ne 0){exit $selectedRecoveryFullExit}
python android/tools/check-unit-receipts.py android | Tee-Object -FilePath 'D:/Github/onevpn-autopilot/.data/android044-unit-xml.log'
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
```

Verbatim tail and exact XML inventory:

```text
BUILD SUCCESSFUL in 44s
33 actionable tasks: 10 executed, 23 up-to-date
ANDROID_UNIT_XML_RECEIPT tests=206 expected=206 failures=0 errors=0 skipped=0 missing=0 duplicates=0 unexpected=0
```

Exit code: `0`. Expected methods are derived from every JVM test source and matched against Gradle's raw XML. Lint completed without errors. Existing SDK XML-version, manifest and deprecated-API/Gradle notices remain; this is not a warning-free claim.

## Existing signer and frozen release build

```powershell
python .data/android044-build-identity.py capture | Tee-Object -FilePath 'D:/Github/onevpn-autopilot/.data/android044-build-inputs.log'
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
$requiredSigning=@('D:/Github/onevpn-autopilot/.data/android-signing/autopilot-release.jks','D:/Github/onevpn-autopilot/.data/android-signing/password.dpapi')
foreach($signingFile in $requiredSigning){if(-not (Test-Path -LiteralPath $signingFile -PathType Leaf)){throw 'Existing release signing material is missing; key generation is refused'};if((Get-Item -LiteralPath $signingFile).Length -le 0){throw 'Existing release signing material is empty; key generation is refused'}}
Write-Output 'ANDROID044_EXISTING_SIGNING_GUARD_PASS key=true dpapi=true generation=false'
& ./android/tools/build.ps1 -Configuration Release 2>&1 | Tee-Object -FilePath 'D:/Github/onevpn-autopilot/.data/android044-release-build.log'
$android044BuildExit=$LASTEXITCODE
if($android044BuildExit -ne 0){exit $android044BuildExit}
```

The build invokes `:app:testDebugUnitTest :app:lintDebug :app:assembleRelease`. Verbatim acceptance and build tail:

```text
ANDROID044_BUILD_INPUTS_CAPTURED files=100 sourceIdentitySha256=f3f90b7fd802e73b70d8333ffa81bce2386184a19bb526dd5ddd68314be1029f
ANDROID044_EXISTING_SIGNING_GUARD_PASS key=true dpapi=true generation=false
BUILD SUCCESSFUL in 34s
81 actionable tasks: 22 executed, 59 up-to-date
APK: D:\Github\onevpn-autopilot\dist\android\Autopilot-Android-0.4.4.apk
SHA256: a67ce51ee098040afd5763bc34851e41dc653f2602de9481aea9821e7c0ae3da
```

Exit code: `0`. Existing signing material was reused, without generation, replacement or password output.

```powershell
& ./android/tools/verify-apk.ps1 -Apk 'D:/Github/onevpn-autopilot/dist/android/Autopilot-Android-0.4.4.apk' 2>&1 | Tee-Object -FilePath 'D:/Github/onevpn-autopilot/.data/android044-apk-verification.log'
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
```

Verbatim identity and verification acceptance lines:

```text
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
Signer #1 certificate SHA-256 digest: 6c6831265f4d5593b499b2bfc1bbcaf93f3eb4d143f49fef192d811d4b6060ab
package: name='app.autopilot.android' versionCode='14' versionName='0.4.4' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
minSdkVersion:'26'
targetSdkVersion:'35'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86_64'
Verified APK contains no account vault, signing key, diagnostics log, or probe runtime fixture.
Verified APK ZIP alignment for 16 KB pages.
```

Exit code: `0`. The verifier also accepted every packaged native library's ELF alignment and RELRO protection for its ABI.

```powershell
@'
const fs=require('node:fs'),p=require('./android/tools/publish-update.cjs');const version=p.parseVersion(fs.readFileSync('android/app/build.gradle','utf8'));const verified=fs.readFileSync('.data/android044-apk-verification.log','utf8').replace(/^\uFEFF/,'');p.verifyApkMetadata(verified,verified,version);console.log(`ANDROID044_APK_IDENTITY_PASS package=${version.packageName} versionName=${version.versionName} versionCode=${version.versionCode} minSdk=${version.minSdk} sameCertificate=${p.CERTIFICATE}`);
'@ | node | Tee-Object -FilePath 'D:/Github/onevpn-autopilot/.data/android044-apk-identity.log'
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
python .data/android044-build-identity.py check | Tee-Object -FilePath 'D:/Github/onevpn-autopilot/.data/android044-frozen-apk.log'
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
```

Verbatim output:

```text
ANDROID044_APK_IDENTITY_PASS package=app.autopilot.android versionName=0.4.4 versionCode=14 minSdk=26 sameCertificate=6c6831265f4d5593b499b2bfc1bbcaf93f3eb4d143f49fef192d811d4b6060ab
ANDROID044_BUILD_INPUTS_MATCH files=100 sourceIdentitySha256=f3f90b7fd802e73b70d8333ffa81bce2386184a19bb526dd5ddd68314be1029f apkAssetsEqual=true nativeLibraries=6 pinnedNativeEqual=true
ANDROID044_APK_FROZEN bytes=52027167 sha256=a67ce51ee098040afd5763bc34851e41dc653f2602de9481aea9821e7c0ae3da
```

Exit code: `0`. The helper compares the complete runtime/test/resource/assets/Gradle input inventory before and after the build, every packaged asset against current source, and packaged native libraries against the preceding verified 0.4.3 APK. The native AAR remains pinned to `01bc41748840c8200bbaf08c044ba8704e35303a3179b75a3cce8517c8944251`.

## Instrumentation compilation and boundaries

```powershell
. ./.data/android-tools/env.ps1
gradle -p android --no-daemon --console=plain :app:assembleDebug :app:assembleDebugAndroidTest :probe:assembleDebug 2>&1 | Tee-Object -FilePath 'D:/Github/onevpn-autopilot/.data/android044-instrumentation-build.log'
$android044InstrumentationExit=$LASTEXITCODE
if($android044InstrumentationExit -ne 0){exit $android044InstrumentationExit}
```

Verbatim tail:

```text
BUILD SUCCESSFUL in 16s
96 actionable tasks: 8 executed, 88 up-to-date
```

Exit code: `0`. This compiled and packaged app/test/probe APKs without installing or executing them. No ADB, UI popup, physical device, real provider API/login/refresh, live VPN/proxy setting or public release was operated by this worker. Hosted runtime results, source ZIP identity and hosted commit are recorded outside this immutable Android source receipt.
