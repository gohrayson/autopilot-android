# Android 0.1.7 publication and actual in-app upgrade

22 October 2026. [Build and runtime receipts](../android/ANDROID_017_RECEIPTS.md).

## Published assets

```powershell
& android/tools/package-source.ps1
node android/tools/publish-update.cjs --dry-run --notes-file .data/android-017-release-notes.txt
node android/tools/publish-update.cjs --publish --notes-file .data/android-017-release-notes.txt
```

The signed release and source passed preparation. The publisher verified all
remote digests before publication. An independent GitHub latest-release read
confirmed `android-v0.1.7`, `draft=false`, with matching asset sizes/digests:

| Asset | Bytes | SHA-256 |
| --- | ---: | --- |
| Autopilot-Android-0.1.7.apk | 47788050 | 6a93a24183d3277afe7184047d812cea73a8c8d58d03ab533c69f8da2c555e60 |
| latest.json | 682 | b3ad779b2c4144858ac2b6e79a6a2b9bc38a8eaf7dcf3242d9c0bd01430ee2d7 |
| Autopilot-Android-0.1.7-source.zip | 384134 | a0f25bbcb30bd6328c45a4b74cb871e61ded45cf3c10c3ba50a4719185752957 |

## Actual phone flow

The user's phone remained on the repaired 0.1.6 package while preparing 0.1.7.
No `adb install` command was used for 0.1.7. The user was instructed to use
Updates → Check now → Download update → Install update and confirm Android.

A direct phone screenshot at 20:01 local time showed:

```text
Installed: 0.1.6
Version 0.1.7 is ready to install
Last checked: 10/22/26 19:59:58
100% · 48 MB of 48 MB
Download complete and verified. Tap Install update to continue.
Automatic checks wait while this update is ready.
```

The user then reported "0.1.7 is installed". The package manager independently
confirms that Autopilot itself initiated and installed this package:

```powershell
& .data/android-tools/sdk/platform-tools/adb.exe -s 603f969d shell dumpsys package app.autopilot.android |
  Select-String -Pattern 'versionCode=|versionName=|installerPackageName=|initiatingPackageName='
```

```text
versionCode=8 minSdk=26 targetSdk=35
versionName=0.1.7
installerPackageName=app.autopilot.android
initiatingPackageName=app.autopilot.android
```

The first automatic check after the restart timed out while the VPN was still
checking nodes. After the VPN connected, the user tapped Check now again and
confirmed "latest version and grey buttons". A fresh screenshot independently
shows installed 0.1.7, "You have the latest version", last checked 20:02:28,
enabled pale blue Check now and grey disabled Download/Install controls. The VPN
foreground service is active. Account state remained present after the upgrade.

Local evidence:

- `.data/android-017-disabled-before.png`: 0.1.6's misleading blue disabled Install.
- `.data/android-017-phone-download.png`: actual 0.1.6-to-0.1.7 verified download.
- `.data/android-updater-017-phone-installed.log`: package version and installer identity.
- `.data/android-017-disabled-after.png`: actual 0.1.7 grey buttons and current status.

This verifies the complete user path: public manifest discovery, APK download,
verification, retained cache, Android installation and the resulting installed
version. The visual correction is confirmed on the real HyperOS phone as well
as the Android instrumentation.

## Desktop guard

The `protectedState()` snapshot from `scripts/probe-runtime.mjs` was compared
against the pre-work snapshot using `assert.deepEqual` after the phone upgrade:

```text
ANDROID_017_DESKTOP_GUARD_PASS proxy=true originalVpnProcesses=true listener7890=true
```

Windows proxy values, original VPN process identities and port-7890 listener are
unchanged. No desktop app installation or VPN reset was performed.
