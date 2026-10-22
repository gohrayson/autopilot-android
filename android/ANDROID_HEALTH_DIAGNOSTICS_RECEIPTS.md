# Android health diagnostics verification — 2026-10-22

The pinned native bridge's `src/main/jni/core/action.go` sends `testDelay` as
`success(handleAsyncTestDelay(...))`. In `proxies.go`, URL-test errors, a deadline,
zero delay, and a missing proxy all become integer `-1`. Android now records that
as `LATENCY_FAILED`; it does not infer DNS failure from that integer.

Source contract:

- https://github.com/oviron/libmihomo-android/blob/5acd6a9ebf7946e18c8f0feaf8302fedf4ebb17d/src/main/jni/core/action.go
- https://github.com/oviron/libmihomo-android/blob/5acd6a9ebf7946e18c8f0feaf8302fedf4ebb17d/src/main/jni/core/proxies.go
- https://github.com/oviron/libmihomo-android/blob/5acd6a9ebf7946e18c8f0feaf8302fedf4ebb17d/src/main/jni/core/log_stream.go
- https://github.com/oviron/libmihomo-android/blob/5acd6a9ebf7946e18c8f0feaf8302fedf4ebb17d/src/main/jni/core/log_bridge.go
- https://github.com/MetaCubeX/mihomo/blob/v1.19.32/log/log.go
- https://github.com/MetaCubeX/mihomo/blob/v1.19.32/log/level.go

The stream filters against the profile's global `info` threshold. The separately
configured `error` logcat threshold does not suppress warning/error stream events.
The Java callback consumes `LogLevel` and `Payload` in memory, retains allowlisted
DNS/dial category counters only, and never stores the raw native network text.
These counters are session observations and do not attribute a failure to a node.

## Focused JVM verification

Run from `D:\Github\onevpn-autopilot`, PowerShell:

```powershell
$ErrorActionPreference='Stop'
$taskRepo='D:\Github\onevpn-autopilot'
$taskTools=Join-Path $taskRepo '.data/android-tools'
$taskJdk=Join-Path $taskTools 'jdk/jdk-17.0.20.1+1/bin'
$taskGradleLib=Join-Path $taskTools 'gradle/gradle-8.13/lib'
$taskJson=Join-Path $taskTools 'gradle-home/caches/modules-2/files-2.1/org.json/json/20240303/ebb88e8fb5122b7506d5cf1d69f1ccdb790d22a/json-20240303.jar'
$taskClasspath="$taskGradleLib/junit-4.13.2.jar;$taskGradleLib/hamcrest-core-1.3.jar;$taskJson"
$taskOut=Join-Path $taskRepo '.data/android-health-diagnostics-tests'
New-Item -ItemType Directory -Force -Path $taskOut | Out-Null
& (Join-Path $taskJdk 'javac.exe') -encoding UTF-8 -cp $taskClasspath -d $taskOut (Join-Path $taskRepo 'android/app/src/main/java/app/autopilot/android/HealthDiagnostics.java') (Join-Path $taskRepo 'android/app/src/test/java/app/autopilot/android/HealthDiagnosticsTest.java')
if($LASTEXITCODE -ne 0){throw 'Health diagnostic test compilation failed'}
& (Join-Path $taskJdk 'java.exe') -cp "$taskOut;$taskClasspath" org.junit.runner.JUnitCore app.autopilot.android.HealthDiagnosticsTest
if($LASTEXITCODE -ne 0){throw 'Health diagnostic tests failed'}
```

Verbatim runner output:

```text
JUnit version 4.13.2
.......
Time: 0.039

OK (7 tests)
```

These tests verify strict response parsing; bridge failure versus watchdog timeout;
fixed-code warning classification; secret-free counters; detached snapshots;
per-provider tested, failed, stale and service-check counts; and allowlisted UI
messages. They do not test the physical phone or prove Android TUN forwarding.
Combined Android compilation, lint and native runtime verification are recorded by
the coordinating build separately.

## Production wiring

- `VpnEngine` stores each latency result with its numeric native action code when
  present, updates both sweep and active-node paths, and exposes test timestamps.
- Fresh nodes remain `Not tested` until a probe finishes. Expired checks and
  failed service checks are separately visible.
- Copied diagnostics include provider health aggregates, the resolver policy ID,
  and fixed-code native network observations. The health-check path explicitly
  identifies Autopilot's excluded UID and mixed listeners; it is not a TUN probe.
- `AutopilotApp` restores persisted rejections as historical failures. A current
  rejection is separate; successful profile/recovery acceptance clears the current
  rejection while retaining the historical record. Baseline acceptance clears a
  current baseline rejection only.

No provider requests, account operations, desktop VPN changes, global Java settings,
build version changes, or dependency changes were performed by this diagnostics task.
