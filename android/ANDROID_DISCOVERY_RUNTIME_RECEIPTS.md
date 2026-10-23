# Production discovery runtime probe

This receipt covers `DiscoveryRuntimeTest` and its dedicated hosted runner. It does **not** claim a successful device or emulator run. The root release workflow runs and records the actual hosted instrumentation after the production runtime and this probe are frozen.

## Observable under test

The test imports a synthetic SOCKS pool through `ProviderRepository.importConfig`, then uses the ordinary `AutopilotVpnService.START` action. It does not seed a current node, invoke a private sweep/promotion method, replace native configuration, or change the selector. JNI `getProxies` is read only.

One healthy outbound is first under the production ordering; the remaining outbounds accept RFC1929 authentication and stall. The healthy fixture forwards opaque TLS records only to the fixed public hosts `www.gstatic.com`, `api.openai.com`, and `api.anthropic.com`, using the production HTTPS clients' certificate validation. A reserved documentation address serves a random HTTP marker locally; it is never forwarded externally. Protocol, relay, and deadline executors are bounded and cleanup closes sockets and waits for their threads.

The sole policy setting changed from a fresh installation is `required=["chatgpt"]`. This probe verifies ChatGPT eligibility and three-origin watchdog transport. It makes no Claude or Gemini service-eligibility claim.

Assertions cover selection within the first minute while the bad tail remains unfinished; watchdog success advancing after the ordinary interval while discovery is active; preservation of the native `AUTOPILOT.now`, current node and production TUN; coalesced retest acknowledgement without cursor/cycle reset; traffic from a different app UID before and after retest; and Stop/fixture cleanup. The retest acknowledgement is a strict increment of `discovery.lastAppliedRetestId`, which changes only after the public manual request reaches the real discovery controller. A periodic scan or an old coalesced snapshot cannot satisfy it. Handoff stage and random nonce must match, so a prior probe result cannot satisfy a later stage.

## Local static checks

Working directory: `D:/Github/onevpn-autopilot`.

```powershell
node --check android/tools/ci-discovery-check.cjs
```

Verbatim output: empty. Exit code: `0`.

```powershell
& 'C:/Program Files/Git/bin/bash.exe' -n android/tools/ci-native-check.sh
```

Verbatim output: empty. Exit code: `0`.

The following negative guard check is intentional. It refuses the local Windows host before inventorying or changing any device:

```powershell
node android/tools/ci-discovery-check.cjs android
```

Verbatim output begins:

```text
Error: Require a disposable GitHub Linux runner.
```

Exit code: `1`. No ADB command was executed by this invocation.

## Local APK compilation

```powershell
. ./.data/android-tools/env.ps1
gradle -p android --no-daemon --console=plain :app:assembleDebug :app:assembleDebugAndroidTest :probe:assembleDebug 2>&1 | Tee-Object -FilePath 'D:/Github/onevpn-autopilot/.data/discovery-runtime-compile.log'
$discoveryCompileExit=$LASTEXITCODE
if($discoveryCompileExit -ne 0){exit $discoveryCompileExit}
```

Verbatim compiler task and build tail:

```text
> Task :app:compileDebugAndroidTestJavaWithJavac
> Task :app:packageDebugAndroidTest
> Task :app:createDebugAndroidTestApkListingFileRedirect UP-TO-DATE
> Task :app:assembleDebugAndroidTest

[Incubating] Problems report is available at: file:///D:/Github/onevpn-autopilot/android/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.13/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 10s
96 actionable tasks: 11 executed, 85 up-to-date
```

Exit code: `0`. This compiled the new instrumentation and packaged debug app/test/probe APKs; it did not execute instrumentation. The installed local SDK also reported its existing command-line-tools XML-version warning. No emulator, physical device, installer, proxy, or VPN was operated for this compilation.

After the final production selector-failure correction, the same task set was rebuilt against frozen runtime sources:

```powershell
. ./.data/android-tools/env.ps1
gradle -p android --no-daemon --console=plain :app:assembleDebug :app:assembleDebugAndroidTest :probe:assembleDebug 2>&1 | Tee-Object -FilePath 'D:/Github/onevpn-autopilot/.data/discovery-runtime-compile-final.log'
$discoveryFinalCompileExit=$LASTEXITCODE
if($discoveryFinalCompileExit -ne 0){exit $discoveryFinalCompileExit}
```

Verbatim final tail:

```text
BUILD SUCCESSFUL in 8s
96 actionable tasks: 5 executed, 91 up-to-date
```

Exit code: `0`. Runtime execution still requires the dedicated hosted runner below.

## Hosted invocation and evidence

On the disposable GitHub Linux emulator, the existing `ci-native-check.sh` calls the new runner **after** the existing separate-UID tunnel runner:

```bash
node "$project/tools/ci-discovery-check.cjs" "$project" 2>&1 | tee "$results/discovery-runner.log"
```

The runner requires exactly one online emulator and no physical devices. It clears only the guarded debug package, grants VPN permission before instrumentation, and passes the explicit `hostedAllowPublicTransport=true` argument. It checks an exact standalone JUnit success, the method's success marker, both independent other-UID replies, and final fixture/cleanup JSON before emitting `ANDROID_DISCOVERY_RUNTIME_RECEIPT`.

The original selected-instrumentation XML catalog remains unchanged. Discovery has its own exact receipt; its result must not be combined with or inferred from the selected catalog's count.

Artifacts are written beneath `ci-native-results`: discovery instrumentation/runner logs, initial and retest readiness snapshots, both other-UID outputs, sanitized fixture counters/failure code, final evidence JSON, fixed-code diagnostics, and the success receipt if all gates pass. The fixture stores no provider account or password and never records HTTPS request paths, queries or bodies.

Public TLS reachability and service eligibility are real dependencies. If a public endpoint is unavailable, the probe fails rather than returning a synthetic successful HTTPS response. No runtime PASS is recorded in this local receipt.
