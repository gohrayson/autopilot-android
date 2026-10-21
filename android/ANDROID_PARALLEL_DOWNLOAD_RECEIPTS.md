# Parallel Android updater core verification

Run on 2026-10-21 from `D:\Github\onevpn-autopilot`. This focused JVM run compiles the real production manifest/downloader and exercises its existing and new tests without starting Gradle, a provider request, an installer or a VPN.

## Contract and implementation

- Existing `download(UpdateManifest, File)` delegates to the new overload with `ProgressListener.onProgress(long downloaded, long total, int activeConnections)`.
- Files at least 4 MiB use four independently bounded ranges after a strict one-byte 206 range probe. Small files retain the sequential path. HTTP 200 at the probe or a subsequent worker causes a clean full-response fallback; cached multipart bytes are discarded, never appended to a full response.
- `resumableBytes(UpdateManifest, File)` reads a bounded local snapshot without networking. `discardResume(UpdateManifest, File)` deletes only the selected manifest's hashed cache, rejects an active writer, and never recursively follows unrelated paths. Discarding a missing cache is an idempotent no-op that creates no directory; null, symlink and ordinary-file roots are rejected. Identity binds package, version code/name, stable APK URL, byte count and expected complete-file SHA-256.
- Each request begins at the stable APK URL; redirects preserve the range and stay within the existing HTTPS whitelist. Redirect signatures are not persisted. Gzip, incorrect Content-Range or declared byte counts, and oversized bodies are rejected. Network I/O, timeouts, 5xx and premature ranged EOF have a three-attempt budget with interruptible backoff. HTTP 429/other 4xx and protocol/hash errors do not retry.
- Network interruption or process restart retains bounded part prefixes; a fresh instance requests only missing suffixes. A premature EOF is treated as a transient interruption, while extra bytes, incorrect range/total/length, unsupported encoding and failed complete-file SHA-256 discard the selected resume cache.
- Progress callbacks are serialized and bytes are monotonic within a transfer attempt. Initial progress includes cached bytes; counts track actual requests rather than configured slots. Losing range support emits an explicit zero-byte/zero-active reset before beginning a fresh sequential transfer. Checksum/merge reports zero active connections.
- Interruption closes tracked responses, cancels workers and joins them before returning. Verified APK publication happens only after merge, full SHA-256, fsync and atomic rename. An existing verified APK remains untouched on failure.

## Focused JVM run

Verbatim invocation:

```powershell
$root=(Get-Location).Path; . .data/android-tools/env.ps1; $jars=@((Get-ChildItem -LiteralPath "$env:GRADLE_USER_HOME/caches/modules-2/files-2.1/org.json/json/20240303" -Recurse -Filter '*.jar' -File).FullName,(Get-ChildItem -LiteralPath "$env:GRADLE_USER_HOME/caches/modules-2/files-2.1/junit/junit/4.13.2" -Recurse -Filter '*.jar' -File).FullName,(Get-ChildItem -LiteralPath "$env:GRADLE_USER_HOME/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3" -Recurse -Filter '*.jar' -File).FullName); $cp=$jars -join [IO.Path]::PathSeparator; $classes=Join-Path $root '.data/parallel-download-unit-classes'; New-Item -ItemType Directory -Force -Path $classes | Out-Null; & "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp $cp -d $classes android/app/src/main/java/app/autopilot/android/UpdateManifest.java android/app/src/main/java/app/autopilot/android/UpdateDownload.java android/app/src/test/java/app/autopilot/android/UpdateManifestTest.java android/app/src/test/java/app/autopilot/android/UpdateDownloadTest.java android/app/src/test/java/app/autopilot/android/UpdateParallelDownloadTest.java; if($LASTEXITCODE -ne 0){throw 'Focused javac failed'}; & "$env:JAVA_HOME/bin/java.exe" -cp ($classes+[IO.Path]::PathSeparator+$cp) org.junit.runner.JUnitCore app.autopilot.android.UpdateManifestTest app.autopilot.android.UpdateDownloadTest app.autopilot.android.UpdateParallelDownloadTest; if($LASTEXITCODE -ne 0){throw 'Focused JUnit failed'}
```

Verbatim runner output:

```text
JUnit version 4.13.2
...........................................
Time: 4.224

OK (43 tests)
```

Exit code: 0. The new fixtures prove actual simultaneous range reads using latches; serialized progress; owner interruption of blocked streams and fresh-instance missing-suffix recovery; HTTP 200 fallback at both probe and worker stages; explicit progress reset after discarding cached ranges; malformed range/size/encoding/oversize cleanup; previous verified-file preservation on whole-file hash mismatch; 5xx versus 429 request budgets; network I/O and premature-EOF suffix retry; persisted prefixes after exhausted EOF retries; range propagation through allowed GitHub redirects; manifest cache isolation/local discard boundaries; missing-cache discard without creating directories; unsafe-root rejection; and zero active connections after a fallback request fails to open. Existing manifest, sequential download and redirect tests ran in the same explicit runner.

## Limits

These deterministic fixtures use injected transports, with real Java threads/filesystem/merge/checksum logic. They do not establish a download-speed improvement on a particular network, phone-side background execution, installer behavior or a physical-device update. Orchestration owns the separate public HTTPS pause/resume runtime probe and full Android build/instrumentation evidence. No live download or full Gradle build was invoked by this focused test run.
