# Android common features verification

Date: 2026-10-23, local Windows development checkout. This receipt covers Android settings, routing, provider metadata, import removal, and the new UI probe. It does not claim a physical-device VPN or installer test.

## Changes covered

- `ProductSettings` validates a complete settings patch before committing; common types, bounds, services, and defaults come from `product-contract.json`. Android package names, countries, IDNA website normalization, and conflicting website rules are checked before persistence.
- `AutopilotApp` installs the contract before components, migrates existing country/bypass preferences, persists common settings, and tracks whether Android app routes need an explicit restart.
- `AndroidRouting` builds website/global/regional rules. Android app allow/disallow plans never route Autopilot's own UID into its tunnel. Include-only app lists cannot silently expand to all apps when configured packages disappear.
- `GeoRouting` verifies the packaged manifest and database SHA-256, reuses a verified cache, repairs corruption using a synced temporary file plus atomic replacement, and reports unavailable geodata without fetching from the network.
- Native UI exposes Pause/Resume Auto, required services and selection thresholds, global/rule routing, app and website splitting, provider subscription/refresh metadata, encrypted imported-source removal, Events, diagnostics copy/share, and sanitized portable preferences.
- Android's app exclusions are operating-system routes: website overrides apply only to traffic actually captured by the VPN. The UI states this limitation. Website/global changes apply live; app route changes show a pending Stop/Start notice.

## Runnable focused build, tests and lint

Run from `D:\Github\onevpn-autopilot`:

```powershell
. .\.data\android-tools\env.ps1
& .\.data\android-tools\gradle\gradle-8.13\bin\gradle.bat -p android :app:testDebugUnitTest --tests app.autopilot.android.ProductSettingsTest --tests app.autopilot.android.AndroidRoutingTest --tests app.autopilot.android.GeoRoutingTest --tests app.autopilot.android.ProviderRepositoryTest :app:compileDebugAndroidTestJavaWithJavac :app:lintDebug --console=plain 2>&1 | Tee-Object -FilePath D:\Github\onevpn-autopilot\.data\android-parity-feature-tests.log
exit $LASTEXITCODE
```

Verbatim runner tail:

```text
> Task :app:lintReportDebug
Wrote HTML report to file:///D:/Github/onevpn-autopilot/android/app/build/reports/lint-results-debug.html

> Task :app:lintDebug

[Incubating] Problems report is available at: file:///D:/Github/onevpn-autopilot/android/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.13/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 29s
41 actionable tasks: 12 executed, 29 up-to-date
```

The new instrumentation source compiled via `compileDebugAndroidTestJavaWithJavac`. Runtime acceptance is a separate check below, not implied by compilation.

## XML count receipt

Runnable command:

```powershell
$suiteFiles = @('ProductSettingsTest','AndroidRoutingTest','GeoRoutingTest','ProviderRepositoryTest') | ForEach-Object { Join-Path 'android/app/build/test-results/testDebugUnitTest' ('TEST-app.autopilot.android.' + $_ + '.xml') }
$totalTests = 0; $totalFailures = 0; $totalErrors = 0; $totalSkipped = 0
foreach ($suiteFile in $suiteFiles) { [xml]$suiteXml = Get-Content -Raw -LiteralPath $suiteFile; $suite = $suiteXml.testsuite; Write-Output ('{0} tests={1} failures={2} errors={3} skipped={4}' -f $suite.name,$suite.tests,$suite.failures,$suite.errors,$suite.skipped); $totalTests += [int]$suite.tests; $totalFailures += [int]$suite.failures; $totalErrors += [int]$suite.errors; $totalSkipped += [int]$suite.skipped }
Write-Output ('ANDROID_PARITY_FEATURE_JVM_RECEIPT suites=4 tests={0} failures={1} errors={2} skipped={3}' -f $totalTests,$totalFailures,$totalErrors,$totalSkipped)
if ($totalFailures -ne 0 -or $totalErrors -ne 0 -or $totalSkipped -ne 0) { throw 'Focused suites were not clean' }
[xml]$lint = Get-Content -Raw android/app/build/reports/lint-results-debug.xml
$severityCounts = $lint.issues.issue | Group-Object severity | ForEach-Object { '{0}={1}' -f $_.Name,$_.Count }
Write-Output ('ANDROID_PARITY_FEATURE_LINT_RECEIPT ' + ($severityCounts -join ' '))
```

Verbatim output:

```text
app.autopilot.android.ProductSettingsTest tests=6 failures=0 errors=0 skipped=0
app.autopilot.android.AndroidRoutingTest tests=4 failures=0 errors=0 skipped=0
app.autopilot.android.GeoRoutingTest tests=2 failures=0 errors=0 skipped=0
app.autopilot.android.ProviderRepositoryTest tests=25 failures=0 errors=0 skipped=0
ANDROID_PARITY_FEATURE_JVM_RECEIPT suites=4 tests=37 failures=0 errors=0 skipped=0
ANDROID_PARITY_FEATURE_LINT_RECEIPT Warning=5
```

Lint completed without errors. Remaining warnings are Android Gradle plugin/dependency upgrade notices and native UI localization notices; they are not a claim of a warning-free build.

## Actual UI and native routing runtime probe

Catalog entry: `app.autopilot.android.ProductParityUiTest#sharedSettingsRoutingAndDiagnosticsHaveWorkingControls`.

This probe operates on an account-free isolated emulator, verifies installed geodata, submits global and regional profiles to the actual bundled JNI core from an isolated private directory, opens the real settings editors, rejects an empty required-service list and conflicting website routes without partial saving, saves valid settings, toggles the global switch, checks the pause label and provider metadata, and restores previous preferences. It does not start a VPN or call a provider API. The orchestration receipt records the emulator run separately.

## Limits

No live desktop VPN, provider account, phone VPN, ADB installation, or release publication was changed by this worker. Android app-list route changes need an explicit Stop/Start by design; choosing an app in Android and a Windows executable are separate operating-system identities, so portable preferences exclude both app lists and their mode. Exported preferences contain no accounts, sessions, passwords, provider configuration keys, or updater authority changes.
