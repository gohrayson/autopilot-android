# Android 0.4.2 synchronized patch verification

2026-10-23. Package `app.autopilot.android`, version name `0.4.2`, version code `12`. This Android release synchronizes version metadata with a desktop-only updater redirect-header fix. Android runtime behavior is unchanged from the verified 0.4.0 baseline.

## Source and native dependency equivalence

Baseline: production source commit `2d259d90ea17d5504250cecc37a6534443a73f3a`, whose actual native/UI, independent updater HTTPS, and separate-UID TUN runtime receipts are in `ANDROID_040_HOSTED_RECEIPTS.md`.

Runnable command from `D:\Github\onevpn-autopilot`:

```powershell
node .data/android042-unchanged-runtime.cjs 2>&1 | Tee-Object -FilePath D:\Github\onevpn-autopilot\.data\android042-runtime-equivalence.log
exit $LASTEXITCODE
```

The retained helper compares every tracked Android app/probe source and resource, test source, Gradle wrapper/dependency configuration, and hosted native workflow against `git show` of that baseline. Text comparison normalizes CRLF to LF. It rejects added runtime source files and permits only the Android version fields in `app/build.gradle` and `product-contract.json` to differ. It hashes the local native AAR and checks that hash against the baseline hosted workflow pin.

Verbatim output:

```text
ANDROID042_UNCHANGED_RUNTIME_RECEIPT baseline=2d259d90ea17d5504250cecc37a6534443a73f3a comparedFiles=94 javaFiles=67 resourceFiles=7 runtimeEqualAfterLf=true metadataOnly=productVersion,androidVersionCode,BuildConfig nativeAarSha256=01bc41748840c8200bbaf08c044ba8704e35303a3179b75a3cce8517c8944251
```

No Android Java, resources, VPN permissions, provider integrations, policy parameters, settings schema, native runtime or workflow behavior changed. The APK's version metadata and generated/inlined version constants change as expected.

## Signed release, full JVM suite and lint

Existing signing files were checked for presence before invoking the build. No signing material was generated, replaced or printed.

Exact invocation:

```powershell
if (-not (Test-Path -LiteralPath .data/android-signing/autopilot-release.jks) -or -not (Test-Path -LiteralPath .data/android-signing/password.dpapi)) { throw 'Existing Android signing material is required; refusing regeneration' }
& android/tools/build.ps1 -Configuration Release 2>&1 | Tee-Object -FilePath D:\Github\onevpn-autopilot\.data\android042-release-build.log
if ($LASTEXITCODE -ne 0) { throw 'Android 0.4.2 release build failed' }
```

The script invokes `:app:testDebugUnitTest :app:lintDebug :app:assembleRelease`.

Verbatim build tail:

```text
BUILD SUCCESSFUL in 22s
81 actionable tasks: 41 executed, 40 up-to-date
APK: D:\Github\onevpn-autopilot\dist\android\Autopilot-Android-0.4.2.apk
SHA256: 40a28ae8be44980066fa5196af8b2e3be9fc2b7ff85b60175bf0ee7043814088
```

Runnable XML catalog verification:

```powershell
python android/tools/check-unit-receipts.py android 2>&1 | Tee-Object -FilePath D:\Github\onevpn-autopilot\.data\android042-unit-xml.log
if ($LASTEXITCODE -ne 0) { throw 'Android JVM XML inventory mismatch' }
```

Verbatim output:

```text
ANDROID_UNIT_XML_RECEIPT tests=178 expected=178 failures=0 errors=0 skipped=0 missing=0 duplicates=0 unexpected=0
```

Lint completed without errors. Gradle emitted existing dependency/deprecation/manifest notices; this is not a warning-free claim.

## APK identity and unchanged packaged native bytes

The existing Android SDK verifier checked the signed file, and the release publisher's pinned metadata validator checked the exact package/version/minSdk and certificate fingerprint:

