# Publishing Android updates

The public update feed is:

`https://github.com/gohrayson/autopilot-android/releases/latest/download/latest.json`

GitHub Releases provides the free HTTPS hosting. The APK, update manifest, SHA256SUMS and corresponding source ZIP belong to one release named `android-v<version>`. The publisher never writes account data or signing keys to GitHub. It reads a noninteractive Git Credential Manager credential in memory, or uses `GH_TOKEN`/`GITHUB_TOKEN` from the environment. Credentials are sent only to GitHub's API and upload origins; HTTP redirects are not followed with credentials.

## Publish a version

1. Increase **both** `versionCode` and `versionName` in `android/app/build.gradle`. Keep the existing local release signing key: changing it would prevent Android from updating an existing installation.
2. Finish the source changes, update receipts, and run the Android build and verification. Keep release notes in a plain UTF-8 text file, at most 4 KB. Use no account information or credentials in the notes.
3. Generate the clean source archive **after all Android source/documentation changes**. The publisher rejects an archive whose files differ from the working tree.
4. Run the read-only publisher check, review its version, package, hashes and release notes, then publish explicitly.

From the repository root in PowerShell:

```powershell
& android/tools/build.ps1
& android/tools/verify-apk.ps1
& android/tools/package-source.ps1
node --test android/tools/publish-update.test.cjs
node android/tools/publish-update.cjs --dry-run --notes-file .data/android-release-notes.txt
node android/tools/publish-update.cjs --publish --notes-file .data/android-release-notes.txt
```

Future publication is one command after the build and source archive are ready:

```powershell
node android/tools/publish-update.cjs --publish --notes-file .data/android-release-notes.txt
```

The default, with no mode flag, is `--dry-run`: local checks and JSON output only. It does not acquire credentials, contact GitHub, modify artifacts or write the feed. `--draft` uploads and verifies a private draft for review. A matching draft can then be resumed with the same notes file and `--publish`; stable APK modification time makes the manifest reproducible across these attempts. If files or notes change after creating the draft, the tool stops on mismatched assets rather than replacing them. Inspect/delete that draft manually, or use a new version. Never overwrite a public release: version bumps are mandatory.

The publisher invokes Java's `apksigner.jar` and `aapt2` directly, without a shell. It verifies the production application ID `app.autopilot.android`, Gradle version, minimum SDK 26, non-debuggable APK, and pinned production certificate SHA-256 `6c6831265f4d5593b499b2bfc1bbcaf93f3eb4d143f49fef192d811d4b6060ab`. Local tools are discovered under `.data/android-tools` or the usual `JAVA_HOME` and `ANDROID_HOME` environment variables. Build tools 35.0.0 are required. The full native alignment verification remains in `verify-apk.ps1`.

All assets are uploaded while the release is a draft. Each upload's server-reported SHA-256 digest, size and state are checked and then read back from GitHub. A final draft enumeration is checked before publication. Missing digests, unknown assets, duplicate assets, foreign upload origins and any changed bytes abort publication. Existing public tag releases are rejected without writes. GitHub is updated only by explicit `--draft` or `--publish`; the script never installs an APK or touches an active VPN.

## Verify the hosted feed

After publication, fetch the public feed without credentials and confirm that the APK URL resolves to the new release. Compare the downloaded APK's SHA-256 with the feed and `SHA256SUMS`. On the phone, use **Check for updates** and confirm that the new version is offered. Unit fixtures and a local dry run cannot prove phone-side installation or HyperOS background scheduling. Android may require user confirmation to install an update; the app's update check cannot silently grant installer permissions.

Publisher fixture evidence is in [PUBLISH_UPDATE_RECEIPTS.md](PUBLISH_UPDATE_RECEIPTS.md). Release-specific Android build and runtime evidence belongs in the other Android receipts; publication of a build alone does not establish that its VPN tunnel works on a physical phone.
