# Android multipart download runtime probe

Prepared 2026-10-21. Android execution of the new download tests is pending the
root-owned build and hosted CI run. Static checks below are preparation evidence,
not a claim that the download integration has passed on Android.

## Observable pipeline

`app/src/androidTest/java/app/autopilot/android/UpdateDownloadRuntimeTest.java`
uses the production `UpdateManifest` parser and `UpdateDownload`, Android's real
`JSONObject`, actual private cache files, threads, merge and SHA-256 verification.
Its deterministic 6 MiB fixture is served entirely by an injected public-HTTPS
transport; it opens no real HTTP connection and contains no account/provider
data. The debug package's `UpdateManager.check` rejects `UPDATE_PREVIEW_BUILD`
before its release-feed fetch. No VPN service, consent, routes, installer or live
provider connection is exercised. Existing native baseline initialization remains
selected separately; this receipt does not claim that all native initialization
is offline.

The download cases are:

- `concurrentRangesPublishOnlyCompleteVerifiedBytes`: holds all response streams
  until four parallel range requests have opened, confirms no complete APK is
  visible, then checks the actual merged file against the complete fixture and
  manifest hash. Progress must expose concurrent connections and exact total bytes.
- `interruptedPrefixesResumeAcrossDownloaderInstances`: lets every part write a
  prefix, blocks subsequent reads, interrupts the owning download thread and
  requires `UPDATE_CANCELLED`. No complete APK may appear. A fresh downloader
  recovers nonzero persisted bytes, reports them in its initial progress, requests
  only missing suffixes, and produces the exact complete file and hash.

The cache test uses the public `resumableBytes` API rather than inspecting private
cache names. Fixture cleanup is restricted to its newly created private cache
directory. Responses implement exact HTTP 206 `Content-Range`, including the
0–0 probe; no implicit fallback to real network transport is possible.

## Runnable hosted command

The existing Ubuntu 24.04 / KVM / API 35 x86_64 workflow and pinned native AAR
remain in place. The instrumentation filter now selects both classes:

```sh
gradle -p "$AUTOPILOT_ANDROID_DIR" --no-daemon --console=plain \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.autopilot.android.NativeCoreConfigTest,app.autopilot.android.UpdateDownloadRuntimeTest
```

`tools/ci-native-check.sh` requires every named method in both selected classes to
appear exactly once in the actual JUnit XML, with zero failures, errors or skips.
The observed selected-case count must equal the named catalog's size. Empty
reports, a missing selected class, duplicate cases and an unexpected method in a
selected class fail CI; adding a method requires updating the named catalog.
The EXIT trap preserves logcat, device
API/ABI/page size and exit status; the workflow uploads reports even on failure.
The helper's runtime receipt starts with `ANDROID_RUNTIME_XML_RECEIPT`.

## Static preparation checks

Executed from `D:\Github\onevpn-autopilot`, with no local Gradle invocation:

The new instrumentation class and production downloader dependencies compile
against the actual Android API 35 and cached AndroidX/JUnit artifacts. This is
compilation only; the SDK stub JAR was not used to claim runtime success.

```powershell
$taskRoot='D:\Github\onevpn-autopilot'
$staticOutput=Join-Path $taskRoot '.data/android-download-android-static-classes'
New-Item -ItemType Directory -Path $staticOutput -Force|Out-Null
$classpath=@(
 (Join-Path $taskRoot '.data/android-tools/sdk/platforms/android-35/android.jar'),
 (Join-Path $taskRoot '.data/android-tools/gradle-home/caches/modules-2/files-2.1/junit/junit/4.13.2/8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12/junit-4.13.2.jar'),
 (Join-Path $taskRoot '.data/android-tools/gradle-home/caches/8.13/transforms/1074e624b237bcb77c2c88a692a0f7b7/transformed/junit-1.2.1/jars/classes.jar'),
 (Join-Path $taskRoot '.data/android-tools/gradle-home/caches/8.13/transforms/4f6262870c2912c1de493a2ebe39a4a0/transformed/monitor-1.7.2/jars/classes.jar'),
 (Join-Path $taskRoot '.data/android-tools/gradle-home/caches/8.13/transforms/6987f66f77b7e97d89297cdbba81dec5/transformed/runner-1.6.2/jars/classes.jar')
)-join';'
& (Join-Path $taskRoot '.data/android-tools/jdk/jdk-17.0.20.1+1/bin/javac.exe') -encoding UTF-8 -source 17 -target 17 -cp $classpath -d $staticOutput android/app/src/main/java/app/autopilot/android/UpdateDownload.java android/app/src/main/java/app/autopilot/android/UpdateManifest.java android/app/src/main/java/app/autopilot/android/UpdateIdentity.java android/app/src/androidTest/java/app/autopilot/android/UpdateDownloadRuntimeTest.java
if($LASTEXITCODE-ne0){exit $LASTEXITCODE}
'ANDROID_RUNTIME_JAVAC_COMPILE_OK'
```

