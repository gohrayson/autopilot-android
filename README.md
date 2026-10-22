# Autopilot

Autopilot selects a healthy VPN node using service availability, country priorities, speed and stability. It supports existing OneVPN and paid Octohide accounts, plus local configuration imports. Starting with 0.4.0, Android and Windows use the same versioned product contract for service requirements, selection thresholds, failure confirmation, country priority and recovery rules.

This repository contains the Android client and its corresponding GPL source. It contains no account data, VPN credentials or release signing key. Releases are signed with the same Autopilot Android certificate so updates retain app data.

Android and Windows release assets and public update feeds are published under [Releases](https://github.com/gohrayson/autopilot-android/releases). Android uses `latest.json`; Windows uses `windows-latest.json`. A first manual installation of an updater-enabled version is required; later versions can be downloaded within Autopilot. Android/HyperOS may still require installation confirmation. Installation does not silently stop a running VPN.

Both platforms expose automatic selection, switch once, pinning, Pause/Resume Auto, country priorities, required services, global/rule routing, app and website splitting, provider metadata, configuration import/removal, Events, diagnostics, and portable preference import/export. App identities and VPN permissions remain specific to each operating system. On Android, app exclusions are system routes: website overrides apply only to captured apps, and app-list changes require an explicit VPN restart.

The hosted runtime workflow uses an isolated, account-free Android emulator. It validates the real native parser, common settings UI and pinned regional database, multipart update downloads, explicit HTTPS updater routing, and recovery fixtures. Its separate-UID traffic gate opens the production TUN path on the emulator and checks IPv4, IPv6, endpoint/bootstrap DNS, application DNS, fake-IP mapping and cleanup from a different application UID. The workflow never signs in to VPN providers, uses real account exports, or changes a user's desktop or physical-device VPN. A green parser or proxy-listener check alone is insufficient to pass that traffic gate.

See `android/README.md` for build instructions and native dependencies. GPL-3.0 licensing follows the embedded mihomo core; see `LICENSE` and `android/THIRD_PARTY_NOTICES.md`.
