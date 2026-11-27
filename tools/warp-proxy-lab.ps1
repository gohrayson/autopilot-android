# Disposable hosted-runner proof only. Never invoke this lab on a workstation.
[CmdletBinding()]
param([switch]$DisposableRunner)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if(-not $DisposableRunner -or $env:GITHUB_ACTIONS -cne 'true' -or $env:RUNNER_OS -cne 'Windows' -or
   $env:RUNNER_ENVIRONMENT -cne 'github-hosted' -or $env:GITHUB_EVENT_NAME -cne 'workflow_dispatch' -or
   $env:GITHUB_REPOSITORY -cne 'gohrayson/autopilot-android'){throw 'REFUSED: manual disposable public GitHub Windows runner required.'}
if(-not [Environment]::Is64BitProcess -or $PSVersionTable.PSVersion -lt [Version]'7.4'){throw '64-bit PowerShell7.4+ required.'}
$principal=[Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if(-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'Disposable runner must be administrator.'}
$pins=[ordered]@{version='2026.8.2115.0';url='https://downloads.cloudflareclient.com/v1/download/windows/version/2026.8.2115.0';size=60104704;
 sha256='ab3138dbebdf132af076b5ebcb25b42345d29258acb76267c3bd1f8cfea57a53';cliSha256='7aca7176ff1b56a937ba89792281a3e70077413e3d4f187d4cbb7c2867fd8011';
 productCode='{BC095815-030E-4AFD-8AA2-6B8A8FAE0B9A}';policySha256='be70277f0a43b9813d2e0a231e4851adb53c5e0e3c1e4fe50d244e86c924671f';
 cabinetSha256='7d2b66e6a2d67f0d1a54a6a91fa9e43b555fa740150c6264e2c6573ce4c625b3';cabinetBytes=59044734}
$policyBytes=[Convert]::FromBase64String('PGRpY3Q+CiAgPGtleT5zZXJ2aWNlX21vZGU8L2tleT4KICA8c3RyaW5nPnByb3h5PC9zdHJpbmc+CiAgPGtleT5wcm94eV9wb3J0PC9rZXk+CiAgPGludGVnZXI+NDAxMDA8L2ludGVnZXI+CiAgPGtleT53YXJwX3R1bm5lbF9wcm90b2NvbDwva2V5PgogIDxzdHJpbmc+bWFzcXVlPC9zdHJpbmc+CiAgPGtleT5hbGxvd191cGRhdGVzPC9rZXk+CiAgPGZhbHNlLz4KPC9kaWN0Pgo=')
function Sha-Bytes([byte[]]$Bytes){return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes)).ToLowerInvariant()}
if((Sha-Bytes $policyBytes) -cne $pins.policySha256){throw 'Embedded policy bytes differ from reviewed policy.'}
function No-Reparse([string]$Path){$p=[IO.DirectoryInfo]::new([IO.Path]::GetFullPath($Path));while($null -ne $p){if($p.Exists -and ($p.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Reparse directory refused.'};$p=$p.Parent}}
if([string]::IsNullOrWhiteSpace($env:RUNNER_TEMP)){throw 'RUNNER_TEMP missing.'}
$tempRoot=(Resolve-Path -LiteralPath $env:RUNNER_TEMP).ProviderPath.TrimEnd('\');No-Reparse $tempRoot
$lab=[IO.Path]::GetFullPath((Join-Path $tempRoot ('autopilot-warp-proxy-'+[Guid]::NewGuid().ToString('N'))))
if(-not $lab.StartsWith($tempRoot+'\',[StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $lab)){throw 'Unique lab path guard failed.'}
$null=New-Item -ItemType Directory -Path $lab
$results=Join-Path $lab 'results';$null=New-Item -ItemType Directory -Path $results
if($env:GITHUB_OUTPUT){Add-Content -LiteralPath $env:GITHUB_OUTPUT -Value "results_path=$results"}
function Lab-Path([string]$Path){$p=[IO.Path]::GetFullPath($Path);if(-not $p.StartsWith($lab+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Path escaped lab.'};No-Reparse ([IO.Path]::GetDirectoryName($p));if((Test-Path -LiteralPath $p)-and((Get-Item -LiteralPath $p).Attributes-band[IO.FileAttributes]::ReparsePoint)){throw 'Reparse file refused.'};return $p}
function Write-Result([string]$Name,$Value){[IO.File]::WriteAllText((Lab-Path (Join-Path $results $Name)),($Value|ConvertTo-Json -Depth 25),[Text.UTF8Encoding]::new($false))}
function Get-Optional($Object,[string]$Name){if($null -ne $Object -and $null -ne $Object.PSObject.Properties[$Name]){return $Object.PSObject.Properties[$Name].Value};return $null}
function Text-Sha([string]$Value){return Sha-Bytes ([Text.Encoding]::UTF8.GetBytes($Value))}
function Network-Invariant {
 $routes=@(Get-CimInstance -Namespace root/StandardCimv2 -ClassName MSFT_NetRoute | Where-Object {$_.DestinationPrefix -in @('0.0.0.0/0','::/0','0.0.0.0/1','128.0.0.0/1','::/1','8000::/1')} | Select-Object DestinationPrefix,NextHop,InterfaceIndex,RouteMetric,Protocol | Sort-Object DestinationPrefix,NextHop,InterfaceIndex)
 $dns=@(Get-CimInstance -Namespace root/StandardCimv2 -ClassName MSFT_DNSClientServerAddress | Where-Object {@($_.ServerAddresses).Count -gt 0} | Select-Object InterfaceIndex,AddressFamily,ServerAddresses | Sort-Object InterfaceIndex,AddressFamily)
 $p=Get-ItemProperty -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
 $proxy=[ordered]@{};foreach($name in @('ProxyEnable','ProxyServer','ProxyOverride','AutoConfigURL','AutoDetect')){$proxy[$name]=Get-Optional $p $name}
 $proxy.winhttp=((netsh.exe winhttp show proxy)-join "`n").Trim()
 return [ordered]@{routes=Text-Sha ($routes|ConvertTo-Json -Depth 8 -Compress);dns=Text-Sha ($dns|ConvertTo-Json -Depth 8 -Compress);proxy=Text-Sha ($proxy|ConvertTo-Json -Depth 8 -Compress)}
}
$state='C:\ProgramData\Cloudflare';$installRoot='C:\Program Files\Cloudflare';$policy=Join-Path $state 'mdm.xml'
$receipt=[ordered]@{schema=1;status='FAILED';scope='official WARP first-boot proxy mode before registration';runId=$env:GITHUB_RUN_ID;runAttempt=$env:GITHUB_RUN_ATTEMPT;commit=$env:GITHUB_SHA;startedAtUtc=[DateTime]::UtcNow.ToString('o');pins=$pins;
 registrationCommandsExecuted=0;connectCommandsExecuted=0;accountCredentialsUsed=$false;policyPreseededBeforeInstall=$false;policyRewrittenAfterInstall=$false;guiSuppressionOnly=$true;unknownCustomActionsDisabled=$false;serviceAutoStartExpectedInCloudOnly=$true;
 networkSamples=@();commandLedger=@();errors=@();cleanup=[ordered]@{} }
$baseline=$null;$installAttempted=$false;$stateOwned=$false;$failure=$null;$script:networkViolation=$false
function Network-Checkpoint([string]$Phase){$n=Network-Invariant;$same=$null -eq $baseline -or (($n|ConvertTo-Json -Compress)-ceq($baseline|ConvertTo-Json -Compress));$receipt.networkSamples += [ordered]@{atUtc=[DateTime]::UtcNow.ToString('o');phase=$Phase;unchanged=$same;hashes=$n};if(-not $same){$script:networkViolation=$true};return $same}
function Assert-Network([string]$Phase){if(-not(Network-Checkpoint $Phase)){throw 'GLOBAL_DEFAULT_ROUTE_NIC_DNS_OR_PROXY_CHANGED'}}
function Invoke-Msi([string[]]$Arguments,[int]$Seconds=180){
 $psi=[Diagnostics.ProcessStartInfo]::new('msiexec.exe');$psi.UseShellExecute=$false;$psi.CreateNoWindow=$true;foreach($a in $Arguments){$psi.ArgumentList.Add($a)}
 $p=[Diagnostics.Process]::Start($psi);$timer=[Diagnostics.Stopwatch]::StartNew();$lastSample=-5
 try{while(-not $p.WaitForExit(500)){if($timer.Elapsed.TotalSeconds -gt $Seconds){$p.Kill();throw 'OWNED_MSI_TIMEOUT'};if($timer.Elapsed.TotalSeconds-$lastSample -ge 2){$null=Network-Checkpoint 'msi-in-flight';$lastSample=$timer.Elapsed.TotalSeconds}};return $p.ExitCode}finally{$p.Dispose()}
}
function Stream-Digest($Record,[int]$Field){$size=[int64]$Record.DataSize($Field);$remaining=$size;$hash=[Security.Cryptography.IncrementalHash]::CreateHash([Security.Cryptography.HashAlgorithmName]::SHA256);try{while($remaining-gt0){$n=[int][Math]::Min(1048576,$remaining);$b=[Text.Encoding]::Latin1.GetBytes([string]$Record.ReadStream($Field,$n,1));if($b.Length-ne$n){throw 'MSI_STREAM_LENGTH'};$hash.AppendData($b);$remaining-=$b.Length};return [ordered]@{bytes=$size;sha256=[Convert]::ToHexString($hash.GetHashAndReset()).ToLowerInvariant()}}finally{$hash.Dispose()}}
function Msi-Matrix($Database){
 $tables=@();$v=$Database.OpenView('SELECT `Name` FROM `_Tables`');$v.Execute();try{while($r=$v.Fetch()){$tables += [string]$r.StringData(1)}}finally{$v.Close()}
 $m=[ordered]@{tables=[ordered]@{};cabinet=$null}
 foreach($table in @($tables|Sort-Object -Unique)){if($table-notmatch'^[_A-Za-z][_A-Za-z0-9]*$'){throw 'MSI_TABLE_IDENTIFIER'};$columns=@();$v=$Database.OpenView(('SELECT `Name`, `Type` FROM `_Columns` WHERE `Table` = ''{0}'' ORDER BY `Number`'-f$table));$v.Execute();try{while($c=$v.Fetch()){$columns += ,[ordered]@{name=[string]$c.StringData(1);type=[int]$c.IntegerData(2)}}}finally{$v.Close()}
  $rows=[ordered]@{};$v=$Database.OpenView(('SELECT * FROM `{0}`'-f$table));$v.Execute();try{while($r=$v.Fetch()){$row=[ordered]@{};$keys=@();for($i=1;$i-le$columns.Count;$i++){$c=$columns[$i-1];$value=$null;if(-not$r.IsNull($i)){if(($c.type-band0x0fff)-eq0x0900){$value=Stream-Digest $r $i}else{$value=[string]$r.StringData($i)}};$row[$c.name]=$value;if(($c.type-band0x2000)-ne0){$keys+=,$value}};if($keys.Count-eq0){throw 'MSI_TABLE_PRIMARY_KEY'};$key=ConvertTo-Json -InputObject @($keys) -Compress;if($rows.Contains($key)){throw 'MSI_DUPLICATE_KEY'};$rows[$key]=$row}}finally{$v.Close()};$m.tables[$table]=[ordered]@{columns=$columns;rows=$rows}
 }
 $v=$Database.OpenView('SELECT `Data` FROM `_Streams` WHERE `Name` = ''cab1.cab''');$v.Execute();try{$r=$v.Fetch();if(-not$r){throw 'MSI_CABINET_MISSING'};$m.cabinet=Stream-Digest $r 1}finally{$v.Close()};return $m
}
function Sql($Database,[string]$Text){$v=$Database.OpenView($Text);try{$v.Execute()}finally{$v.Close()}}
function Build-GuiOnlyTransform([string]$Msi,[string]$Mst){
 $installer=New-Object -ComObject WindowsInstaller.Installer;$reference=$null;$modified=$null;$applied=$null
 try{$reference=$installer.OpenDatabase($Msi,0);$before=Msi-Matrix $reference
  $copy=Lab-Path (Join-Path $lab 'gui-only-copy.msi');Copy-Item -LiteralPath $Msi -Destination $copy;$modified=$installer.OpenDatabase($copy,1)
  Sql $modified 'UPDATE `InstallExecuteSequence` SET `Condition` = ''0'' WHERE `Action` = ''LaunchGUICA'''
  Sql $modified 'DELETE FROM `Registry` WHERE `Registry` = ''regNKl9Anl9wEmQZvLkfAcad5hm5j8'' AND `Root` = 2 AND `Key` = ''SOFTWARE\Microsoft\Windows\CurrentVersion\Run'' AND `Name` = ''CloudflareWARP'' AND `Value` = ''[#Cloudflare_WARP.exe]'' AND `Component_` = ''Cloudflare_WARP.exe'''
  $modified.Commit();$after=Msi-Matrix $modified
  if(($before.tables.Keys-join'|')-cne($after.tables.Keys-join'|')){throw 'MSI_TABLE_SET_CHANGED'}
  $diff=@();foreach($table in $before.tables.Keys){if(($before.tables[$table].columns|ConvertTo-Json -Depth 8 -Compress)-cne($after.tables[$table].columns|ConvertTo-Json -Depth 8 -Compress)){throw 'MSI_SCHEMA_CHANGED'};$a=$before.tables[$table].rows;$b=$after.tables[$table].rows;foreach($key in @(@($a.Keys)+@($b.Keys)|Sort-Object -Unique)){$old=if($a.Contains($key)){$a[$key]}else{$null};$new=if($b.Contains($key)){$b[$key]}else{$null};if(($old|ConvertTo-Json -Depth 12 -Compress)-cne($new|ConvertTo-Json -Depth 12 -Compress)){$diff += ,[ordered]@{table=$table;key=$key;before=$old;after=$new}}}}
  if($diff.Count-ne2){throw 'GUI_TRANSFORM_UNEXPECTED_DELTA_COUNT'}
  $gui=@($diff|Where-Object {$_.table-ceq'InstallExecuteSequence'-and$_.key-ceq'["LaunchGUICA"]'});$run=@($diff|Where-Object {$_.table-ceq'Registry'-and$_.key-ceq'["regNKl9Anl9wEmQZvLkfAcad5hm5j8"]'})
  if($gui.Count-ne1-or$run.Count-ne1-or$null-ne$run[0].after-or$gui[0].after.Condition-cne'0'){throw 'GUI_TRANSFORM_UNEXPECTED_ROWS'}
  $expectedGui=[ordered]@{};foreach($k in $gui[0].before.Keys){$expectedGui[$k]=$gui[0].before[$k]};$expectedGui.Condition='0';if(($expectedGui|ConvertTo-Json -Compress)-cne($gui[0].after|ConvertTo-Json -Compress)){throw 'GUI_TRANSFORM_OTHER_FIELDS_CHANGED'}
  if($before.cabinet.sha256-cne$pins.cabinetSha256-or$before.cabinet.bytes-ne$pins.cabinetBytes-or($before.cabinet|ConvertTo-Json -Compress)-cne($after.cabinet|ConvertTo-Json -Compress)){throw 'MSI_EMBEDDED_PAYLOAD_CHANGED'}
  if(-not$modified.GenerateTransform($reference,$Mst)){throw 'GUI_TRANSFORM_NOT_GENERATED'};$modified.CreateTransformSummaryInfo($reference,$Mst,0,2339)
  [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($modified);$modified=$null
  $fresh=Lab-Path (Join-Path $lab 'fresh-transform-verification.msi');Copy-Item -LiteralPath $Msi -Destination $fresh;$applied=$installer.OpenDatabase($fresh,1);$applied.ApplyTransform($Mst,0);$applied.Commit();[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($applied);$applied=$installer.OpenDatabase($fresh,0);$roundtrip=Msi-Matrix $applied
  if(($roundtrip|ConvertTo-Json -Depth 16 -Compress)-cne($after|ConvertTo-Json -Depth 16 -Compress)){throw 'GUI_TRANSFORM_FRESH_BASE_ROUNDTRIP_FAILED'}
  return [ordered]@{deltaCount=2;deltas=$diff;allOtherTablesSchemasRowsBinaryCellsUnchanged=$true;customActionsUnchanged=$true;serviceInstallAndControlUnchanged=$true;cabinet=$before.cabinet;freshBaseRoundtrip=$true;validationFlags=2339;suppressedTransformErrors=0;transformSha256=(Get-FileHash -LiteralPath $Mst -Algorithm SHA256).Hash.ToLowerInvariant()}
 }finally{foreach($d in @($applied,$modified,$reference)){if($d){[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($d)}};[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer)}
}
function Redact-Value($Value){
 if($null-eq$Value){return $null};if($Value-is[string]){return $Value-replace'(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}','<uuid>'}
 if($Value-is[Collections.IDictionary]){$o=[ordered]@{};foreach($k in $Value.Keys){$o[$k]=if([string]$k-match'(?i)token|secret|private.?key|license|client.?id|device.?id|registration.?id'){'<redacted>'}else{Redact-Value $Value[$k]}};return $o}
 if($Value-is[Collections.IEnumerable]){return ,@($Value|ForEach-Object {Redact-Value $_})};return $Value
}
function Read-Cli([ValidateSet('settings','status')][string]$Kind,[string]$Cli){
 $args=@('--accept-tos','--json')+$(if($Kind-ceq'settings'){@('settings','list')}else{@('status')});$receipt.commandLedger += ,$args
 $psi=[Diagnostics.ProcessStartInfo]::new($Cli);$psi.UseShellExecute=$false;$psi.CreateNoWindow=$true;$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true;foreach($a in $args){$psi.ArgumentList.Add($a)};$p=[Diagnostics.Process]::Start($psi)
 try{$out=$p.StandardOutput.ReadToEndAsync();$err=$p.StandardError.ReadToEndAsync();if(-not$p.WaitForExit(15000)){$p.Kill();throw 'READ_ONLY_CLI_TIMEOUT'};$text=$out.GetAwaiter().GetResult();$errorText=$err.GetAwaiter().GetResult();if($text.Length-gt131072-or$errorText.Length-gt32768){throw 'CLI_OUTPUT_BOUND'};$parsed=$null;$validJson=$false;try{$parsed=ConvertFrom-Json -InputObject $text -AsHashtable -Depth 50;$validJson=$true}catch{};$result=[ordered]@{kind=$Kind;exitCode=$p.ExitCode;validJson=$validJson;data=if($validJson){Redact-Value $parsed}else{$text-replace'(?i)[0-9a-f]{8}-[0-9a-f-]{27}','<uuid>'};stderr=$errorText-replace'(?i)[0-9a-f]{8}-[0-9a-f-]{27}','<uuid>'};Write-Result ($Kind+'.json') $result;return [ordered]@{public=$result;parsed=$parsed;raw=$text}}
 finally{$p.Dispose()}
}
function Effective-Scalar($Value){if($Value-is[Collections.IDictionary]){foreach($k in @('value','current','effective','effective_value')){if($Value.Contains($k)){return Effective-Scalar $Value[$k]}}};return $Value}
function Setting-Entries($Value,[string]$Path='root'){
 $found=@();if($Value-is[Collections.IDictionary]){foreach($k in $Value.Keys){$n=([string]$k-replace'[_\-\s]','').ToLowerInvariant();$v=Effective-Scalar $Value[$k];if($n-in@('mode','servicemode','operationmode','servicemodev2','proxyport','warpproxyport','tunnelprotocol','warptunnelprotocol','preferredtunnelprotocol')){$found += ,[ordered]@{path=$Path+'.'+$k;key=$n;value=$v}};$found += @(Setting-Entries $Value[$k] ($Path+'.'+$k))}}
 elseif($Value-is[Collections.IEnumerable]-and$Value-isnot[string]){$i=0;foreach($v in $Value){$found += @(Setting-Entries $v ($Path+'['+$i+']'));$i++}};return $found
}
function Policy-Summary {
 if(-not(Test-Path -LiteralPath $policy -PathType Leaf)){return [ordered]@{exists=$false}}
 $bytes=[IO.File]::ReadAllBytes($policy);$same=(Sha-Bytes $bytes)-ceq$pins.policySha256;$doc=[xml]([Text.Encoding]::UTF8.GetString($bytes));$keys=@($doc.SelectNodes('/dict/key')|ForEach-Object {$_.InnerText});return [ordered]@{exists=$true;sha256=Sha-Bytes $bytes;equalsReviewedPolicy=$same;keys=$keys;noAutoConnectKey=(@($keys|Where-Object {$_-match'(?i)auto.?connect'}).Count-eq0)}
}
try {
 if((Test-Path -LiteralPath $state)-or(Test-Path -LiteralPath $installRoot)-or@(Get-Service|Where-Object {$_.Name-in@('CloudflareWARP','CloudflareWARPUpdater','WarpSvc')}).Count){throw 'PREEXISTING_CLOUDFLARE_STATE_REFUSED'}
 if(@(Get-NetTCPConnection -State Listen -LocalPort 40100 -ErrorAction SilentlyContinue).Count){throw 'PORT_40100_ALREADY_USED'}
 $baseline=Network-Invariant;$null=Network-Checkpoint 'baseline'
 $msi=Lab-Path (Join-Path $lab 'Cloudflare-WARP-2026.8.2115.0.msi');Invoke-WebRequest -Uri $pins.url -OutFile $msi -ConnectionTimeoutSeconds 20 -OperationTimeoutSeconds 180 -MaximumRetryCount 1
 if((Get-Item -LiteralPath $msi).Length-ne$pins.size-or(Get-FileHash -LiteralPath $msi -Algorithm SHA256).Hash.ToLowerInvariant()-cne$pins.sha256){throw 'OFFICIAL_MSI_PIN_MISMATCH'}
 $sig=Get-AuthenticodeSignature -LiteralPath $msi;if($sig.Status-ne'Valid'-or$null-eq$sig.SignerCertificate-or$sig.SignerCertificate.Subject-notmatch'Cloudflare,? Inc\.?'){throw 'OFFICIAL_MSI_SIGNATURE_FAILED'}
 $receipt.msi=[ordered]@{sha256=$pins.sha256;size=$pins.size;authenticode=[string]$sig.Status;signer=$sig.SignerCertificate.Subject}
 $mst=Lab-Path (Join-Path $lab 'gui-only.mst');$transform=Build-GuiOnlyTransform $msi $mst;Write-Result 'transform-review.json' $transform;$receipt.transformVerified=$true
 No-Reparse ([IO.Path]::GetDirectoryName($state));$null=New-Item -ItemType Directory -Path $state;$stateOwned=$true;[IO.File]::WriteAllBytes($policy,$policyBytes);$receipt.policyPreseededBeforeInstall=$true;$receipt.policyCreatedAtUtc=(Get-Item -LiteralPath $policy).CreationTimeUtc.ToString('o');Write-Result 'policy-before.json' (Policy-Summary);Assert-Network 'policy-preseeded'
 $installAttempted=$true;$receipt.installStartedAtUtc=[DateTime]::UtcNow.ToString('o');$receipt.installExitCode=Invoke-Msi @('/i',$msi,('TRANSFORMS='+$mst),'SERVICE_MODE=proxy','/qn','/norestart','/l*v',(Lab-Path (Join-Path $lab 'private-install.log')))
 Assert-Network 'after-installer';if($script:networkViolation){throw 'GLOBAL_NETWORK_CHANGE_OBSERVED_DURING_INSTALL'};if($receipt.installExitCode-ne0){throw 'INSTALL_FAILED_OR_REBOOT_REQUIRED'}
 $afterPolicy=Policy-Summary;Write-Result 'policy-after.json' $afterPolicy;if(-not$afterPolicy.exists){throw 'PRESEEDED_POLICY_MISSING_AFTER_INSTALL'};$receipt.policyRewrittenAfterInstall=-not$afterPolicy.equalsReviewedPolicy
 $service=Get-CimInstance Win32_Service -Filter "Name='CloudflareWARP'";if($null-eq$service-or$service.State-ne'Running'-or$service.ProcessId-le0){throw 'EXPECTED_CLOUD_SERVICE_AUTO_START_NOT_OBSERVED'}
 $daemon=Get-CimInstance Win32_Process -Filter "ProcessId=$($service.ProcessId)";$receipt.service=[ordered]@{name=$service.Name;state=$service.State;startMode=$service.StartMode;pid=$service.ProcessId;startedAtUtc=$daemon.CreationDate.ToUniversalTime().ToString('o')}
 if((Get-Item -LiteralPath $policy).CreationTimeUtc-gt$daemon.CreationDate.ToUniversalTime()){throw 'POLICY_NOT_PRESENT_BEFORE_FIRST_SERVICE_PROCESS'}
 $autorun=Get-ItemProperty -LiteralPath 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run';if($null-ne(Get-Optional $autorun 'CloudflareWARP')-or@(Get-Process -Name 'Cloudflare WARP','Cloudflare_WARP' -ErrorAction SilentlyContinue).Count){throw 'GUI_SUPPRESSION_FAILED'}
 $cli=Join-Path $installRoot 'Cloudflare WARP\warp-cli.exe';if(-not(Test-Path -LiteralPath $cli)){throw 'OFFICIAL_CLI_MISSING'};if((Get-FileHash -LiteralPath $cli -Algorithm SHA256).Hash.ToLowerInvariant()-cne$pins.cliSha256-or(Get-AuthenticodeSignature -LiteralPath $cli).Status-ne'Valid'){throw 'INSTALLED_CLI_IDENTITY_FAILED'}
 $settings=Read-Cli settings $cli;Assert-Network 'after-settings-read';$status=Read-Cli status $cli;Assert-Network 'after-status-read'
 $entries=@(Setting-Entries $settings.parsed);$modes=@($entries|Where-Object {$_.key-in@('mode','servicemode','operationmode','servicemodev2')-and$_.value-is[string]-and$_.value-in@('WarpProxy','proxy')});$ports=@($entries|Where-Object {$_.key-in@('proxyport','warpproxyport')-and[string]$_.value-ceq'40100'});$protocols=@($entries|Where-Object {$_.key-in@('tunnelprotocol','warptunnelprotocol','preferredtunnelprotocol')-and$_.value-is[string]-and$_.value-ieq'MASQUE'})
 $statusText=$status.raw;$missingRegistration=$statusText-match'(?i)(registration.?missing|missing.?registration|unregistered|not.?registered|registration.{0,30}(missing|not found|not completed))'
 $proof=[ordered]@{settingsExitCode=$settings.public.exitCode;settingsValidJson=$settings.public.validJson;modeWarpProxy=($modes.Count-gt0);proxyPort40100=($ports.Count-gt0);protocolMasque=($protocols.Count-gt0);unregisteredStatusObserved=$missingRegistration;matchingSettingEntries=Redact-Value $entries;commands=$receipt.commandLedger;registrationCommandsExecuted=0;connectCommandsExecuted=0;policyUnchangedAfterFirstBoot=$afterPolicy.equalsReviewedPolicy};Write-Result 'mode-proof.json' $proof;$receipt.modeProof=$proof
 if($settings.public.exitCode-ne0-or-not$settings.public.validJson-or-not$proof.modeWarpProxy-or-not$proof.proxyPort40100-or-not$proof.protocolMasque){throw 'EFFECTIVE_PROXY_40100_MASQUE_NOT_PROVEN_BEFORE_REGISTRATION'}
 if(-not$afterPolicy.equalsReviewedPolicy-or-not$afterPolicy.noAutoConnectKey){throw 'FIRST_BOOT_POLICY_REWRITTEN'}
 if(-not$missingRegistration){throw 'ZERO_ACCOUNT_STATUS_NOT_PROVEN'}
 $receipt.status='PASS'
}catch{$failure=$_;$receipt.errors += $_.Exception.Message;$receipt.failure=[ordered]@{message=$_.Exception.Message;line=$_.InvocationInfo.ScriptLineNumber;stack=$_.ScriptStackTrace}}finally{
 if($installAttempted){try{foreach($n in @('CloudflareWARP','CloudflareWARPUpdater')){if(Get-Service -Name $n -ErrorAction SilentlyContinue){Set-Service -Name $n -StartupType Disabled;Stop-Service -Name $n -Force -ErrorAction SilentlyContinue}};$receipt.cleanup.uninstallExitCode=Invoke-Msi @('/x',$pins.productCode,'/qn','/norestart','/l*v',(Lab-Path (Join-Path $lab 'private-uninstall.log')));if($receipt.cleanup.uninstallExitCode-ne0){throw 'UNINSTALL_FAILED_OR_REBOOT_REQUIRED'};$receipt.cleanup.servicesAbsent=(@(Get-Service|Where-Object {$_.Name-in@('CloudflareWARP','CloudflareWARPUpdater')}).Count-eq0);$receipt.cleanup.guiAbsent=(@(Get-Process -Name 'Cloudflare WARP','Cloudflare_WARP' -ErrorAction SilentlyContinue).Count-eq0);if(-not$receipt.cleanup.servicesAbsent-or-not$receipt.cleanup.guiAbsent){throw 'OWNED_CLOUDFLARE_CLEANUP_INCOMPLETE'}}catch{$receipt.errors += ('cleanup: '+$_.Exception.Message);$receipt.status='FAILED'}}
 if($stateOwned-and(Test-Path -LiteralPath $state)){try{$resolvedState=(Resolve-Path -LiteralPath $state).ProviderPath;if(-not[string]::Equals($resolvedState,'C:\ProgramData\Cloudflare',[StringComparison]::OrdinalIgnoreCase)){throw 'OWNED_STATE_DELETE_PATH_MISMATCH'};No-Reparse $resolvedState;if(@(Get-ChildItem -LiteralPath $resolvedState -Recurse -Force|Where-Object {$_.Attributes-band[IO.FileAttributes]::ReparsePoint}).Count){throw 'OWNED_STATE_CONTAINS_REPARSE'};Remove-Item -LiteralPath $resolvedState -Recurse -Force;$receipt.cleanup.ownedStateRemoved=$true}catch{$receipt.errors += ('state cleanup: '+$_.Exception.Message);$receipt.status='FAILED'}}
 if($null-ne$baseline){try{Assert-Network 'after-cleanup';$receipt.cleanup.globalNetworkUnchanged=$true}catch{$receipt.errors += $_.Exception.Message;$receipt.status='FAILED'}}
 if($script:networkViolation){$receipt.errors += 'A sampled default-route, NIC DNS, or proxy invariant changed.';$receipt.status='FAILED'}
 if($installAttempted){$receipt.cleanup.cliAbsent=-not(Test-Path -LiteralPath (Join-Path $installRoot 'Cloudflare WARP\warp-cli.exe'));$receipt.cleanup.proxyListenerAbsent=(@(Get-NetTCPConnection -State Listen -LocalPort 40100 -ErrorAction SilentlyContinue).Count-eq0);if(-not$receipt.cleanup.cliAbsent-or-not$receipt.cleanup.proxyListenerAbsent){$receipt.errors += 'Owned Cloudflare executable or proxy listener remains.';$receipt.status='FAILED'}}
 if($null-ne$failure){$receipt.status='FAILED'}
 $receipt.finishedAtUtc=[DateTime]::UtcNow.ToString('o');Write-Result 'receipt.json' $receipt
 Write-Output ('WARP_PROXY_BOOT_LAB_RESULT status='+$receipt.status+' registered=false connected=false receipt='+$results+'\receipt.json')
}
if($receipt.status-cne'PASS'){if($null-ne$failure){throw $failure};throw 'WARP proxy boot lab failed; inspect receipt.json.'}
