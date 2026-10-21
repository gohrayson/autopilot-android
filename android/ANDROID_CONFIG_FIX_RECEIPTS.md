# Android configuration and native isolation verification

Signed APK, JVM inventory, lint, delivery, device limitations and broader Windows guard observation: [ANDROID_CONFIG_FIX_BUILD_RECEIPTS.md](ANDROID_CONFIG_FIX_BUILD_RECEIPTS.md).

The final postbuild checks execute the actual compiled VpnEngine.configuration() method. Synthetic profiles use the production NodeParser subscription/WireGuard paths. Every exact generated JSONObject is written unchanged and submitted to bundled mihomo v1.19.32 with -t in an isolated directory. No listener or TUN starts and no provider account API is called.

The second stage runs the actual production ProfileValidation and CoreDiagnostics source against real native parser responses, rather than mock error strings. Each installer attempt calls the same compiled VpnEngine.configuration() method for its current candidate list. Private native output stays in memory and only the production allowlisted diagnostic is emitted.

These results prove the recorded generated configurations and node-isolation behavior. They do not identify the specific profile cached on the user phone or prove Android native initialization/TUN behavior. The semantic checker here is the pinned Windows mihomo binary; it does not load the Android native library.

## Synthetic parser and production isolation invocation

```powershell
Set-Location D:\Github\onevpn-autopilot
& .\android\tools\probe-config-parser.ps1 2>&1 | Tee-Object -FilePath .data\android-config-parser-final-synthetic.log
```

The invocation exited 0. Verbatim case results and runner tail:

