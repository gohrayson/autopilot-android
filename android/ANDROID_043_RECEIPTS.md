# Android 0.4.3 build and source verification

2026-10-23. Package `app.autopilot.android`, version name `0.4.3`, version code `13`, minimum SDK `26`. Production discovery scheduling changed; this receipt records local compilation, JVM/lint, signature, packaged asset/native identity and source audit. It does not claim a physical-device or hosted-emulator runtime PASS. The release owner records those later results outside the Android source tree to keep the hosted source and source ZIP immutable.

## Signed release from frozen sources

The existing signing key and DPAPI password files were required to be present and nonempty before invoking the build. No signing material was generated, replaced or printed.

Working directory: `D:/Github/onevpn-autopilot`. Exact invocation:

```powershell
python .data/android043-build-identity.py capture | Tee-Object -FilePath 'D:/Github/onevpn-autopilot/.data/android043-build-inputs.log'
$requiredSigning=@('D:/Github/onevpn-autopilot/.data/android-signing/autopilot-release.jks','D:/Github/onevpn-autopilot/.data/android-signing/password.dpapi')
foreach($signingFile in $requiredSigning){if(-not (Test-Path -LiteralPath $signingFile -PathType Leaf)){throw 'Existing release signing material is missing; key generation is refused'};if((Get-Item -LiteralPath $signingFile).Length -le 0){throw 'Existing release signing material is empty; key generation is refused'}}
Write-Output 'ANDROID_EXISTING_SIGNING_GUARD_PASS key=true dpapi=true generation=false'
& ./android/tools/build.ps1 -Configuration Release 2>&1 | Tee-Object -FilePath 'D:/Github/onevpn-autopilot/.data/android-043-release-build-final.log'
$android043FinalExit=$LASTEXITCODE
if($android043FinalExit -ne 0){exit $android043FinalExit}
```

The build invokes `:app:testDebugUnitTest :app:lintDebug :app:assembleRelease`. Verbatim guard, source capture and final build tail:

```text
ANDROID043_BUILD_INPUTS_CAPTURED files=98 sourceIdentitySha256=eee851955ea085b46a185cc74846c725a463fa98ce0e4cdabbac353fabfd3fca
ANDROID_EXISTING_SIGNING_GUARD_PASS key=true dpapi=true generation=false
BUILD SUCCESSFUL in 15s
81 actionable tasks: 11 executed, 70 up-to-date
APK: D:\Github\onevpn-autopilot\dist\android\Autopilot-Android-0.4.3.apk
SHA256: 03bb8b578ff1c3fb65b6c212cb8543e4adf06e3061801c302997c0835813753a
```

Exit code: `0`. Existing SDK XML-version, Gradle deprecation and deprecated-API notices remain; this is not a warning-free claim. JVM and lint tasks were current against the runtime worker's final full suite; the release Java compiler and release packaging ran again after that final correction.

## Exact JVM XML inventory

```powershell
python android/tools/check-unit-receipts.py android | Tee-Object -FilePath 'D:/Github/onevpn-autopilot/.data/android043-unit-xml.log'
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
```

Verbatim output:

```text
ANDROID_UNIT_XML_RECEIPT tests=199 expected=199 failures=0 errors=0 skipped=0 missing=0 duplicates=0 unexpected=0
```

Exit code: `0`. The catalog derives expected methods from every JVM test source, then checks Gradle XML for missing, duplicate, unexpected, failed, errored and skipped cases. The production scheduling instrumentation is separate; its compilation and hosted invocation are recorded in `ANDROID_DISCOVERY_RUNTIME_RECEIPTS.md`.

## APK signature, metadata and native alignment

```powershell
& ./android/tools/verify-apk.ps1 -Apk 'D:/Github/onevpn-autopilot/dist/android/Autopilot-Android-0.4.3.apk' 2>&1 | Tee-Object -FilePath 'D:/Github/onevpn-autopilot/.data/android043-apk-verification.log'
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
```

Verbatim acceptance lines:

```text
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
Signer #1 certificate SHA-256 digest: 6c6831265f4d5593b499b2bfc1bbcaf93f3eb4d143f49fef192d811d4b6060ab
package: name='app.autopilot.android' versionCode='13' versionName='0.4.3' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
minSdkVersion:'26'
targetSdkVersion:'35'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86_64'
Verified 16384 byte ELF alignment and RELRO protection: lib/arm64-v8a/libclash.so, 4 load segments
Verified 16384 byte ELF alignment and RELRO protection: lib/arm64-v8a/libmihomo-jni.so, 3 load segments
Verified 4096 byte ELF alignment and RELRO protection: lib/armeabi-v7a/libclash.so, 4 load segments
Verified 4096 byte ELF alignment and RELRO protection: lib/armeabi-v7a/libmihomo-jni.so, 3 load segments
Verified 16384 byte ELF alignment and RELRO protection: lib/x86_64/libclash.so, 4 load segments
Verified 16384 byte ELF alignment and RELRO protection: lib/x86_64/libmihomo-jni.so, 3 load segments
Verified APK contains no account vault, signing key, diagnostics log, or probe runtime fixture.
Verified APK ZIP alignment for 16 KB pages.
```

Exit code: `0`. The signer is the established release signer, so this remains an in-place Android update.

The publisher's pinned metadata check consumed that actual verifier output:

```powershell
@'
const fs=require('node:fs'),p=require('./android/tools/publish-update.cjs');const version=p.parseVersion(fs.readFileSync('android/app/build.gradle','utf8'));const verified=fs.readFileSync('.data/android043-apk-verification.log','utf8').replace(/^\uFEFF/,'');p.verifyApkMetadata(verified,verified,version);console.log(`ANDROID043_APK_IDENTITY_PASS package=${version.packageName} versionName=${version.versionName} versionCode=${version.versionCode} minSdk=${version.minSdk} sameCertificate=${p.CERTIFICATE}`);
'@ | node | Tee-Object -FilePath 'D:/Github/onevpn-autopilot/.data/android043-apk-identity.log'
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
python .data/android043-build-identity.py check | Tee-Object -FilePath 'D:/Github/onevpn-autopilot/.data/android043-frozen-apk.log'
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
```

Verbatim output:

```text
ANDROID043_APK_IDENTITY_PASS package=app.autopilot.android versionName=0.4.3 versionCode=13 minSdk=26 sameCertificate=6c6831265f4d5593b499b2bfc1bbcaf93f3eb4d143f49fef192d811d4b6060ab
ANDROID043_BUILD_INPUTS_MATCH files=98 sourceIdentitySha256=eee851955ea085b46a185cc74846c725a463fa98ce0e4cdabbac353fabfd3fca apkAssetsEqual=true nativeLibraries=6 pinnedNativeEqual=true
ANDROID043_APK_FROZEN bytes=52026503 sha256=03bb8b578ff1c3fb65b6c212cb8543e4adf06e3061801c302997c0835813753a
```

Exit code: `0`. The retained helper hashes the app/probe source, test/resource/assets, Gradle inputs/wrapper and pinned AAR before and after the final build. It checks every packaged asset against current source and compares every packaged native library with the verified preceding release. Documentation changes are excluded from the runtime build identity.

## Limits

No ADB, emulator, physical-device installation, account, desktop app, proxy, VPN or route was operated by this build/probe worker. The hosted runner is deliberately unavailable on Windows and rejects physical devices. Public TLS reachability remains an honest dependency of the new discovery runtime test. No public release was published by this worker.
