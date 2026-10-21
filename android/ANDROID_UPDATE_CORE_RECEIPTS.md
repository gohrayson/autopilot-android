# Android update core verification

Verified 2026-10-21, Asia/Shanghai. Scope: the pure Java manifest parser and streamed APK downloader. This run does not prove Android's installation UI, live hosting, background scheduling, package signing checks, or phone VPN behavior.

The fixtures run the real parser and downloader against injectable HTTP response streams. The complete pipeline fixture fetches manifest bytes, parses them, downloads separate APK fixture bytes, writes a private temporary file, verifies actual byte count and SHA-256, and atomically publishes the complete artifact. Other fixtures cover schema/type/package/version bounds, same-origin URL constraints, malformed UTF-8, truncated/oversized bytes, corrupted hashes, preserved existing files, interruption and temporary-file cleanup. Controlled redirect fixtures cover relative same-origin redirects, GitHub's exact release asset hosts, transient signed queries, rejected cleartext/credentials/other hosts/ports, and redirect loops.

Run from PowerShell; all dependencies are already in the repository's Android tool cache:

```powershell
. 'D:\Github\onevpn-autopilot\.data\android-tools\env.ps1'
$updateCoreRepo = 'D:\Github\onevpn-autopilot'
$updateCoreOutput = Join-Path $updateCoreRepo '.data\android-update-core-test-classes'
New-Item -ItemType Directory -Force -Path $updateCoreOutput | Out-Null
$updateCoreJson = (Get-ChildItem -LiteralPath "$env:GRADLE_USER_HOME\caches\modules-2\files-2.1\org.json\json\20240303" -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$updateCoreJunit = (Get-ChildItem -LiteralPath "$env:GRADLE_USER_HOME\caches\modules-2\files-2.1\junit\junit\4.13.2" -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$updateCoreHamcrest = (Get-ChildItem -LiteralPath "$env:GRADLE_USER_HOME\caches\modules-2\files-2.1\org.hamcrest\hamcrest-core\1.3" -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$updateCoreClasspath = "$updateCoreJson;$updateCoreJunit;$updateCoreHamcrest"
$updateCoreMain = Join-Path $updateCoreRepo 'android\app\src\main\java\app\autopilot\android'
$updateCoreTests = Join-Path $updateCoreRepo 'android\app\src\test\java\app\autopilot\android'
& javac --release 17 -encoding UTF-8 -cp $updateCoreClasspath -d $updateCoreOutput "$updateCoreMain\UpdateManifest.java" "$updateCoreMain\UpdateDownload.java" "$updateCoreTests\UpdateManifestTest.java" "$updateCoreTests\UpdateDownloadTest.java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp "$updateCoreOutput;$updateCoreClasspath" org.junit.runner.JUnitCore app.autopilot.android.UpdateManifestTest app.autopilot.android.UpdateDownloadTest
```

Verbatim runner tail:

```text
JUnit version 4.13.2
............................
Time: 0.159

OK (28 tests)
```

The command exited 0. No Gradle process, provider API, VPS write, installed application, VPN setting, proxy setting, routing setting, or Windows VPN process was touched by this verification.