```text
EMIT empty nodes=0 expected=accept
EMIT socks5 nodes=1 expected=accept
EMIT vless nodes=1 expected=accept
EMIT wireguard-ipv4 nodes=1 expected=accept
EMIT wireguard-dual-stack nodes=1 expected=accept
EMIT all-valid nodes=4 expected=accept
EMIT invalid-shadowsocks-cipher nodes=1 expected=reject
EMIT invalid-with-wireguards nodes=3 expected=reject
EMIT invalid-with-all nodes=5 expected=reject
EMIT invalid-shifted-indexes nodes=6 expected=reject
CORE_PARSE case=empty nodes=0 expected=True actual=True exit=0 matched=True
CORE_PARSE case=socks5 nodes=1 expected=True actual=True exit=0 matched=True
CORE_PARSE case=vless nodes=1 expected=True actual=True exit=0 matched=True
CORE_PARSE case=wireguard-ipv4 nodes=1 expected=True actual=True exit=0 matched=True
CORE_PARSE case=wireguard-dual-stack nodes=1 expected=True actual=True exit=0 matched=True
CORE_PARSE case=all-valid nodes=4 expected=True actual=True exit=0 matched=True
CORE_PARSE case=invalid-shadowsocks-cipher nodes=1 expected=False actual=False exit=1 matched=True
CORE_PARSE case=invalid-with-wireguards nodes=3 expected=False actual=False exit=1 matched=True
CORE_PARSE case=invalid-with-all nodes=5 expected=False actual=False exit=1 matched=True
CORE_PARSE case=invalid-shifted-indexes nodes=6 expected=False actual=False exit=1 matched=True
NATIVE_VALIDATION {"sourceIndexes":[],"nativeCalls":1,"sourceNodes":0,"accepted":true,"remainingNodes":0,"matched":true,"id":"empty","nativeProxyIndexes":[],"selectedRetained":false,"skipped":0}
NATIVE_VALIDATION {"sourceIndexes":[],"nativeCalls":1,"sourceNodes":1,"accepted":true,"remainingNodes":1,"matched":true,"id":"socks5","nativeProxyIndexes":[],"selectedRetained":true,"skipped":0}
NATIVE_VALIDATION {"sourceIndexes":[],"nativeCalls":1,"sourceNodes":1,"accepted":true,"remainingNodes":1,"matched":true,"id":"vless","nativeProxyIndexes":[],"selectedRetained":true,"skipped":0}
NATIVE_VALIDATION {"sourceIndexes":[],"nativeCalls":1,"sourceNodes":1,"accepted":true,"remainingNodes":1,"matched":true,"id":"wireguard-ipv4","nativeProxyIndexes":[],"selectedRetained":true,"skipped":0}
NATIVE_VALIDATION {"sourceIndexes":[],"nativeCalls":1,"sourceNodes":1,"accepted":true,"remainingNodes":1,"matched":true,"id":"wireguard-dual-stack","nativeProxyIndexes":[],"selectedRetained":true,"skipped":0}
NATIVE_VALIDATION {"sourceIndexes":[],"nativeCalls":1,"sourceNodes":4,"accepted":true,"remainingNodes":4,"matched":true,"id":"all-valid","nativeProxyIndexes":[],"selectedRetained":true,"skipped":0}
NATIVE_REJECTION case=invalid-shadowsocks-cipher diagnostic={"proxyType":"ss","code":"CORE_PROXY_CIPHER_INVALID","category":"proxy","message":"A node uses an unsupported or invalid cipher. Refresh the provider configuration.","proxyIndex":0} skipped=1
NATIVE_VALIDATION {"sourceIndexes":[0],"nativeCalls":2,"sourceNodes":1,"accepted":true,"remainingNodes":0,"matched":true,"id":"invalid-shadowsocks-cipher","nativeProxyIndexes":[0],"selectedRetained":false,"skipped":1}
NATIVE_REJECTION case=invalid-with-wireguards diagnostic={"proxyType":"ss","code":"CORE_PROXY_CIPHER_INVALID","category":"proxy","message":"A node uses an unsupported or invalid cipher. Refresh the provider configuration.","proxyIndex":0} skipped=1
NATIVE_VALIDATION {"sourceIndexes":[0],"nativeCalls":2,"sourceNodes":3,"accepted":true,"remainingNodes":2,"matched":true,"id":"invalid-with-wireguards","nativeProxyIndexes":[0],"selectedRetained":false,"skipped":1}
NATIVE_REJECTION case=invalid-with-all diagnostic={"proxyType":"ss","code":"CORE_PROXY_CIPHER_INVALID","category":"proxy","message":"A node uses an unsupported or invalid cipher. Refresh the provider configuration.","proxyIndex":4} skipped=1
NATIVE_VALIDATION {"sourceIndexes":[4],"nativeCalls":2,"sourceNodes":5,"accepted":true,"remainingNodes":4,"matched":true,"id":"invalid-with-all","nativeProxyIndexes":[4],"selectedRetained":false,"skipped":1}
NATIVE_REJECTION case=invalid-shifted-indexes diagnostic={"proxyType":"ss","code":"CORE_PROXY_CIPHER_INVALID","category":"proxy","message":"A node uses an unsupported or invalid cipher. Refresh the provider configuration.","proxyIndex":1} skipped=1
NATIVE_REJECTION case=invalid-shifted-indexes diagnostic={"proxyType":"ss","code":"CORE_PROXY_CIPHER_INVALID","category":"proxy","message":"A node uses an unsupported or invalid cipher. Refresh the provider configuration.","proxyIndex":2} skipped=2
NATIVE_VALIDATION {"sourceIndexes":[1,3],"nativeCalls":3,"sourceNodes":6,"accepted":true,"remainingNodes":4,"matched":true,"id":"invalid-shifted-indexes","nativeProxyIndexes":[1,2],"selectedRetained":false,"skipped":2}
NATIVE_VALIDATION_SUMMARY matched=10/10 nativeCalls=15
CONFIG_PARSER_SUMMARY matched=10/10 hostStateUnchanged=true workspace=D:\Github\onevpn-autopilot\.data\android-config-parser-probe\20261021-201622-42f2565b
```

The full native command executed for every attempt is:

```powershell
vendor\mihomo\mihomo.exe -t -d <isolated-case-or-attempt-directory> -f <exact-exported-configuration.json>
```

The empty baseline passed (native exit 0). Its stderr file is empty. Verbatim output:

```text
time="2026-10-21T20:16:23.498238900+08:00" level=info msg="Start initial configuration in progress"
time="2026-10-21T20:16:23.500775200+08:00" level=info msg="Geodata Loader mode: memconservative"
time="2026-10-21T20:16:23.502805100+08:00" level=info msg="Geosite Matcher implementation: succinct"
time="2026-10-21T20:16:23.508388500+08:00" level=info msg="Initial configuration complete, total time: 7ms"
configuration file D:\Github\onevpn-autopilot\.data\android-config-parser-probe\20261021-201622-42f2565b\empty.json test is successful
```

The unsupported Shadowsocks cipher is accepted by source shape validation but rejected by real native semantic parsing. The same node rejects an otherwise-valid full profile. Verbatim negative-case output:

