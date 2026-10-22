# Android 0.1.4 investigation and release verification

The phone and desktop share the same router. Android 0.1.3 loaded provider nodes
and accepted its native baseline/profile, but other apps could not send traffic.
The old schema rejection in copied diagnostics was historical.

## Reproduced device traffic failure

Hosted run [39704336018](https://github.com/gohrayson/autopilot-android/actions/runs/39704336018),
job `119580694481`, source `f49d76edbc90372d7613af1f6be1663ee9a68a97`,
ran the production service with an isolated SOCKS/DNS fixture. Invocation:

```bash
bash "$AUTOPILOT_ANDROID_DIR/tools/ci-native-check.sh" "$AUTOPILOT_ANDROID_DIR"
```

The baseline/download tests passed, then the separate probe APK's numeric IPv4
request failed. Verbatim decisive output:

```text
2026-10-22T01:14:46.5849907Z ANDROID_RUNTIME_XML_RECEIPT tests=3 expected=3 failures=0 errors=0 skipped=0 missing=0 duplicates=0 unexpected=0
2026-10-22T01:14:59.2665658Z app.autopilot.android.NativeTunnelRuntimeTest:INSTRUMENTATION_RESULT: error=SocketTimeoutException: failed to connect to /203.0.113.7 (port 80) from /172.19.0.1 (port 48520) after 10000ms
2026-10-22T01:14:59.2669417Z INSTRUMENTATION_RESULT: resolved=203.0.113.7
2026-10-22T01:14:59.2670136Z INSTRUMENTATION_RESULT: result=FAIL
```

Artifact `12393834797` records one successful native `testDelay` marker request
and endpoint DNS lookup, but no SOCKS request from the separate UID. This
reproduces successful node checks with broken application TCP; it does not rely
on a live provider or on DNS resolution for the failing destination.

The previous `mixed` TUN stack uses a kernel TCP forwarder. Autopilot excludes
its own UID to keep provider/update traffic outside the tunnel; this conflicts
with the forwarder's return route. The candidate uses the existing AAR's
`gvisor` TCP/UDP stack instead, preserving UID exclusion and socket protection.
Native source: [mixed stack](https://github.com/MetaCubeX/sing-tun/blob/v0.4.27/stack_mixed.go),
[kernel forwarder](https://github.com/MetaCubeX/sing-tun/blob/v0.4.27/stack_system.go),
[userspace stack](https://github.com/MetaCubeX/sing-tun/blob/v0.4.27/stack_gvisor.go).

Android's distinct Cloudflare/Google resolver policy was also replaced with
the desktop AliDNS/Tencent bootstrap and resolver policy. The runtime factory
comparison is in [ANDROID_DNS_PARITY_RECEIPTS.md](ANDROID_DNS_PARITY_RECEIPTS.md).
That configuration difference is proven; the phone's former resolver reachability
was not measured. Different node totals also include different country filtering:
Android drops CN/HK/MO during parsing, whereas desktop retains those in its cache
and excludes them during eligibility checks.

## Final local build

```powershell
& android/tools/build.ps1 -Configuration Release 2>&1 |
  Tee-Object -FilePath 'D:\Github\onevpn-autopilot\.data\android-v014-gvisor-build.log'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

Verbatim tail, exit 0:

```text
BUILD SUCCESSFUL in 22s
79 actionable tasks: 20 executed, 59 up-to-date
APK: D:\Github\onevpn-autopilot\dist\android\Autopilot-Android-0.1.4.apk
SHA256: 77409c8b3a087ccf10534925c518ad71c2e45429bb869ef173c7acc69b7a827e
```

The build invokes unit tests, lint and signed release assembly. Numeric test
results are read from the actual JUnit XML, not from task totals:

```powershell
$suites = Get-ChildItem android/app/build/test-results/testDebugUnitTest/TEST-*.xml | ForEach-Object { [xml]$r=Get-Content -LiteralPath $_.FullName -Raw; $r.testsuite }
$tests=($suites | Measure-Object tests -Sum).Sum; $fails=($suites | Measure-Object failures -Sum).Sum; $errors=($suites | Measure-Object errors -Sum).Sum; $skips=($suites | Measure-Object skipped -Sum).Sum
"ANDROID_014_JVM_XML_RECEIPT tests=$tests failures=$fails errors=$errors skipped=$skips suites=$($suites.Count)"
$declared = (Get-ChildItem android/app/src/test/java -Recurse -Filter *.java | ForEach-Object { ([regex]::Matches((Get-Content -Raw -LiteralPath $_.FullName), '@Test\b')).Count } | Measure-Object -Sum).Sum
"ANDROID_014_DECLARED_JVM_TESTS=$declared"
if ($declared -ne $tests -or $fails -or $errors -or $skips) { throw 'JUnit source/report mismatch or failed verification' }
```

```text
ANDROID_014_JVM_XML_RECEIPT tests=134 failures=0 errors=0 skipped=0 suites=15
ANDROID_014_DECLARED_JVM_TESTS=134
```

## APK identity and native packaging

```powershell
& android/tools/verify-apk.ps1 2>&1 |
  Tee-Object -FilePath 'D:\Github\onevpn-autopilot\.data\android-v014-gvisor-verify.log'
```

The signed artifact reports package `app.autopilot.android`, version code 5,
version name 0.1.4, minimum SDK 26 and the existing release signer
`6c6831265f4d5593b499b2bfc1bbcaf93f3eb4d143f49fef192d811d4b6060ab`.
The verifier checks native alignment, RELRO, ZIP alignment and absence of private
account/signing/log or probe runtime artifacts. The release publisher separately
requires this exact package/version/SDK and signing certificate before upload.

## Physical Android 16 baseline

The USB-authorized phone reports installed version 0.1.3, SDK 36. Its active
Autopilot VPN covers UID 2000 while excluding owner UID 10436. A private DEX
helper executed as the shell UID without installing an APK, changing settings,
changing VPN routes, or reading account files:

```powershell
& .data/android-tools/sdk/platform-tools/adb.exe -s 603f969d push .data/android-phone-probe/autopilot-shell-probe.jar /data/local/tmp/autopilot-shell-probe.jar
& .data/android-tools/sdk/platform-tools/adb.exe -s 603f969d shell 'CLASSPATH=/data/local/tmp/autopilot-shell-probe.jar app_process /system/bin app.autopilot.probe.ShellTunnelProbe'
```

Verbatim probe output, exit 0 (the helper reports observations; it does not
return an all-network-checks-passed exit status):

```text
{"kind":"start","uid":2000}
{"kind":"network","uid":2000,"label":"literal_tcp","result":"SOCKET_TIMEOUT","status":-1,"duration_ms":5005}
{"kind":"network","uid":2000,"label":"literal_https","result":"SOCKET_TIMEOUT","status":-1,"duration_ms":5071}
{"kind":"network","uid":2000,"label":"hostname_https","result":"SOCKET_TIMEOUT","status":-1,"duration_ms":5015}
{"kind":"network","uid":2000,"label":"openai_https","result":"SOCKET_TIMEOUT","status":-1,"duration_ms":5013}
{"kind":"complete","result":"FINISHED","uid":2000}
```

The helper uses `Proxy.NO_PROXY` and strict Android TLS, emits no response
bodies or addresses, and bounds each operation. This corroborates the physical
phone's application traffic failure before the patch.

## Hosted post-change verification

Run [39704959404](https://github.com/gohrayson/autopilot-android/actions/runs/39704959404),
source `536009485c18f1d0915a4dfd3c68c14f125b6ed0`, changed the production
stack from mixed to gVisor and restored both numeric IPv4 and IPv6 traffic in
the same failing fixture. Its subsequent hostname request was blocked by
Android's UID policy before any DNS packet left netd. The test-only probe now
opens a foreground activity and waits for Android's VPN callback to report
`blocked=false`; no production DNS policy was disabled or bypassed for this.

The complete run [39705698643](https://github.com/gohrayson/autopilot-android/actions/runs/39705698643),
job `119585104735`, source `b623dd51f50b26031dc261b8e65fb41a809adc67`,
passed the original native/download XML gate and the separate-UID TUN gate.
Invocation is the same `ci-native-check.sh` command above. Verbatim results:

```text
2026-10-22T01:28:55.8313837Z ANDROID_RUNTIME_XML_RECEIPT tests=3 expected=3 failures=0 errors=0 skipped=0 missing=0 duplicates=0 unexpected=0
2026-10-22T01:28:59.5283099Z INSTRUMENTATION_RESULT: result=PASS
2026-10-22T01:28:59.5363875Z INSTRUMENTATION_RESULT: target=http://203.0.113.7/autopilot-probe
2026-10-22T01:29:01.6241268Z INSTRUMENTATION_RESULT: result=PASS
2026-10-22T01:29:01.6303703Z INSTRUMENTATION_RESULT: target=http://[2001:db8::7]/autopilot-probe
2026-10-22T01:29:02.9629619Z INSTRUMENTATION_RESULT: result=PASS
2026-10-22T01:29:02.9630447Z INSTRUMENTATION_RESULT: target=http://tunnel-fixture.autopilot.test/autopilot-probe
2026-10-22T01:29:04.8579506Z INSTRUMENTATION_RESULT: resolved=198.18.0.4
2026-10-22T01:29:04.8581038Z INSTRUMENTATION_RESULT: result=PASS
2026-10-22T01:29:04.8582262Z INSTRUMENTATION_RESULT: target=http://fakeip-fixture.autopilot.test/autopilot-probe
2026-10-22T01:29:05.2865599Z ANDROID_TUN_RUNTIME_RECEIPT tests=1 expected=1 failures=0 separateUid=true ipv4=true ipv6=true endpointBootstrapDns=true appDns=true fakeIpPool=true fakeIpReverseMapping=true stopCleanup=true
```

The probe UID was 10152, distinct from VPN owner UID 10150; each request
reported VPN readiness and `importance=100`. Fixture assertions require an
actual upstream DNS query for the endpoint and filtered application hostname,
and require fake-IP reverse mapping to the original unfiltered hostname.
Only Android's opportunistic DoT attempts to the synthetic DNS address are
allowed to be rejected. Other unexpected destinations fail the run.
Artifact `12395201627` preserves the actual fixture, probes and logcat.

The signed production APK uses the same production Java sources as this
successful run. Following changes to this receipt and README are documentation
only. No public 0.1.4 release was made on the strength of local tests alone.

## Publication and physical phone verification

```powershell
node android/tools/publish-update.cjs --publish --notes-file .data/android-release-notes-014.txt
```

The publisher returned `published: true`. Independent GitHub API readback
confirmed release `420217896`, tag `android-v0.1.4`, `draft: false`, and matching
SHA-256 digests for every uploaded asset. APK size is 47,778,670 bytes with the
hash above. The corresponding source ZIP SHA-256 is
`5f753b98658c3848307b58a19256d9fbf86ba5fdba5aa50c193b3b85a4bb7860`.
Release source commit is `1c79241` (documentation only after the successful
runtime commit). Release: <https://github.com/gohrayson/autopilot-android/releases/tag/android-v0.1.4>.
The existing feed URL remains unchanged.

The installed phone was updated in place:

```powershell
& .data/android-tools/sdk/platform-tools/adb.exe -s 603f969d install -r dist/android/Autopilot-Android-0.1.4.apk
```

```text
Performing Streamed Install
Success
versionCode=5 minSdk=26 targetSdk=35
versionName=0.1.4
```

Android correctly refused a shell start of the private VPN service. The user
started it through the app and confirmed `Connected`. The same shell UID
remained covered by the new Autopilot VPN. After a diagnostic helper update
that adds only allowlisted error class reporting, the same bounded strict-TLS
requests returned:

```text
{"kind":"start","uid":2000}
{"kind":"network","uid":2000,"label":"literal_tcp","result":"TCP_CONNECTED","status":0,"local_family":"ipv4","tun_source":true,"duration_ms":3}
{"kind":"network","uid":2000,"label":"literal_https","result":"SOCKET_TIMEOUT","status":-1,"failure_class":"java.net.SocketTimeoutException","cause_classes":[],"tls_classification":"TLS_UNCLASSIFIED","duration_ms":5036}
{"kind":"network","uid":2000,"label":"hostname_https","result":"HTTP_RESPONSE","status":204,"duration_ms":290}
{"kind":"network","uid":2000,"label":"openai_https","result":"HTTP_RESPONSE","status":200,"duration_ms":468}
{"kind":"complete","result":"FINISHED","uid":2000}
```

Thus actual HTTPS to the production latency endpoint and OpenAI responded on
the phone through an excluded-owner, different-UID TUN path. The literal
Cloudflare trace endpoint still timed out and is not counted as passing. An
earlier immediate run had TLS failures while the connection was settling;
those are also not counted as passing. The user independently confirmed that
browsing and sending now work, and confirmed provider nodes in response to
the OneVPN availability question.

The original 0.1.2 updater classes, compiled from their published tag, also
fetched and accepted the real public 0.1.4 feed. Exact invocation:

```powershell
& .data/hosted-update-probe/probe-legacy.ps1 -CompileOnly
. .data/android-tools/env.ps1
$jar=@(Get-ChildItem -LiteralPath (Join-Path $env:GRADLE_USER_HOME 'caches/modules-2/files-2.1/org.json/json/20240303') -Recurse -File -Filter json-20240303.jar)[0].FullName
$cp=(Join-Path $PWD '.data/legacy-012-discovery/classes')+[IO.Path]::PathSeparator+$jar
& (Join-Path $env:JAVA_HOME 'bin/java.exe') '-Dhttps.proxyHost=127.0.0.1' '-Dhttps.proxyPort=7890' -cp $cp app.autopilot.android.LegacyFeedProbe 'https://github.com/gohrayson/autopilot-android/releases/latest/download/latest.json' '0.1.4' '77409c8b3a087ccf10534925c518ad71c2e45429bb869ef173c7acc69b7a827e'
```

```text
LEGACY_012_UPDATE_DISCOVERY_PASS version=0.1.4 versionCode=5 newerThanInstalled3=true Android16Supported=true sha256=77409c8b3a087ccf10534925c518ad71c2e45429bb869ef173c7acc69b7a827e
```

This section is post-publication evidence; the published source ZIP and release
assets remain unchanged. The desktop VPN, proxy and running app were not altered.
