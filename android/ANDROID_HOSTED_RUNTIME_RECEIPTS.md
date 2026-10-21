# Hosted Android native runtime probe

Prepared and executed 2026-10-21. The actual Android AAR accepts the production
empty baseline after the slash-encoding fix and returns the `AUTOPILOT` selector
as `REJECT`. This is an Android JNI/configuration probe, with no TUN or provider
connection. It does not establish phone traffic, routing or app-update installation.

## Runnable hosted probe

Copy `tools/hosted-native-probe.yml` to
`.github/workflows/hosted-native-probe.yml` in the public Android source repository.
Keep `tools/ci-native-check.sh` and
`app/src/androidTest/java/app/autopilot/android/NativeCoreConfigTest.java` with the
same source snapshot. Both Android-at-repository-root and nested `android/` layouts
are supported. Push to `main`, or invoke the workflow manually.

The disposable `ubuntu-24.04` runner uses Temurin JDK 17, Gradle 8.13, Android API
35 x86_64 and KVM. Only this instrumentation class runs:

```sh
gradle -p "$AUTOPILOT_ANDROID_DIR" --no-daemon --console=plain \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.autopilot.android.NativeCoreConfigTest
```

The fixture submits the exact empty `VpnEngine.configuration()` baseline, encoded
by the production `CoreConfigEncoding.encode` writer, to the
actual Android AAR `Clash.quickSetup`, checks its raw return value, then verifies
the native `AUTOPILOT` selector through `getProxies`. It requests no VPN consent,
starts no TUN, and contains no account or provider data. The exact submitted
baseline and raw native error can therefore be preserved in JUnit/logcat without
redacting the field that caused rejection. It makes no live provider API call.

The helper fails if Gradle fails, or if the selected class produces zero tests,
failures, errors or skips. Its EXIT trap captures logcat and device API/ABI/page
size while the emulator still runs. `actions/upload-artifact@v4` runs with
`if: always()` and retains build output, JUnit reports and logcat for seven days,
including when native setup or instrumentation fails. No release signing,
credentials, keystores, publishing, Windows settings, routes or running VPNs are
part of this workflow.

## Pinned native dependency

The workflow downloads only the pinned upstream release when the AAR is absent,
and verifies SHA-256 even when it already exists:

- URL: <https://github.com/oviron/libmihomo-android/releases/download/v0.3.11/libmihomo-android-v0.3.11.aar>
- SHA-256: `01bc41748840c8200bbaf08c044ba8704e35303a3179b75a3cce8517c8944251`

