# Source-only lab. NEVER invoke on a workstation or a self-hosted runner.
# The canonical workflow is android/ci/windows-tun-lab.yml; see docs/WINDOWS_TUN_LAB_PLAN.md.
[CmdletBinding()]
param([switch]$DisposableRunner)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $DisposableRunner -or $env:GITHUB_ACTIONS -cne 'true' -or $env:RUNNER_OS -cne 'Windows' -or
    $env:RUNNER_ENVIRONMENT -cne 'github-hosted' -or $env:GITHUB_EVENT_NAME -cne 'workflow_dispatch' -or
    $env:GITHUB_REPOSITORY -cne 'gohrayson/autopilot-android') {
    throw 'REFUSED: explicit -DisposableRunner and a manual GitHub-hosted Windows run in gohrayson/autopilot-android are required.'
}
if (-not [Environment]::Is64BitProcess -or $PSVersionTable.PSVersion -lt [Version]'7.4') { throw '64-bit PowerShell 7.4+ is required.' }
$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Hosted runner is not administrator; driver gate failed.' }

# Pins come from SPEC.md F12 and src/main/mihomo-manifest.json. ZIP hash is the
# upstream v1.19.32 release asset digest, NOT the digest of the extracted EXE.
$pins = [ordered]@{
    version = 'v1.19.32'
    coreUrl = 'https://github.com/MetaCubeX/mihomo/releases/download/v1.19.32/mihomo-windows-amd64-v3-v1.19.32.zip'
    archiveSha256 = '657b43fcc8dd2bdb373e2e5d04f7047e65412f822090199e5de595a2f7f0e28b'
    exeSha256 = '38a73d4298b2fc44524c4c50a666339046fa919ee94d57269921de7f13ca1820'
    exeSize = 61219840
    wintunUrl = 'https://www.wintun.net/builds/wintun-0.14.1.zip'
    wintunArchiveSha256 = '07c256185d6ee3652e09fa55c0b673e2624b565e02c4b9091c79ca7d2f24ef51'
}
$prefixes = @('203.0.113.0/24', '2001:db8::/64')
$nonce = [Guid]::NewGuid().ToString('N')
function Assert-NoReparse([string]$Path) {
    $cursor = [IO.DirectoryInfo]::new([IO.Path]::GetFullPath($Path))
    while ($null -ne $cursor) {
        if ($cursor.Exists -and ($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Reparse path refused: $($cursor.FullName)" }
        $cursor = $cursor.Parent
    }
}
if ([string]::IsNullOrWhiteSpace($env:RUNNER_TEMP)) { throw 'RUNNER_TEMP missing.' }
$tempRoot = (Resolve-Path -LiteralPath $env:RUNNER_TEMP).ProviderPath.TrimEnd('\')
Assert-NoReparse $tempRoot
$lab = [IO.Path]::GetFullPath((Join-Path $tempRoot "autopilot-windows-tun-$nonce"))
if (-not $lab.StartsWith($tempRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $lab)) { throw 'Unique runner workspace guard failed.' }
$null = New-Item -ItemType Directory -Path $lab
$results = Join-Path $lab 'results'
$null = New-Item -ItemType Directory -Path $results
if ($env:GITHUB_OUTPUT) { Add-Content -LiteralPath $env:GITHUB_OUTPUT -Value "results_path=$results" }

function Assert-LabPath([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith($lab + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Path escaped owned lab directory.' }
    Assert-NoReparse ([IO.Path]::GetDirectoryName($full))
    if ((Test-Path -LiteralPath $full) -and ((Get-Item -LiteralPath $full).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Reparse file refused.' }
    return $full
}
function Write-Json([string]$Name, $Value) {
    $target = Assert-LabPath (Join-Path $results $Name)
    [IO.File]::WriteAllText($target, ($Value | ConvertTo-Json -Depth 30), [Text.UTF8Encoding]::new($false))
}
function Get-Sha([string]$Path) { return (Get-FileHash -LiteralPath (Assert-LabPath $Path) -Algorithm SHA256).Hash.ToLowerInvariant() }
function Get-TextSha([string]$Value) { return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Value))).ToLowerInvariant() }
function Receive-PinnedArchive([string]$Url, [string]$Target, [string]$Sha) {
    $targetPath = Assert-LabPath $Target
    # HTTPS/certificate validation stays enabled. No token or account is sent.
    Invoke-WebRequest -Uri $Url -OutFile $targetPath -ConnectionTimeoutSeconds 20 -OperationTimeoutSeconds 120 -MaximumRetryCount 2
    if ((Get-Sha $targetPath) -cne $Sha) { throw "Downloaded archive hash mismatch: $([IO.Path]::GetFileName($Target))" }
}
function Expand-OnlyEntry([string]$Archive, [string]$EntryName, [string]$Target) {
    $zip = [IO.Compression.ZipFile]::OpenRead((Assert-LabPath $Archive))
    try {
        $entries = @($zip.Entries | Where-Object { $_.FullName -ceq $EntryName })
        if ($entries.Count -ne 1) { throw "Expected one pinned archive entry: $EntryName" }
        $targetPath = Assert-LabPath $Target
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entries[0], $targetPath, $false)
    } finally { $zip.Dispose() }
}
function Wait-Gate([string]$Label, [scriptblock]$Check, [int]$Seconds = 20) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        if (& $Check) { return }
        Start-Sleep -Milliseconds 200
    } while ($timer.Elapsed.TotalSeconds -lt $Seconds)
    throw "Timed out: $Label"
}

$receipt = [ordered]@{
    schema = 1; status = 'FAILED'; scope = 'reserved-prefix Windows TUN fixture only'; pins = $pins
    runId = $env:GITHUB_RUN_ID; runAttempt = $env:GITHUB_RUN_ATTEMPT; commit = $env:GITHUB_SHA
    runnerImage = $env:ImageOS; runnerImageVersion = $env:ImageVersion; startedAt = [DateTime]::UtcNow.ToString('o')
    defaultRouteCoverage = $false; hostDnsConfigurationCoverage = $false; productionBrokerCoverage = $false
    stages = @(); cleanup = @(); errors = @()
}
$ownedChildren = [Collections.Generic.List[object]]::new()
$ownedRoutes = [Collections.Generic.List[object]]::new()
$ownedAdapters = [Collections.Generic.List[object]]::new()
$fixture = $null
$api = $null
$baseline = $null
$failure = $null

function Get-HostInvariant {
    # Compare in memory. Never upload global routes, NIC names, IPs or DNS servers.
    $defaults = @(Get-NetRoute -PolicyStore ActiveStore | Where-Object { $_.DestinationPrefix -in @('0.0.0.0/0', '::/0') } |
        Select-Object InterfaceIndex, DestinationPrefix, NextHop, RouteMetric | Sort-Object InterfaceIndex, DestinationPrefix, NextHop)
    $dns = @(Get-DnsClientServerAddress | Where-Object { $_.InterfaceIndex -in $script:baselineInterfaceIndexes } |
        Select-Object InterfaceIndex, AddressFamily, ServerAddresses | Sort-Object InterfaceIndex, AddressFamily)
    return [ordered]@{ defaults = Get-TextSha ($defaults | ConvertTo-Json -Depth 8 -Compress); dns = Get-TextSha ($dns | ConvertTo-Json -Depth 8 -Compress) }
}
function Assert-HostInvariant {
    $now = Get-HostInvariant
    if ($now.defaults -cne $baseline.defaults -or $now.dns -cne $baseline.dns) { throw 'Default route or pre-existing NIC DNS changed; gate failed.' }
}
function Start-OwnedCore([string]$Phase, [string]$ConfigPath) {
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $script:coreExe
    $psi.WorkingDirectory = $lab
    $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
    foreach ($arg in @('-d', $lab, '-f', (Assert-LabPath $ConfigPath))) { $null = $psi.ArgumentList.Add($arg) }
    # Child-only switch exercises IPv6 inside the fixture even if the hosted NIC has no global IPv6.
    $psi.Environment['SKIP_SYSTEM_IPV6_CHECK'] = '1'
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $psi
    if (-not $process.Start()) { throw 'Native process did not start.' }
    $child = [pscustomobject]@{ Process = $process; Phase = $Phase; Started = $process.StartTime.ToUniversalTime();
        Stdout = $process.StandardOutput.ReadToEndAsync(); Stderr = $process.StandardError.ReadToEndAsync(); Logged = $false }
    $ownedChildren.Add($child)
    return $child
}
function Stop-OwnedChild($Child) {
    $p = $Child.Process
    if (-not $p.HasExited) {
        $identity = Get-CimInstance Win32_Process -Filter "ProcessId=$($p.Id)"
        if ($null -eq $identity -or $identity.ParentProcessId -ne $PID -or
            -not [string]::Equals($identity.ExecutablePath, $script:coreExe, [StringComparison]::OrdinalIgnoreCase) -or
            [Math]::Abs(($identity.CreationDate.ToUniversalTime() - $Child.Started).TotalSeconds) -gt 2) { throw 'Owned-child identity mismatch; refusing termination.' }
        $p.Kill($false)
        if (-not $p.WaitForExit(10000)) { throw 'Owned native child failed to terminate.' }
    }
    if (-not $Child.Logged) {
        if (-not $Child.Stdout.Wait(5000) -or -not $Child.Stderr.Wait(5000)) { throw 'Native log capture timeout.' }
        [IO.File]::WriteAllText((Assert-LabPath (Join-Path $results "$($Child.Phase)-core.log")), $Child.Stdout.Result + "`n" + $Child.Stderr.Result)
        $Child.Logged = $true
    }
}
function Invoke-CoreApi([string]$Method, [string]$Resource, [string]$Body = '') {
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), "http://127.0.0.1:$script:controllerPort$Resource")
    try {
        $request.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $script:controllerSecret)
        if ($Body) { $request.Content = [Net.Http.StringContent]::new($Body, [Text.Encoding]::UTF8, 'application/json') }
        $response = $api.SendAsync($request).GetAwaiter().GetResult()
        try { $null = $response.EnsureSuccessStatusCode(); return $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() }
        finally { $response.Dispose() }
    } finally { $request.Dispose() }
}
function Get-OwnedAdapter([string]$Name) {
    return ,@(Get-NetAdapter -IncludeHidden | Where-Object { $_.Name -ceq $Name })
}
function Remove-OwnedRoutes {
    foreach ($route in $ownedRoutes) {
        if ($route.Cleaned) { continue }
        if ($route.Prefix -notin $prefixes) { throw 'Route cleanup prefix guard failed.' }
        $remaining = @(Get-NetRoute -PolicyStore ActiveStore |
            Where-Object { $_.InterfaceIndex -eq $route.Index -and $_.DestinationPrefix -ceq $route.Prefix -and $_.NextHop -ceq $route.NextHop })
        if ($remaining.Count) {
            $adapter = @(Get-NetAdapter -IncludeHidden | Where-Object { [string]$_.InterfaceGuid -eq $route.Guid })
            if ($adapter.Count -ne 1 -or $adapter[0].ifIndex -ne $route.Index -or $adapter[0].Name -cne $route.Name) { throw 'Owned-route interface identity mismatch; refusing removal.' }
            foreach ($item in $remaining) { $item | Remove-NetRoute -Confirm:$false }
        }
        $route.Cleaned = $true
    }
}
function Assert-CleanStage($Adapter, [string]$Phase) {
    Wait-Gate "$Phase owned interface removal" { @(Get-NetAdapter -IncludeHidden | Where-Object { [string]$_.InterfaceGuid -eq $Adapter.Guid }).Count -eq 0 }
    foreach ($prefix in $prefixes) {
        if (@(Get-NetRoute -PolicyStore ActiveStore -ErrorAction Stop | Where-Object { $_.InterfaceIndex -eq $Adapter.Index -and $_.DestinationPrefix -ceq $prefix }).Count) {
            throw "$Phase left owned route $prefix"
        }
    }
    Assert-HostInvariant
    $receipt.cleanup += [ordered]@{ phase = $Phase; adapterAbsent = $true; ownedRoutesAbsent = $true; defaultRouteUnchanged = $true; hostDnsUnchanged = $true }
}

