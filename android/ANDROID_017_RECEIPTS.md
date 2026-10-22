# Android 0.1.7 disabled-button appearance

22 October 2026. Production source: `3795d4bc1f30179ad053a672f01e7d381740964e`.

## Observed defect

The phone's 0.1.6 Updates panel correctly reports the current version, with
Download/Install disabled in the actual Android view hierarchy. The user's
observation that Install still looks enabled is correct: its background tint is
a constant blue and its text tint is a constant white. A direct phone screenshot
records the misleading bright blue disabled Install button at
`.data/android-017-disabled-before.png`.

## Change

The shared button helper now uses stateful Android color lists for background
and text. Disabled controls use `#e3e5e9` / `#8b919c`; enabled primary actions use
the existing blue / white; enabled secondary actions use pale blue / blue. The
native button background and ripple remain in place. Updater behavior, transport,
cache and signing policies are unchanged from 0.1.6.

Existing UpdateStatusUiTest scenarios now inspect the actual view drawable state,
resolved background tint and current text color while checking, after release
discovery, and with a ready APK. The selected test/method catalog is unchanged.

## Local verification

```powershell
& 'D:/Github/onevpn-autopilot/android/tools/build.ps1' -Configuration Release 2>&1 |
  Tee-Object -FilePath 'D:/Github/onevpn-autopilot/.data/android-updater-017-release-build.log'
```

```text
BUILD SUCCESSFUL in 22s
79 actionable tasks: 29 executed, 50 up-to-date
APK: D:\Github\onevpn-autopilot\dist\android\Autopilot-Android-0.1.7.apk
SHA256: 6a93a24183d3277afe7184047d812cea73a8c8d58d03ab533c69f8da2c555e60
```

This invokes unit tests, Android lint and the signed release assembly. The
source-catalog/XML count audit for this full run is:

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
print(f'ANDROID_017_UNIT_XML_RECEIPT classes={len(seen)} expectedClasses={len(expected)} '+ ' '.join(f'{k}={v}' for k,v in totals.items()))
'@ | python -
```

```text
ANDROID_017_UNIT_XML_RECEIPT classes=17 expectedClasses=17 tests=144 failures=0 errors=0 skipped=0
```

```powershell
& android/tools/verify-apk.ps1 -ApkPath D:/Github/onevpn-autopilot/dist/android/Autopilot-Android-0.1.7.apk
```

Verified package `app.autopilot.android`, version 0.1.7 / code 8, minimum SDK 26,
release signature, native/ZIP alignment and absence of private/probe data.
Signing certificate SHA-256 remains
`6c6831265f4d5593b499b2bfc1bbcaf93f3eb4d143f49fef192d811d4b6060ab`.

## Runtime and delivery

[Run 39774036072](https://github.com/gohrayson/autopilot-android/actions/runs/39774036072)
completed successfully, job `119808373624`. The actual job log confirms:

```bash
bash "$AUTOPILOT_ANDROID_DIR/tools/ci-native-check.sh" "$AUTOPILOT_ANDROID_DIR"
```

```text
2026-10-22T11:56:13.8971534Z ANDROID_RUNTIME_XML_RECEIPT tests=8 expected=8 failures=0 errors=0 skipped=0 missing=0 duplicates=0 unexpected=0
2026-10-22T11:56:19.9044984Z app.autopilot.android.UpdateHttpsTransportRuntimeTest:AUTOPILOT_UPDATE_HTTPS_ACCEPTED package=app.autopilot.android versionCode=7 connects=2 hosts=[github.com, release-assets.githubusercontent.com] tlsSent=2909 tlsReceived=20889 explicitLoopback=true platformTls=true parsedManifest=true cancelClosesConnect=true deadlineClosesConnect=true SDK=35
2026-10-22T11:56:19.9046263Z OK (1 test)
2026-10-22T11:56:19.9050002Z ANDROID_UPDATE_HTTPS_RECEIPT tests=1 expected=1 failures=0 explicitProxy=true trustedTls=true liveManifest=true
2026-10-22T11:56:30.7694979Z OK (1 test)
2026-10-22T11:56:30.8016144Z ANDROID_TUN_RUNTIME_RECEIPT tests=1 expected=1 failures=0 separateUid=true ipv4=true ipv6=true endpointBootstrapDns=true appDns=true fakeIpPool=true fakeIpReverseMapping=true stopCleanup=true
```

This release is to be downloaded and installed through the repaired 0.1.6 phone
updater. It has not been installed by ADB; the phone VPN remains running during
preparation. Actual publication and phone installation evidence belongs in the
repository's publication receipt rather than modifying this released source ZIP.
