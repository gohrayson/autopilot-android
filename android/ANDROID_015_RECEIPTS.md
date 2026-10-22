# Android 0.1.5 connection status verification

The prior status title used an empty selected-node name as a reason to display
`Ready to connect`, even while the controller was starting or testing nodes.
The Activity now uses the actual controller phase and stage for its title and
badge. Startup publishes before core I/O on the serialized worker; progress
counts completed latency checks at existing batch boundaries. Service checks
show the candidate being checked. A connected node keeps its name during
background checks, and Auto/Pinned detail is restored when checks finish.

The change preserves the 0.1.4 userspace TUN, resolver policy, provider access
and updater. The physical phone remains connected on the verified 0.1.4 while
this UI release is prepared; no further phone restart was performed.

## Local build and Android instrumentation compilation

```powershell
& android/tools/build.ps1 -Configuration Release 2>&1 |
  Tee-Object -FilePath 'D:\Github\onevpn-autopilot\.data\android-v015-build.log'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
gradle.bat -p android --no-daemon --console=plain :app:assembleDebug :app:assembleDebugAndroidTest :probe:assembleDebug 2>&1 |
  Tee-Object -FilePath 'D:\Github\onevpn-autopilot\.data\android-v015-instrument-build.log'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

Verbatim runner tails, exit 0:

```text
BUILD SUCCESSFUL in 30s
79 actionable tasks: 37 executed, 42 up-to-date
APK: D:\Github\onevpn-autopilot\dist\android\Autopilot-Android-0.1.5.apk
SHA256: ac20475463374df5784566203a4d10bcb9d65af6bfeae37fc791a5f6fbf59d11

BUILD SUCCESSFUL in 8s
95 actionable tasks: 16 executed, 79 up-to-date
```

JUnit XML receipt (the build invokes the real unit test runner):

```powershell
$suites = Get-ChildItem android/app/build/test-results/testDebugUnitTest/TEST-*.xml | ForEach-Object { [xml]$r=Get-Content -LiteralPath $_.FullName -Raw; $r.testsuite }
$tests=($suites | Measure-Object tests -Sum).Sum; $fails=($suites | Measure-Object failures -Sum).Sum; $errors=($suites | Measure-Object errors -Sum).Sum; $skips=($suites | Measure-Object skipped -Sum).Sum
"ANDROID_015_JVM_XML_RECEIPT tests=$tests failures=$fails errors=$errors skipped=$skips suites=$($suites.Count)"
```

```text
ANDROID_015_JVM_XML_RECEIPT tests=134 failures=0 errors=0 skipped=0 suites=15
```

Signed package and native packaging verification:

```powershell
& android/tools/verify-apk.ps1 2>&1 |
  Tee-Object -FilePath 'D:\Github\onevpn-autopilot\.data\android-v015-verify.log'
