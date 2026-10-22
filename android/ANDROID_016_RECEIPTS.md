# Android 0.1.6 updater investigation and verification

22 October 2026. Source commit for runtime verification:
`870aad76f2d6c93d0bc573b9890380721faa8d48` (production code is unchanged from
`5014f893059a982e42f81de0ec41cc1784758556`).

## Phone evidence before repair

The user's Android 16 / arm64 phone runs signed release 0.1.4, version code 5.
The saved feed is the correct public `latest.json`; the reported `tatest.json`
was a transcription error. The public release is 0.1.5 during this investigation.

Copied diagnostics record `UPDATE_READY` at 10:43:56 UTC, followed by several
`UPDATE_TIMEOUT` events. Transfer progress remains 47,779,978 / 47,779,978 bytes.
ADB UI inspection directly confirmed all three update buttons disabled while
a later check was running. After that check ended, Install was enabled and the
summary said that 0.1.5 was ready, with a network error underneath it.

A deliberate user tap on the enabled Install button produced no installer.
The summary changed to "The update server returned an invalid response" and
remained offscreen above the controls. In the shipped pre-install path this
message corresponds to the local APK size/presence check. No installation
session reached `UPDATE_INSTALLING`. `REQUEST_INSTALL_PACKAGES` is allowed.
The stale ready flag and transfer counter did not establish that an APK still
existed and passed pre-install validation.

Local, untracked evidence: `.data/android-updater-016-phone-ui.xml`,
`android-updater-016-phone-ui-unlocked.xml`,
`android-updater-016-phone-after-install-tap.xml`. No credentials or raw provider
configurations were requested.

## Source defects and changes

- The app UID bypasses its own TUN. The old updater used ordinary HTTPS, while
  node checks explicitly use loopback proxy listeners. The updater now uses the
  verified current node's existing listener, including for redirects and ranges.
  It reports an unavailable route when a running VPN has no verified node. It
  uses explicit direct transport when no Autopilot engine exists.
- Automatic checks no longer acquire busy ownership over a ready APK. Manual
  checks show immediate status, elapsed time, indeterminate progress and specific
  errors. The manifest watchdog tracks requests before blocking network reads.
- Pre-install validation clears invalid readiness and progress, keeps the public
  manifest, and enables Download. Recovery messages also appear beside controls.
  Diagnostics distinguish cached file existence/size from transfer progress.
- Download paths are canonical; the old cleanup compared `File` path spellings.
  Different spellings of the same file could cause cleanup to delete the retained
  APK. Cleanup now uses canonical identity and ownership checks. Download/restore
  publish ready only after cleanup confirms that the complete file remains.
  This defect is reproduced with actual path aliases; its involvement in this
  phone's earlier file loss remains unverified.
- HTTPS trust, redirect restrictions, size/hash, package/version and signing
  checks remain enforced. No direct fallback is attempted for an active VPN.

## Local build, tests and APK

Run from `D:\Github\onevpn-autopilot`:

```powershell
& 'D:/Github/onevpn-autopilot/android/tools/build.ps1' -Configuration Release 2>&1 |
  Tee-Object -FilePath 'D:/Github/onevpn-autopilot/.data/android-updater-016-release-build.log'
```

Runner tail:

```text
BUILD SUCCESSFUL in 23s
79 actionable tasks: 21 executed, 58 up-to-date
APK: D:\Github\onevpn-autopilot\dist\android\Autopilot-Android-0.1.6.apk
SHA256: ad473e088a19504bdecdb9aaaafb846155a111cacdb9e7d925145f311a4f31eb
```

The build runs `:app:testDebugUnitTest`, `:app:lintDebug` and
`:app:assembleRelease`. Test-runner XML was audited against every source test
class, rather than counting stale or filtered reports:

```powershell
@'
from pathlib import Path
import xml.etree.ElementTree as ET
root=Path('android/app')
expected={p.stem for p in (root/'src/test/java/app/autopilot/android').glob('*Test.java')}
seen=set(); totals={k:0 for k in ['tests','failures','errors','skipped']}
for report in (root/'build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    suite=ET.parse(report).getroot();seen.add(suite.attrib['name'].rsplit('.',1)[-1])
    for key in totals:totals[key]+=int(suite.attrib.get(key,0))
assert seen==expected,(sorted(expected-seen),sorted(seen-expected))
assert not(totals['failures'] or totals['errors'] or totals['skipped'])
print(f'ANDROID_016_UNIT_XML_RECEIPT classes={len(seen)} expectedClasses={len(expected)} '+ ' '.join(f'{k}={v}' for k,v in totals.items()))
'@ | python -
```

```text
ANDROID_016_UNIT_XML_RECEIPT classes=17 expectedClasses=17 tests=144 failures=0 errors=0 skipped=0
```

