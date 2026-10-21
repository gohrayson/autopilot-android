# Android 0.1.3 IDM-style updater verification

Executed 2026-10-21 from `D:\Github\onevpn-autopilot`. Version name `0.1.3`,
version code `4`, package `app.autopilot.android`, minimum API `26`.

## Release build and JVM inventory

```powershell
& android/tools/build.ps1 -Configuration Release 2>&1 |
  Tee-Object -FilePath 'D:\Github\onevpn-autopilot\.data\android-v013-final-build.log'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

The build explicitly runs `:app:testDebugUnitTest`, `:app:lintDebug` and
`:app:assembleRelease`. Verbatim runner tail (exit 0):

```text
BUILD SUCCESSFUL in 41s
79 actionable tasks: 47 executed, 32 up-to-date
APK: D:\Github\onevpn-autopilot\dist\android\Autopilot-Android-0.1.3.apk
SHA256: ed1798448c1f9c1dd272347db4d91b5ce29e7e08ddbcace5810ca6105145b26c
```

Runnable inventory assertion against the executed JUnit XML and all declared,
non-parameterized JVM tests:

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
rg 'errors,.*warnings' android/app/build/reports/lint-results-debug.txt
```

Observed output:

```text
{"errors":0,"skipped":0,"tests":124,"declared":124,"failures":0}
0 errors, 4 warnings
```

The new downloader fixtures cover real concurrent requests, persisted range
prefixes, interruption, new-instance resume, bounded transient retries, HTTP 429,
wrong ranges and encodings, corruption, fallback, unsafe paths and missing-cache
discard. The progress fixtures cover restored-byte speed accounting, pause timing,
fallback restart, bounds and concurrent snapshots. Focused downloader receipt:
[ANDROID_PARALLEL_DOWNLOAD_RECEIPTS.md](ANDROID_PARALLEL_DOWNLOAD_RECEIPTS.md).

## Signed APK and native packaging

```powershell
& android/tools/verify-apk.ps1 *> .data/android-v013-verify.log
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Get-Content .data/android-v013-verify.log -Tail 13
```

Observed final verifier lines (exit 0):

```text
Verified 16384 byte ELF alignment and RELRO protection: lib/arm64-v8a/libclash.so, 4 load segments
Verified 16384 byte ELF alignment and RELRO protection: lib/arm64-v8a/libmihomo-jni.so, 3 load segments
Verified 4096 byte ELF alignment and RELRO protection: lib/armeabi-v7a/libclash.so, 4 load segments
Verified 4096 byte ELF alignment and RELRO protection: lib/armeabi-v7a/libmihomo-jni.so, 3 load segments
Verified 16384 byte ELF alignment and RELRO protection: lib/x86_64/libclash.so, 4 load segments
Verified 16384 byte ELF alignment and RELRO protection: lib/x86_64/libmihomo-jni.so, 3 load segments
Verified APK contains no account vault, signing key, diagnostics log, or probe runtime fixture.
Verified APK ZIP alignment for 16 KB pages.
```

APK size is `47773702` bytes; SHA-256:
`ed1798448c1f9c1dd272347db4d91b5ce29e7e08ddbcace5810ca6105145b26c`.
The signature verifies against unchanged production certificate SHA-256
`6c6831265f4d5593b499b2bfc1bbcaf93f3eb4d143f49fef192d811d4b6060ab`.

## Separate publisher runner

```powershell
node --test android/tools/publish-update.test.cjs 2>&1 |
  Tee-Object .data/android-v013-publisher-tests.log
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

Verbatim tail (exit 0):

```text
ℹ tests 13
ℹ suites 0
ℹ pass 13
ℹ fail 0
ℹ cancelled 0
ℹ skipped 0
ℹ todo 0
ℹ duration_ms 72.7401
```

## Runtime and delivery scope

Android instrumentation preparation and actual hosted results belong in
[ANDROID_DOWNLOAD_ANDROID_RECEIPTS.md](ANDROID_DOWNLOAD_ANDROID_RECEIPTS.md).
The live public HTTPS range, pause, new-instance resume and signature probe is
recorded below after it completes. Unit fixtures alone do not prove the phone's
network speed or background behavior. Connection parallelism does not remove a
server-wide or network-wide bandwidth limit. Android/HyperOS installation and
phone VPN traffic remain device checks; automatic installation waits for the VPN
to stop, retaining the existing checks and installer confirmation flow.
