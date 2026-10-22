# Autopilot Android 0.4.0 release verification

2026-10-23. Package `app.autopilot.android`, version code `10`. Canonical workspace `D:\Github\onevpn-autopilot`.

## Signed release, full JVM suite and lint

Exact invocation from the workspace root:

```powershell
& android/tools/build.ps1 -Configuration Release 2>&1 | Tee-Object -FilePath 'D:/Github/onevpn-autopilot/.data/parity-040-android-final-build.log'
```

Verbatim build tail:

```text
BUILD SUCCESSFUL in 39s
81 actionable tasks: 19 executed, 62 up-to-date
APK: D:\Github\onevpn-autopilot\dist\android\Autopilot-Android-0.4.0.apk
SHA256: a1ee844465d85ae601145ec7ae98d5e72c0799050831087d3f3a95abf6ff08c8
```

The build invokes `:app:testDebugUnitTest :app:lintDebug :app:assembleRelease`. A source-method inventory audits the JUnit XML rather than relying on task success alone:

```powershell
python android/tools/check-unit-receipts.py android | Tee-Object .data/parity-040-unit-xml.log
```

```text
ANDROID_UNIT_XML_RECEIPT tests=178 expected=178 failures=0 errors=0 skipped=0 missing=0 duplicates=0 unexpected=0
```

This includes both-language shared policy scenarios, settings/routing validation, profile transitions, provider parsing, encrypted caches, update behavior and native-contract encoding. The JavaScript consumer of the same scenarios is covered by `docs/PARITY_RELEASE_RECEIPTS.md`.

## APK identity and artifact

```powershell
& android/tools/verify-apk.ps1 -Apk 'D:/Github/onevpn-autopilot/dist/android/Autopilot-Android-0.4.0.apk' *> .data/parity-040-apk-final-identity.log
```

Verbatim identity lines:

```text
Signer #1 certificate SHA-256 digest: 6c6831265f4d5593b499b2bfc1bbcaf93f3eb4d143f49fef192d811d4b6060ab
package: name='app.autopilot.android' versionCode='10' versionName='0.4.0' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
Verified APK contains no account vault, signing key, diagnostics log, or probe runtime fixture.
Verified APK ZIP alignment for 16 KB pages.
```

APK size: `52014219` bytes. The unchanged Android release certificate permits an in-place upgrade retaining private app data. Existing physical-device sessions were not accessed by this build. Physical-device installation and behavior of this version have not been verified here; hosted native UI/TUN/update evidence is recorded separately after CI completes.
