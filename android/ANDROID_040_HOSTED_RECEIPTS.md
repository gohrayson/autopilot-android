# Android 0.4.0 hosted runtime receipts

Source commit: `2d259d90ea17d5504250cecc37a6534443a73f3a`.

Workflow: https://github.com/gohrayson/autopilot-android/actions/runs/39814530081

Job: `119949448552`, `native-core`, Ubuntu 24.04 / Android API 35 x86_64 emulator.

This document records source preparation and hosted runtime acceptance for the exact commit above. The final job succeeded; the retained XML catalog and the separate updater/TUN receipts were independently checked.

## Clean source archive

Run from `D:\Github\onevpn-autopilot`:

```powershell
& .\android\tools\package-source.ps1 2>&1 | Tee-Object -FilePath D:\Github\onevpn-autopilot\.data\android040-ci-package.log
if ($LASTEXITCODE -ne 0) { throw 'Source packaging failed' }
@'
const fs=require('node:fs'),path=require('node:path'),p=require('./android/tools/publish-update.cjs');const version=p.parseVersion(fs.readFileSync('android/app/build.gradle','utf8'));const file=`dist/android/Autopilot-Android-${version.versionName}-source.zip`;const bytes=fs.readFileSync(file);const count=p.verifySourceZip(bytes,version,name=>fs.readFileSync(path.resolve(name)));console.log(`ANDROID040_SOURCE_ZIP_RECEIPT version=${version.versionName} entries=${count} bytes=${bytes.length} sha256=${require('node:crypto').createHash('sha256').update(bytes).digest('hex')} privateFiles=false pinnedGeo=true`);
'@ | node
```

Verbatim output:

```text
Source ZIP Unix modes verified and set: 149 files
ANDROID040_SOURCE_ZIP_RECEIPT version=0.4.0 entries=149 bytes=4710542 sha256=da195968b27813472d9a86a3c9c748d4ec2f19c00e1f1d21b0d1ad7008652fe9 privateFiles=false pinnedGeo=true
```

That archive was expanded into `.data/android-hosted-repo`; `android/tools/hosted-native-probe.yml` was copied to `.github/workflows/hosted-native-probe.yml`. Hosted README now describes the shared contract and the separate-UID production TUN gate accurately. Existing accounts, credentials, generated builds and signing keys were excluded.

## Canonical-to-hosted normalization

The hosted checkout uses `.gitattributes` to normalize text to LF. Canonical Windows CRLF text remains unchanged. `*.metadb` is explicitly binary. Historical receipt whitespace was cleaned in the hosted checkout only.

Runnable verification before the production commit:

```powershell
git -C .data/android-hosted-repo diff --cached --check
git -C .data/android-hosted-repo ls-files -s android/gradlew
@'
const fs=require('node:fs'),cp=require('node:child_process'),path=require('node:path'),crypto=require('node:crypto');const root='.data/android-hosted-repo';const files=cp.execFileSync('git',['-C',root,'ls-files','android'],{encoding:'utf8'}).trim().split(/\r?\n/);let verified=0;for(const name of files.filter(x=>/\.(?:java|json)$/.test(x))){const canonical=fs.readFileSync(name,'utf8').replaceAll('\r\n','\n');const staged=cp.execFileSync('git',['-C',root,'show',':'+name],{encoding:'utf8'}).replaceAll('\r\n','\n');if(canonical!==staged)throw Error('Canonical/hosted source differs: '+name);verified++;}const geo='android/app/src/main/assets/geoip.metadb',expected=JSON.parse(fs.readFileSync('android/app/src/main/assets/geoip-manifest.json','utf8'));const binary=cp.execFileSync('git',['-C',root,'show',':'+geo],{maxBuffer:16*1024*1024});if(binary.length!==expected.size||crypto.createHash('sha256').update(binary).digest('hex')!==expected.sha256)throw Error('Hosted GeoIP binary differs');console.log(`HOSTED_CANONICAL_NORMALIZATION_PASS javaAndJson=${verified} lineEndingOnly=true geoBytes=${binary.length} geoSha256=${expected.sha256}`);
'@ | node
```

