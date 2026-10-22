# Android workflow warnings and handoff race

22 October 2026. Repository: `gohrayson/autopilot-android`.

## Evidence from the reported history

The screenshot's error/warning combination occurs in earlier failed jobs. The
repository's latest run when this investigation began was the successful
[0.1.5 run 39707595366](https://github.com/gohrayson/autopilot-android/actions/runs/39707595366).
The older failures had different causes:

- [39704336018](https://github.com/gohrayson/autopilot-android/actions/runs/39704336018):
  separate-UID TCP timed out through the old mixed TUN stack.
- [39704959404](https://github.com/gohrayson/autopilot-android/actions/runs/39704959404):
  IPv4/IPv6 traffic passed, but the fixture application DNS lookup failed.

Both paths passed in the released 0.1.5 verification. The three workflow warnings
still appeared in that successful run: Node 20 action runtimes, deprecated
setup-java v4, and `gradlew` committed without executable permission.

## Workflow and source export changes

Commit `b7eea004ae2071d2060eb71b284a4a9ac57fdb3b` makes the minimal verified Node
24 migration. Each selected tag's actual `action.yml` was read before editing:

| Action | Ref | Primary source |
| --- | --- | --- |
| Checkout | `actions/checkout@v5` | [action.yml](https://github.com/actions/checkout/blob/v5/action.yml) |
| Java | `actions/setup-java@v5` | [action.yml](https://github.com/actions/setup-java/blob/v5/action.yml) |
| Upload artifacts | `actions/upload-artifact@v6` | [action.yml](https://github.com/actions/upload-artifact/blob/v6/action.yml) |
| Android SDK | `android-actions/setup-android@v4` | [action.yml](https://github.com/android-actions/setup-android/blob/v4/action.yml) |
| Gradle | `gradle/actions/setup-gradle@v5` | [action.yml](https://github.com/gradle/actions/blob/v5/setup-gradle/action.yml) |

The emulator action already used Node 24. Java 17, Gradle 8.13, Android command-line
tools 12266719, API 35 and the pinned native AAR checksum remain unchanged. No test
class/catalog, traffic check or failure condition was removed.

The Git tree now records `android/gradlew` as `100755`. Android Git attributes
force LF for `gradlew` and shell scripts. The workflow tests `-x` and `bash -n`
before any action can silently repair wrapper permission.

The source exporter accepts an explicit `-OutputPath` and preserves Unix creator
metadata and regular/executable file modes. Its helper validates the ZIP layout
before changing metadata, without changing payloads. An explicit temporary source
archive was used; published 0.1.5 APK/source assets were not regenerated.

## Fresh run exposed an independent test handoff race

[Run 39714614124](https://github.com/gohrayson/autopilot-android/actions/runs/39714614124),
job `119613638129`, had no `##[warning]` annotations. The baseline/download/UI
instrumentation passed and every separate-UID traffic request returned PASS.
It then failed reading the completion marker:

```text
2026-10-22T03:04:45.5547877Z org.junit.ComparisonFailure: expected:<[PASS]> but was:<[]>
2026-10-22T03:04:45.5580282Z at app.autopilot.android.NativeTunnelRuntimeTest.productionTunnelCarriesIpv4Ipv6AndDnsFromOtherUid(NativeTunnelRuntimeTest.java:84)
```

The producer used `printf PASS > files/tunnel-probe-complete`. Redirection creates
the final file before `printf` writes. The Android reader used file existence as
its readiness signal and observed that empty interval. The ready JSON had the
same publication issue.

Commit `6eff99724d4c86390872ac26c1c360b2f98730fe` fixes both producers: completion
writes a sibling temporary file and publishes with `mv`; ready JSON is written,
synced, closed and published with `Files.move(..., ATOMIC_MOVE)`. Temporary files
are cleaned before/after the test. The PASS comparison, traffic checks and real
production VPN Stop assertions are unchanged. This changes instrumentation and
its CI harness only, not the production application.

## Source archive and publisher verification

From `D:\Github\onevpn-autopilot`:

```powershell
powershell.exe -NoProfile -File android/tools/package-source.ps1 -OutputPath .data/android-ci-cleanup-source-probe.zip
python .data/android-ci-cleanup-source-probe.py
node .data/android-ci-cleanup-source-publisher-probe.cjs
```

The workspace probes inspect the actual exported archive, compare every payload
with current source, check CRC, complete inclusion/exclusion and Unix modes, and
feed it into the production publisher validator. Malformed copies must fail
without mutation. Verbatim relevant output, all commands exit 0:

```text
"archive_sha256": "ca3b1000585e70e192c989d55ea8ccf8d6150d70ed0afdf2b1e6d275bd18a19c"
"files": 115
"creator_os": 3
"executable_modes": "100755"
"regular_modes": "100644"
"payload_crc_and_source_bytes": "PASS"
"private_generated_exclusions": "PASS"
Malformed archive rejected without mutation: truncated
Malformed archive rejected without mutation: unexpected-record
Production publisher accepts actual source ZIP: 115 source files; version 0.1.5
```

Logs: `.data/android-ci-cleanup-final-source-packaging.log`,
`.data/android-ci-cleanup-final-source-verification.log`,
`.data/android-ci-cleanup-final-source-publisher.log`.

Info-ZIP independently reported Unix origin and executable script metadata.
The published artifact hashes were checked unchanged:

- APK: `ac20475463374df5784566203a4d10bcb9d65af6bfeae37fc791a5f6fbf59d11`
- Source: `2d55503b1646b8285262f9f358a77c72154b39e69ac334d33e21b505bdbeb4dd`

## Explicit Node tool suite

```powershell
node --test android/tools/publish-update.test.cjs android/tools/ci-tunnel-fixture.test.cjs 2>&1 |
  Tee-Object -FilePath 'D:\Github\onevpn-autopilot\.data\android-ci-cleanup-node-tests.log'
exit $LASTEXITCODE
```

Verbatim tail, exit 0:

```text
ℹ tests 14
ℹ suites 0
ℹ pass 14
ℹ fail 0
ℹ cancelled 0
ℹ skipped 0
ℹ todo 0
ℹ duration_ms 150.9052
```

## Final hosted Android result

[Run 39715276552](https://github.com/gohrayson/autopilot-android/actions/runs/39715276552),
job `119615772416`, source `6eff99724d4c86390872ac26c1c360b2f98730fe`,
completed successfully at `2026-10-22T03:12:31Z` (11:12:31 UTC+08:00).
The workflow executes:

```bash
bash "$AUTOPILOT_ANDROID_DIR/tools/ci-native-check.sh" "$AUTOPILOT_ANDROID_DIR"
```

Verbatim completed job log:

```text
2026-10-22T03:11:56.8770607Z Starting 4 tests on emulator-5554 - 15
2026-10-22T03:12:01.8753837Z Finished 4 tests on emulator-5554 - 15
2026-10-22T03:12:03.2622897Z ANDROID_RUNTIME_XML_RECEIPT tests=4 expected=4 failures=0 errors=0 skipped=0 missing=0 duplicates=0 unexpected=0
2026-10-22T03:12:14.6035512Z OK (1 test)
2026-10-22T03:12:14.6974254Z ANDROID_TUN_RUNTIME_RECEIPT tests=1 expected=1 failures=0 separateUid=true ipv4=true ipv6=true endpointBootstrapDns=true appDns=true fakeIpPool=true fakeIpReverseMapping=true stopCleanup=true
```

The completed log contains no `##[warning]` or `##[error]` lines. GitHub's
check-run API and annotation collection were also read and asserted directly.
The connector does not expose check-run endpoints, so this read-only probe used
the existing trusted GitHub API helper and the existing credential in memory:

```powershell
node -e "const assert=require('node:assert/strict'),{credentials,createGitHubApi}=require('./android/tools/publish-update.cjs');(async()=>{const api=createGitHubApi(credentials());const [check,annotations]=await Promise.all([api.get('/check-runs/119615772416'),api.get('/check-runs/119615772416/annotations?per_page=100')]);assert.equal(check.conclusion,'success');assert.equal(check.output.annotations_count,0);assert.ok(Array.isArray(annotations));assert.equal(annotations.length,0);console.log(JSON.stringify({run:39715276552,job:119615772416,conclusion:check.conclusion,annotations:check.output.annotations_count,warnings:0,errors:0}));})().catch(e=>{console.error(e.message);process.exitCode=1;});"
```

Verbatim output, exit 0:

```json
{"run":39715276552,"job":119615772416,"conclusion":"success","annotations":0,"warnings":0,"errors":0}
```

Log: `.data/android-ci-cleanup-final-annotations.log`.

## Running applications preserved

The final Windows proxy/process/listener snapshot is strictly equal to the
snapshot taken during this investigation. Reproduction:

```powershell
node --input-type=module -e "import fs from 'node:fs/promises';import assert from 'node:assert/strict';import {protectedState} from './scripts/probe-runtime.mjs';const before=JSON.parse(await fs.readFile('.data/android-ci-cleanup-vpn-before.json','utf8'));const after=await protectedState();await fs.writeFile('.data/android-ci-cleanup-vpn-after.json',JSON.stringify(after,null,2));assert.deepEqual(after,before);console.log('ANDROID_CI_CLEANUP_LIVE_GUARD_PASS proxy=true originalVpnProcesses=true listener7890=true');"
```

```text
ANDROID_CI_CLEANUP_LIVE_GUARD_PASS proxy=true originalVpnProcesses=true listener7890=true
```

`git diff android-v0.1.5 HEAD --exit-code -- android/app/src/main android/app/build.gradle android/probe android/gradle`
also exits 0. Production app code/version and core configuration are unchanged.
No phone command, desktop app restart, VPN reset or account request was performed.
These fixes need no APK reinstall. Older failed workflow runs remain historical
failures; the new linked run is the verified result. This receipt is a subsequent
documentation-only addition and does not alter the tested sources.