```

The APK reports package `app.autopilot.android`, version code 6, version name
0.1.5 and the unchanged release certificate
`6c6831265f4d5593b499b2bfc1bbcaf93f3eb4d143f49fef192d811d4b6060ab`.
The verifier passed native alignment, RELRO, ZIP alignment and the private-data
exclusion checks. The publisher additionally validates the pinned identity.

## Actual Android status rendering

`VpnStatusUiTest.connectionProgressAndActiveNodeRenderTruthfully` opens the
real `MainActivity`, publishes controller snapshots through `AutopilotApp`,
and reads the displayed TextViews after Android's main-thread callbacks.
It asserts starting, connection counts, service checks, connected Auto/Pinned,
background checks preserving the active node, degraded/error, stopped, and
account-required states. It does not create a VPN, import credentials or call a
provider. The hosted runner explicitly includes this method in its XML catalog,
alongside the native baseline/download cases; the real separate-UID TUN gate
still runs afterward.

Hosted run [39707595366](https://github.com/gohrayson/autopilot-android/actions/runs/39707595366),
job `119591167571`, source `f75e194277a4ff796440e28694faca302cb21524`,
completed successfully. Exact invocation:

```bash
bash "$AUTOPILOT_ANDROID_DIR/tools/ci-native-check.sh" "$AUTOPILOT_ANDROID_DIR"
```

Verbatim runner output:

```text
2026-10-22T01:48:23.5163711Z Starting 4 tests on emulator-5554 - 15
2026-10-22T01:48:27.0154869Z Finished 4 tests on emulator-5554 - 15
2026-10-22T01:48:28.1378719Z ANDROID_RUNTIME_XML_RECEIPT tests=4 expected=4 failures=0 errors=0 skipped=0 missing=0 duplicates=0 unexpected=0
2026-10-22T01:48:36.4168024Z OK (1 test)
2026-10-22T01:48:36.4376223Z ANDROID_TUN_RUNTIME_RECEIPT tests=1 expected=1 failures=0 separateUid=true ipv4=true ipv6=true endpointBootstrapDns=true appDns=true fakeIpPool=true fakeIpReverseMapping=true stopCleanup=true
```

Artifact `12396120420` preserves the actual XML, UI-test logcat and fixture
traffic. The strict XML method catalog requires
`VpnStatusUiTest.connectionProgressAndActiveNodeRenderTruthfully` to exist and
pass exactly once; missing, duplicate, skipped and unexpected methods fail CI.
The separate TUN gate additionally verifies real IPv4/IPv6, endpoint/application
DNS, fake-IP reverse mapping and service Stop cleanup.

The 0.1.5 UI was verified on the hosted Android emulator. The physical phone's
working 0.1.4 connection was preserved while preparing the update. Documentation
changes following this successful run do not change the tested Java sources.

## Publication and live updater discovery

Published release `420229110` at `2026-10-22T01:55:03Z`:
[android-v0.1.5](https://github.com/gohrayson/autopilot-android/releases/tag/android-v0.1.5).
The public release was read back through the GitHub API: `draft=false`, all
assets uploaded, APK size `47779978`, APK SHA-256
`ac20475463374df5784566203a4d10bcb9d65af6bfeae37fc791a5f6fbf59d11`.
The source ZIP SHA-256 is
`2d55503b1646b8285262f9f358a77c72154b39e69ac334d33e21b505bdbeb4dd`.
Tag source `1367538` adds only documentation after the tested `f75e194` sources.

The retained original updater classes from tag `android-v0.1.2` fetched and
validated the actual public latest manifest through the existing local HTTPS
proxy; no proxy or VPN settings changed. Reproduction from the repo root:

```powershell
$ErrorActionPreference='Stop'
& .data/hosted-update-probe/probe-legacy.ps1 -CompileOnly
. .data/android-tools/env.ps1
$jsonJar=@(Get-ChildItem -LiteralPath (Join-Path $env:GRADLE_USER_HOME 'caches/modules-2/files-2.1/org.json/json/20240303') -Recurse -File -Filter 'json-20240303.jar')
if($jsonJar.Count -ne 1){throw 'Expected cached org.json JAR'}
$classPath=(Join-Path (Get-Location) '.data/legacy-012-discovery/classes')+[IO.Path]::PathSeparator+$jsonJar[0].FullName
& (Join-Path $env:JAVA_HOME 'bin/java.exe') '-Dhttps.proxyHost=127.0.0.1' '-Dhttps.proxyPort=7890' -cp $classPath app.autopilot.android.LegacyFeedProbe 'https://github.com/gohrayson/autopilot-android/releases/latest/download/latest.json' '0.1.5' 'ac20475463374df5784566203a4d10bcb9d65af6bfeae37fc791a5f6fbf59d11'
if($LASTEXITCODE -ne 0){throw 'Published feed discovery failed'}
```

Verbatim output:

```text
Legacy discovery probe compiled against retained 0.1.2 classes; no network request.
LEGACY_012_UPDATE_DISCOVERY_PASS version=0.1.5 versionCode=6 newerThanInstalled3=true Android16Supported=true sha256=ac20475463374df5784566203a4d10bcb9d65af6bfeae37fc791a5f6fbf59d11
```

Log: `.data/android-015-live-update-feed.log`. This proves hosted discovery and
manifest compatibility; it does not claim the phone has installed 0.1.5.
The physical phone remains on its working 0.1.4 connection. The release uses the
same app ID and signing certificate for an in-place update retaining app data.
This publication receipt is a post-release documentation addition; published
release assets were not regenerated or overwritten.
