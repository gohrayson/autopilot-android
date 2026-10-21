$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repoRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot '..'))
$versionMatch=[regex]::Match((Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'app/build.gradle')), "versionName\s+'([0-9]+\.[0-9]+\.[0-9]+)'")
if(-not $versionMatch.Success){throw 'Could not read Android version from app/build.gradle'}
$output=Join-Path $repoRoot "dist/android/Autopilot-Android-$($versionMatch.Groups[1].Value)-source.zip"
New-Item -ItemType Directory -Force (Split-Path $output) | Out-Null
Add-Type -AssemblyName System.IO.Compression
$stream=[IO.File]::Open($output,[IO.FileMode]::Create,[IO.FileAccess]::Write)
$zip=New-Object IO.Compression.ZipArchive($stream,[IO.Compression.ZipArchiveMode]::Create)
try{
    foreach($file in Get-ChildItem -LiteralPath $projectRoot -Recurse -Force -File){
        $relative=$file.FullName.Substring($projectRoot.Length+1).Replace('\','/')
        if($relative -match '(^|/)(build|\.gradle|\.data)/|(^|/)local\.properties$|\.(aar|jks|keystore|enc|dpapi|log)$'){continue}
        $entry=$zip.CreateEntry('android/'+$relative,[IO.Compression.CompressionLevel]::Optimal)
        $source=[IO.File]::OpenRead($file.FullName);$destination=$entry.Open()
        try{$source.CopyTo($destination)}finally{$source.Dispose();$destination.Dispose()}
    }
}finally{$zip.Dispose();$stream.Dispose()}
Get-FileHash -LiteralPath $output -Algorithm SHA256
