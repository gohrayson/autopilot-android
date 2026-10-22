# Android / desktop DNS parity verification

Date: 2026-10-22. Working directory: `D:\Github\onevpn-autopilot`.

The Android production configuration now uses the desktop resolver policy:
literal IPv4 bootstrap servers, AliDNS/Tencent encrypted resolvers for both
ordinary queries and proxy-hostname resolution, the desktop fake-IP exclusions,
and IPv6 DNS/outbound-resolution disabled. Android TUN addresses and IPv6 routes
were not changed by this patch.

This proves configuration parity and builder behavior. It does **not** prove
resolver reachability from the user's phone or successful traffic through its
VPN interface. The separate hosted Android TUN runtime probe is required to
verify that integration with deterministic fixture endpoints; its outcome is
tracked in its own receipt.

## Actual production-factory comparison

The probe compiles the actual `CoreDnsConfig.java` with JDK 17, executes its JSON
factory, and compares it with the actual desktop `src/main/core.js` `buildConfig`
output. It permits only the Android-specific loopback `dns.listen` difference.
It does not compare separately copied lists or alter either running VPN.

This probe requires the combined source tree: the standalone Android source
checkout intentionally does not include the desktop product.

Verbatim invocation:

```powershell
. .\.data\android-tools\env.ps1
$dnsJsonJar = Get-ChildItem -LiteralPath (Join-Path $env:GRADLE_USER_HOME 'caches\modules-2\files-2.1\org.json\json\20240303') -Filter 'json-20240303.jar' -Recurse -File | Select-Object -First 1 -ExpandProperty FullName
node .\android\tools\verify-desktop-dns-parity.cjs $dnsJsonJar 2>&1 | Tee-Object -FilePath 'D:\Github\onevpn-autopilot\.data\android-dns-parity-probe.log'
if ($LASTEXITCODE -ne 0) { throw 'Cross-platform DNS parity probe failed' }
```

Verbatim output, exit 0:

```text
DESKTOP_ANDROID_DNS_PARITY_OK {"ipv6":false,"dns":{"fake-ip-range":"198.18.0.1/16","proxy-server-nameserver":["https://dns.alidns.com/dns-query","https://doh.pub/dns-query"],"enable":true,"ipv6":false,"default-nameserver":["223.5.5.5","119.29.29.29"],"nameserver":["https://dns.alidns.com/dns-query","https://doh.pub/dns-query"],"fake-ip-filter":["*.lan","*.local","+.msftconnecttest.com","+.msftncsi.com","localhost.ptlogin2.qq.com"],"enhanced-mode":"fake-ip"}}
```

## Scoped JVM builder checks

Coverage: hostname-proxy bootstrap has literal resolver addresses and no dependency
on a selected outbound; fixture resolver injection uses the production builder;
resolver inputs and returned JSON cannot mutate the default policy; empty/blank
resolver policies are rejected; existing loopback listener and closed selector
behavior remains covered.

Verbatim invocation:

```powershell
. .\.data\android-tools\env.ps1
gradle.bat -p android --no-daemon --console=plain :app:testDebugUnitTest --tests app.autopilot.android.CoreDnsConfigTest --tests app.autopilot.android.EngineConfigurationTest 2>&1 | Tee-Object -FilePath 'D:\Github\onevpn-autopilot\.data\android-dns-parity-unit.log'
if ($LASTEXITCODE -ne 0) { throw 'DNS policy unit tests failed' }
Get-ChildItem -LiteralPath 'android/app/build/test-results/testDebugUnitTest' -Filter 'TEST-*.xml' -File | Where-Object { $_.Name -in 'TEST-app.autopilot.android.CoreDnsConfigTest.xml','TEST-app.autopilot.android.EngineConfigurationTest.xml' } | ForEach-Object { [xml]$dnsSuiteXml = Get-Content -LiteralPath $_.FullName -Raw; 'DNS_UNIT_XML_RECEIPT '+$dnsSuiteXml.testsuite.name+' tests='+$dnsSuiteXml.testsuite.tests+' failures='+$dnsSuiteXml.testsuite.failures+' errors='+$dnsSuiteXml.testsuite.errors+' skipped='+$dnsSuiteXml.testsuite.skipped }
```

Verbatim runner tail and XML summary, exit 0:

```text
> Task :app:bundleDebugClassesToCompileJar
> Task :app:bundleDebugClassesToRuntimeJar
> Task :app:compileDebugUnitTestJavaWithJavac
> Task :app:testDebugUnitTest

[Incubating] Problems report is available at: file:///D:/Github/onevpn-autopilot/android/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.13/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 18s
21 actionable tasks: 5 executed, 16 up-to-date
DNS_UNIT_XML_RECEIPT app.autopilot.android.CoreDnsConfigTest tests=3 failures=0 errors=0 skipped=0
DNS_UNIT_XML_RECEIPT app.autopilot.android.EngineConfigurationTest tests=3 failures=0 errors=0 skipped=0
```

The first test draft used `JSONArray.toList()`, which Android's compile API does
not expose; it failed compilation and was replaced with explicit iteration. A
subsequent check encountered the concurrent diagnostic patch before its
`AutopilotApp.recordCoreAccepted` and `historicalCoreDiagnostic` methods were
written. The final invocation above ran after those methods were available and
passed. These intermediate failures are not counted as successful verification.
