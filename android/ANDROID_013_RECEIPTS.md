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

## Live production download, pause and new-instance resume

The unmodified final production `UpdateManifest` and `UpdateDownload` were
compiled with the private live probe. Final downloader source SHA-256:
`c7c0e08ca77a6f61af8ac35f7af752a27389c085ea86786762735f85dd381391`.
The public feed still served 0.1.2 while 0.1.3 was being verified, so the fixture
downloaded the actual signed 0.1.2 APK using the new engine. No mock transport,
provider headers, account credentials or TLS exceptions were used. Only this
JVM used the already-running desktop HTTPS proxy at `127.0.0.1:7890`; no network
setting, route, service or VPN was changed.

```powershell
& .data/parallel-update-probe/probe.ps1 -HttpsProxyPort 7890 2>&1 |
  Tee-Object -FilePath 'D:\Github\onevpn-autopilot\.data\android-v013-parallel-live-final.log'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

Decisive verbatim output (exit 0; intervening progress lines omitted):

```text
PUBLIC_FEED_OK version=0.1.2 code=3 bytes=47761278
PAUSE_OK cachedBytes=2099675 completeApkExists=false peakConnections=4
LIVE_PROGRESS bytes=2099675 total=47761278 connections=0
LIVE_PROGRESS bytes=2116059 total=47761278 connections=4
LIVE_PROGRESS bytes=47761278 total=47761278 connections=0
NEW_INSTANCE_RESUME_OK retainedBytes=2099675 peakConnections=4
FINAL_SHA256_OK 036649a428973de2c0a99dc7acb0ec04b82ed7ec663df0ed887a9da8617a98a3
RESUME_SECONDS=162.7487361
APK=D:\Github\onevpn-autopilot\.data\parallel-update-probe\run-a9e43d48543049a8bbaafecd5bc6decd\autopilot-update-3.apk
LIVE_DOWNLOADED_APK_SIGNATURE_IDENTITY_OK
LIVE_PARALLEL_RESUME_PROBE_PASS
```

The coordinating thread was intentionally interrupted after data was persisted.
It returned `UPDATE_CANCELLED`, retained nonzero partial data and exposed no
complete APK. A new downloader instance recovered the same cached byte count,
requested missing ranges and completed the real HTTPS download. A separate real
`apksigner`/`aapt2` check verified the downloaded APK's certificate, package,
version and minimum SDK. This establishes real range concurrency and resume;
it is not a controlled speed-up benchmark or evidence about phone throughput.

## Final connection-limit source-of-truth recheck

The UI validator's connection limit was changed to derive from
`UpdateDownload.PARALLEL_CONNECTIONS`. Required checks were rerun:

```powershell
& android/tools/build.ps1 -Configuration Release 2>&1 |
  Tee-Object -FilePath 'D:\Github\onevpn-autopilot\.data\android-v013-ssot-build.log'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

Verbatim tail (exit 0):

```text
BUILD SUCCESSFUL in 17s
79 actionable tasks: 16 executed, 63 up-to-date
APK: D:\Github\onevpn-autopilot\dist\android\Autopilot-Android-0.1.3.apk
SHA256: ed1798448c1f9c1dd272347db4d91b5ce29e7e08ddbcace5810ca6105145b26c
```

The production APK bytes are identical to the earlier verified release build.
