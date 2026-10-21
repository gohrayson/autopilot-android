# Android download route and ARM64 mirror receipt

Run on 2026-10-21 from `D:\Github\onevpn-autopilot`. These measurements describe this **desktop's existing proxy route**, not the user's Android connection. They do not establish that the mirror is faster on the phone.

Read-only discovery found Windows proxy enabled at `127.0.0.1:7890`; `netstat -ano -p tcp` showed that listener owned by PID 12280. No proxy, VPN, route, service or registry setting was changed. Requests use public URLs without credentials. The curl default configuration is disabled, and response headers and redirected signed URLs are not printed.

## Bounded samples

Both invocations request bytes 0–1048575, permit only HTTPS redirects, stop after 15 seconds, enforce a 2 MiB response ceiling and delete their uniquely named sample file.

Canonical invocation:

```powershell
node .data/android-download-route-probe/probe.cjs canonical https://github.com/gohrayson/autopilot-android/releases/download/android-v0.1.2/Autopilot-Android-0.1.2.apk 7890
```

Verbatim output:

```json
{"label":"canonical","network":"desktop-existing-local-proxy","curlExitCode":0,"httpStatus":206,"downloadedBytes":1048576,"firstByteSeconds":0.952205,"averageBytesPerSecond":400328,"totalSeconds":2.619292,"redirects":1}
```

Mirror invocation:

```powershell
node .data/android-download-route-probe/probe.cjs mirror https://gohrayson.github.io/autopilot-android/Autopilot-Android-0.1.2-arm64-v8a.apk 7890
```

Verbatim output:

```json
{"label":"mirror","network":"desktop-existing-local-proxy","curlExitCode":0,"httpStatus":206,"downloadedBytes":1048576,"firstByteSeconds":2.058655,"averageBytesPerSecond":173602,"totalSeconds":6.040105,"redirects":0}
```

Both public routes returned the requested sample. The mirror sample was slower than the canonical sample in this sequential desktop measurement. Cache state and network conditions can vary between requests; this is not a controlled throughput benchmark.

## Full ARM64 APK attempt

The full attempt allowed 60 seconds with a 17 MiB response ceiling. The helper would verify the complete expected size, SHA-256, actual APK signature, pinned production certificate, package/version/minSdk and ARM64-only ABI after a successful download. It deleted the partial file on failure.

Verbatim invocation:

```powershell
node .data/android-download-route-probe/full-mirror.cjs
```

Verbatim output:

```text
{"label":"mirror-full-arm64","network":"desktop-existing-local-proxy","curlExitCode":28,"httpStatus":200,"downloadedBytes":2774848,"firstByteSeconds":0.58562,"averageBytesPerSecond":46245,"totalSeconds":60.002066,"redirects":0}
D:\Github\onevpn-autopilot\.data\android-download-route-probe\full-mirror.cjs:18
  if (result.error || result.status !== 0 || performance.httpStatus !== 200) throw new Error('Bounded full download failed.');
                                                                             ^

Error: Bounded full download failed.
    at Object.<anonymous> (D:\Github\onevpn-autopilot\.data\android-download-route-probe\full-mirror.cjs:18:84)
    at Module._compile (node:internal/modules/cjs/loader:1812:14)
    at Object..js (node:internal/modules/cjs/loader:1943:10)
    at Module.load (node:internal/modules/cjs/loader:1533:32)
    at Module._load (node:internal/modules/cjs/loader:1335:12)
    at wrapModuleLoad (node:internal/modules/cjs/loader:255:19)
    at Module.executeUserEntryPoint [as runMain] (node:internal/modules/run_main:154:5)
    at node:internal/main/run_main_module:33:47

Node.js v24.14.0
```

Exit code: 1. The server responded HTTP 200, but curl timed out after downloading 2,774,848 bytes. **Hosted full-file hash, signature and package checks were not reached.** No complete APK was retained or installed.

Expected ARM64 release metadata supplied by orchestration, **not independently established by this incomplete mirror download**:

- Size: `16270898` bytes.
- SHA-256: `bb7a0d5f8b53f6d3a043c62bc376ef752487ab4461c2cf172483fd69ea350028`.
- Production certificate SHA-256 pin: `6c6831265f4d5593b499b2bfc1bbcaf93f3eb4d143f49fef192d811d4b6060ab`.

The existing full universal release was previously verified through the production updater; its evidence is in `ANDROID_UPDATE_HOSTED_RECEIPTS.md`. That result does not substitute for verification of this separate ARM64 mirror artifact.
