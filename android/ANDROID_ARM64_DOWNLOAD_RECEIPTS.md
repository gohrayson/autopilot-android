# Smaller Android 0.1.2 download

Produced 2026-10-21 from the existing signed universal release. The ARM64-only
alternative is **16,270,898 bytes (15.52 MiB)**, down from 47,761,278 bytes:
31,490,380 fewer bytes, or 65.93% smaller. No application code, resources,
configuration, package name or version changed. This reduces bytes transferred;
it does not establish a faster GitHub connection.

| Artifact | SHA-256 |
| --- | --- |
| `dist/android/Autopilot-Android-0.1.2.apk` (unchanged universal) | `036649a428973de2c0a99dc7acb0ec04b82ed7ec663df0ed887a9da8617a98a3` |
| `dist/android/Autopilot-Android-0.1.2-arm64-v8a.apk` | `bb7a0d5f8b53f6d3a043c62bc376ef752487ab4461c2cf172483fd69ea350028` |

## Measured source ZIP sizes

Read from the actual canonical APK's ZIP central directory:

| Group | Entries | Uncompressed bytes | Compressed bytes |
| --- | ---: | ---: | ---: |
| ARM64 (`arm64-v8a`) | 2 | 43,529,272 | 14,989,489 |
| ARMv7 (`armeabi-v7a`) | 2 | 41,531,348 | 15,568,343 |
| x86_64 | 2 | 47,601,448 | 16,335,502 |
| Other app entries | 19 | 2,669,423 | 860,513 |

Source ZIP/signature overhead is 7,431 bytes. Removing the unused ABI compressed
payloads initially projected about 15.86 MB. The actual repackaged APK is 16.27 MB
because .NET recompresses retained entries differently; all their **uncompressed
payload bytes remain identical**. `resources.arsc` remains stored, and ZIP
alignment is verified after signing.

Only these entries were removed:

```text
lib/armeabi-v7a/libclash.so
lib/armeabi-v7a/libmihomo-jni.so
lib/x86_64/libclash.so
lib/x86_64/libmihomo-jni.so
```

## Runnable packaging receipt

Executed from `D:\Github\onevpn-autopilot`:

```powershell
& android/tools/package-arm64.ps1 2>&1 | Tee-Object -FilePath .data/android-download-size-analysis/package-arm64-run.log
```

The script pins the canonical source hash, verifies its signature, copies the
retained ZIP entries into a unique private staging directory, runs `zipalign`
before signing, and signs with the **existing** release key and DPAPI password.
It never creates or replaces a signing key, prints a password, changes source
files, invokes Gradle, modifies a release feed, or publishes an asset. Staging and
output paths are checked against their owned directories. An existing output is
refused; final copying also refuses replacement.

Actual signing/alignment tools invoked by the script:

```powershell
& $zipalign -P 16 4 $unsigned $aligned
& $apksigner sign --ks $key --ks-key-alias autopilot --ks-pass env:AUTOPILOT_ARM64_SIGNING_PASSWORD --key-pass env:AUTOPILOT_ARM64_SIGNING_PASSWORD --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false --v4-signing-enabled false --out $signed $aligned
& $apksigner verify --verbose --print-certs $signed
& $zipalign -c -P 16 4 $signed
& $aapt dump badging $signed
```

Verbatim signature result from the produced variant:

```text
VARIANT_SIGNATURE
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
Signer #1 certificate DN: CN=Autopilot Android
Signer #1 certificate SHA-256 digest: 6c6831265f4d5593b499b2bfc1bbcaf93f3eb4d143f49fef192d811d4b6060ab
```

Verbatim manifest and payload-verifier tail (exit 0):

```text
package: name='app.autopilot.android' versionCode='3' versionName='0.1.2' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
minSdkVersion:'26'
targetSdkVersion:'35'
native-code: 'arm64-v8a'
NATIVE_BYTES_IDENTICAL ELF_16K_OK lib/arm64-v8a/libclash.so load_segments=4
NATIVE_BYTES_IDENTICAL ELF_16K_OK lib/arm64-v8a/libmihomo-jni.so load_segments=3
RETAINED_PAYLOADS_IDENTICAL entries=21 removed=4
CANONICAL_APK_UNCHANGED SHA256=036649a428973de2c0a99dc7acb0ec04b82ed7ec663df0ed887a9da8617a98a3
APK=D:\Github\onevpn-autopilot\dist\android\Autopilot-Android-0.1.2-arm64-v8a.apk
BYTES=16270898
SHA256=bb7a0d5f8b53f6d3a043c62bc376ef752487ab4461c2cf172483fd69ea350028
VERIFICATION=D:\Github\onevpn-autopilot\.data\android-download-size-analysis\package-arm64\227c1e34201045dba29056f30bcf9989\verification.json
```

The verifier hashes every retained entry against the original, including
`classes.dex`, `AndroidManifest.xml`, resources and both native libraries. All
ARM64 `PT_LOAD` segments retain at least 16,384-byte alignment with congruent
file/virtual offsets; identical ELF payloads also preserve the previously verified
RELRO layout. The signed ZIP passes `zipalign -c -P 16 4` with exit 0. A separate
analysis invocation rechecked the variant's two native payload hashes:

```powershell
& .data/android-download-size-analysis/analyze-apk.ps1 -CompareArm64Apk dist/android/Autopilot-Android-0.1.2-arm64-v8a.apk -OutputJson .data/android-download-size-analysis/arm64-comparison.json
```

Verbatim relevant tail:

```text
APK_BYTES=47761278 ESTIMATED_ARM64_BYTES=15857433 SAVED_BYTES=31903845
SOURCE_APK_SHA256=036649a428973de2c0a99dc7acb0ec04b82ed7ec663df0ed887a9da8617a98a3
ARM64_ELF_16K_ALIGNMENT_OK libraries=2
VARIANT_NATIVE_BYTES_IDENTICAL
```

## Compatibility and scope

This alternate APK is for devices supporting `arm64-v8a`. Its application ID,
versionCode 3, versionName 0.1.2, minimum API 26, target API 35 and production
certificate are unchanged. Account/preferences belong to the same package; this
is an in-place signed installation, not a separate app. No new version or update
feed change was introduced. The current updater requires a higher versionCode, so
this same-version alternative is a direct-download choice, not a new offered update.

A future universal APK remains compatible when it keeps this package and signing
certificate, includes ARM64, supports the device's Android version, and has a
higher versionCode. The existing universal update feed can therefore stay as-is.
No phone install, Xiaomi ABI query, package replacement, VPN connection or runtime
network probe was performed for this packaging task.

Primary references:

- [Android ABI packaging and native-library selection](https://developer.android.com/ndk/guides/abis)
- [Android APK signing and alignment order](https://developer.android.com/tools/apksigner)
