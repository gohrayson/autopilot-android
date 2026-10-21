# Autopilot for Android

Native Android client for Autopilot. Requires Android 8.0 or later; includes ARM64, ARMv7 and x86_64. The Windows application is independent.

This is an Android test build. The first HyperOS 3 / Android 16 phone attempt confirmed installation and OneVPN sign-in/node refresh, then reported a native configuration rejection before tunnel startup. Version 0.1.1 adds native rejection diagnostics and isolates incompatible nodes. Build and parser verification are recorded in `ANDROID_CONFIG_FIX_RECEIPTS.md`; the first build's evidence remains in `ANDROID_RECEIPTS.md`. Actual phone VPN traffic still needs verification: this Windows host's official Android emulator crashes before its guest kernel starts. The emulator runtime probe refuses physical devices.

## Features

- Device-wide VPN through Android `VpnService`, with a persistent disconnect notification.
- Mihomo v1.19.32 embedded through libmihomo-android v0.3.11, with VLESS, Hysteria2, WireGuard and other supported outbounds.
- OneVPN and paid Octohide email/password sign-in, encrypted sessions, direct node refresh and local configuration import.
- Ordered, freely editable country preferences; automatic health, service, latency and stability selection; switch once or explicitly pin.
- Per-app bypass, applied on the next VPN start.
- Local diagnostics that exclude account passwords, sessions, configuration keys and response bodies.

## Build on Windows

Use JDK 17, Gradle 8.13 and the Android SDK with platform 35 and build-tools 35. Set `JAVA_HOME` and `ANDROID_HOME`; put Gradle on PATH. The project's isolated provisioning uses `.data/android-tools/env.ps1` when present.

```powershell
powershell -ExecutionPolicy Bypass -File android/tools/build.ps1
```

This verifies the pinned native AAR checksum, runs unit tests and Android lint, builds a locally signed release, and copies it to `dist/android/Autopilot-Android-<version>.apk`. The version comes from `app/build.gradle`. The AAR is downloaded from the pinned upstream release when absent. `-Configuration Debug` builds a separate preview package. Android/Gradle caches can stay on D:.

The release signing key is created once in `.data/android-signing/`. Its password is encrypted for the current Windows account using DPAPI. Keep that directory backed up securely to sign future updates. Neither the key nor its password belongs in a source archive.

## Install and use

1. Copy the APK to the phone and open it. Allow the file manager to install this app if Android asks.
2. Open Autopilot and sign in to OneVPN, Octohide, or both. Alternatively use **More → Import configuration** for a local Clash JSON/YAML or WireGuard export.
3. Choose **Edit priority order** to add and reorder preferred countries. An empty list chooses the fastest healthy node anywhere.
4. Tap **Start VPN** and accept Android's VPN permission. Allow notifications so the disconnect control remains visible.
5. A node tap switches once while leaving automation enabled. Hold a node to pin it; **Resume Auto** releases it.

Install updates over the existing app to retain accounts and country priorities. Do not uninstall first. When startup fails, use **⋯ → Diagnostics → Copy**. Diagnostics identify the configuration stage, a fixed rejection category, and the affected protocol/index when supplied by the core; credentials and raw provider configurations are excluded.

For HyperOS background operation, open Autopilot's Android app settings and set battery usage to **No restrictions** if HyperOS stops the VPN when the screen is off. Availability and wording vary by HyperOS build. Android's system VPN settings also support Always-on VPN and blocking connections without VPN; those settings remain the device owner's choice.

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
