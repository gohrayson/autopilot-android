# Android update identity verification

Verified 2026-10-21 (Asia/Shanghai). These host JVM fixtures exercise the manifest parser plus the policy called by Android's PackageManager adapter: exact production app identity, increasing archive version matching release metadata, matching version name and minimum Android version, nonempty signing certificate sets, and exact equality of every current signer. They do not execute Android's PackageManager, PackageInstaller, permissions, notifications, JobScheduler, or a Xiaomi device.

Runnable PowerShell invocation:

```powershell
. 'D:\Github\onevpn-autopilot\.data\android-tools\env.ps1'
$updateIdentityRepo='D:\Github\onevpn-autopilot'
$updateIdentityOutput=Join-Path $updateIdentityRepo '.data\android-update-identity-test-classes'
New-Item -ItemType Directory -Force -Path $updateIdentityOutput | Out-Null
$updateIdentityJson=(Get-ChildItem -LiteralPath "$env:GRADLE_USER_HOME\caches\modules-2\files-2.1\org.json\json\20240303" -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$updateIdentityJunit=(Get-ChildItem -LiteralPath "$env:GRADLE_USER_HOME\caches\modules-2\files-2.1\junit\junit\4.13.2" -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$updateIdentityHamcrest=(Get-ChildItem -LiteralPath "$env:GRADLE_USER_HOME\caches\modules-2\files-2.1\org.hamcrest\hamcrest-core\1.3" -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$updateIdentityClasspath="$updateIdentityJson;$updateIdentityJunit;$updateIdentityHamcrest"
$updateIdentityMain=Join-Path $updateIdentityRepo 'android\app\src\main\java\app\autopilot\android'
$updateIdentityTests=Join-Path $updateIdentityRepo 'android\app\src\test\java\app\autopilot\android'
& javac --release 17 -encoding UTF-8 -cp $updateIdentityClasspath -d $updateIdentityOutput "$updateIdentityMain\UpdateManifest.java" "$updateIdentityMain\UpdateDownload.java" "$updateIdentityMain\UpdateIdentity.java" "$updateIdentityTests\UpdateIdentityTest.java"
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& java -cp "$updateIdentityOutput;$updateIdentityClasspath" org.junit.runner.JUnitCore app.autopilot.android.UpdateIdentityTest
```

Verbatim runner tail:

```text
JUnit version 4.13.2
......
Time: 0.022

OK (6 tests)
```

Exit code: 0. No Gradle process, provider API, installed application, Windows VPN process, proxy, or routing configuration was changed by this verification.

Android installation contract used: [SessionParams.setRequireUserAction](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)) permits supported self-updates to request no additional user action; [Session.commit](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session#commit(android.content.IntentSender)) requires a mutable callback PendingIntent for target API 35; [STATUS_PENDING_USER_ACTION](https://developer.android.com/reference/android/content/pm/PackageInstaller#STATUS_PENDING_USER_ACTION) requires preserving the confirmation path when Android requests interaction. HyperOS installation behavior remains unverified until a phone runs this build.
