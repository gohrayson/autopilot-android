# Android 0.1.8 independent connection health verification

22 October 2026. Production commit: `09f6acd157147a9e954917e0172ca02452f34609`.

The active-node monitor now runs independently of discovery, confirms repeated HTTPS failures, freshly validates replacements and keeps a separate failed-path cooldown. Repeated single-service failure permits a comparative switch only if the new node works on that path. Already-selected, recently transport-proven nodes can retain general connectivity during an isolated service outage; startup and candidate service validation remain strict. Google-only test failures use actual measured HTTPS timing rather than fabricated latency. Status and diagnostics distinguish confirmation, recovery and stale evidence.

Design/evidence is in the root `docs/SMART_HEALTH_*` files. The focused implementation/test receipt is [ANDROID_SMART_HEALTH_RECEIPTS.md](ANDROID_SMART_HEALTH_RECEIPTS.md). No physical Android device was attached during this run; production node-outbound checks do not prove all other apps' TUN traffic.

## Signed release build and unit catalog

```powershell
& D:/Github/onevpn-autopilot/android/tools/build.ps1 -Configuration Release 2>&1 |
  Tee-Object -FilePath D:/Github/onevpn-autopilot/.data/android-smart-health-018-release-build-final.log
```

Verbatim tail:

```text
BUILD SUCCESSFUL in 1m 1s
79 actionable tasks: 30 executed, 49 up-to-date
APK: D:\Github\onevpn-autopilot\dist\android\Autopilot-Android-0.1.8.apk
SHA256: bd798ba3f1b543db13ad678f8a93e7ac077c9f44bb6f823d25f8425f535d165f
```

This invokes the complete unit suite, Android lint and signed release assembly. The first broader run exposed a test readiness race in the new interruption fixture: it announced readiness before the request entered the active set. The final fixture waits at response-read entry after registration; the final run above includes that correction. Existing SDK XML/Gradle/deprecated-API warnings remain.

Runnable catalog/XML audit from repository root:

```powershell
@'
from pathlib import Path
import xml.etree.ElementTree as ET
root=Path('android/app')
expected={p.stem for p in (root/'src/test/java/app/autopilot/android').glob('*Test.java')}
seen=set(); totals={k:0 for k in ['tests','failures','errors','skipped']}
for report in (root/'build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    suite=ET.parse(report).getroot(); seen.add(suite.attrib['name'].rsplit('.',1)[-1])
    for key in totals: totals[key]+=int(suite.attrib.get(key,0))
assert seen==expected,(sorted(expected-seen),sorted(seen-expected))
assert not(totals['failures'] or totals['errors'] or totals['skipped'])
print(f'ANDROID_018_UNIT_XML_RECEIPT classes={len(seen)} expectedClasses={len(expected)} '+ ' '.join(f'{k}={v}' for k,v in totals.items()))
'@ | python -
```

```text
ANDROID_018_UNIT_XML_RECEIPT classes=19 expectedClasses=19 tests=158 failures=0 errors=0 skipped=0
```

## APK identity and packaging

```powershell
& D:/Github/onevpn-autopilot/android/tools/verify-apk.ps1 -Apk D:/Github/onevpn-autopilot/dist/android/Autopilot-Android-0.1.8.apk 2>&1 |
  Tee-Object -FilePath D:/Github/onevpn-autopilot/.data/android-smart-health-018-apk-verify.log
```

Verified package `app.autopilot.android`, version `0.1.8`, code `9`, minimum SDK 26, target 35, ARM64/ARMv7/x86_64, native alignment/RELRO and ZIP alignment. The same release signing certificate remains:

```text
Signer #1 certificate SHA-256 digest: 6c6831265f4d5593b499b2bfc1bbcaf93f3eb4d143f49fef192d811d4b6060ab
Verified APK contains no account vault, signing key, diagnostics log, or probe runtime fixture.
Verified APK ZIP alignment for 16 KB pages.
```

## Publisher and fixture tool runner

```powershell
node --test android/tools/publish-update.test.cjs android/tools/ci-tunnel-fixture.test.cjs 2>&1 |
  Tee-Object -FilePath D:/Github/onevpn-autopilot/.data/android-smart-health-018-tools-tests.log
```

```text
ℹ tests 14
ℹ suites 0
ℹ pass 14
ℹ fail 0
ℹ cancelled 0
ℹ skipped 0
ℹ todo 0
ℹ duration_ms 149.838
```

## Hosted Android runtime gate

[Run 39805351091](https://github.com/gohrayson/autopilot-android/actions/runs/39805351091), job `119917008955`, completed successfully for the production commit above. The runnable selected-test invocation remains:

```bash
bash android/tools/ci-native-check.sh android
```

Verbatim job-log receipt lines:

```text
2026-10-22T15:32:28.1114366Z ANDROID_RUNTIME_XML_RECEIPT tests=9 expected=9 failures=0 errors=0 skipped=0 missing=0 duplicates=0 unexpected=0
2026-10-22T15:32:32.7812957Z app.autopilot.android.UpdateHttpsTransportRuntimeTest:AUTOPILOT_UPDATE_HTTPS_ACCEPTED package=app.autopilot.android versionCode=8 connects=2 hosts=[github.com, release-assets.githubusercontent.com] tlsSent=2911 tlsReceived=20455 explicitLoopback=true platformTls=true parsedManifest=true cancelClosesConnect=true deadlineClosesConnect=true SDK=35
2026-10-22T15:32:32.7821157Z ANDROID_UPDATE_HTTPS_RECEIPT tests=1 expected=1 failures=0 explicitProxy=true trustedTls=true liveManifest=true
2026-10-22T15:32:43.5391273Z ANDROID_TUN_RUNTIME_RECEIPT tests=1 expected=1 failures=0 separateUid=true ipv4=true ipv6=true endpointBootstrapDns=true appDns=true fakeIpPool=true fakeIpReverseMapping=true stopCleanup=true
instrumentation_exit=0
```

The update probe read the then-current public 0.1.7 manifest (code 8), deliberately before 0.1.8 publication. It verified real trusted HTTPS and manifest parsing, not an installation of the older package.

The artifact XML explicitly includes `ReachabilityRuntimeTest.independentWatchdogBoundsRealConnectsWhileControllerIsBusy` once, with no failure/error/skip. Its actual logcat marker is:

```text
ANDROID_HEALTH_TRANSPORT_ACCEPTED independentController=true origins=3 rounds=2 confirmed=true absoluteDeadlineClosesConnect=true platformHttpsConnect=true
```

That new fixture proves real Android CONNECT deadlines/cancellation and independent monitoring during a blocked controller. It deliberately does not finish TLS with its blackhole peer; strict TLS is separately exercised by the public-feed probe. The existing native separate-UID fixture proves reserved IPv4/IPv6, endpoint bootstrap DNS, app DNS, fake-IP reverse mapping and stop cleanup. It does not prove live provider failover on the user's phone.

Local evidence: `.data/android-018-ci-39805351091/{native-core.log,receipts.log,reachability-xml-receipt.txt,artifact/}`. The downloaded artifact's SHA-256 matched GitHub's digest `6c0cc6925a7f84d9485865536d194e7efb1be9265ee6c1955767bba69c13ecdb`.
