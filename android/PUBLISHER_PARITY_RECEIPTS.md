# Unified release publisher verification

Date: 2026-10-23. These checks prepare data or use fixture GitHub APIs. No release upload/publication, build, signing-key access, application installation, VPN change, or provider request occurred in this worker.

## Unified asset contract

For Android versions at or above `0.4.0`, `prepareRelease` requires:

- Android `app/build.gradle`, desktop `package.json`, and the shared Android/desktop contract to describe the same version and Android version code.
- Desktop product identity `Autopilot`, app ID `local.autopilot.proxy`, and both NSIS/setup and portable targets.
- `dist/Autopilot-<version>-setup.exe` and `dist/Autopilot-<version>-portable.exe`, each with an MZ/PE header, NSIS signature, and unambiguous VERSIONINFO ProductName/FileVersion/ProductVersion matching the release. No executable is launched for verification.
- A clean source ZIP whose files equal the current local sources. Only the exact `android/app/src/main/assets/geoip.metadb` entry may exceed the ordinary 4 MiB per-entry bound, up to 12 MiB. Its size and SHA-256 must match the local `geoip-manifest.json`, its bytes must equal the local asset, and both database and manifest must be included. Other entry and aggregate bounds are unchanged.

The shared GitHub tag remains `android-v<version>` so existing Android update URLs keep working. Prepared assets are:

```text
Autopilot-Android-<version>.apk
latest.json
Autopilot-Android-<version>-source.zip
Autopilot-<version>-setup.exe
Autopilot-<version>-portable.exe
windows-latest.json
SHA256SUMS
```

`latest.json` retains the existing exact Android fields. `windows-latest.json` has exactly:

```json
{
  "schemaVersion": 1,
  "packageName": "local.autopilot.proxy",
  "arch": "x64",
  "versionName": "0.4.0",
  "installerUrl": "https://github.com/gohrayson/autopilot-android/releases/download/android-v0.4.0/Autopilot-0.4.0-setup.exe",
  "sha256": "<SHA-256 of setup bytes>",
  "sizeBytes": "<integer setup byte count>",
  "notes": "<shared release notes>",
  "publishedAt": "<UTC ISO publication timestamp>"
}
```

`SHA256SUMS` covers every primary asset, including both manifests and Windows executables. Legacy pre-0.4.0 Android-only preparation is preserved. Public releases remain immutable; matching drafts resume without reuploading matching assets, and any remote digest mismatch blocks publication.

## Runnable fixture tests

From `D:\Github\onevpn-autopilot`:

```powershell
node --test android/tools/publish-update.test.cjs 2>&1 | Tee-Object -FilePath D:\Github\onevpn-autopilot\.data\parity-publisher-tests.log
exit $LASTEXITCODE
```

Verbatim runner tail:

```text
✔ unified release keeps Android schema and adds verified setup/portable/windows manifest with full checksums (11.9283ms)
✔ unified version mismatch and stale Windows product identity stop preparation before publication (39.9546ms)
✔ Windows identity checks require an actual PE/NSIS header and unambiguous resource version (0.424ms)
✔ Windows update manifest is exact and hashes the installer under the same immutable tag (11.3613ms)
✔ only exact pinned GeoIP entry may exceed ordinary source limit and it must match manifest/hash/local bytes (31.3394ms)
✔ unified draft uploads all assets once and publication remains immutable with verified remote digests (11.7147ms)
ℹ tests 19
ℹ suites 0
ℹ pass 19
ℹ fail 0
ℹ cancelled 0
ℹ skipped 0
ℹ todo 0
ℹ duration_ms 164.985
```

## Read-only real NSIS artifact probe

This checks the parser against actual existing Electron-builder output, separately from the synthetic PE fixtures. It is not a claim that the new release has been built or published.

Runnable command:

```powershell
@'
const fs=require('node:fs');const publisher=require('./android/tools/publish-update.cjs');for(const kind of ['setup','portable']){const name=`dist/Autopilot-0.3.3-${kind}.exe`;const info=publisher.verifyWindowsExecutable(fs.readFileSync(name),'0.3.3',kind);console.log(`WINDOWS_RELEASE_IDENTITY_PROBE file=${name} product=Autopilot version=0.3.3 pe=true nsis=true sizeBytes=${info.sizeBytes} sha256=${info.sha256}`)}
'@ | node
```

Verbatim output:

```text
WINDOWS_RELEASE_IDENTITY_PROBE file=dist/Autopilot-0.3.3-setup.exe product=Autopilot version=0.3.3 pe=true nsis=true sizeBytes=115627953 sha256=e57270b4ff13ffd15488b584ebb405b0014266b2d682c5b9338c432fb2a6be51
WINDOWS_RELEASE_IDENTITY_PROBE file=dist/Autopilot-0.3.3-portable.exe product=Autopilot version=0.3.3 pe=true nsis=true sizeBytes=115410558 sha256=a68ef5fd88d32c266073458af6ab7b79459d10d2f1849fbf4145ff4179966e1f
```

New-release dry-run preparation must still be executed after final APK/Windows builds and source packaging. This receipt deliberately does not stand in for that actual artifact check.