Local verification invocation:

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'D:\Github\onevpn-autopilot\android\app\libs\libmihomo-android-v0.3.11.aar'
```

Observed hash:

```text
SHA256  01BC41748840C8200BBAF08C044BA8704E35303A3179B75A3CCE8517C8944251
```

## Static preparation receipt

Executed from `D:\Github\onevpn-autopilot`. Git Bash performs syntax checking
only; no local Gradle or emulator invocation occurs:

```powershell
node -e 'const fs=require("fs"),yaml=require("yaml"),cp=require("child_process");const p="android/tools/hosted-native-probe.yml";const d=yaml.parse(fs.readFileSync(p,"utf8"));if(!Object.hasOwn(d.on,"workflow_dispatch")||d.jobs["native-core"]["runs-on"]!=="ubuntu-24.04")throw Error("workflow shape invalid");const bash="C:/Program Files/Git/bin/bash.exe";cp.execFileSync(bash,["-n","android/tools/ci-native-check.sh"],{stdio:"pipe"});let checked=0;for(const s of d.jobs["native-core"].steps){if(s.run){cp.execFileSync(bash,["-n"],{input:s.run});checked++;}}for(const p of ["android/tools/hosted-native-probe.yml","android/tools/ci-native-check.sh"]){if(fs.readFileSync(p).includes(13))throw Error("CRLF "+p)}console.log("WORKFLOW_YAML_PARSE_OK");console.log("BASH_HELPER_SYNTAX_OK");console.log("WORKFLOW_INLINE_BASH_SYNTAX_OK scripts="+checked);console.log("LF_ONLY_OK");'
```

Verbatim output, exit 0:

```text
WORKFLOW_YAML_PARSE_OK
BASH_HELPER_SYNTAX_OK
WORKFLOW_INLINE_BASH_SYNTAX_OK scripts=3
LF_ONLY_OK
```

Primary setup references:

- [Official emulator runner and KVM instructions](https://github.com/ReactiveCircus/android-emulator-runner)
- [Emulator action input contract](https://github.com/ReactiveCircus/android-emulator-runner/blob/main/action.yml)
- [Official Gradle setup action](https://github.com/gradle/actions/tree/main/setup-gradle)
- [Official Java setup action](https://github.com/actions/setup-java)
- [Official artifact uploader v4](https://github.com/actions/upload-artifact/tree/v4)

## Hosted execution evidence

Evidence was fetched through the GitHub connector. The artifact ZIP was read in
memory; its JUnit XML, Gradle tail, device-property files and logcat were inspected.
No account data or live provider profiles are included.

| Run | Result | Evidence |
| --- | --- | --- |
| [39601339601](https://github.com/gohrayson/autopilot-android/actions/runs/39601339601) | CI provisioning failed, exit 127; native test did not run | `sdkmanager` was missing from PATH. Added `android-actions/setup-android@v3` before the SDK/build step. |
| [39601782528](https://github.com/gohrayson/autopilot-android/actions/runs/39601782528) | Native empty baseline rejected | Actual Android JSON bytes contained optional `\/` escapes; YAML parsing returned `yaml: found unknown escape character`. |
| [39603051050](https://github.com/gohrayson/autopilot-android/actions/runs/39603051050) | Passed | Same fixed fixture now uses the production encoder, is accepted by the actual Android core, and reports selector `REJECT`. |

The initial SDK failure's `ci-native-results/sdk-install.log` contains:

```text
/home/runner/work/_temp/6c8e336d-b3dc-48ee-9dbb-38d8a562d428.sh: line 10: sdkmanager: command not found
```

The native-rejection run used commit
`b78daf1616fcefb82dfb5136d5b5263952dceca8`. Its artifact is
[native-core-api35-39601782528-1](https://github.com/gohrayson/autopilot-android/actions/runs/39601782528/artifacts/12346457393).
The actual JUnit failure preserved these credential-free fields:

```text
SDK=35 ABI=x86_64 bytes=623 sha256=84a600e2d5594f75694b69c9b6e7c868926d08eca8fa5ff96376b2b90f36260e
RAW_NATIVE_RESULT=yaml: found unknown escape character
```

The rejected profile contained `"fake-ip-range":"198.18.0.1\/16"` and
`"nameserver":["https:\/\/1.1.1.1\/dns-query", ...]`. Android's `JSONObject`
emits these valid JSON escapes; the core reads `config.yaml` using YAML, whose
double-quoted scalar grammar rejects `\/`. The earlier desktop/JVM JSON serializer
did not emit these escapes, so its passing host-parser probe missed the defect.
`CoreConfigEncoding` consumes escape pairs atomically and removes only optional
slash escapes, preserving literal backslashes and string values. `VpnEngine`
and `NativeCoreConfigTest` both call this same encoder.

Successful source commit:
[`742c5f4045628848798bf981ae095fcc458c0736`](https://github.com/gohrayson/autopilot-android/commit/742c5f4045628848798bf981ae095fcc458c0736).
Job: [119232150825](https://github.com/gohrayson/autopilot-android/actions/runs/39603051050/job/119232150825).
Artifact: [native-core-api35-39603051050-1](https://github.com/gohrayson/autopilot-android/actions/runs/39603051050/artifacts/12346866988),
68,897 bytes, SHA-256
`b9a6cdfda923a25990c95c4a4ea1760d495e266e20a848e37b0d234b5e3aff85`
(downloaded ZIP hash matched GitHub's artifact digest). The artifact expires on
2026-10-28; the excerpts below retain the decisive evidence.

Device property files and helper exit status, verbatim:

```text
device-api.txt: 35
device-abi.txt: x86_64
device-pagesize.txt: 4096
exit-status.txt: instrumentation_exit=0
```

Exact runner invocation is the `gradle ... :app:connectedDebugAndroidTest` command
above, issued by `bash "$AUTOPILOT_ANDROID_DIR/tools/ci-native-check.sh" "$AUTOPILOT_ANDROID_DIR"`.
Verbatim test-runner tail from `ci-native-results/native-core-gradle.log`:

```text
> Task :app:connectedDebugAndroidTest
Starting 1 tests on emulator-5554 - 15

Finished 1 tests on emulator-5554 - 15
```

Final runner result and independent XML check:

```text
BUILD SUCCESSFUL in 18s
64 actionable tasks: 1 executed, 63 up-to-date
NATIVE_CORE_XML_RECEIPT tests=1 failures=0 errors=0 skipped=0
```

The actual `TEST-emulator-5554 - 15-_app-.xml` confirms the selected method:

```xml
<testsuite name="app.autopilot.android.NativeCoreConfigTest" tests="1" failures="0" errors="0" skipped="0" time="0.745" timestamp="2026-10-21T12:54:20" hostname="localhost">
  <testcase name="emptyProductionBaselineIsAcceptedByActualAndroidCore" classname="app.autopilot.android.NativeCoreConfigTest" time="0.05" />
</testsuite>
```

Decisive `ci-native-results/logcat.txt` lines:

```text
10-21 12:54:19.847  2304  2329 I libclash: Initial configuration complete, total time: 5ms
10-21 12:54:19.921  2304  2325 I System.out: AUTOPILOT_NATIVE_BASELINE_ACCEPTED selector=REJECT SDK=35 ABI=x86_64
```

Scope: actual `libclash.so` loaded, production baseline bytes parsed, native
`quickSetup` returned success, and `getProxies` returned `AUTOPILOT.now=REJECT`.
No TUN permission, routed traffic, live provider connection, ARM64/16 KB guest,
HyperOS phone connection, or Android package-update installation was exercised.
