# Updater explicit VPN route verification

Working directory: `D:\Github\onevpn-autopilot`.

The updater now selects explicit `Proxy.NO_PROXY` only when no Autopilot VPN exists,
or the verified current node's existing HTTP proxy at `127.0.0.1`. An active VPN
without an eligible node returns `UPDATE_VPN_ROUTE_UNAVAILABLE`; failed proxied
requests never fall back to direct. Platform TLS, redirect restrictions, range
validation, and APK size/hash validation remain active. Manifest requests now use
the same session watchdog as APK transfers, so cancellation/deadline closes the
tracked HTTPS response even while waiting for headers or a body read.

## Local unit execution

Verbatim PowerShell invocation:

```powershell
. .data/android-tools/env.ps1; gradle -p android --no-daemon --console=plain :app:testDebugUnitTest --tests app.autopilot.android.UpdateHttpsTransportTest --tests app.autopilot.android.UpdateDownloadTest --tests app.autopilot.android.UpdateParallelDownloadTest --tests app.autopilot.android.UpdateManifestTest --tests app.autopilot.android.UpdateProgressTest
```

Verbatim successful runner tail (2026-10-22):

```text
> Task :app:testDebugUnitTest

[Incubating] Problems report is available at: file:///D:/Github/onevpn-autopilot/android/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.13/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 11s
21 actionable tasks: 5 executed, 16 up-to-date
```

Verbatim XML receipt invocation:

```powershell
Get-ChildItem android/app/build/test-results/testDebugUnitTest/TEST-*.xml | ForEach-Object { [xml]$report = Get-Content -LiteralPath $_.FullName -Raw; [pscustomobject]@{ name=$report.testsuite.name; tests=$report.testsuite.tests; failures=$report.testsuite.failures; errors=$report.testsuite.errors; skipped=$report.testsuite.skipped } } | ConvertTo-Json -Compress
```

Verbatim output:

```json
[{"name":"app.autopilot.android.UpdateDownloadTest","tests":"18","failures":"0","errors":"0","skipped":"0"},{"name":"app.autopilot.android.UpdateHttpsTransportTest","tests":"7","failures":"0","errors":"0","skipped":"0"},{"name":"app.autopilot.android.UpdateManifestTest","tests":"10","failures":"0","errors":"0","skipped":"0"},{"name":"app.autopilot.android.UpdateParallelDownloadTest","tests":"15","failures":"0","errors":"0","skipped":"0"},{"name":"app.autopilot.android.UpdateProgressTest","tests":"10","failures":"0","errors":"0","skipped":"0"}]
```

The new transport tests cover direct/proxy selection, loopback-only addressing,
unchanged TLS defaults, route changes at redirects, active-route failure with no
direct fallback, certificate rejection, the complete redirected manifest and
parallel APK range pipeline, cancellation while reading a manifest body, and a
manifest deadline while waiting for headers. Cancellation and deadline tests also
assert that the owned watcher terminates.

## Android runtime probe

Added opt-in test (not claimed executed by this local unit receipt):

```text
app.autopilot.android.UpdateHttpsTransportRuntimeTest#githubManifestUsesTrustedTlsThroughExplicitConnectProxy
```

It requires instrumentation argument `updateTransportLive=true`. It first uses
actual platform HTTPS connections against a loopback CONNECT endpoint that
withholds a response, proving cancellation/deadline close the real TCP request.
It then fetches the live public GitHub manifest through a loopback CONNECT relay.
The relay forwards encrypted TLS bytes unchanged and asserts both directions
carried bytes, the explicit route was used, and the real manifest parser accepts
the result. No test trust manager, hostname verifier, response payload injection,
provider request, APK installation, or existing VPN mutation is used.

Successful runtime output must contain `AUTOPILOT_UPDATE_HTTPS_ACCEPTED`; its
execution evidence belongs in the release/CI receipt after that probe actually
runs.

## Review follow-up awaiting central execution

The transport retains transient proxied I/O as a sanitized internal IOException
marker until the existing bounded download retry completes. Normalization then
returns `UPDATE_VPN_PROXY_ERROR`. This avoids inadvertently disabling the
existing retry policy by converting transient I/O into a terminal UpdateException
too early. Added regressions cover one proxy reset followed by successful range
retry, and persistent resets exhausting the existing bounded budget without
publishing an APK. These additions postdate the local invocation above; their
execution belongs in the final central build receipt.