```text
time="2026-10-21T20:16:24.090032000+08:00" level=info msg="Start initial configuration in progress"
time="2026-10-21T20:16:24.093118100+08:00" level=error msg="proxy 4: ss 192.0.2.22:443 cipher: fixture-nonexistent-cipher initialize error: unknown method: fixture-nonexistent-cipher"
configuration file D:\Github\onevpn-autopilot\.data\android-config-parser-probe\20261021-201622-42f2565b\invalid-with-all.json test failed
```

The production isolation loop removes only the native-indexed node and reparses the entire rebuilt configuration. The final mixed case verifies index shifts: native proxy indices 1 then 2 correspond to original source indices 1 then 3. The remaining valid nodes are accepted. An all-invalid one-node profile reparses as the accepted empty baseline. Installer assertions also verify that a removed selected node changes selection to REJECT and a surviving selected node is retained.

## Private current Windows profile invocation

The root agent supplied a private configuration file from the running Windows app. This probe reads only that input; it does not read any credential vault or call any account API. Private generated configurations remain under .data and their contents are not reproduced here. All raw private native stdout/stderr stays in memory; rejection classification passes through the actual production CoreDiagnostics implementation.

```powershell
Set-Location D:\Github\onevpn-autopilot
& .\android\tools\probe-config-parser.ps1 -InputProfile D:\Github\onevpn-autopilot\.data\android-config-probe\windows-nodes-private-input.json 2>&1 | Tee-Object -FilePath .data\android-config-parser-final-private.log
```

The invocation exited 0. Verbatim sanitized output:

```text
EMIT input-profile nodes=52 expected=accept
CORE_PARSE case=input-profile nodes=52 expected=True actual=True exit=0 matched=True
CORE_PARSE_SANITIZED code= category=ACCEPTED proxyIndex= proxyType= exit=0

NATIVE_VALIDATION {"sourceIndexes":[],"nativeCalls":1,"sourceNodes":52,"accepted":true,"remainingNodes":52,"matched":true,"id":"input-profile","nativeProxyIndexes":[],"selectedRetained":true,"skipped":0}
NATIVE_VALIDATION_SUMMARY matched=1/1 nativeCalls=1
CONFIG_PARSER_SUMMARY matched=1/1 hostStateUnchanged=true workspace=D:\Github\onevpn-autopilot\.data\android-config-parser-probe\20261021-201624-1ba2f8b1
```

The actual NodeParser imported 52 nodes from the supplied snapshot. The exact generated full profile was accepted, and production ProfileValidation retained all 52 without dropping a node. This snapshot therefore does not reproduce the phone-specific rejection.

## Provenance and host guard

- Compiled VpnEngine: 
D:\Github\onevpn-autopilot\android\app\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes\app\autopilot\android\VpnEngine.class
- Compiled VpnEngine SHA-256: 
22E2E785457BDA5F79DA19A1A3B977C88CDCCB42159049564857A0410DD0EF98
- VpnEngine source SHA-256: 
296FC30754874625F40835A35DDEA4633F68527F5DA3B18EA1EB4307246A0279
- Native binary SHA-256: 
38A73D4298B2FC44524C4C50A666339046FA919EE94D57269921DE7F13CA1820
- Synthetic workspace: 
D:\Github\onevpn-autopilot\.data\android-config-parser-probe\20261021-201622-42f2565b
- Private workspace: 
D:\Github\onevpn-autopilot\.data\android-config-parser-probe\20261021-201624-1ba2f8b1
- Both runs compare registry proxy, existing mihomo process IDs, and the 127.0.0.1:7890 listener before/after. Both snapshots match.

Production source components compiled into the JVM fixture (the app files are read-only to this probe):

```text
Node.java SHA256=6D7B9A75B9ADD1622CB6A6A7FEEB54A50A960D9CD3FA989A08F0D7A30575162A
CoreDiagnostics.java SHA256=C6E394E39E71749F2EE80C894EBD2E80F838E7EA1444368073496C4D834F00CE
ProfileValidation.java SHA256=4CC3F5B390EA3A86DA3F48A0A15BD6E85F04910CD663ECEFA39E20D3C635462D
```

Native version:

```text
Mihomo Meta v1.19.32 windows amd64 with go1.26.8 Wed Sep 30 16:55:20 UTC 2026
Use tags: with_gvisor
```

No Windows VPN process was stopped or started, no Windows proxy or route changed, and no provider API was called. Only isolated -t parser processes ran.
