param(
    [string]$ClassesDirectory,
    [string]$Workspace,
    [string]$InputProfile
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$toolRoot = Join-Path $repo '.data\android-tools'
. (Join-Path $toolRoot 'env.ps1')
if (-not $ClassesDirectory) { $ClassesDirectory = Join-Path $repo 'android\app\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes' }
if (-not $Workspace) { $Workspace = Join-Path $repo ('.data\android-config-parser-probe\' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)) }
$Workspace = [IO.Path]::GetFullPath($Workspace)
$allowed = [IO.Path]::GetFullPath((Join-Path $repo '.data')) + '\'
if (-not $Workspace.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) { throw 'Probe workspace must be isolated under this repository .data directory' }
New-Item -ItemType Directory -Path $Workspace -Force | Out-Null
$fixtureClasses = Join-Path $Workspace 'fixture-classes'
New-Item -ItemType Directory -Path $fixtureClasses -Force | Out-Null
$moduleCache = Join-Path $env:GRADLE_USER_HOME 'caches\modules-2\files-2.1'
function Find-Jar([string]$path, [string]$filter) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $moduleCache $path) -Recurse -File -Filter $filter | Select-Object -First 1
    if (-not $jar) { throw "Missing dependency: $path / $filter" }
    return $jar.FullName
}
$json = Find-Jar 'org.json\json\20240303' '*.jar'
$yaml = Find-Jar 'org.yaml\snakeyaml\2.4' '*.jar'
$kotlin = Find-Jar 'org.jetbrains.kotlin\kotlin-stdlib\2.2.20' '*.jar'
$aarClasses = Get-ChildItem -LiteralPath (Join-Path $env:GRADLE_USER_HOME 'caches\8.13\transforms') -Recurse -File -Filter 'classes.jar' |
    Where-Object { $_.FullName -match 'libmihomo-android-v0\.3\.11' } | Select-Object -First 1 -ExpandProperty FullName
if (-not $aarClasses) { throw 'Missing compiled libmihomo AAR classes; compile the Android app first' }
$android = Join-Path $env:ANDROID_HOME 'platforms\android-35\android.jar'
$classpath = @($fixtureClasses, $ClassesDirectory, $json, $yaml, $kotlin, $aarClasses, $android) -join ';'
$engineClass = Join-Path $ClassesDirectory 'app\autopilot\android\VpnEngine.class'
$engineHash = (Get-FileHash -LiteralPath $engineClass -Algorithm SHA256).Hash
$sourceHash = (Get-FileHash -LiteralPath (Join-Path $repo 'android\app\src\main\java\app\autopilot\android\VpnEngine.java') -Algorithm SHA256).Hash
$native = Join-Path $repo 'vendor\mihomo\mihomo.exe'
$nativeHash = (Get-FileHash -LiteralPath $native -Algorithm SHA256).Hash
function Protected-Host {
    $proxy = Get-ItemProperty -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -Name ProxyEnable,ProxyServer -ErrorAction SilentlyContinue
    $pids = @(Get-CimInstance Win32_Process -Filter "Name='mihomo.exe'" | ForEach-Object { $_.ProcessId } | Sort-Object)
    $listener = @(& netstat.exe -ano -p TCP | Where-Object { $_ -match '^\s*TCP\s+127\.0\.0\.1:7890\s+.*\s+LISTENING\s+\d+\s*$' })
    return [ordered]@{ proxyEnabled=$proxy.ProxyEnable; proxyServer=$proxy.ProxyServer; mihomoPids=$pids; listener7890=$listener }
}
$before = Protected-Host
$before | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $Workspace 'host-before.json')
$productionPackage = Join-Path $repo 'android\app\src\main\java\app\autopilot\android'
$fixtureSources = @(
    (Join-Path $PSScriptRoot 'ConfigParserFixtures.java'),
    (Join-Path $PSScriptRoot 'ConfigParserValidationProbe.java'),
    (Join-Path $productionPackage 'Node.java'),
    (Join-Path $productionPackage 'CoreDiagnostics.java'),
    (Join-Path $productionPackage 'ProfileValidation.java')
)
& (Join-Path $env:JAVA_HOME 'bin\javac.exe') -encoding UTF-8 --release 17 -cp $classpath -d $fixtureClasses @fixtureSources
if ($LASTEXITCODE -ne 0) { throw 'Production configuration fixture compilation failed' }
$fixtureArguments = @('-cp', $classpath, 'app.autopilot.android.ConfigParserFixtures', $Workspace)
if ($InputProfile) { $fixtureArguments += [IO.Path]::GetFullPath($InputProfile) }
& (Join-Path $env:JAVA_HOME 'bin\java.exe') @fixtureArguments
if ($LASTEXITCODE -ne 0) { throw 'Production configuration fixture export failed' }
$version = (& $native -v 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch 'Mihomo Meta v1\.19\.32') { throw "Unexpected checker version: $version" }
$cases = Get-Content -LiteralPath (Join-Path $Workspace 'manifest.json') -Raw | ConvertFrom-Json
function Safe-Diagnostic([string]$raw, [string]$configuration) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = Join-Path $env:JAVA_HOME 'bin\java.exe'
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true; $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    @('-cp',$classpath,'app.autopilot.android.ConfigParserFixtures','--classify',$configuration) | ForEach-Object { [void]$info.ArgumentList.Add($_) }
    $classifier = New-Object System.Diagnostics.Process
    $classifier.StartInfo = $info; [void]$classifier.Start()
    $safeOutput = $classifier.StandardOutput.ReadToEndAsync(); $classifierErrors = $classifier.StandardError.ReadToEndAsync()
    $classifier.StandardInput.Write($raw); $classifier.StandardInput.Close()
    if (-not $classifier.WaitForExit(10000)) { $classifier.Kill(); throw 'Safe production diagnostic classifier timed out' }
    if ($classifier.ExitCode -ne 0) { throw 'Safe production diagnostic classifier failed' }
    return $safeOutput.GetAwaiter().GetResult() | ConvertFrom-Json
}
$results = @()
foreach ($case in $cases) {
    $caseHome = Join-Path $Workspace ($case.id + '-home')
    New-Item -ItemType Directory -Path $caseHome -Force | Out-Null
    $stdout = Join-Path $Workspace ($case.id + '.stdout.log')
    $stderr = Join-Path $Workspace ($case.id + '.stderr.log')
    $safeCategory = ''; $safeCode = ''; $proxyType = ''; $proxyIndex = $null
    if ($InputProfile) {
        # Private native output stays in memory: malformed fields may contain credentials.
        $info = New-Object System.Diagnostics.ProcessStartInfo
        $info.FileName = $native; $info.UseShellExecute = $false; $info.CreateNoWindow = $true
        $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
        @('-t','-d',$caseHome,'-f',$case.file) | ForEach-Object { [void]$info.ArgumentList.Add($_) }
        $process = New-Object System.Diagnostics.Process
        $process.StartInfo = $info; [void]$process.Start()
        $readOutput = $process.StandardOutput.ReadToEndAsync(); $readError = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(30000)) { $process.Kill(); throw 'Private core parser timeout' }
        $exitCode = $process.ExitCode
        $privateOutput = $readOutput.GetAwaiter().GetResult() + "`n" + $readError.GetAwaiter().GetResult()
        $safeCategory = if ($exitCode -eq 0) { 'ACCEPTED' } else { 'CORE_CONFIG_REJECTED' }
        if ($exitCode -ne 0) {
            $diagnostic = Safe-Diagnostic $privateOutput $case.file
            $safeCategory = $diagnostic.category; $safeCode = $diagnostic.code
            $proxyIndex = $diagnostic.proxyIndex; $proxyType = $diagnostic.proxyType
        }
        $privateOutput = $null
        Set-Content -LiteralPath $stdout -Value "CORE_PARSE_SANITIZED code=$safeCode category=$safeCategory proxyIndex=$proxyIndex proxyType=$proxyType exit=$exitCode"
        Set-Content -LiteralPath $stderr -Value ''
    } else {
        $process = Start-Process -FilePath $native -ArgumentList @('-t','-d',$caseHome,'-f',$case.file) -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
        $handle = $process.Handle
        if (-not $process.WaitForExit(30000)) { $process.Kill(); throw "Core parser timeout for $($case.id)" }
        $exitCode = $process.ExitCode
        if ($exitCode -ne 0) {
            $diagnostic = Safe-Diagnostic ((Get-Content -LiteralPath $stdout,$stderr -Raw) -join "`n") $case.file
            $safeCategory = $diagnostic.category; $safeCode = $diagnostic.code
            $proxyIndex = $diagnostic.proxyIndex; $proxyType = $diagnostic.proxyType
        }
    }
    $accepted = $exitCode -eq 0
    $matched = $accepted -eq $case.expectedAccepted
    $result = [pscustomobject]@{ id=$case.id; nodes=$case.nodes; expectedAccepted=$case.expectedAccepted; accepted=$accepted; exitCode=$exitCode; matched=$matched; code=$safeCode; category=$safeCategory; proxyIndex=$proxyIndex; proxyType=$proxyType; configuration=$case.file; stdout=$stdout; stderr=$stderr }
    $results += $result
    Write-Output "CORE_PARSE case=$($case.id) nodes=$($case.nodes) expected=$($case.expectedAccepted) actual=$accepted exit=$exitCode matched=$matched"
    Get-Content -LiteralPath $stdout,$stderr | ForEach-Object { Write-Output $_ }
    $results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $Workspace 'results.json')
}
& (Join-Path $env:JAVA_HOME 'bin\java.exe') -cp $classpath app.autopilot.android.ConfigParserValidationProbe $native $Workspace 2>&1 | Tee-Object -FilePath (Join-Path $Workspace 'validation.log')
if ($LASTEXITCODE -ne 0) { throw 'Production profile validation disagreed with the real native parser' }
$after = Protected-Host
$after | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $Workspace 'host-after.json')
if (($before | ConvertTo-Json -Depth 4 -Compress) -ne ($after | ConvertTo-Json -Depth 4 -Compress)) { throw 'Host VPN/proxy/listener state changed during parser probe' }
if ((Get-FileHash -LiteralPath $engineClass -Algorithm SHA256).Hash -ne $engineHash) { throw 'Compiled VpnEngine changed during probe; rerun after compilation finishes' }
$summary = [ordered]@{ workspace=$Workspace; generatedBy='actual compiled VpnEngine.configuration'; engineClass=$engineClass; engineClassSha256=$engineHash; engineSourceSha256=$sourceHash; nativeVersion=$version; nativeSha256=$nativeHash; cases=$results.Count; matched=@($results | Where-Object matched).Count; hostStateUnchanged=$true; results=$results }
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $Workspace 'summary.json')
Write-Output "CONFIG_PARSER_SUMMARY matched=$($summary.matched)/$($summary.cases) hostStateUnchanged=true workspace=$Workspace"
if ($summary.matched -ne $summary.cases) { throw 'At least one exact production configuration disagreed with the native parser expectation' }
$global:LASTEXITCODE = 0