```powershell
. .\.data\android-tools\env.ps1
& (Join-Path $env:JAVA_HOME 'bin/java.exe') -jar (Join-Path $env:ANDROID_HOME 'build-tools/35.0.0/lib/apksigner.jar') verify --verbose --print-certs dist/android/Autopilot-Android-0.4.2.apk 2>&1 | Tee-Object -FilePath D:\Github\onevpn-autopilot\.data\android042-apksigner.log
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
& (Join-Path $env:ANDROID_HOME 'build-tools/35.0.0/aapt2.exe') dump badging dist/android/Autopilot-Android-0.4.2.apk | Out-File -LiteralPath D:\Github\onevpn-autopilot\.data\android042-aapt-badging.log -Encoding utf8
if ($LASTEXITCODE -ne 0) { throw 'APK package metadata read failed' }
@'
const fs=require('node:fs'),p=require('./android/tools/publish-update.cjs');const version=p.parseVersion(fs.readFileSync('android/app/build.gradle','utf8'));p.verifyApkMetadata(fs.readFileSync('.data/android042-apksigner.log','utf8'),fs.readFileSync('.data/android042-aapt-badging.log','utf8').replace(/^\uFEFF/,''),version);console.log(`ANDROID042_APK_IDENTITY_PASS package=${version.packageName} versionName=${version.versionName} versionCode=${version.versionCode} minSdk=${version.minSdk} sameCertificate=${p.CERTIFICATE}`);
'@ | node
```

Verbatim acceptance:

```text
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
Signer #1 certificate SHA-256 digest: 6c6831265f4d5593b499b2bfc1bbcaf93f3eb4d143f49fef192d811d4b6060ab
ANDROID042_APK_IDENTITY_PASS package=app.autopilot.android versionName=0.4.2 versionCode=12 minSdk=26 sameCertificate=6c6831265f4d5593b499b2bfc1bbcaf93f3eb4d143f49fef192d811d4b6060ab
```

Runnable APK comparison against the prior verified release:

```powershell
@'
from pathlib import Path
import hashlib,json,zipfile
old=Path('dist/android/Autopilot-Android-0.4.0.apk');new=Path('dist/android/Autopilot-Android-0.4.2.apk')
with zipfile.ZipFile(old) as a, zipfile.ZipFile(new) as b:
 native=lambda z:{name:hashlib.sha256(z.read(name)).hexdigest() for name in z.namelist() if name.startswith('lib/') and name.endswith('.so')}
 x,y=native(a),native(b)
 assert x and x==y,'Bundled native library bytes changed'
 geo='assets/geoip.metadb';assert a.read(geo)==b.read(geo),'Pinned regional database changed'
 ac=json.loads(a.read('assets/product-contract.json'));bc=json.loads(b.read('assets/product-contract.json'))
 assert ac.pop('productVersion')=='0.4.0' and bc.pop('productVersion')=='0.4.2'
 assert ac.pop('androidVersionCode')==10 and bc.pop('androidVersionCode')==12
 assert ac==bc,'Shared policy changed in packaged APK'
 print('ANDROID042_PACKAGED_RUNTIME_EQUAL nativeLibraries='+str(len(x))+' nativeBytesEqual=true geoBytesEqual=true policyEqual=true metadataOnly=true')
 print('ANDROID042_APK_FROZEN bytes='+str(new.stat().st_size)+' sha256='+hashlib.sha256(new.read_bytes()).hexdigest())
'@ | python
```

Verbatim output:

```text
ANDROID042_PACKAGED_RUNTIME_EQUAL nativeLibraries=6 nativeBytesEqual=true geoBytesEqual=true policyEqual=true metadataOnly=true
ANDROID042_APK_FROZEN bytes=52014211 sha256=40a28ae8be44980066fa5196af8b2e3be9fc2b7ff85b60175bf0ee7043814088
```

## Limits and publication scope

The native emulator suite was not rerun for this metadata-only Android patch. The unchanged runtime relies on the exact 0.4.0 hosted acceptance, with fresh source/native equivalence, full JVM, lint, signed APK metadata and packaged binary checks above. No physical-device VPN, ADB installation, provider account, desktop VPN, proxy or routes were changed. No release was published by this worker. Hosted source changes use `[skip ci]` because application runtime sources and native dependencies are unchanged.
