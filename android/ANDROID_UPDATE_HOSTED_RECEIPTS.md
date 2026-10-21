# Hosted Android update integration receipt

Repository: `D:\Github\onevpn-autopilot`. Probe prepared on 2026-10-21.

## Compilation

Verbatim invocation from the repository root:

```powershell
& .data/hosted-update-probe/probe.ps1 -CompileOnly
```

Verbatim output:

```text
Compiled unmodified production UpdateManifest and UpdateDownload with hosted probe.
Compile-only mode: no network request, account access or VPN changes.
```

The helper compiles the unmodified production `UpdateManifest.java` and `UpdateDownload.java`, using the existing JDK and Gradle-cached org.json dependency. The live mode reads the feed constant from production `UpdateManager.java`, validates package/version/minSdk against the current Gradle configuration, then uses the actual production no-auth HTTPS transport, redirect whitelist, size limits and SHA-256 verification to download an APK into `.data/hosted-update-probe/cache`. It verifies the resulting APK cryptographic signature and pinned signer/package/version using `apksigner.jar`, `aapt2` and the existing publisher metadata adapter. It never invokes a provider, installer, VPN service or route command.

## Public hosted feed: direct desktop transport

This is a **post-release verification receipt**: the public `android-v0.1.2` release/source archive was already published before this result was added and has been retained unchanged. No release asset was replaced. This is our publication policy; it does not claim GitHub's separate immutable-release flag is enabled.

Verbatim invocation after publication:

```powershell
& .data/hosted-update-probe/probe.ps1
```

Verbatim output:

```text
Compiled unmodified production UpdateManifest and UpdateDownload with hosted probe.
Exception in thread "main" app.autopilot.android.UpdateDownload$UpdateException: UPDATE_TIMEOUT
    at app.autopilot.android.UpdateDownload.error(UpdateDownload.java:175)
    at app.autopilot.android.UpdateDownload.fetchManifest(UpdateDownload.java:68)
    at app.autopilot.android.HostedUpdateProbe.main(HostedUpdateProbe.java:21)
Exception: D:\Github\onevpn-autopilot\.data\hosted-update-probe\probe.ps1:33
Line |
  33 |  … CODE -ne 0){throw 'Production hosted update manifest/download probe f …
     |                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     | Production hosted update manifest/download probe failed.
```

Exit code: 1. The production downloader timed out fetching the public manifest on this desktop's direct Java HTTPS network path. No APK was downloaded, so this attempt does not verify the hosted APK's hash or signature. Desktop `HttpsURLConnection` does not automatically inherit the Windows HTTP proxy configuration. The result does not establish phone-side feed reachability, background scheduling or installation.

## Public hosted feed: existing desktop proxy

The existing Windows proxy was read without changing it: enabled, `127.0.0.1:7890`. The second probe supplied only JVM process-local `-Dhttps.proxyHost=127.0.0.1` and `-Dhttps.proxyPort=7890` properties, so ordinary JVM proxy selection used that existing listener. Production `UpdateDownload.HttpsTransport`, redirect handling, size/hash checks and public no-auth requests remained unchanged. No VPN, proxy registry setting, route or service was changed.

Verbatim invocation:

```powershell
& .data/hosted-update-probe/probe.ps1 -HttpsProxyPort 7890
```

Verbatim combined output:

```text
Compiled unmodified production UpdateManifest and UpdateDownload with hosted probe.
Using existing desktop proxy via JVM process-local HTTPS settings: 127.0.0.1:7890.
Production manifest fetch/parse: PASS
Feed: https://github.com/gohrayson/autopilot-android/releases/latest/download/latest.json
Package: app.autopilot.android
Version: 0.1.2 / 3
Minimum SDK: 26
APK URL: https://github.com/gohrayson/autopilot-android/releases/download/android-v0.1.2/Autopilot-Android-0.1.2.apk
Production redirect/size/SHA-256 verification: PASS
APK size: 47761278
APK SHA-256: 036649a428973de2c0a99dc7acb0ec04b82ed7ec663df0ed887a9da8617a98a3
Downloaded APK: D:\Github\onevpn-autopilot\.data\hosted-update-probe\cache\autopilot-update-3.apk
Downloaded APK cryptographic signature, pinned production signer, package, version and minSdk: PASS
HOSTED_UPDATE_PROBE_PASS: public feed, production downloader, APK size/hash, pinned signer and package/version/minSdk.
```

Exit code: 0. The public release feed and APK were fetched without GitHub/provider credentials. The actual production parser accepted the hosted metadata; actual production download code followed its allowed HTTPS redirect chain, enforced the announced byte count, verified SHA-256, and wrote only the private local cache file. Real `apksigner` verification passed and the metadata adapter confirmed the existing production signing certificate, application ID, version and minimum SDK.

This proves the hosted update download path through this desktop's existing proxy. It does **not** prove a phone's independent GitHub reachability, Android/HyperOS background execution, installer permission prompts or an actual update installation. This post-release receipt and its live results were added after the release/source archive was published; no production source or release asset was changed.
