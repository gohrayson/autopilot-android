param([ValidateSet('Debug','Release')][string]$Configuration='Release',[switch]$SkipChecks)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repoRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot '..'))
$versionMatch=[regex]::Match((Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'app/build.gradle')), "versionName\s+'([0-9]+\.[0-9]+\.[0-9]+)'")
if(-not $versionMatch.Success){throw 'Could not read Android version from app/build.gradle'}
$versionName=$versionMatch.Groups[1].Value
$toolsRoot=Join-Path $repoRoot '.data\android-tools'
if(Test-Path -LiteralPath (Join-Path $toolsRoot 'env.ps1')){. (Join-Path $toolsRoot 'env.ps1')}
if(-not (Get-Command java -ErrorAction SilentlyContinue)){throw 'Install JDK 17 and set JAVA_HOME, or provision .data/android-tools first.'}
if(-not $env:ANDROID_HOME){throw 'Set ANDROID_HOME to an Android SDK with platform 35 and build-tools 35.'}
$gradle=(Get-Command gradle.bat -ErrorAction SilentlyContinue).Source
if(-not $gradle){throw 'Install Gradle 8.13 or provision .data/android-tools first.'}
$aar=Join-Path $projectRoot 'app\libs\libmihomo-android-v0.3.11.aar'
$expected='01bc41748840c8200bbaf08c044ba8704e35303a3179b75a3cce8517c8944251'
if(-not (Test-Path -LiteralPath $aar)){
    New-Item -ItemType Directory -Force (Split-Path $aar) | Out-Null
    Invoke-WebRequest -UseBasicParsing 'https://github.com/oviron/libmihomo-android/releases/download/v0.3.11/libmihomo-android-v0.3.11.aar' -OutFile $aar
}
if((Get-FileHash -LiteralPath $aar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected){throw 'Android native core checksum mismatch.'}
$priorKeyPath=$env:AUTOPILOT_KEYSTORE_PATH
$priorKeyPassword=$env:AUTOPILOT_KEYSTORE_PASSWORD
try{
    if($Configuration -eq 'Release'){
        $signingDir=Join-Path $repoRoot '.data\android-signing'
        New-Item -ItemType Directory -Force $signingDir | Out-Null
        $passwordPath=Join-Path $signingDir 'password.dpapi'
        $keyPath=Join-Path $signingDir 'autopilot-release.jks'
        if(-not (Test-Path -LiteralPath $passwordPath)){
            if(Test-Path -LiteralPath $keyPath){throw 'Existing signing key has no matching saved password. Restore its signing password.'}
            $random=New-Object byte[] 32
            $generator=[Security.Cryptography.RandomNumberGenerator]::Create()
            try{$generator.GetBytes($random)}finally{$generator.Dispose()}
            $secure=ConvertTo-SecureString ([Convert]::ToBase64String($random)) -AsPlainText -Force
            ConvertFrom-SecureString $secure | Set-Content -LiteralPath $passwordPath
        }
        $saved=Get-Content -LiteralPath $passwordPath | ConvertTo-SecureString
        $credential=New-Object Management.Automation.PSCredential('local-signing',$saved)
        $env:AUTOPILOT_KEYSTORE_PATH=$keyPath
        $env:AUTOPILOT_KEYSTORE_PASSWORD=$credential.GetNetworkCredential().Password
        if(-not (Test-Path -LiteralPath $keyPath)){
            & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -keystore $keyPath -storetype JKS -alias autopilot -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Autopilot Android' -storepass:env AUTOPILOT_KEYSTORE_PASSWORD -keypass:env AUTOPILOT_KEYSTORE_PASSWORD
            if($LASTEXITCODE -ne 0){throw 'Could not create local Android signing key.'}
        }
    }
    Push-Location $projectRoot
    try{
        $tasks=@(':app:assemble'+$Configuration)
        if(-not $SkipChecks){$tasks=@(':app:testDebugUnitTest',':app:lintDebug')+$tasks}
        & $gradle --no-daemon --console=plain @tasks
        if($LASTEXITCODE -ne 0){throw 'Android build/checks failed.'}
    }finally{Pop-Location}
    $kind=$Configuration.ToLowerInvariant()
    $source=Join-Path $projectRoot "app\build\outputs\apk\$kind\app-$kind.apk"
    $out=Join-Path $repoRoot 'dist\android'
    New-Item -ItemType Directory -Force $out | Out-Null
    $name=if($Configuration -eq 'Release'){"Autopilot-Android-$versionName.apk"}else{"Autopilot-Android-$versionName-preview.apk"}
    $target=Join-Path $out $name
    Copy-Item -LiteralPath $source -Destination $target -Force
    $digest=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
    "$digest  $name" | Set-Content -LiteralPath "$target.sha256" -Encoding ascii
    Write-Output "APK: $target"
    Write-Output "SHA256: $digest"
}finally{
    $env:AUTOPILOT_KEYSTORE_PATH=$priorKeyPath
    $env:AUTOPILOT_KEYSTORE_PASSWORD=$priorKeyPassword
}
