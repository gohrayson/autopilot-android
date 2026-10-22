# Autopilot for Android

Native Android client for Autopilot. Requires Android 8.0 or later; includes ARM64, ARMv7 and x86_64. The Windows application is independent.

Version 0.1.4 replaces the Android tunnel's mixed TCP handling with the userspace gVisor stack. The mixed stack's kernel TCP forwarder conflicted with Autopilot's own UID being excluded from the VPN: node checks passed while other apps' TCP connections timed out. The failing separate-UID test and the same test's restored IPv4/IPv6 traffic are recorded in [the investigation receipt](ANDROID_014_RECEIPTS.md).

Android's DNS bootstrap, encrypted resolvers, fake-IP filters and IPv4 resolution policy now match the desktop app. The previous Android configuration used different resolvers, including a single Cloudflare resolver for every VPN server hostname. Node checks distinguish untested nodes from failures and copied diagnostics include safe DNS/network observations. Old core failures are labelled historical after a successful start. See [the DNS comparison](ANDROID_DNS_PARITY_RECEIPTS.md), [health diagnostics](ANDROID_HEALTH_DIAGNOSTICS_RECEIPTS.md), and [device tunnel probe](ANDROID_TUN_RUNTIME_RECEIPTS.md) for verification boundaries.

Version 0.1.2 fixes the native configuration rejection reproduced after the first HyperOS 3 / Android 16 phone installation. Android's JSON serializer escaped forward slashes as `\/`; the embedded core reads the profile as YAML and rejected those escapes, including in the empty baseline before any provider node was loaded. The production writer now removes only optional slash escapes while preserving the values of passwords, paths and other strings.

The actual Android native library now accepts the production empty baseline and reports the `AUTOPILOT` selector as `REJECT` on a hosted Android 15 / API 35 x86_64 emulator. [The hosted runtime receipt](ANDROID_HOSTED_RUNTIME_RECEIPTS.md) records the failure, fix, source commit, JUnit report and logcat. This verifies JNI configuration loading; phone VPN traffic, TUN routing and provider connections still need device verification. The release build evidence is in [ANDROID_012_RECEIPTS.md](ANDROID_012_RECEIPTS.md), earlier host parser evidence is in [ANDROID_CONFIG_FIX_RECEIPTS.md](ANDROID_CONFIG_FIX_RECEIPTS.md), and the first build's evidence remains in [ANDROID_RECEIPTS.md](ANDROID_RECEIPTS.md).

## Features

- Device-wide VPN through Android `VpnService`, with a persistent disconnect notification.
- Mihomo v1.19.32 embedded through libmihomo-android v0.3.11, with VLESS, Hysteria2, WireGuard and other supported outbounds.
- OneVPN and paid Octohide email/password sign-in, encrypted sessions, direct node refresh and local configuration import.
- Ordered, freely editable country preferences; automatic health, service, latency and stability selection; switch once or explicitly pin.
- Per-app bypass, applied on the next VPN start.
- Local diagnostics that exclude account passwords, sessions, configuration keys and response bodies.
- In-app update checks, verified downloads and Android package installation for signed release builds.
- Version 0.1.3 adds parallel update downloads, saved transfer progress, pause/resume controls and live speed/progress.

## Build on Windows

Use JDK 17, Gradle 8.13 and the Android SDK with platform 35 and build-tools 35. Set `JAVA_HOME` and `ANDROID_HOME`; put Gradle on PATH. The project's isolated provisioning uses `.data/android-tools/env.ps1` when present.

```powershell
powershell -ExecutionPolicy Bypass -File android/tools/build.ps1
```

This verifies the pinned native AAR checksum, runs unit tests and Android lint, builds a locally signed release, and copies it to `dist/android/Autopilot-Android-<version>.apk`. The version comes from `app/build.gradle`. The AAR is downloaded from the pinned upstream release when absent. `-Configuration Debug` builds a separate preview package. Android/Gradle caches can stay on D:.

The release signing key is created once in `.data/android-signing/`. Its password is encrypted for the current Windows account using DPAPI. Keep that directory backed up securely to sign future updates. Neither the key nor its password belongs in a source archive.

## Install and use