Release APK verification:

```powershell
& android/tools/verify-apk.ps1 -ApkPath D:/Github/onevpn-autopilot/dist/android/Autopilot-Android-0.1.6.apk
```

Verified package `app.autopilot.android`, version code 7 / 0.1.6, min SDK 26,
non-debuggable release, native ELF/ZIP alignment and no private/probe data.
Signing certificate SHA-256 remains
`6c6831265f4d5593b499b2bfc1bbcaf93f3eb4d143f49fef192d811d4b6060ab`.

Publisher/fixture tools:

```powershell
node --test android/tools/publish-update.test.cjs android/tools/ci-tunnel-fixture.test.cjs
```

```text
tests 14
pass 14
fail 0
cancelled 0
skipped 0
todo 0
```

## Android runtime verification

[Run 39772166705](https://github.com/gohrayson/autopilot-android/actions/runs/39772166705)
is the successful combined runtime run, job `119802134103`. The actual
instrumentation logs and XML were inspected. The workflow explicitly selects
each expected test/method and rejects missing, duplicate, unexpected or skipped
results. Real HTTPS uses the platform TLS stack through an observable CONNECT
relay; the fixture never synthesizes the public manifest or changes trust.

The first run, 39770908847, exposed a fixture setup error. Its preserved logcat
records `ActivityManager: Killing ... app.autopilot.android.debug ...
REQUEST_INSTALL_PACKAGES changed.` The test had changed its own app-op during
instrumentation, which Android terminates. The corrected runner preinstalls the
debug APK and grants installation/notification permissions before starting the
test process. No production code was changed for this runner correction.

Run 39771618146 then passed the selected native/download/UI/cache suite:

```bash
bash "$AUTOPILOT_ANDROID_DIR/tools/ci-native-check.sh" "$AUTOPILOT_ANDROID_DIR"
```

```text
2026-10-22T11:37:24.1427976Z ANDROID_RUNTIME_XML_RECEIPT tests=8 expected=8 failures=0 errors=0 skipped=0 missing=0 duplicates=0 unexpected=0
```

That run stopped before live HTTPS because Gradle removed its fixture APKs after
the connected suite. Logcat records both app and test packages removed at
11:37:22 UTC, followed by `Unable to find instrumentation info`. The independent
HTTPS runner now installs its own app/test APKs, as the TUN runner already does.
This correction also changes only the harness.

Final runner tails for the same invocation:

```text
2026-10-22T11:41:37.6406621Z ANDROID_RUNTIME_XML_RECEIPT tests=8 expected=8 failures=0 errors=0 skipped=0 missing=0 duplicates=0 unexpected=0
2026-10-22T11:41:42.1015293Z app.autopilot.android.UpdateHttpsTransportRuntimeTest:AUTOPILOT_UPDATE_HTTPS_ACCEPTED package=app.autopilot.android versionCode=6 connects=2 hosts=[github.com, release-assets.githubusercontent.com] tlsSent=2911 tlsReceived=20686 explicitLoopback=true platformTls=true parsedManifest=true cancelClosesConnect=true deadlineClosesConnect=true SDK=35
2026-10-22T11:41:42.1018920Z OK (1 test)
2026-10-22T11:41:42.1020160Z ANDROID_UPDATE_HTTPS_RECEIPT tests=1 expected=1 failures=0 explicitProxy=true trustedTls=true liveManifest=true
2026-10-22T11:41:52.0767459Z OK (1 test)
2026-10-22T11:41:52.0826155Z ANDROID_TUN_RUNTIME_RECEIPT tests=1 expected=1 failures=0 separateUid=true ipv4=true ipv6=true endpointBootstrapDns=true appDns=true fakeIpPool=true fakeIpReverseMapping=true stopCleanup=true
```

The successful artifact is `12423700085`, retained locally under
`.data/android-016-ci-39772166705/artifact/ci-native-results`. Its
`exit-status.txt` records `instrumentation_exit=0`. GitHub check-run metadata and
annotations were read separately and confirm success with zero annotations.
Gradle/emulator console deprecation or console messages are not classified as
product failures or claimed absent.

The controlled UI tests do not assert that a seeded ready flag is proof of a
signed installable APK. Missing-file recovery uses the actual Install handler;
APK/signature policy is not widened for debug tests. A future on-device update
installation still needs a separately observed Android package-installer result.

## Desktop connection guard

The same `protectedState()` probe from `scripts/probe-runtime.mjs` was saved
before and after the work and compared with `assert.deepEqual`:

```text
ANDROID_016_DESKTOP_GUARD_PASS proxy=true originalVpnProcesses=true listener7890=true
```

Windows proxy values, original VPN process identities and the port-7890 listener
are identical. No desktop app installation, VPN restart or route change was made.
