param([string]$Apk)
$ErrorActionPreference='Stop'
$repoRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $repoRoot '.data/android-tools/env.ps1')
if(-not $Apk){
    $versionMatch=[regex]::Match((Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'android/app/build.gradle')), "versionName\s+'([0-9]+\.[0-9]+\.[0-9]+)'")
    if(-not $versionMatch.Success){throw 'Could not read Android version from app/build.gradle'}
    $Apk=Join-Path $repoRoot "dist/android/Autopilot-Android-$($versionMatch.Groups[1].Value).apk"
}
$Apk=(Resolve-Path -LiteralPath $Apk).Path
& (Join-Path $env:ANDROID_HOME 'build-tools/35.0.0/apksigner.bat') verify --verbose --print-certs $Apk
if($LASTEXITCODE -ne 0){throw 'APK signature verification failed'}
& (Join-Path $env:ANDROID_HOME 'build-tools/35.0.0/aapt2.exe') dump badging $Apk
if($LASTEXITCODE -ne 0){throw 'Could not inspect APK manifest'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead($Apk)
try{
    $native=@($archive.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/[^/]+\.so$' })
    if($native.Count -ne 6){throw 'Expected the pinned pair of native libraries for each of three ABIs'}
    foreach($entry in $native){
        $input=$entry.Open();$bytes=New-Object byte[] 16384
        try{$read=0;while($read -lt $bytes.Length){$n=$input.Read($bytes,$read,$bytes.Length-$read);if($n -eq 0){break};$read+=$n}}finally{$input.Dispose()}
        if($bytes[0] -ne 0x7f -or $bytes[1] -ne 0x45 -or $bytes[2] -ne 0x4c -or $bytes[3] -ne 0x46 -or $bytes[5] -ne 1){throw 'Unexpected ELF header'}
        $is64=$bytes[4] -eq 2
        # Android's 16 KB device requirement concerns ARM64/x86_64. The legacy
        # ARMv7 slice runs on 32-bit 4 KB devices (Android's official checker
        # specifically checks arm64-v8a and x86_64).
        $pageSize=if($is64){16384}else{4096}
        $offset=if($is64){[BitConverter]::ToUInt64($bytes,32)}else{[BitConverter]::ToUInt32($bytes,28)}
        $stride=[BitConverter]::ToUInt16($bytes,$(if($is64){54}else{42}))
        $count=[BitConverter]::ToUInt16($bytes,$(if($is64){56}else{44}))
        $loads=@();$relros=@()
        for($i=0;$i -lt $count;$i++){
            $at=[int]($offset+$i*$stride)
            if($at+$stride -gt $read){throw 'ELF program headers exceed verified read bounds'}
            $type=[BitConverter]::ToUInt32($bytes,$at)
            if($type -eq 0x6474e552 -and $is64){
                $relroAddress=[BitConverter]::ToUInt64($bytes,$at+16)
                $relroSize=[BitConverter]::ToUInt64($bytes,$at+40)
                $relros+=@{Start=$relroAddress;End=$relroAddress+$relroSize}
            }
            if($type -ne 1){continue}
            $align=if($is64){[BitConverter]::ToUInt64($bytes,$at+48)}else{[BitConverter]::ToUInt32($bytes,$at+28)}
            $fileOffset=if($is64){[BitConverter]::ToUInt64($bytes,$at+8)}else{[BitConverter]::ToUInt32($bytes,$at+4)}
            $address=if($is64){[BitConverter]::ToUInt64($bytes,$at+16)}else{[BitConverter]::ToUInt32($bytes,$at+8)}
            if($align -lt $pageSize -or $fileOffset%$pageSize -ne $address%$pageSize){throw "Native library lacks $pageSize ELF alignment: $($entry.FullName)"}
            $flags=if($is64){[BitConverter]::ToUInt32($bytes,$at+4)}else{[BitConverter]::ToUInt32($bytes,$at+24)}
            $memorySize=if($is64){[BitConverter]::ToUInt64($bytes,$at+40)}else{[BitConverter]::ToUInt32($bytes,$at+20)}
            $loads+=@{Start=$address;End=$address+$memorySize;Flags=$flags}
        }
        if($loads.Count -eq 0){throw 'Native library has no loadable segments'}
        # Bionic rounds RELRO outward, then mprotects those pages. The JNI bridge
        # pads/separates its next writable LOAD, so an unaligned nominal RELRO
        # end is safe here. Check the actual protected layout rather than treating
        # an end-modulo heuristic as a linker rejection rule:
        # https://android.googlesource.com/platform/bionic/+/refs/heads/android16-release/linker/linker_phdr.cpp
        foreach($relro in $relros){
            $protectStart=[uint64]([Math]::Floor($relro.Start/16384.0)*16384)
            $protectEnd=[uint64]([Math]::Ceiling($relro.End/16384.0)*16384)
            $mappedEnd=$protectStart
            foreach($load in ($loads | Sort-Object Start)){
                $mapStart=[uint64]([Math]::Floor($load.Start/16384.0)*16384)
                $mapEnd=[uint64]([Math]::Ceiling($load.End/16384.0)*16384)
                if($mapStart -le $mappedEnd -and $mapEnd -gt $mappedEnd){$mappedEnd=$mapEnd}
                $overlapStart=[Math]::Max($load.Start,$protectStart)
                $overlapEnd=[Math]::Min($load.End,$protectEnd)
                if($overlapStart -ge $overlapEnd){continue}
                if(($load.Flags -band 1) -ne 0){throw 'Rounded 16 KB RELRO would remove executable permissions'}
                if(($load.Flags -band 2) -ne 0 -and ($overlapStart -lt $relro.Start -or $overlapEnd -gt $relro.End)){
                    throw 'Rounded 16 KB RELRO would protect mutable data outside the intended RELRO interval'
                }
            }
            if($mappedEnd -lt $protectEnd){throw 'Rounded RELRO is not backed by mapped LOAD pages'}
        }
        Write-Output "Verified $pageSize byte ELF alignment and RELRO protection: $($entry.FullName), $($loads.Count) load segments"
    }
    $unexpected=@($archive.Entries | Where-Object { $_.FullName -match '(?i)(\.enc$|settings\.json$|probe-ready|\.jks$|\.keystore$|password\.dpapi|diagnostics\.log)' })
    if($unexpected.Count){throw 'Unexpected private/runtime material in APK'}
    Write-Output 'Verified APK contains no account vault, signing key, diagnostics log, or probe runtime fixture.'
}finally{$archive.Dispose()}
& (Join-Path $env:ANDROID_HOME 'build-tools/35.0.0/zipalign.exe') -c -P 16 4 $Apk
if($LASTEXITCODE -ne 0){throw 'APK ZIP alignment verification failed'}
Write-Output 'Verified APK ZIP alignment for 16 KB pages.'
Get-FileHash -LiteralPath $Apk -Algorithm SHA256