Verbatim output:

```text
100755 249efbb032ce46a80c687c0723eb172e85f6a136 0 android/gradlew
HOSTED_CANONICAL_NORMALIZATION_PASS javaAndJson=72 lineEndingOnly=true geoBytes=8422018 geoSha256=5c36afe6534dd3ef5e79a565ba2701fd511ff0a5b5fd6542e1abf58e76ee795a
```

`git diff --cached --check` exited successfully without whitespace findings, and `gradlew` was separately checked to contain no CR bytes.

## Production source push

```powershell
git -C .data/android-hosted-repo commit -m "Unify Android and Windows product behavior and verify runtime parity"
$env:GIT_TERMINAL_PROMPT = '0'
$env:GCM_INTERACTIVE = 'Never'
git -C .data/android-hosted-repo push origin main
git -C .data/android-hosted-repo rev-parse HEAD
```

Verbatim output tail:

```text
To https://github.com/gohrayson/autopilot-android.git
   139b6f5..2d259d9  main -> main
2d259d90ea17d5504250cecc37a6534443a73f3a
```

No release or tag was published by this operation.

## Runtime outcome

Read-only REST verification confirmed the run and job both `completed / success`, with the exact source SHA above. The job used label `ubuntu-24.04`, runner `GitHub Actions 1000001877`, and started at `2026-10-22T16:30:14Z`. The final run update was `2026-10-22T16:41:13Z`; its check run contained no annotations. Every selected job step completed successfully.

Earlier connector run/job snapshots remained stale at `queued`. A read-only REST audit using the existing noninteractive Git Credential Manager path established the actual final state. No duplicate dispatch or workflow change was made to bypass the apparent queue. Audit output is retained at `.data/android040-ci-rest-audit.log`.

Job logs were fetched with `github.fetch_workflow_job_logs(repo_full_name="gohrayson/autopilot-android", job_id=119949448552)`. The retained runtime artifact was fetched using the GitHub artifact connector:

```text
artifact ID: 12440759791
artifact name: native-core-api35-39814530081-1
archive bytes: 296254
archive SHA-256: bb96a54a83be434f25671c147e63dd7fb670000e531c92183efe02a26b54b7ea
```

The downloaded archive SHA-256 equals GitHub's artifact digest. Extracted evidence is retained at `.data/android040-ci-artifact/extracted`; decoded job logs are at `.data/android040-ci-job.log`.

The exact hosted job ran:

```bash
gradle -p "$AUTOPILOT_ANDROID_DIR" --no-daemon --console=plain \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :probe:assembleDebug
python3 "$AUTOPILOT_ANDROID_DIR/tools/check-unit-receipts.py" "$AUTOPILOT_ANDROID_DIR"
bash "$AUTOPILOT_ANDROID_DIR/tools/ci-native-check.sh" "$AUTOPILOT_ANDROID_DIR"
```

Verbatim job-log receipts:

```text
2026-10-22T16:31:19.2628838Z ANDROID_UNIT_XML_RECEIPT tests=178 expected=178 failures=0 errors=0 skipped=0 missing=0 duplicates=0 unexpected=0
2026-10-22T16:33:12.7404928Z ANDROID_RUNTIME_XML_RECEIPT tests=10 expected=10 failures=0 errors=0 skipped=0 missing=0 duplicates=0 unexpected=0
2026-10-22T16:33:17.2265661Z app.autopilot.android.UpdateHttpsTransportRuntimeTest:AUTOPILOT_UPDATE_HTTPS_ACCEPTED package=app.autopilot.android versionCode=9 connects=2 hosts=[github.com, release-assets.githubusercontent.com] tlsSent=2913 tlsReceived=20618 explicitLoopback=true platformTls=true parsedManifest=true cancelClosesConnect=true deadlineClosesConnect=true SDK=35
2026-10-22T16:33:17.2269363Z OK (1 test)
2026-10-22T16:33:17.2273877Z ANDROID_UPDATE_HTTPS_RECEIPT tests=1 expected=1 failures=0 explicitProxy=true trustedTls=true liveManifest=true
2026-10-22T16:33:28.4039546Z OK (1 test)
2026-10-22T16:33:28.4211731Z ANDROID_TUN_RUNTIME_RECEIPT tests=1 expected=1 failures=0 separateUid=true ipv4=true ipv6=true endpointBootstrapDns=true appDns=true fakeIpPool=true fakeIpReverseMapping=true stopCleanup=true
```

