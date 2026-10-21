# Android 0.1.2 verification

Executed 2026-10-21 from `D:\Github\onevpn-autopilot`. This build fixes the
Android JSON slash escaping rejected by the embedded core, and adds the public
GitHub release updater. Production profile writing and the Android native probe
both use `CoreConfigEncoding.encode`.

## Signed release, JVM suite and lint

Runnable invocation:

```powershell
& android/tools/build.ps1 -Configuration Release 2>&1 |
  Tee-Object -FilePath 'D:\Github\onevpn-autopilot\.data\android-v012-encoding-final-build.log'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

The script explicitly invokes `:app:testDebugUnitTest`, `:app:lintDebug` and
`:app:assembleRelease`. Verbatim runner tail (exit 0):

```text
BUILD SUCCESSFUL in 16s
79 actionable tasks: 21 executed, 58 up-to-date
APK: D:\Github\onevpn-autopilot\dist\android\Autopilot-Android-0.1.2.apk
SHA256: 036649a428973de2c0a99dc7acb0ec04b82ed7ec663df0ed887a9da8617a98a3
```

Assert the executed JUnit inventory against the declared, non-parameterized
JUnit tests, rather than relying only on the Gradle success line:

```powershell
$reports=@(Get-ChildItem android/app/build/test-results/testDebugUnitTest -Filter '*.xml')
$totals=@{tests=0;failures=0;errors=0;skipped=0}
foreach ($report in $reports) {
  [xml]$xml=Get-Content -Raw $report.FullName
  foreach ($key in @('tests','failures','errors','skipped')) {
    $totals[$key]+=[int]$xml.testsuite.$key
  }
}
$declared=@(rg -o '@Test\b' android/app/src/test/java).Count
$totals.declared=$declared
$totals | ConvertTo-Json -Compress
if ($totals.tests -ne $declared -or $totals.failures -or $totals.errors -or $totals.skipped) {
  throw 'JUnit inventory mismatch or failed result'
}
rg 'errors,.*warnings|issues found|No issues' android/app/build/reports/lint-results-debug.txt
```

Observed output (JSON property order is immaterial):

```text
{"failures":0,"declared":99,"skipped":0,"tests":99,"errors":0}
0 errors, 4 warnings
```

## APK identity and native packaging

```powershell
& android/tools/verify-apk.ps1 *> .data/android-v012-verify.log
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Get-Content .data/android-v012-verify.log -Tail 14
Get-FileHash dist/android/Autopilot-Android-0.1.2.apk -Algorithm SHA256
```

Observed verifier lines (exit 0):

```text
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

Release identity: `app.autopilot.android`, version name `0.1.2`, version code `3`,
minimum SDK `26`, size `47761278` bytes. APK SHA-256:
`036649a428973de2c0a99dc7acb0ec04b82ed7ec663df0ed887a9da8617a98a3`.
The verified production certificate SHA-256 is unchanged from earlier releases:
`6c6831265f4d5593b499b2bfc1bbcaf93f3eb4d143f49fef192d811d4b6060ab`.
Installing this APK over the existing release is an in-place Android update.

## Publisher runner

This is a separate Node runner and is invoked explicitly:

```powershell
node --test android/tools/publish-update.test.cjs 2>&1 |
  Tee-Object .data/android-v012-publisher-tests.log
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

Verbatim runner tail (exit 0):

```text
ℹ tests 13
ℹ suites 0
ℹ pass 13
ℹ fail 0
ℹ cancelled 0
ℹ skipped 0
ℹ todo 0
ℹ duration_ms 61.937
```

## Runtime scope

The actual Android JNI baseline and selector result are recorded separately in
[ANDROID_HOSTED_RUNTIME_RECEIPTS.md](ANDROID_HOSTED_RUNTIME_RECEIPTS.md), including
the failing original Android bytes and passing encoder correction. Public feed
and actual APK download evidence is recorded in
[ANDROID_UPDATE_HOSTED_RECEIPTS.md](ANDROID_UPDATE_HOSTED_RECEIPTS.md).

These checks establish configuration acceptance on Android API 35 x86_64 and
release transport/integrity. Full VPN traffic on the user's Android 16 ARM64
phone, automatic background scheduling on HyperOS, and PackageInstaller's
silent-versus-confirmation behavior on that phone remain unverified. The updater
waits for the VPN to stop before automatic installation and handles Android's
pending-user-confirmation result.
