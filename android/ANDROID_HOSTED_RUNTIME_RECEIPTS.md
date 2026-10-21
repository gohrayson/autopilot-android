# Hosted Android native runtime probe

Prepared 2026-10-21. This receipt verifies CI preparation only. No hosted Android
run has completed in this receipt; Android native acceptance remains unverified.
The existing Windows host parser result does not establish Android JNI behavior.

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

The fixture submits the exact empty `VpnEngine.configuration()` baseline to the
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

Pending root-owned publication/dispatch. Append the run URL, exact source commit,
downloadable artifact, device API/ABI/page size, JUnit runner tail and raw native
fixture result here after completion. A failed native baseline is diagnostic
evidence, not a successful Android integration claim.
