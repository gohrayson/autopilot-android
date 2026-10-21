# Autopilot for Android

Autopilot selects a healthy VPN node using service availability, country priorities, speed and stability. It supports existing OneVPN and paid Octohide accounts, plus local configuration imports.

This repository contains the Android client and its corresponding GPL source. It contains no account data, VPN credentials or release signing key. Releases are signed with the same Autopilot Android certificate so updates retain app data.

Android releases and the public update feed are published under [Releases](https://github.com/gohrayson/autopilot-android/releases). A first manual installation of an updater-enabled version is required; later versions can be downloaded within Autopilot. Android/HyperOS may still require installation confirmation.

The initial runtime workflow exercises only a credential-free native startup profile on an isolated Android emulator. It never signs in to VPN providers or opens a device-wide tunnel.

See `android/README.md` for build instructions and native dependencies. GPL-3.0 licensing follows the embedded mihomo core; see `LICENSE` and `android/THIRD_PARTY_NOTICES.md`.
