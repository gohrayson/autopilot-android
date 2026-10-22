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
✔ hosted TUN fixture resolves fixed DNS names and serves reserved IPv4/IPv6 and domain SOCKS markers (112.0203ms)
ℹ tests 1
ℹ suites 0
ℹ pass 1
ℹ fail 0
ℹ cancelled 0
ℹ skipped 0
ℹ todo 0
ℹ duration_ms 156.2643
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
