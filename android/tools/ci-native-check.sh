#!/usr/bin/env bash
# Fresh hosted emulator only. Synthetic native setup; no account, provider API or TUN.
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
adb wait-for-device
adb logcat -c

set +e
gradle -p "$project" --no-daemon --console=plain \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.autopilot.android.NativeCoreConfigTest \
  2>&1 | tee "$results/native-core-gradle.log"
gradle_status=${PIPESTATUS[0]}
set -e

# A successful task with no selected instrumentation tests must never pass CI.
set +e
python3 - "$project" <<'PY' | tee "$results/junit-receipt.txt"
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1]) / "app/build/outputs/androidTest-results/connected"
cases = []
for report in root.rglob("*.xml"):
    tree = ET.parse(report)
    cases.extend(case for case in tree.iter("testcase")
                 if case.get("classname", "") == "app.autopilot.android.NativeCoreConfigTest")
failed = sum(case.find("failure") is not None for case in cases)
errors = sum(case.find("error") is not None for case in cases)
skipped = sum(case.find("skipped") is not None for case in cases)
print(f"NATIVE_CORE_XML_RECEIPT tests={len(cases)} failures={failed} errors={errors} skipped={skipped}")
if not cases or failed or errors or skipped:
    raise SystemExit(1)
PY
xml_status=${PIPESTATUS[0]}
set -e
if [[ "$gradle_status" -ne 0 ]]; then exit "$gradle_status"; fi
exit "$xml_status"
