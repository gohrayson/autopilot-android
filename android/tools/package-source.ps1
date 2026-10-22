#requires -Version 5.1
param([string]$OutputPath)

$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repoRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot '..'))
$versionMatch=[regex]::Match((Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'app/build.gradle')), "versionName\s+'([0-9]+\.[0-9]+\.[0-9]+)'")
if(-not $versionMatch.Success){throw 'Could not read Android version from app/build.gradle'}
if([string]::IsNullOrWhiteSpace($OutputPath)){$OutputPath=Join-Path $repoRoot "dist/android/Autopilot-Android-$($versionMatch.Groups[1].Value)-source.zip"}
$output=[IO.Path]::GetFullPath($OutputPath)
if($output.StartsWith($projectRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Source ZIP output must be outside the Android source directory'}
$node=(Get-Command node -CommandType Application -ErrorAction Stop).Source
New-Item -ItemType Directory -Force (Split-Path $output) | Out-Null
Add-Type -AssemblyName System.IO.Compression
$stream=[IO.File]::Open($output,[IO.FileMode]::Create,[IO.FileAccess]::Write)
$zip=New-Object IO.Compression.ZipArchive($stream,[IO.Compression.ZipArchiveMode]::Create)
try{
    foreach($file in Get-ChildItem -LiteralPath $projectRoot -Recurse -Force -File){
        $relative=$file.FullName.Substring($projectRoot.Length+1).Replace('\','/')
        # Keep the same private/generated-file exclusions as publish-update.cjs.
        if($relative -match '(^|/)(build|\.gradle|\.data|\.git|node_modules)(/|$)|(^|/)local\.properties$|\.(aar|jks|keystore|enc|dpapi|log|apk|pem|p12|pfx)$|(^|/)(\.env(\..*)?|settings\.json|credentials[^/]*|diagnostics[^/]*|probe-ready)$'){continue}
        $entry=$zip.CreateEntry('android/'+$relative,[IO.Compression.CompressionLevel]::Optimal)
        $source=[IO.File]::OpenRead($file.FullName);$destination=$entry.Open()
        try{$source.CopyTo($destination)}finally{$source.Dispose();$destination.Dispose()}
    }
}finally{$zip.Dispose();$stream.Dispose()}
# ZipArchive on Windows writes a DOS creator OS, so Unix extractors ignore modes.
& $node (Join-Path $PSScriptRoot 'set-source-zip-modes.cjs') $output
if($LASTEXITCODE -ne 0){throw 'Could not set source ZIP Unix permissions'}
Get-FileHash -LiteralPath $output -Algorithm SHA256
