# Android APK verification receipt

Observed on 2026-10-21. This is the first Android test build. The Windows application's source, running processes and proxy were not changed by this work.

## Build and JVM checks

From `D:\Github\onevpn-autopilot`:

```powershell
& android/tools/build.ps1 -Configuration Release
```

The script explicitly invokes `:app:testDebugUnitTest :app:lintDebug :app:assembleRelease` through Gradle 8.13 / AGP 8.10.1 with JDK17 and Android SDK35. All caches and signing material stay in the private D: workspace. The signing password is DPAPI encrypted for this Windows account; no account password is included in the APK or logs.

Verbatim final build tail:

```text
You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.13/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 35s
79 actionable tasks: 17 executed, 62 up-to-date
APK: D:\Github\onevpn-autopilot\dist\android\Autopilot-Android-0.1.0.apk
SHA256: 4ca4cb4ea075fd63bccc82fe7dd4d5e15a232c427a681943b563da5e56d43e38
```

Runnable suite-count receipt (JUnit4 is the JVM runner):

```powershell
Get-ChildItem android/app/build/test-results/testDebugUnitTest -Filter '*.xml' | ForEach-Object {
    [xml]$suiteXml=Get-Content -LiteralPath $_.FullName
    '{0}: tests={1} failures={2} errors={3} skipped={4}' -f $suiteXml.testsuite.name,$suiteXml.testsuite.tests,$suiteXml.testsuite.failures,$suiteXml.testsuite.errors,$suiteXml.testsuite.skipped
}
```

```text
app.autopilot.android.EngineConfigurationTest: tests=3 failures=0 errors=0 skipped=0
app.autopilot.android.ProfileTransitionTest: tests=4 failures=0 errors=0 skipped=0
app.autopilot.android.ProviderRepositoryTest: tests=20 failures=0 errors=0 skipped=0
app.autopilot.android.SelectionPolicyTest: tests=6 failures=0 errors=0 skipped=0
app.autopilot.android.ServiceChecksTest: tests=4 failures=0 errors=0 skipped=0
```

Total: 37 JVM tests, zero failures/errors/skips. Final Android lint report: zero errors; three informational version-availability warnings (pinned Gradle and test dependencies). No baseline or disabled lint check was used.

The tests cover real provider parsing/decryption, fixed API payloads, token-write ordering, failure/cooldown persistence, targeted export renewal, active-node preservation, country/freshness/stability policy, real stalled-loopback cancellation, exact native config construction, and profile replacement against a fixture that actively installs DIRECT defaults on semantic rejection. Fixtures do not establish device behavior.

## Signed artifact inspection

```powershell
& android/tools/verify-apk.ps1
```

Verbatim signature output:

```text
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
Signer #1 certificate SHA-1 digest: 8de65c4c3b55cb9f301052437764751b02ad6b78
Signer #1 certificate MD5 digest: 4d74fd8008406c45e9122fb124a1fd8b
Signer #1 key algorithm: RSA
Signer #1 key size (bits): 3072
Signer #1 public key SHA-256 digest: b3ce45cd1812c04513999a05e167d991f9a1ce2c8b701795d7b292a5ca1d650e
Signer #1 public key SHA-1 digest: 9bf3c9b69d058776546f2f52d2fd8fe5a46215c0
Signer #1 public key MD5 digest: a07db0ab023e7ff0dcd2ff346c819d10
```

Manifest: `app.autopilot.android`, version `0.1.0`, minSDK26, targetSDK35. ARM64, ARMv7 and x86_64 are included.

```text
Verified 16384 byte ELF alignment and RELRO protection: lib/arm64-v8a/libclash.so, 4 load segments
Verified 16384 byte ELF alignment and RELRO protection: lib/arm64-v8a/libmihomo-jni.so, 3 load segments
Verified 4096 byte ELF alignment and RELRO protection: lib/armeabi-v7a/libclash.so, 4 load segments
Verified 4096 byte ELF alignment and RELRO protection: lib/armeabi-v7a/libmihomo-jni.so, 3 load segments
Verified 16384 byte ELF alignment and RELRO protection: lib/x86_64/libclash.so, 4 load segments
Verified 16384 byte ELF alignment and RELRO protection: lib/x86_64/libmihomo-jni.so, 3 load segments
Verified APK contains no account vault, signing key, diagnostics log, or probe runtime fixture.
Verified APK ZIP alignment for 16 KB pages.
```

The ELF verifier checks all 64-bit LOAD alignment/congruence and actual page-rounded RELRO protection against executable/mutable LOAD bytes. Bionic rounds RELRO outward; the small JNI library has a padded/separated next writable LOAD, so a nominal end-modulo-only heuristic is overly conservative for that layout. Source contract: https://android.googlesource.com/platform/bionic/+/refs/heads/android16-release/linker/linker_phdr.cpp . These are static binary checks, not execution on a 16 KB phone.

The pinned native AAR is libmihomo-android v0.3.11, SHA256 `01bc41748840c8200bbaf08c044ba8704e35303a3179b75a3cce8517c8944251`, embedding mihomo v1.19.32. No native binary was patched.

## Runtime status: not verified on Android

The device probe is implemented and runnable only on a local emulator:

```powershell
. .data/android-tools/env.ps1
gradle.bat -p android :app:assembleDebug :app:assembleDebugAndroidTest :probe:assembleDebug --console=plain --no-daemon
node android/tools/probe-runtime.cjs emulator-5580
```

It exercises AndroidKeyStore encryption, native load/setup/selection, actual traffic from another Android UID through IPv4/IPv6 TUN routes, and Stop cleanup. It imports only a local test proxy; it never signs in to live provider accounts or installs on a physical phone automatically.

This host's official emulator37.2.12 crashed with Windows native access violation `0xC0000005` before guest kernel execution, including minimal single-core, GPU-off software mode. No hypervisor/driver was enabled and no reboot was performed. ADB has no connected device. Exact logs and Windows Application Error1000 excerpts: `.data/android-tools/TOOLCHAIN_RECEIPTS.md`. All owned failed emulator helpers were stopped.

Verbatim probe attempt:

```text
Error: An online local emulator is required. Physical devices are refused.
    at main (D:\Github\onevpn-autopilot\android\tools\probe-runtime.cjs:26:74)
    at process.processTicksAndRejections (node:internal/process/task_queues:104:5)
PASS: Windows VPN processes, proxy and port 7890 unchanged.
```

Consequently Android UI rendering, actual AndroidKeyStore execution, live provider sign-in and device-wide VPN traffic are still unverified. The user's HyperOS phone must provide that first device check. This APK must not be described as fully device-tested.

## Windows connection guard

The runtime probe and a final independent read-only `protectedState()` comparison confirm identical Autopilot/OneVPN helper/mihomo process identities, registry proxy and port7890 listener before/after Android work. No installed Windows app was restarted; no Windows proxy or routes were changed. Supporting private snapshots: `.data/android-windows-vpn-before.json`, `.data/android-runtime/windows-before.json`, `.data/android-runtime/windows-after.json`.
