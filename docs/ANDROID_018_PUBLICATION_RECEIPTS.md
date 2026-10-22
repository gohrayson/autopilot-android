# Android 0.1.8 publication and delivery

22 October 2026. [Release](https://github.com/gohrayson/autopilot-android/releases/tag/android-v0.1.8). Production changes were tested at commit `09f6acd157147a9e954917e0172ca02452f34609`; `8037905` adds verification documents only. The signed package and source archive are published and immutable.

## Verification before publication

See [Android build/runtime receipt](../android/ANDROID_018_RECEIPTS.md) for exact unit/lint/build commands, native CI output, actual CONNECT cancellation, updater HTTPS and separate-UID TUN gates. Root independently read the actual XML/logcat marker and matched the downloaded CI artifact SHA-256 to GitHub's digest. All production Android source bytes matched the hosted tested checkout before publication:

```text
ANDROID_018_PRODUCTION_SOURCE_MATCH testedCommit=09f6acd157147a9e954917e0172ca02452f34609
```

Release invocations from `D:\Github\onevpn-autopilot`:

```powershell
& android/tools/package-source.ps1
node android/tools/publish-update.cjs --dry-run --notes-file .data/android-018-release-notes.txt
node android/tools/publish-update.cjs --publish --notes-file .data/android-018-release-notes.txt
```

The publisher verified the installed-app signing identity, package/version/minimum SDK, source ZIP contents, staged GitHub assets and every digest before publication. The result reports `published: true` and the existing feed:

`https://github.com/gohrayson/autopilot-android/releases/latest/download/latest.json`

| Asset | Bytes | SHA-256 |
| --- | ---: | --- |
| Autopilot-Android-0.1.8.apk | 47795970 | bd798ba3f1b543db13ad678f8a93e7ac077c9f44bb6f823d25f8425f535d165f |
| latest.json | 850 | 762ef1add3b3a85c3788c57b2f62c6ca50d16e3af0be54a34e5d9aa843fe6e42 |
| Autopilot-Android-0.1.8-source.zip | 404018 | 0665f79d3916989d52b10fdaf6ce90ef442ae3468d97ba32a62fa9c27fcc100d |
| SHA256SUMS | 273 | fde11bd053492364a49a3eee5e8eceb544fb411e59bceed3eee225fad82246a9 |

An independent actual HTTPS GET followed the same public `/releases/latest/download/latest.json` URL used by the app, passed the production `validateManifest` function and checked version, size and APK hash:

```text
ANDROID_018_PUBLIC_FEED_VERIFIED version=0.1.8 code=9 apkSize=47795970 matchingHash=true strictManifest=true redirectedHTTPS=true
```

An independent public GitHub latest-release API GET verified `tag_name=android-v0.1.8`, `draft=false`, uploaded asset states and matching sizes/digests:

```text
ANDROID_018_REMOTE_RELEASE_VERIFIED latest=true draft=false assetDigestsMatch=true
```

Local exact outputs: `.data/android-smart-health-018-{publish.log,public-feed.log,public-feed.json,public-release.log,public-release.json}`.

## Installation and practical limits

Existing 0.1.7 installations can obtain 0.1.8 through Updates → Check now → Download → Install; the package, signing key and account/settings storage are retained. This run did not install 0.1.8 on a physical phone, so no installed-device failover claim is made. An Android installation may require its ordinary confirmation and app restart.

Windows 0.3.3 was separately built and verified. Its setup is `dist/Autopilot-0.3.3-setup.exe`; it has not been installed over the active desktop copy. The final task-wide protected-state comparison reports:

```text
SMART_HEALTH_FINAL_DESKTOP_GUARD_PASS proxy=true originalVpnProcesses=true listener7890=true
```

That comparison covers the Windows proxy values, original VPN/app process identities and port-7890 listener, measured before this task and after all builds/probes. See the local `DESKTOP_033_RECEIPTS.md` for Windows artifact and runtime verification.
