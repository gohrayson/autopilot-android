# Android 0.1.6 publication and phone repair

22 October 2026. [Build/runtime evidence](../android/ANDROID_016_RECEIPTS.md).

## Publication

```powershell
& android/tools/package-source.ps1
node android/tools/publish-update.cjs --dry-run --notes-file .data/android-016-release-notes.txt
node android/tools/publish-update.cjs --publish --notes-file .data/android-016-release-notes.txt
```

The publisher validated the local signed APK and corresponding source, then
verified every remote asset digest before publishing. A separate read of the
live GitHub latest release confirmed `android-v0.1.6`, `draft=false`.

| Asset | Bytes | SHA-256 |
| --- | ---: | --- |
| Autopilot-Android-0.1.6.apk | 47787754 | ad473e088a19504bdecdb9aaaafb846155a111cacdb9e7d925145f311a4f31eb |
| latest.json | 1135 | 50f03d70c44e4493cf33035e8c478aee95a0b5147fa12a238392a497c179c9a0 |
| Autopilot-Android-0.1.6-source.zip | 381093 | 526d8f2e6a57ca6d633d7e9be3aba812abffd35b91115b5cfe27aa38964aa5aa |

The original retained 0.1.2 updater classes also fetched and parsed the real live
manifest through the existing desktop proxy. The explicit expected arguments
were `0.1.6` and the APK hash above; the probe recorded:

```text
LEGACY_012_UPDATE_DISCOVERY_PASS version=0.1.6 versionCode=7 newerThanInstalled3=true Android16Supported=true sha256=ad473e088a19504bdecdb9aaaafb846155a111cacdb9e7d925145f311a4f31eb
```

## Phone replacement and actual check

The user's phone could not reach Android's installer from its broken cached
0.1.5 update. A one-time USB replacement was installed over the existing 0.1.4
package, without uninstalling it:

```powershell
& .data/android-tools/sdk/platform-tools/adb.exe -s 603f969d install -r --no-incremental dist/android/Autopilot-Android-0.1.6.apk
& .data/android-tools/sdk/platform-tools/adb.exe -s 603f969d shell dumpsys package app.autopilot.android
& .data/android-tools/sdk/platform-tools/adb.exe -s 603f969d shell am start -n app.autopilot.android/.MainActivity
```

```text
Performing Streamed Install
Success
versionCode=7 minSdk=26 targetSdk=35
versionName=0.1.6
```

The user connected the VPN and tapped Updates → Check now. The user reported
"You have the latest version". ADB UI inspection independently observed:

```text
Installed: 0.1.6
You have the latest version
Last checked: 10/22/26 19:49:09
No newer release was found. Tap Check now to check again.
Check now: enabled=true
Download update: enabled=false
Install update: enabled=false
```

`dumpsys activity services app.autopilot.android` confirmed the foreground VPN
service remained active for that check. Local evidence is
`.data/android-updater-016-phone-verified-ui.xml`. The Windows proxy, VPN process
identities and port-7890 listener matched their pre-work snapshot.

The user then correctly identified a separate visual defect: disabled Install
remained bright blue because its background/text tints were fixed colors.
That correction belongs to 0.1.7; these published 0.1.6 assets remain immutable.
The next real installation through Autopilot's updater is being used to verify
the complete update flow rather than infer it from a successful check alone.
