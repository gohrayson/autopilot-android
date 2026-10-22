#!/usr/bin/env bash
# Fresh hosted emulator only. Synthetic native/update/TUN probes; no provider account or API.
set -Eeuo pipefail

project="$(cd "${1:-.}" && pwd)"
results="${GITHUB_WORKSPACE:-$project}/ci-native-results"
mkdir -p "$results"
collect_evidence() {
  status=$?
  trap - EXIT
  adb devices -l > "$results/adb-devices.txt" 2>&1 || true
  adb shell getprop ro.build.version.sdk > "$results/device-api.txt" 2>&1 || true
  adb shell getprop ro.product.cpu.abi > "$results/device-abi.txt" 2>&1 || true
  adb shell getconf PAGESIZE > "$results/device-pagesize.txt" 2>&1 || true
  adb logcat -d -v threadtime > "$results/logcat.txt" 2>&1 || true
  printf 'instrumentation_exit=%s\n' "$status" > "$results/exit-status.txt"
  exit "$status"
}
trap collect_evidence EXIT

test -f "$project/app/src/androidTest/java/app/autopilot/android/NativeCoreConfigTest.java"
test -f "$project/app/src/androidTest/java/app/autopilot/android/UpdateDownloadRuntimeTest.java"
test -f "$project/app/src/androidTest/java/app/autopilot/android/VpnStatusUiTest.java"
test -f "$project/app/src/androidTest/java/app/autopilot/android/UpdateStatusUiTest.java"
test -f "$project/app/src/androidTest/java/app/autopilot/android/UpdateCacheRuntimeTest.java"
test -f "$project/app/src/androidTest/java/app/autopilot/android/UpdateHttpsTransportRuntimeTest.java"
test -f "$project/app/src/androidTest/java/app/autopilot/android/ReachabilityRuntimeTest.java"
adb wait-for-device
adb logcat -c

# Changing REQUEST_INSTALL_PACKAGES while instrumentation is alive kills its process.
# Set fixture permissions on the disposable emulator before Gradle starts the runner.
[[ "${GITHUB_ACTIONS:-}" == "true" && "$(uname -s)" == "Linux" ]] || { echo 'Require disposable hosted Linux emulator' >&2; exit 1; }
mapfile -t devices < <(adb devices | awk 'NR>1 && NF {print $1 " " $2}')
[[ "${#devices[@]}" -eq 1 && "${devices[0]}" =~ ^emulator-[0-9]+\ device$ ]] || { echo 'Require exactly one emulator and no physical device' >&2; exit 1; }
serial="${devices[0]%% *}"
adb -s "$serial" install -r -t "$project/app/build/outputs/apk/debug/app-debug.apk"
adb -s "$serial" shell appops set app.autopilot.android.debug REQUEST_INSTALL_PACKAGES allow
adb -s "$serial" shell pm grant app.autopilot.android.debug android.permission.POST_NOTIFICATIONS

set +e
gradle -p "$project" --no-daemon --console=plain \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.autopilot.android.NativeCoreConfigTest,app.autopilot.android.UpdateDownloadRuntimeTest,app.autopilot.android.VpnStatusUiTest,app.autopilot.android.UpdateStatusUiTest,app.autopilot.android.UpdateCacheRuntimeTest,app.autopilot.android.ReachabilityRuntimeTest \
  2>&1 | tee "$results/native-core-gradle.log"
gradle_status=${PIPESTATUS[0]}
set -e

# A successful task with no selected instrumentation tests must never pass CI.
set +e
python3 - "$project" <<'PY' | tee "$results/junit-receipt.txt"
from pathlib import Path
from collections import Counter
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1]) / "app/build/outputs/androidTest-results/connected"
selected = {
    "app.autopilot.android.NativeCoreConfigTest": {"emptyProductionBaselineIsAcceptedByActualAndroidCore"},
    "app.autopilot.android.UpdateDownloadRuntimeTest": {
        "concurrentRangesPublishOnlyCompleteVerifiedBytes",
        "interruptedPrefixesResumeAcrossDownloaderInstances",
    },
    "app.autopilot.android.VpnStatusUiTest": {"connectionProgressAndActiveNodeRenderTruthfully"},
    "app.autopilot.android.UpdateStatusUiTest": {
        "checkClicksRenderProgressErrorsAndReleaseActions",
        "readyUpdateSkipsAutomaticChecksAndKeepsInstallEnabled",
        "missingReadyApkInvalidatesCacheAndEnablesDownload",
    },
    "app.autopilot.android.UpdateCacheRuntimeTest": {"canonicalAliasCleanupKeepsCompleteApkAndLeavesResumeFiles"},
    "app.autopilot.android.ReachabilityRuntimeTest": {"independentWatchdogBoundsRealConnectsWhileControllerIsBusy"},
}
cases = []
for report in root.rglob("*.xml"):
    tree = ET.parse(report)
    cases.extend(case for case in tree.iter("testcase")
                 if case.get("classname", "") in selected)
failed = sum(case.find("failure") is not None for case in cases)
errors = sum(case.find("error") is not None for case in cases)
skipped = sum(case.find("skipped") is not None for case in cases)
missing = []
for classname, methods in selected.items():
    observed = {case.get("name") for case in cases if case.get("classname") == classname}
    missing.extend(f"{classname}.{method}" for method in sorted(methods - observed))
expected = sum(len(methods) for methods in selected.values())
counts = Counter((case.get("classname"), case.get("name")) for case in cases)
duplicates = [f"{classname}.{method}" for (classname, method), count in counts.items() if count > 1]
unexpected = [f"{classname}.{method}" for classname, method in counts if method not in selected[classname]]
print(f"ANDROID_RUNTIME_XML_RECEIPT tests={len(cases)} expected={expected} failures={failed} errors={errors} skipped={skipped} missing={len(missing)} duplicates={len(duplicates)} unexpected={len(unexpected)}")
for method in missing:
    print(f"MISSING_SELECTED_TEST {method}")
for method in duplicates:
    print(f"DUPLICATE_SELECTED_TEST {method}")
for method in unexpected:
    print(f"UNEXPECTED_SELECTED_TEST {method}")
if len(cases) != expected or missing or duplicates or unexpected or failed or errors or skipped:
    raise SystemExit(1)
PY
xml_status=${PIPESTATUS[0]}
set -e
if [[ "$gradle_status" -ne 0 ]]; then exit "$gradle_status"; fi
if [[ "$xml_status" -ne 0 ]]; then exit "$xml_status"; fi
node "$project/tools/ci-update-route-check.cjs" "$project" 2>&1 | tee "$results/update-https-runner.log"
node "$project/tools/ci-tunnel-check.cjs" "$project" 2>&1 | tee "$results/tunnel-runner.log"