Verbatim output, exit 0:

```text
ANDROID_RUNTIME_JAVAC_COMPILE_OK
```

```powershell
node -e 'const fs=require("fs"),yaml=require("yaml"),cp=require("child_process");const d=yaml.parse(fs.readFileSync("android/tools/hosted-native-probe.yml","utf8"));const bash="C:/Program Files/Git/bin/bash.exe";cp.execFileSync(bash,["-n","android/tools/ci-native-check.sh"],{stdio:"pipe"});let checked=0;for(const s of d.jobs["native-core"].steps){if(s.run){cp.execFileSync(bash,["-n"],{input:s.run});checked++;}}for(const p of ["android/tools/hosted-native-probe.yml","android/tools/ci-native-check.sh","android/app/src/androidTest/java/app/autopilot/android/UpdateDownloadRuntimeTest.java"]){if(fs.readFileSync(p).includes(13))throw Error("CRLF "+p)}console.log("WORKFLOW_YAML_PARSE_OK");console.log("BASH_HELPER_SYNTAX_OK");console.log("WORKFLOW_INLINE_BASH_SYNTAX_OK scripts="+checked);console.log("LF_ONLY_OK");'
```

Verbatim output, exit 0:

```text
WORKFLOW_YAML_PARSE_OK
BASH_HELPER_SYNTAX_OK
WORKFLOW_INLINE_BASH_SYNTAX_OK scripts=3
LF_ONLY_OK
```

The inline XML policy was also executed against in-memory synthetic JUnit
documents, not Android results. Exact invocation:

```powershell
$runtimePolicyCode=@'
from pathlib import Path
import ast, contextlib, io, sys
from unittest.mock import patch
import xml.etree.ElementTree as ET
text=Path("android/tools/ci-native-check.sh").read_text()
code=text.split("<<'PY'",1)[1].split("\n",1)[1].split("\nPY\n",1)[0]
ast.parse(code)
native="app.autopilot.android.NativeCoreConfigTest"
download="app.autopilot.android.UpdateDownloadRuntimeTest"
methods=[(native,"emptyProductionBaselineIsAcceptedByActualAndroidCore"),(download,"concurrentRangesPublishOnlyCompleteVerifiedBytes"),(download,"interruptedPrefixesResumeAcrossDownloaderInstances")]
def validate(cases):
    suite=ET.Element("testsuite")
    for classname,name,state in cases:
        element=ET.SubElement(suite,"testcase",classname=classname,name=name)
        if state: ET.SubElement(element,state)
    status=0
    with patch("pathlib.Path.rglob",return_value=[Path("synthetic.xml")]),patch("xml.etree.ElementTree.parse",return_value=ET.ElementTree(suite)),patch.object(sys,"argv",["probe","."]),contextlib.redirect_stdout(io.StringIO()):
        try: exec(compile(code,"ci-native-check.sh:inline-python","exec"),{})
        except SystemExit as result: status=result.code
    return status
complete=[(klass,name,"") for klass,name in methods]
assert validate(complete)==0
assert validate(complete[:1])!=0
assert validate([])!=0
assert validate(complete[:-1]+[(methods[-1][0],methods[-1][1],"failure")])!=0
assert validate(complete[:-1]+[(methods[-1][0],methods[-1][1],"skipped")])!=0
assert validate(complete+[complete[-1]])!=0
assert validate(complete+[(download,"unregisteredRuntimeMethod","")])!=0
print("SYNTHETIC_XML_POLICY_OK complete_accepts; missing_class_rejects; empty_rejects; failure_rejects; skip_rejects; duplicate_rejects; unexpected_method_rejects")
'@
python -c $runtimePolicyCode
```

Verbatim output, exit 0:

```text
SYNTHETIC_XML_POLICY_OK complete_accepts; missing_class_rejects; empty_rejects; failure_rejects; skip_rejects; duplicate_rejects; unexpected_method_rejects
```

## Android execution evidence

Pending. Append the exact source commit, CI run/artifact URL, actual JUnit tail
and device properties after the hosted run. Expected observable markers are
`AUTOPILOT_ANDROID_MULTIPART_ACCEPTED` and `AUTOPILOT_ANDROID_RESUME_ACCEPTED`.
These names are expectations, not observed Android output in this receipt yet.

## Completed hosted execution — 2026-10-21

This completed run supersedes the initial pending preparation status above.
The actual Android downloader accepted the complete concurrent download and the
interrupted-prefix resume through a fresh downloader instance. The native
baseline probe also passed in the same invocation.

