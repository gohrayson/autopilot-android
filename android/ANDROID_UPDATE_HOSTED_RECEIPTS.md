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

## Public hosted feed

Pending publication. Compilation alone does not prove public feed accessibility, successful download, app-side background checks or phone installation. After publication, run:

```powershell
& .data/hosted-update-probe/probe.ps1
```

Record the exact output below only after the live production downloader succeeds.
