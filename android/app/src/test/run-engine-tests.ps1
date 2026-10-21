$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$tools = Join-Path $repo '.data/android-tools'
$jdk = Join-Path $tools 'jdk/jdk-17.0.20.1+1/bin'
$gradleLib = Join-Path $tools 'gradle/gradle-8.13/lib'
$junit = Join-Path $gradleLib 'junit-4.13.2.jar'
$hamcrest = Join-Path $gradleLib 'hamcrest-core-1.3.jar'
$out = Join-Path $repo '.data/android-engine-unit-classes'
$source = Join-Path $repo 'android/app/src/main/java/app/autopilot/android'
$tests = Join-Path $PSScriptRoot 'java/app/autopilot/android'
New-Item -ItemType Directory -Path $out -Force | Out-Null
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 -cp "$junit;$hamcrest" -d $out (Join-Path $source 'SelectionPolicy.java') (Join-Path $source 'ServiceChecks.java') (Join-Path $tests 'SelectionPolicyTest.java') (Join-Path $tests 'ServiceChecksTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Engine test compilation failed' }
& (Join-Path $jdk 'java.exe') -cp "$out;$junit;$hamcrest" org.junit.runner.JUnitCore app.autopilot.android.SelectionPolicyTest app.autopilot.android.ServiceChecksTest
if ($LASTEXITCODE -ne 0) { throw 'Engine tests failed' }
