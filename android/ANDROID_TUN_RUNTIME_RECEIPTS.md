# Separate UID TUN runtime probe

The hosted probe exercises the production Android VPN service, retained TUN descriptor,
native configuration builder, native SOCKS hostname bootstrap and IPv4/IPv6 device traffic.
The request APK has a different UID from Autopilot, so excluding Autopilot from its own VPN
cannot accidentally bypass this verification. Both endpoints are reserved documentation
addresses; only the isolated fixture can return the random marker.

The fixture never forwards requests to the Internet or the desktop proxy. The test starts
with no provider accounts or cached nodes, so the production watchdog cannot run live AI
service checks. Test-side reflection serializes native setup on the engine's worker and
invokes production `establish(true)`; no test stub is added to a production flow.

The bootstrap negative control uses a closed resolver at `127.0.0.1:29554`. The positive
control uses emulator host DNS at `10.0.2.2:29553`, with a SOCKS endpoint that itself requires
DNS (`proxy-fixture.autopilot.test`). The application domain is excluded from fake-IP answers
inside the fixture configuration. The runner requires evidence of actual upstream DNS
questions for both names before it accepts the probe. An unavailable resolver reproduces
the failure mechanism; it does not prove which resolver is unreachable on the user's phone.

A fourth separate UID request uses the unfiltered `fakeip-fixture.autopilot.test` domain.
The runner requires a DNS result inside `198.18.0.0/16`, a successful marker response, and
the original domain in the SOCKS request. It rejects a real DNS fixture query for that
domain. These checks cover both the ordinary fake-IP answer and reverse mapping when the
application connects to its synthetic address. They are hosted runtime assertions, not
facts established by the local fixture self-test below.

## Fixture verification run on Windows

Working directory: `D:\Github\onevpn-autopilot`

```powershell
node --check android/tools/ci-tunnel-check.cjs
node --test android/tools/ci-tunnel-fixture.test.cjs
```

Verbatim runner tail (2026-10-22):

```text
✔ hosted TUN fixture resolves fixed DNS names and serves reserved IPv4/IPv6 and domain SOCKS markers (112.6639ms)
ℹ tests 1
ℹ suites 0
ℹ pass 1
ℹ fail 0
ℹ cancelled 0
ℹ skipped 0
ℹ todo 0
ℹ duration_ms 155.6248
```

## Hosted runtime invocation

Build with the pinned toolchain, then invoke from the hosted emulator action:

```bash
gradle -p "$AUTOPILOT_ANDROID_DIR" --no-daemon --console=plain \
  :app:assembleDebug :app:assembleDebugAndroidTest :probe:assembleDebug
bash "$AUTOPILOT_ANDROID_DIR/tools/ci-native-check.sh" "$AUTOPILOT_ANDROID_DIR"
```

The existing JNI baseline/download JUnit XML gate runs first. The separate UID TUN stage
then runs `node "$AUTOPILOT_ANDROID_DIR/tools/ci-tunnel-check.cjs" "$AUTOPILOT_ANDROID_DIR"`
and selects only
`app.autopilot.android.NativeTunnelRuntimeTest.productionTunnelCarriesIpv4Ipv6AndDnsFromOtherUid`.
It checks the instrumentation test receipt, marker requests, distinct UID, native endpoint
DNS, application DNS, reserved IPv4/IPv6 destinations, fake-IP pool/reverse mapping, and
production Stop cleanup.

The evidence directory is `ci-native-results/` and includes `tunnel-instrumentation.log`,
`tunnel-other-uid.log`, `tunnel-fixture.json`, `tunnel-ready.json`, `tunnel-receipt.txt`,
and the existing logcat/device/JUnit outputs.

Hosted runtime has not been executed at this handoff. The orchestrator owns the combined
Android compilation, workflow upload, hosted execution and subsequent runtime receipts.
No claim of a working device TUN is made from the fixture self-test.

## Foreground UID network-policy readiness

Hosted run `39704959404` established IPv4 and IPv6 marker traffic after the native stack
change, then the domain request failed immediately. The collected resolver log reported
`network access blocked` for the isolated probe UID before the fixture received an
application DNS question. This does not establish a native DNS failure.

The probe APK now launches a visible `ProbeActivity` and registers a default network
callback. It waits for an actual VPN network and `onBlockedStatusChanged(false)` before
calling the ordinary `InetAddress.getAllByName`; there is no guessed startup delay. Each
result records UID, network handle, VPN/blocked state, process importance and background
restriction status. Probe failure also captures `dumpsys connectivity`, `netpolicy`,
activity process state and the probe package's UID/permissions in the hosted artifacts.
The coordinating run subsequently executed this probe successfully on Android API 35:
[run 39705698643](https://github.com/gohrayson/autopilot-android/actions/runs/39705698643),
source `b623dd51f50b26031dc261b8e65fb41a809adc67`. The full failing/passing
comparison and verbatim runtime receipts are in [ANDROID_014_RECEIPTS.md](ANDROID_014_RECEIPTS.md).

The fixture records and rejects Android's optional DNS-over-TLS attempts to exactly
`172.19.0.2:853`. Those expected rejections do not fail the runner. Every other unexpected
SOCKS destination still fails the runtime gate. The local fixture test above verifies both
the expected DoT classification and the separate unexpected-destination classification.
