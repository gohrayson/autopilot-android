#requires -Version 5.1
# Repackage the verified 0.1.2 release; never rebuild or change application bytes.
$ErrorActionPreference='Stop'
$repoRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$source=Join-Path $repoRoot 'dist/android/Autopilot-Android-0.1.2.apk'
$target=Join-Path $repoRoot 'dist/android/Autopilot-Android-0.1.2-arm64-v8a.apk'
$expectedSource='036649a428973de2c0a99dc7acb0ec04b82ed7ec663df0ed887a9da8617a98a3'
$expectedCertificate='6c6831265f4d5593b499b2bfc1bbcaf93f3eb4d143f49fef192d811d4b6060ab'
$stagingRoot=[IO.Path]::GetFullPath((Join-Path $repoRoot '.data/android-download-size-analysis/package-arm64'))
function Assert-ChildPath([string]$Path,[string]$Root){
    $absolute=[IO.Path]::GetFullPath($Path)
    $prefix=[IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
    if(-not $absolute.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)){throw "Refusing write outside owned directory: $absolute"}
    return $absolute
}
$run=Assert-ChildPath (Join-Path $stagingRoot ([Guid]::NewGuid().ToString('N'))) $stagingRoot
$unsigned=Assert-ChildPath (Join-Path $run 'arm64-unsigned.apk') $stagingRoot
$aligned=Assert-ChildPath (Join-Path $run 'arm64-aligned.apk') $stagingRoot
$signed=Assert-ChildPath (Join-Path $run 'arm64-signed.apk') $stagingRoot
$verification=Assert-ChildPath (Join-Path $run 'verification.json') $stagingRoot
$null=Assert-ChildPath $target (Join-Path $repoRoot 'dist/android')
if([IO.Path]::GetFullPath($source).Equals([IO.Path]::GetFullPath($target),[StringComparison]::OrdinalIgnoreCase)){throw 'Source and target must differ'}
if(Test-Path -LiteralPath $target){throw 'ARM64 output already exists; inspect it before an explicit replacement'}
if((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedSource){throw 'Canonical APK does not match the pinned 0.1.2 release'}
. (Join-Path $repoRoot '.data/android-tools/env.ps1')
$buildTools=Join-Path $env:ANDROID_HOME 'build-tools/35.0.0'
$apksigner=Join-Path $buildTools 'apksigner.bat'
$zipalign=Join-Path $buildTools 'zipalign.exe'
$aapt=Join-Path $buildTools 'aapt2.exe'
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Entry-Hash($Entry){
    $stream=$Entry.Open();$sha=[Security.Cryptography.SHA256]::Create()
    try{return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}
    finally{$sha.Dispose();$stream.Dispose()}
}
function Assert-Signature([string]$Apk){
    $lines=@(& $apksigner verify --verbose --print-certs $Apk 2>&1)
    if($LASTEXITCODE -ne 0){throw 'APK signature verification failed'}
    $text=$lines -join "`n"
    if($text -notmatch ('(?m)^Signer #1 certificate SHA-256 digest: '+$expectedCertificate+'\r?$') -or $text -notmatch '(?m)^Number of signers: 1\r?$'){throw 'APK signer differs from pinned production certificate'}
    if($text -notmatch 'Verified using v2 scheme \(APK Signature Scheme v2\): true'){throw 'Expected APK v2 signature'}
    Write-Output $lines
}
Write-Output 'SOURCE_SIGNATURE'
Assert-Signature $source
$sourceZip=[IO.Compression.ZipFile]::OpenRead($source)
$removed=@();$retained=@{}
try{
    # Current canonical APK is v2-only. Reject unexpected JAR signature metadata
    # instead of silently dropping any additional retained payload.
    if(@($sourceZip.Entries|Where-Object {$_.FullName -match '^META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))$'}).Count){throw 'Unexpected v1 signing metadata; inspect source APK first'}
    New-Item -ItemType Directory -Path $run -Force|Out-Null
    $output=[IO.File]::Open($unsigned,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write)
    $destination=[IO.Compression.ZipArchive]::new($output,[IO.Compression.ZipArchiveMode]::Create,$false)
    try{
        foreach($entry in $sourceZip.Entries){
            if($entry.FullName -match '^lib/(armeabi-v7a|x86_64)/'){$removed+=$entry.FullName;continue}
            if($retained.ContainsKey($entry.FullName)){throw 'Duplicate source ZIP entry'}
            $retained[$entry.FullName]=Entry-Hash $entry
            # Android requires resources.arsc to remain stored and 4-byte aligned.
            $compression=if($entry.CompressedLength -eq $entry.Length -or $entry.FullName -eq 'resources.arsc'){[IO.Compression.CompressionLevel]::NoCompression}else{[IO.Compression.CompressionLevel]::Optimal}
            $copy=$destination.CreateEntry($entry.FullName,$compression)
            $copy.LastWriteTime=$entry.LastWriteTime
            $copy.ExternalAttributes=$entry.ExternalAttributes
            $input=$entry.Open();$out=$copy.Open()
            try{$input.CopyTo($out)}finally{$out.Dispose();$input.Dispose()}
        }
    }finally{$destination.Dispose();$output.Dispose()}
}finally{$sourceZip.Dispose()}
if($removed.Count -ne 4){throw 'Expected exactly four removed ARMv7/x86_64 libraries'}
& $zipalign -P 16 4 $unsigned $aligned
if($LASTEXITCODE -ne 0){throw 'zipalign failed'}
$key=Join-Path $repoRoot '.data/android-signing/autopilot-release.jks'
$passwordFile=Join-Path $repoRoot '.data/android-signing/password.dpapi'
if(-not(Test-Path -LiteralPath $key) -or -not(Test-Path -LiteralPath $passwordFile)){throw 'Existing production signing key/password required; no key will be created'}
$priorPassword=$env:AUTOPILOT_ARM64_SIGNING_PASSWORD
try{
    $secure=Get-Content -LiteralPath $passwordFile|ConvertTo-SecureString
    $credential=New-Object Management.Automation.PSCredential('local-signing',$secure)
    $env:AUTOPILOT_ARM64_SIGNING_PASSWORD=$credential.GetNetworkCredential().Password
    & $apksigner sign --ks $key --ks-key-alias autopilot --ks-pass env:AUTOPILOT_ARM64_SIGNING_PASSWORD --key-pass env:AUTOPILOT_ARM64_SIGNING_PASSWORD --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false --v4-signing-enabled false --out $signed $aligned
    if($LASTEXITCODE -ne 0){throw 'Production signing failed'}
}finally{$env:AUTOPILOT_ARM64_SIGNING_PASSWORD=$priorPassword;$credential=$null;$secure=$null}
Write-Output 'VARIANT_SIGNATURE'
Assert-Signature $signed
& $zipalign -c -P 16 4 $signed
if($LASTEXITCODE -ne 0){throw 'Signed APK ZIP alignment failed'}
$badging=@(& $aapt dump badging $signed 2>&1)
if($LASTEXITCODE -ne 0){throw 'APK manifest inspection failed'}
$badgingText=$badging -join "`n"
if($badgingText -notmatch "package: name='app\.autopilot\.android' versionCode='3' versionName='0\.1\.2'" -or $badgingText -notmatch "minSdkVersion:'26'" -or $badgingText -notmatch "targetSdkVersion:'35'" -or $badgingText -notmatch "(?m)^native-code: 'arm64-v8a'\s*$"){throw 'Variant package/version/SDK/ABI manifest mismatch'}
$badging|Where-Object {$_ -match '^(package:|minSdkVersion:|targetSdkVersion:|native-code:)'}|Write-Output
$variant=[IO.Compression.ZipFile]::OpenRead($signed)
$nativeProof=@()
try{
    if($variant.Entries.Count -ne $retained.Count){throw 'Variant retained entry count differs'}
    foreach($entry in $variant.Entries){
        if(-not $retained.ContainsKey($entry.FullName) -or (Entry-Hash $entry) -ne $retained[$entry.FullName]){throw "Retained ZIP payload changed: $($entry.FullName)"}
    }
    $native=@($variant.Entries|Where-Object {$_.FullName -match '^lib/[^/]+/[^/]+\.so$'})
    if($native.Count -ne 2){throw 'Expected exactly two ARM64 native libraries'}
    foreach($entry in $native){
        if($entry.FullName -notmatch '^lib/arm64-v8a/(libclash|libmihomo-jni)\.so$'){throw 'Unexpected native library'}
        $stream=$entry.Open();$bytes=New-Object byte[] 16384
        try{$read=0;while($read -lt $bytes.Length){$n=$stream.Read($bytes,$read,$bytes.Length-$read);if($n -eq 0){break};$read+=$n}}finally{$stream.Dispose()}
        if($bytes[0] -ne 0x7f -or $bytes[1] -ne 0x45 -or $bytes[2] -ne 0x4c -or $bytes[3] -ne 0x46 -or $bytes[4] -ne 2 -or $bytes[5] -ne 1){throw 'Unexpected ARM64 ELF header'}
        $offset=[BitConverter]::ToUInt64($bytes,32);$stride=[BitConverter]::ToUInt16($bytes,54);$count=[BitConverter]::ToUInt16($bytes,56);$loads=0
        for($i=0;$i -lt $count;$i++){
            $at=[int]($offset+$i*$stride);if($at+$stride -gt $read){throw 'ELF program headers exceed verified bounds'}
            if([BitConverter]::ToUInt32($bytes,$at) -ne 1){continue}
            $align=[BitConverter]::ToUInt64($bytes,$at+48);$fileOffset=[BitConverter]::ToUInt64($bytes,$at+8);$address=[BitConverter]::ToUInt64($bytes,$at+16)
            if($align -lt 16384 -or $fileOffset%16384 -ne $address%16384){throw 'ARM64 library lacks 16 KB ELF alignment'}
            $loads++
        }
        if($loads -eq 0){throw 'Native library has no loadable segments'}
        $nativeProof+=[PSCustomObject]@{Name=$entry.FullName;Sha256=$retained[$entry.FullName];LoadSegments=$loads;ElfAlignment=16384;IdenticalToUniversal=$true}
        Write-Output "NATIVE_BYTES_IDENTICAL ELF_16K_OK $($entry.FullName) load_segments=$loads"
    }
}finally{$variant.Dispose()}
$sourceAfter=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
if($sourceAfter -ne $expectedSource){throw 'Canonical APK changed during packaging'}
[IO.File]::Copy($signed,$target,$false)
$size=(Get-Item -LiteralPath $target).Length
$hash=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
[ordered]@{Source=$source;SourceSha256=$sourceAfter;Output=$target;OutputSha256=$hash;OutputBytes=$size;SourceBytes=(Get-Item -LiteralPath $source).Length;RemovedEntries=$removed;RetainedEntries=$retained.Count;RetainedPayloadHashes=$retained;CertificateSha256=$expectedCertificate;Package='app.autopilot.android';VersionCode=3;VersionName='0.1.2';MinSdk=26;TargetSdk=35;Native=$nativeProof}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath $verification -Encoding utf8
Write-Output "RETAINED_PAYLOADS_IDENTICAL entries=$($retained.Count) removed=$($removed.Count)"
Write-Output "CANONICAL_APK_UNCHANGED SHA256=$sourceAfter"
Write-Output "APK=$target"
Write-Output "BYTES=$size"
Write-Output "SHA256=$hash"
Write-Output "VERIFICATION=$verification"