try {
    $script:baselineInterfaceIndexes = @(Get-NetAdapter -IncludeHidden | ForEach-Object { $_.ifIndex })
    $baseline = Get-HostInvariant
    foreach ($prefix in $prefixes) {
        if (@(Get-NetRoute -PolicyStore ActiveStore | Where-Object { $_.DestinationPrefix -ceq $prefix }).Count) { throw "Fixture prefix already routed: $prefix" }
    }
    $coreZip = Join-Path $lab 'mihomo.zip'
    $wintunZip = Join-Path $lab 'wintun.zip'
    $script:coreExe = Join-Path $lab 'mihomo.exe'
    $referenceDll = Join-Path $lab 'wintun-reference.dll'
    Receive-PinnedArchive $pins.coreUrl $coreZip $pins.archiveSha256
    Receive-PinnedArchive $pins.wintunUrl $wintunZip $pins.wintunArchiveSha256
    Expand-OnlyEntry $coreZip 'mihomo-windows-amd64-v3.exe' $script:coreExe
    Expand-OnlyEntry $wintunZip 'wintun/bin/amd64/wintun.dll' $referenceDll
    if ((Get-Sha $script:coreExe) -cne $pins.exeSha256 -or (Get-Item -LiteralPath $script:coreExe).Length -ne $pins.exeSize) { throw 'Extracted core does not match the production binary manifest.' }
    $signature = Get-AuthenticodeSignature -LiteralPath $referenceDll
    if ($signature.Status -ne 'Valid' -or $null -eq $signature.SignerCertificate -or $signature.SignerCertificate.Subject -notmatch 'WireGuard') {
        throw 'Official Wintun reference DLL Authenticode validation failed; nothing will be loaded.'
    }

    # All fixture code is compiled ONLY after the disposable-runner guards and byte checks.
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Collections.Concurrent;

public sealed class TunLabFixture : IDisposable {
    public readonly string Marker;
    public readonly ConcurrentQueue<string> TcpHits = new ConcurrentQueue<string>();
    public readonly ConcurrentQueue<string> UdpHits = new ConcurrentQueue<string>();
    public readonly ConcurrentQueue<string> DnsHits = new ConcurrentQueue<string>();
    public readonly ConcurrentQueue<string> Unexpected = new ConcurrentQueue<string>();
    readonly CancellationTokenSource cancel = new CancellationTokenSource(TimeSpan.FromMinutes(8));
    readonly ConcurrentBag<IDisposable> sockets = new ConcurrentBag<IDisposable>();
    readonly ConcurrentBag<Task> tasks = new ConcurrentBag<Task>();
    readonly TcpListener echoTcp, socksTcp;
    readonly UdpClient echoUdp, socksUdp, dnsUdp;
    public int EchoPort { get { return ((IPEndPoint)echoTcp.LocalEndpoint).Port; } }
    public int SocksPort { get { return ((IPEndPoint)socksTcp.LocalEndpoint).Port; } }
    public int DnsPort { get { return ((IPEndPoint)dnsUdp.Client.LocalEndPoint).Port; } }
    public TunLabFixture(string marker) {
        Marker = marker;
        echoTcp = new TcpListener(IPAddress.Loopback, 0); echoTcp.Start();
        socksTcp = new TcpListener(IPAddress.Loopback, 0); socksTcp.Start();
        echoUdp = new UdpClient(new IPEndPoint(IPAddress.Loopback, EchoPort));
        socksUdp = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
        dnsUdp = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
        sockets.Add(echoUdp); sockets.Add(socksUdp); sockets.Add(dnsUdp);
        tasks.Add(Accept(echoTcp, Echo)); tasks.Add(Accept(socksTcp, Socks));
        tasks.Add(EchoDatagrams()); tasks.Add(SocksDatagrams()); tasks.Add(DnsDatagrams());
    }
    static byte[] Read(Stream s, int count) {
        var b = new byte[count]; int n = 0;
        while(n < count) { int got = s.Read(b,n,count-n); if(got == 0) throw new EndOfStreamException(); n += got; }
        return b;
    }
    static byte[] Line(Stream s) {
        using(var b = new MemoryStream()) {
            for(int i=0;i<1024;i++) { int v=s.ReadByte(); if(v<0) throw new EndOfStreamException(); b.WriteByte((byte)v); if(v==10) return b.ToArray(); }
            throw new IOException("Fixture line too long");
        }
    }
    async Task Accept(TcpListener listener, Action<TcpClient> handle) {
        try { while(!cancel.IsCancellationRequested) {
            var c = await listener.AcceptTcpClientAsync(cancel.Token); sockets.Add(c);
            tasks.Add(Task.Run(() => { using(c) { try { c.ReceiveTimeout=5000; c.SendTimeout=5000; handle(c); }
                catch(Exception e) { if(!cancel.IsCancellationRequested && !(e is EndOfStreamException)) Unexpected.Enqueue("tcp:"+e.GetType().Name); } } }));
        } } catch(OperationCanceledException) {} catch(ObjectDisposedException) {}
    }
    void Echo(TcpClient c) {
        if(!IPAddress.IsLoopback(((IPEndPoint)c.Client.RemoteEndPoint).Address)) throw new IOException("Nonloopback echo");
        var payload=Line(c.GetStream()); var text=Encoding.ASCII.GetString(payload);
        TcpHits.Enqueue(text.TrimEnd('\n')); var response=Encoding.ASCII.GetBytes(Marker+"|"+text);
        c.GetStream().Write(response,0,response.Length);
    }
    static byte[] Reply(int port) { return new byte[]{5,0,0,1,127,0,0,1,(byte)(port>>8),(byte)port}; }
    bool Allowed(IPAddress ip, int port) { return port==EchoPort && (ip.Equals(IPAddress.Parse("203.0.113.7")) || ip.Equals(IPAddress.Parse("2001:db8::7"))); }
    void Socks(TcpClient c) {
        var s=c.GetStream(); var hello=Read(s,2); if(hello[0]!=5 || hello[1]==0) throw new IOException("SOCKS greeting");
        if(!Read(s,hello[1]).Contains((byte)0)) throw new IOException("SOCKS auth"); s.Write(new byte[]{5,0},0,2);
        var h=Read(s,4); if(h[0]!=5 || h[2]!=0 || (h[3]!=1 && h[3]!=4)) throw new IOException("SOCKS header");
        var address=Read(s,h[3]==1?4:16); var p=Read(s,2); int port=(p[0]<<8)|p[1];
        if(h[1]==3) {
            var r=Reply(((IPEndPoint)socksUdp.Client.LocalEndPoint).Port); s.Write(r,0,r.Length);
            // Keep the UDP-associate control connection alive; shutdown closes this owned socket.
            c.ReceiveTimeout=0;
            try { while(!cancel.IsCancellationRequested && s.ReadByte()!=-1) {} }
            catch(IOException) { /* A core Stop/crash can reset its association; no destination was forwarded. */ }
        } else if(h[1]==1 && Allowed(new IPAddress(address),port)) {
            using(var target=new TcpClient(AddressFamily.InterNetwork)) {
                target.ReceiveTimeout=5000; target.SendTimeout=5000;
                if(!target.ConnectAsync(IPAddress.Loopback,EchoPort).Wait(5000)) throw new TimeoutException("Loopback echo connect");
                var r=Reply(EchoPort); s.Write(r,0,r.Length); var payload=Line(s);
                target.GetStream().Write(payload,0,payload.Length); var answer=Line(target.GetStream()); s.Write(answer,0,answer.Length);
            }
        } else { Unexpected.Enqueue("SOCKS denied owned-fixture mismatch"); s.Write(new byte[]{5,2,0,1,0,0,0,0,0,0},0,10); }
    }
    async Task EchoDatagrams() {
        try { while(!cancel.IsCancellationRequested) {
            var d=await echoUdp.ReceiveAsync(cancel.Token); if(!IPAddress.IsLoopback(d.RemoteEndPoint.Address)) { Unexpected.Enqueue("nonloopback UDP"); continue; }
            var text=Encoding.ASCII.GetString(d.Buffer); UdpHits.Enqueue(text);
            var reply=Encoding.ASCII.GetBytes(Marker+"|"+text); await echoUdp.SendAsync(reply,reply.Length,d.RemoteEndPoint);
        } } catch(OperationCanceledException) {} catch(ObjectDisposedException) {}
    }
    async Task SocksDatagrams() {
        try { while(!cancel.IsCancellationRequested) {
            var d=await socksUdp.ReceiveAsync(cancel.Token); var b=d.Buffer;
            if(!IPAddress.IsLoopback(d.RemoteEndPoint.Address) || b.Length<10 || b[0]!=0 || b[1]!=0 || b[2]!=0 || (b[3]!=1 && b[3]!=4)) { Unexpected.Enqueue("bad SOCKS UDP"); continue; }
            int n=b[3]==1?4:16, end=6+n;
            if(b.Length<end || !Allowed(new IPAddress(b.Skip(4).Take(n).ToArray()),(b[end-2]<<8)|b[end-1])) { Unexpected.Enqueue("SOCKS UDP denied"); continue; }
            using(var target=new UdpClient(AddressFamily.InterNetwork)) {
                target.Client.ReceiveTimeout=5000; target.Connect(IPAddress.Loopback,EchoPort);
                var payload=b.Skip(end).ToArray(); target.Send(payload,payload.Length);
                IPEndPoint remote=null; var echo=target.Receive(ref remote);
                var answer=b.Take(end).Concat(echo).ToArray(); await socksUdp.SendAsync(answer,answer.Length,d.RemoteEndPoint);
            }
        } } catch(OperationCanceledException) {} catch(ObjectDisposedException) {}
          catch(Exception e) { Unexpected.Enqueue("udp:"+e.GetType().Name); }
    }
    async Task DnsDatagrams() {
        try { while(!cancel.IsCancellationRequested) {
            var d=await dnsUdp.ReceiveAsync(cancel.Token); var q=d.Buffer; int pos=12; var labels=new System.Collections.Generic.List<string>();
            if(!IPAddress.IsLoopback(d.RemoteEndPoint.Address) || q.Length<17 || q[4]!=0 || q[5]!=1) throw new IOException("DNS header");
            while(pos<q.Length && q[pos]!=0) { int size=q[pos++]; if(size>63 || pos+size>=q.Length) throw new IOException("DNS label"); labels.Add(Encoding.ASCII.GetString(q,pos,size)); pos+=size; }
            pos++; if(pos+4>q.Length) throw new IOException("DNS tail"); int type=(q[pos]<<8)|q[pos+1];
            string name=String.Join(".",labels); if(!name.EndsWith(".autopilot.test",StringComparison.Ordinal) || (type!=1 && type!=28)) throw new IOException("DNS name/type");
            DnsHits.Enqueue(name+"|"+type);
            var ip=IPAddress.Parse(type==1?"203.0.113.7":"2001:db8::7").GetAddressBytes();
            var head=new byte[]{q[0],q[1],0x81,0x80,0,1,0,1,0,0,0,0};
            var answer=new byte[]{0xc0,0x0c,0,(byte)type,0,1,0,0,0,0,0,(byte)ip.Length};
            var reply=head.Concat(q.Skip(12).Take(pos+4-12)).Concat(answer).Concat(ip).ToArray();
            await dnsUdp.SendAsync(reply,reply.Length,d.RemoteEndPoint);
        } } catch(OperationCanceledException) {} catch(ObjectDisposedException) {}
          catch(Exception e) { Unexpected.Enqueue("dns:"+e.GetType().Name); }
    }
    public static bool ContainsEmbeddedDll(string exe, string dll) {
        var hay=File.ReadAllBytes(exe); var needle=File.ReadAllBytes(dll);
        for(int i=0;i<=hay.Length-needle.Length;i++) {
            if(hay[i]!=needle[0] || hay[i+1]!=needle[1]) continue;
            int j=2; while(j<needle.Length && hay[i+j]==needle[j]) j++;
            if(j==needle.Length) return true;
        }
        return false;
    }
    public static int FreeTcpPort() { var l=new TcpListener(IPAddress.Loopback,0); l.Start(); int p=((IPEndPoint)l.LocalEndpoint).Port; l.Stop(); return p; }
    public static string NativeTcp(string address, int port, string payload, string marker) {
        var ip=IPAddress.Parse(address); using(var c=new TcpClient(ip.AddressFamily)) {
            c.ReceiveTimeout=5000; c.SendTimeout=5000;
            if(!c.ConnectAsync(ip,port).Wait(5000)) throw new TimeoutException("Native TCP");
            var source=((IPEndPoint)c.Client.LocalEndPoint).Address.ToString(); var b=Encoding.ASCII.GetBytes(payload+"\n");
            c.GetStream().Write(b,0,b.Length); var answer=Encoding.ASCII.GetString(Line(c.GetStream()));
            if(answer!=marker+"|"+payload+"\n") throw new IOException("TCP marker mismatch"); return source;
        }
    }
    public static string NativeUdp(string address, int port, string payload, string marker) {
        var ip=IPAddress.Parse(address); using(var c=new UdpClient(ip.AddressFamily)) {
            c.Client.ReceiveTimeout=5000; c.Connect(ip,port); var b=Encoding.ASCII.GetBytes(payload); c.Send(b,b.Length);
            var source=((IPEndPoint)c.Client.LocalEndPoint).Address.ToString(); IPEndPoint remote=null; var answer=c.Receive(ref remote);
            if(!remote.Address.Equals(ip) || remote.Port!=port || Encoding.ASCII.GetString(answer)!=marker+"|"+payload) throw new IOException("UDP marker mismatch"); return source;
        }
    }
    public static string NativeDns(string address, string name, int type) {
        var ip=IPAddress.Parse(address); var q=new System.Collections.Generic.List<byte>(new byte[]{0x6a,0x71,1,0,0,1,0,0,0,0,0,0});
        foreach(var label in name.Split('.')) { var l=Encoding.ASCII.GetBytes(label); q.Add((byte)l.Length); q.AddRange(l); }
        q.AddRange(new byte[]{0,0,(byte)type,0,1}); var packet=q.ToArray();
        using(var c=new UdpClient(ip.AddressFamily)) {
            c.Client.ReceiveTimeout=5000; c.Connect(ip,53); c.Send(packet,packet.Length);
            string source=((IPEndPoint)c.Client.LocalEndPoint).Address.ToString(); IPEndPoint remote=null; var b=c.Receive(ref remote);
            var expected=IPAddress.Parse(type==1?"203.0.113.7":"2001:db8::7").GetAddressBytes();
            if(!remote.Address.Equals(ip) || remote.Port!=53 || b.Length<packet.Length+12+expected.Length || b[0]!=0x6a || b[1]!=0x71 || (b[3]&15)!=0 || b[6]!=0 || b[7]!=1 || !b.Skip(b.Length-expected.Length).SequenceEqual(expected)) throw new IOException("DNS answer mismatch");
            return source;
        }
    }
    public void Dispose() {
        cancel.Cancel(); echoTcp.Stop(); socksTcp.Stop(); foreach(var s in sockets) s.Dispose();
        if(!Task.WaitAll(tasks.ToArray(),10000)) throw new TimeoutException("Fixture socket/task cleanup"); cancel.Dispose();
    }
}
'@
    # sing-tun v0.4.27 embeds its DLL and loads it from memory. A side-by-side DLL
    # would NOT replace it. Demand an exact embedded copy of the verified reference.
    if (-not [TunLabFixture]::ContainsEmbeddedDll($script:coreExe, $referenceDll)) { throw 'Embedded Wintun differs from the signed 0.14.1 reference; driver provenance gate failed.' }
    $receipt.driverProvenance = [ordered]@{ embeddedDllEqualsOfficialSignedReference = $true; referenceDllSha256 = Get-Sha $referenceDll;
        authenticode = [string]$signature.Status; signer = $signature.SignerCertificate.Subject; signerThumbprint = $signature.SignerCertificate.Thumbprint }
    $fixture = [TunLabFixture]::new("AUTOPILOT_WINDOWS_TUN_$nonce")
    $handler = [Net.Http.HttpClientHandler]::new(); $handler.UseProxy = $false
    $api = [Net.Http.HttpClient]::new($handler); $api.Timeout = [TimeSpan]::FromSeconds(2)

    foreach ($phase in @('stop', 'crash')) {
        $name = "aptun-$phase-$($nonce.Substring(0,8))"
        if ((Get-OwnedAdapter $name).Count) { throw 'Fixture adapter name already exists.' }
        $script:controllerPort = [TunLabFixture]::FreeTcpPort()
        $script:controllerSecret = [Guid]::NewGuid().ToString('N')
        $config = [ordered]@{
            'mixed-port' = 0; port = 0; 'socks-port' = 0; 'allow-lan' = $false; mode = 'rule'; ipv6 = $true
            'log-level' = 'debug'; 'find-process-mode' = 'off'; 'geo-auto-update' = $false
            'external-controller' = "127.0.0.1:$controllerPort"; secret = $controllerSecret
            profile = @{ 'store-selected' = $false; 'store-fake-ip' = $false }
            tun = @{ enable = $true; device = $name; stack = 'mips'; 'auto-route' = $false; 'auto-redirect' = $false;
                'strict-route' = $false; 'auto-detect-interface' = $false; mtu = 1400; 'inet6-address' = @('2001:db8::1/126');
                'dns-hijack' = @('udp://203.0.113.53:53', 'udp://[2001:db8::53]:53') }
            # Keep the adapter's automatic connected /30 distinct from our explicit /24
            # test route. Otherwise New-NetRoute collides with Windows' connected route.
            dns = @{ enable = $true; ipv6 = $true; 'enhanced-mode' = 'redir-host'; 'fake-ip-range' = '203.0.113.1/30';
                'use-hosts' = $false; 'use-system-hosts' = $false; 'respect-rules' = $false;
                'default-nameserver' = @('127.0.0.1'); nameserver = @("udp://127.0.0.1:$($fixture.DnsPort)") }
            proxies = @(@{ name = 'OWNED_FIXTURE'; type = 'socks5'; server = '127.0.0.1'; port = $fixture.SocksPort; udp = $true })
            rules = @('IP-CIDR,203.0.113.7/32,OWNED_FIXTURE,no-resolve', 'IP-CIDR6,2001:db8::7/128,OWNED_FIXTURE,no-resolve', 'MATCH,REJECT')
        }
        $configPath = Assert-LabPath (Join-Path $lab "$phase-config.json")
        [IO.File]::WriteAllText($configPath, ($config | ConvertTo-Json -Depth 12))
        $child = Start-OwnedCore $phase $configPath
        Wait-Gate "$phase native controller" {
            if ($child.Process.HasExited) { throw 'Core exited before readiness; kernel/driver/config gate failed.' }
            try { $version = Invoke-CoreApi GET '/version' | ConvertFrom-Json; return $version.version -ceq $pins.version } catch [Net.Http.HttpRequestException] { return $false } catch [Threading.Tasks.TaskCanceledException] { return $false }
        }
        Wait-Gate "$phase TUN adapter Up" { $a = Get-OwnedAdapter $name; $a.Count -eq 1 -and $a[0].Status -eq 'Up' }
        $a = (Get-OwnedAdapter $name)[0]
        $adapter = [pscustomobject]@{ Name = $name; Index = [int]$a.ifIndex; Guid = [string]$a.InterfaceGuid; Pnp = [string]$a.PnPDeviceID }
        $ownedAdapters.Add($adapter)
        Wait-Gate "$phase TUN addresses usable after duplicate-address detection" {
            $usable = @(Get-NetIPAddress -InterfaceIndex $adapter.Index | Where-Object { $_.AddressState -eq 'Preferred' } | ForEach-Object { $_.IPAddress })
            '203.0.113.1' -in $usable -and '2001:db8::1' -in $usable
        }
        $addresses = @(Get-NetIPAddress -InterfaceIndex $adapter.Index | ForEach-Object { $_.IPAddress })
        if ('203.0.113.1' -notin $addresses -or '2001:db8::1' -notin $addresses) { throw 'TUN did not receive both fixture-family addresses.' }
        $drivers = @(Get-CimInstance Win32_PnPSignedDriver | Where-Object { $_.DeviceID -ceq $adapter.Pnp })
        if ($drivers.Count -ne 1 -or -not $drivers[0].IsSigned -or $drivers[0].DriverProviderName -notmatch 'WireGuard') { throw 'Observed TUN driver is not a signed WireGuard PnP driver.' }
        if (@(Get-NetRoute -InterfaceIndex $adapter.Index -PolicyStore ActiveStore | Where-Object { $_.DestinationPrefix -in @('0.0.0.0/0', '::/0', '0.0.0.0/1', '128.0.0.0/1', '::/1', '8000::/1') }).Count) { throw 'Unexpected broad TUN route; scoped-route gate failed.' }
        foreach ($prefix in $prefixes) {
            $nextHop = if ($prefix -eq $prefixes[0]) { '0.0.0.0' } else { '::' }
            $route = [pscustomobject]@{ Prefix = $prefix; Index = $adapter.Index; Guid = $adapter.Guid; Name = $name; NextHop = $nextHop; Cleaned = $false }
            $ownedRoutes.Add($route) # Record intent before mutation, including partial failures.
            $null = New-NetRoute -DestinationPrefix $prefix -InterfaceIndex $adapter.Index -NextHop $nextHop -RouteMetric 5 -PolicyStore ActiveStore
            $observed = @(Get-NetRoute -DestinationPrefix $prefix -InterfaceIndex $adapter.Index -PolicyStore ActiveStore | Where-Object { $_.NextHop -ceq $nextHop })
            if ($observed.Count -ne 1) { throw "Missing/ambiguous owned route: $prefix" }
        }
        Assert-HostInvariant
        $stage = [ordered]@{ phase = $phase; status = 'FAILED'; adapter = $adapter; adapterStatus = [string]$a.Status;
            driver = @{ signed = [bool]$drivers[0].IsSigned; signer = $drivers[0].Signer; version = $drivers[0].DriverVersion; provider = $drivers[0].DriverProviderName };
            observedRoutes = @($ownedRoutes | Where-Object { $_.Guid -eq $adapter.Guid }); probes = @() }
        $receipt.stages += $stage
        foreach ($family in @('v4', 'v6')) {
            $target = if ($family -eq 'v4') { '203.0.113.7' } else { '2001:db8::7' }
            $resolver = if ($family -eq 'v4') { '203.0.113.53' } else { '2001:db8::53' }
            $type = if ($family -eq 'v4') { 1 } else { 28 }
            $payload = "$nonce|$phase|$family"
            $tcpSource = [TunLabFixture]::NativeTcp($target, $fixture.EchoPort, "$payload|tcp", $fixture.Marker)
            $udpSource = [TunLabFixture]::NativeUdp($target, $fixture.EchoPort, "$payload|udp", $fixture.Marker)
            $queryName = "lab-$phase-$family-$nonce.autopilot.test"
            $dnsSource = [TunLabFixture]::NativeDns($resolver, $queryName, $type)
            if ($tcpSource -notin $addresses -or $udpSource -notin $addresses -or $dnsSource -notin $addresses) { throw 'Native client source did not belong to the observed TUN interface.' }
            if (@($fixture.TcpHits.ToArray() | Where-Object { $_ -ceq "$payload|tcp" }).Count -ne 1 -or
                @($fixture.UdpHits.ToArray() | Where-Object { $_ -ceq "$payload|udp" }).Count -ne 1 -or
                "$queryName|$type" -notin $fixture.DnsHits.ToArray()) { throw 'Native traffic did not reach the owned echo/DNS fixture exactly as requested.' }
            $stage.probes += @{ family = $family; tcp = $true; udp = $true; dns = $true; nativeSocket = $true; source = $tcpSource; dnsType = $type }
        }
        # Core outbounds and DNS use loopback; their destination must never select the TUN.
        $loopback = @(Find-NetRoute -RemoteIPAddress '127.0.0.1' | Where-Object { $_.PSObject.Properties.Name -contains 'DestinationPrefix' })
        if ($loopback.Count -ne 1 -or $loopback[0].InterfaceIndex -eq $adapter.Index) { throw 'Loopback outbound selected TUN; loop prevention failed.' }
        $null = [TunLabFixture]::NativeTcp('127.0.0.1', $fixture.EchoPort, "$nonce|$phase|loopback", $fixture.Marker)
        if ($fixture.Unexpected.Count -ne 0) { throw 'Fixture observed an unexpected destination, protocol or error.' }
        $stage.loopbackBypassObserved = $true
        if ($phase -eq 'stop') {
            # Supervisor owns manual routes; native PATCH disables the adapter without killing the process.
            Remove-OwnedRoutes
            $null = Invoke-CoreApi PATCH '/configs' '{"tun":{"enable":false}}'
            Assert-CleanStage $adapter 'stop'
            if ($child.Process.HasExited) { throw 'Stop cleanup was not observed with the native process alive.' }
            Stop-OwnedChild $child
        } else {
            # Deliberately bypass native shutdown, then perform supervisor-owned route cleanup.
            Stop-OwnedChild $child
            Remove-OwnedRoutes
            Assert-CleanStage $adapter 'crash'
        }
        $stage.status = 'PASS'
    }
    $receipt.status = 'PASS'
} catch {
    $failure = $_
    $receipt.errors += $_.Exception.Message
} finally {
    foreach ($child in $ownedChildren) {
        try { Stop-OwnedChild $child } catch { $receipt.errors += "child cleanup: $($_.Exception.Message)"; $receipt.status = 'FAILED' }
    }
    try { Remove-OwnedRoutes } catch { $receipt.errors += "route cleanup: $($_.Exception.Message)"; $receipt.status = 'FAILED' }
    foreach ($adapter in $ownedAdapters) {
        try {
            if (@(Get-NetAdapter -IncludeHidden | Where-Object { [string]$_.InterfaceGuid -eq $adapter.Guid }).Count) {
                # Do not delete a shared driver package or reset networking. A residual adapter is a failed gate;
                # GitHub destroys this VM after the job, including a failed/cancelled job.
                $receipt.errors += "Residual owned adapter: $($adapter.Name)"; $receipt.status = 'FAILED'
            }
        } catch { $receipt.errors += "adapter cleanup observation: $($_.Exception.Message)"; $receipt.status = 'FAILED' }
    }
    if ($null -ne $fixture) {
        try { $fixture.Dispose() } catch { $receipt.errors += "fixture cleanup: $($_.Exception.Message)"; $receipt.status = 'FAILED' }
        if ($fixture.Unexpected.Count -ne 0) { $receipt.errors += 'Fixture recorded an unexpected destination, protocol or error.'; $receipt.status = 'FAILED' }
        Write-Json 'fixture.json' @{ tcpHits = $fixture.TcpHits.ToArray(); udpHits = $fixture.UdpHits.ToArray(); dnsHits = $fixture.DnsHits.ToArray(); unexpected = $fixture.Unexpected.ToArray() }
    }
    if ($null -ne $api) { $api.Dispose() }
    if ($null -ne $baseline) {
        try { Assert-HostInvariant } catch { $receipt.errors += $_.Exception.Message; $receipt.status = 'FAILED' }
    }
    $receipt.finishedAt = [DateTime]::UtcNow.ToString('o')
    Write-Json 'receipt.json' $receipt
    # Only logs/receipts are artifacts. Configs, controller tokens, downloads and EXEs stay in runner temp.
    Write-Output "WINDOWS_TUN_LAB_RESULT status=$($receipt.status) scope=reserved-prefix-fixture receipt=$results\receipt.json"
}
if ($receipt.status -cne 'PASS') { if ($null -ne $failure) { throw $failure }; throw 'Windows TUN lab failed; read receipt.json.' }