1. On the phone, open the [latest release](https://github.com/gohrayson/autopilot-android/releases/latest), download its `Autopilot-Android-<version>.apk` asset and open it. Allow the browser or file manager to install this app if Android asks. No PC transfer is required.
2. Open Autopilot and sign in to OneVPN, Octohide, or both. Alternatively use **More → Import configuration** for a local Clash JSON/YAML or WireGuard export.
3. Choose **Edit priority order** to add and reorder preferred countries. An empty list chooses the fastest healthy node anywhere.
4. Tap **Start VPN** and accept Android's VPN permission. Allow notifications so the disconnect control remains visible.
5. A node tap switches once while leaving automation enabled. Hold a node to pin it; **Resume Auto** releases it.

Install the signed release over the existing app to retain accounts and country priorities. The update must use the same signing certificate; do not uninstall first. When startup fails, use **⋯ → Diagnostics → Copy**. Diagnostics identify the configuration stage, a fixed rejection category, and the affected protocol/index when supplied by the core; credentials and raw provider configurations are excluded.

For HyperOS background operation, open Autopilot's Android app settings and set battery usage to **No restrictions** if HyperOS stops the VPN when the screen is off. Availability and wording vary by HyperOS build. Android's system VPN settings also support Always-on VPN and blocking connections without VPN; those settings remain the device owner's choice.

## App updates

Version 0.1.2 includes an **Updates** panel on the main screen and in the **⋯** menu. It uses the free public GitHub release feed at [`latest.json`](https://github.com/gohrayson/autopilot-android/releases/latest/download/latest.json) by default; no update server setup or GitHub sign-in is needed. A custom public HTTPS feed can be configured through **Update server**.

Automatic checks, Wi-Fi downloads and automatic installation are enabled by default. Checks run on app launch, every 15 minutes while the app is open, and about every six hours in the background; Android may delay background work. Automatic download requires the active network to be validated, unmetered Wi-Fi. If another VPN hides that transport, use **Download update** manually.

From version 0.1.3, APKs of at least 4 MiB use up to four parallel range connections when the server supports them. **Updates** shows the downloaded amount, speed, remaining time and active connections. **Pause download** keeps saved parts and stays paused across app restarts and automatic checks; **Resume download** continues missing ranges. Transient failures receive bounded retries. Servers without range support use a single connection, and changing range support safely restarts that transfer. Connection parallelism can improve a limited single connection; total network or server limits still apply.

An older installed version uses its existing download engine to obtain the current release. The accelerated engine is available from 0.1.3 onward. Existing installations can receive releases through **Updates → Check now** without transferring an APK from a PC.

Before installation, the app verifies the APK size and SHA-256, package identity, newer version, Android compatibility and the same signing certificate as the installed app. Open **Updates → Allow installation from Autopilot** once to grant Android's installation permission. On Android 12 / API 31 or later, automatic installation is attempted only while Autopilot's VPN is stopped and no connection is requested. Android or HyperOS may still ask for confirmation. On older Android versions, tap **Install update**. Installing restarts Autopilot; reopen it to reconnect. The app keeps accounts and preferences, and automatic installation does not stop a running VPN.

These update rules describe the implemented source behavior. Installation through Android's package installer has not yet been verified on the phone. Preview/debug builds use a separate package and do not accept the release update feed.

## Integration boundaries

This is a first Android release. Account sign-in must be performed on each device; Windows encrypted sessions are not transferable. Android cannot read the private login saved by the separate OneVPN app. The provider API remains authoritative for credentials, subscription and request limits. The known verified OneVPN login contract uses `appId=windows_app`; this constant is preserved rather than guessing an unverified Android app ID. Octohide uses its paid WireGuard export path, retains a small stable pool and respects persisted provider cooldowns. No Android integrity or account verification is bypassed.

Automatic selection requires fresh latency and Claude, ChatGPT and Gemini availability signals. A Cloudflare challenge is usable with a score penalty. Unknown or blocked signals cannot qualify a new node. While connected, a higher preferred healthy country takes priority; healthy upgrades within a tier require dwell and measurable improvement. Node identities include the complete outbound configuration, so rotated keys invalidate previous health.

Android owns the TUN routes. Both IPv4 and IPv6 are routed into the VPN; native outbound sockets are protected, and the app's own provider/control sockets are excluded to avoid a routing loop. Bootstrap DNS uses protected encrypted DNS so an initially rejected selector cannot deadlock its own health checks. The selector starts at REJECT until a node qualifies.

During a provider profile change, Java retains the VPN interface while the native reader reloads. Traffic pauses until native configuration succeeds. A rejected configuration can only attach a separately verified empty REJECT profile; it cannot expose the native library's default DIRECT fallback. Stop releases both the native reader and retained interface. A profile change can interrupt existing Android connections; ordinary healthy node upgrades keep the interface in place.

## Native dependency and source

The AAR is pinned to [libmihomo-android v0.3.11](https://github.com/oviron/libmihomo-android/releases/tag/v0.3.11), commit `5acd6a9ebf7946e18c8f0feaf8302fedf4ebb17d`.

SHA256: `01bc41748840c8200bbaf08c044ba8704e35303a3179b75a3cce8517c8944251`.

Its exact API is verified from source: initialization uses `home-dir`, profile path is `<home>/config.yaml`, action payload objects are JSON strings, and the wrapper does not start a REST controller. The native libraries require extraction, which is enabled explicitly. The ARM64 and x86_64 libraries have 16 KB ELF alignment; the legacy ARMv7 slice uses 4 KB alignment for 32-bit devices. The release verifier checks load segments, RELRO, ZIP alignment and the APK signature, following [Android's native compatibility guidance](https://developer.android.com/guide/practices/page-sizes).

Mihomo and the native bridge are GPL-3.0. The license is included as `COPYING` and in the app. The Android source and build instructions accompany the APK. Kotlin and SnakeYAML use Apache-2.0; JUnit is a test-only EPL-1.0 dependency. See `THIRD_PARTY_NOTICES.md`.