The `versionCode=9` HTTPS line describes the existing live public update manifest that the candidate parsed over the real route. The candidate itself is version `0.4.0`, code `10`; it does not require publishing a new feed before this route check.

## Independent retained XML verification

The local verification helper inventories JVM methods directly from production commit `2d259d90ea17d5504250cecc37a6534443a73f3a` and extracts the selected instrumentation catalog from that same commit's `ci-native-check.sh`. It compares those inventories with retained artifact XML, rejecting missing, duplicate, unexpected, failed or skipped tests. It also checks artifact digest, native/UI acceptance markers, separate updater HTTPS/TUN receipts, distinct UIDs and cleanup status.

Runnable command in the retained workspace:

```powershell
python .data/android040-verify-hosted.py 2>&1 | Tee-Object -FilePath D:\Github\onevpn-autopilot\.data\android040-independent-xml.log
exit $LASTEXITCODE
```

Verbatim output:

```text
ANDROID040_INDEPENDENT_UNIT_XML_RECEIPT tests=178 expected=178 failures=0 errors=0 skipped=0 missing=0 duplicates=0 unexpected=0
ANDROID040_INDEPENDENT_RUNTIME_XML_RECEIPT tests=10 expected=10 failures=0 errors=0 skipped=0 missing=0 duplicates=0 unexpected=0
ANDROID040_RUNTIME_GATE_PASS productParityUi=true pinnedGeoNative=true updaterHttps=true separateUidTun=true otherUidRequests=4 fixtureCleanup=true artifactDigest=true
```

The selected XML contains these exact methods, all without failure/error/skipped children:

```text
NativeCoreConfigTest.emptyProductionBaselineIsAcceptedByActualAndroidCore
UpdateDownloadRuntimeTest.concurrentRangesPublishOnlyCompleteVerifiedBytes
UpdateDownloadRuntimeTest.interruptedPrefixesResumeAcrossDownloaderInstances
VpnStatusUiTest.connectionProgressAndActiveNodeRenderTruthfully
UpdateStatusUiTest.missingReadyApkInvalidatesCacheAndEnablesDownload
UpdateStatusUiTest.checkClicksRenderProgressErrorsAndReleaseActions
UpdateStatusUiTest.readyUpdateSkipsAutomaticChecksAndKeepsInstallEnabled
UpdateCacheRuntimeTest.canonicalAliasCleanupKeepsCompleteApkAndLeavesResumeFiles
ReachabilityRuntimeTest.independentWatchdogBoundsRealConnectsWhileControllerIsBusy
ProductParityUiTest.sharedSettingsRoutingAndDiagnosticsHaveWorkingControls
```

Verbatim native/UI logcat acceptance:

```text
10-22 16:32:50.513  2379  2398 I System.out: AUTOPILOT_NATIVE_BASELINE_ACCEPTED selector=REJECT SDK=35 ABI=x86_64
10-22 16:33:09.831  2379  2508 I System.out: AUTOPILOT_PRODUCT_PARITY_UI_ACCEPTED settingsAtomic=true requiredServices=true websiteConflictGuard=true globalMode=true pauseState=true providerMetadata=true importedStatus=true noVpnMutation=true pinnedGeo=true nativeRuleAndGlobalAccepted=true
```

The separate-UID TUN fixture recorded VPN UID `10150`, request UID `10152`, and successful IPv4, IPv6, DNS and fake-IP requests on an unblocked VPN network. Its final status is:

```text
instrumentation_exit=0
```

This is emulator runtime evidence. It does not claim a new physical-device VPN session or an actual Windows/Android installer run.