- Run: [39613950384](https://github.com/gohrayson/autopilot-android/actions/runs/39613950384), completed successfully.
- Source commit: [`52e3853e285f4a02a2c77d7fdc601e82dcb273b8`](https://github.com/gohrayson/autopilot-android/commit/52e3853e285f4a02a2c77d7fdc601e82dcb273b8).
- Job: [119269838856](https://github.com/gohrayson/autopilot-android/actions/runs/39613950384/job/119269838856).
- Artifact: [native-core-api35-39613950384-1](https://github.com/gohrayson/autopilot-android/actions/runs/39613950384/artifacts/12351618456), 122,157 bytes.
- Artifact SHA-256: `bc98964a5c4e2482f77b577d26a7c356654988ac2b410644687c7f0b917a8fc0`.

The GitHub connector supplied the job log, artifact metadata and artifact ZIP.
The ZIP was read in memory and its SHA-256 matched GitHub's artifact digest.
Its actual JUnit XML, device-property files, Gradle output and logcat were
inspected. The hosted artifact expires on 2026-10-28; decisive excerpts follow.

Exact selected-method runner command, issued by
`bash "$AUTOPILOT_ANDROID_DIR/tools/ci-native-check.sh" "$AUTOPILOT_ANDROID_DIR"`:

```sh
gradle -p "$AUTOPILOT_ANDROID_DIR" --no-daemon --console=plain \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.autopilot.android.NativeCoreConfigTest,app.autopilot.android.UpdateDownloadRuntimeTest
```

Verbatim runner output from `ci-native-results/native-core-gradle.log`:

```text
> Task :app:connectedDebugAndroidTest
Starting 3 tests on emulator-5554 - 15

Finished 3 tests on emulator-5554 - 15
```

Final runner result and independent selected-method XML verifier, verbatim:

```text
BUILD SUCCESSFUL in 28s
64 actionable tasks: 1 executed, 63 up-to-date
ANDROID_RUNTIME_XML_RECEIPT tests=3 expected=3 failures=0 errors=0 skipped=0 missing=0 duplicates=0 unexpected=0
```

Actual `TEST-emulator-5554 - 15-_app-.xml`:

```xml
<?xml version='1.0' encoding='UTF-8' ?>
<testsuite name="app.autopilot.android.NativeCoreConfigTest" tests="3" failures="0" errors="0" skipped="0" time="1.871" timestamp="2026-10-21T14:11:45" hostname="localhost">
  <properties>
    <property name="device" value="emulator-5554 - 15" />
    <property name="flavor" value="" />
    <property name="project" value=":app" />
  </properties>
  <testcase name="emptyProductionBaselineIsAcceptedByActualAndroidCore" classname="app.autopilot.android.NativeCoreConfigTest" time="0.086" />
  <testcase name="concurrentRangesPublishOnlyCompleteVerifiedBytes" classname="app.autopilot.android.UpdateDownloadRuntimeTest" time="0.516" />
  <testcase name="interruptedPrefixesResumeAcrossDownloaderInstances" classname="app.autopilot.android.UpdateDownloadRuntimeTest" time="0.384" />
</testsuite>
```

Device-property files and helper exit status, verbatim values:

```text
device-api.txt: 35
device-abi.txt: x86_64
device-pagesize.txt: 4096
exit-status.txt: instrumentation_exit=0
```

Decisive `ci-native-results/logcat.txt` lines, verbatim:

```text
10-21 14:11:43.837  2328  2348 I System.out: AUTOPILOT_NATIVE_BASELINE_ACCEPTED selector=REJECT SDK=35 ABI=x86_64
10-21 14:11:44.389  2328  2355 I System.out: AUTOPILOT_ANDROID_MULTIPART_ACCEPTED bytes=6291456 concurrent=4 sha256=cbe5f087d3a142f2cdcaf082ee1a4a00961dbd928cd49ef508600c173177f553 SDK=35
10-21 14:11:44.766  2328  2362 I System.out: AUTOPILOT_ANDROID_RESUME_ACCEPTED cached=262144 network=6029312 total=6291456 SDK=35
```

The resumed transfer reused 262,144 persisted bytes and fetched only the
remaining 6,029,312 bytes, producing the exact 6,291,456-byte fixture. Passing
assertions also established no premature verified APK, restored initial cached
progress, correct missing suffixes, complete output bytes and SHA-256.

Verified scope: the production downloader on an actual API 35 x86_64 Android
emulator, using Android JSON, private filesystem, threads, interruption, persisted
resume and merge/hash validation through an injected transport. This run does
not establish physical-phone behavior, ARM64/16 KB execution, internet download
speed, actual server range support, provider/VPN/TUN traffic, installer behavior
or UI interaction.
