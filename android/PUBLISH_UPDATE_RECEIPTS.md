# Android update publisher fixture receipt

Run on 2026-10-21, repository `D:\Github\onevpn-autopilot`.

Verbatim invocation:

```powershell
node --test android/tools/publish-update.test.cjs
```

Verbatim runner output:

```text
✔ default arguments are read-only and conflicting publication flags fail (0.9753ms)
✔ release preparation emits the exact app updater contract and hashes the bytes (1.8134ms)
✔ APK signer, package, version and SDK are pinned (0.7889ms)
✔ actual aapt2 35 minSdkVersion output is accepted alongside the legacy spelling (0.1729ms)
✔ dry-run preparation rejects a mislabeled old signed APK before touching publication (0.16ms)
✔ source archives reject stale files, account/signing data, traversal and duplicates (0.5692ms)
✔ manifest rejects unsupported fields, unsafe notes and foreign download origins (0.3682ms)
✔ credential lookup is noninteractive, shell-free and never printed (0.1468ms)
✔ credentials only reach exact GitHub API/upload origins and redirects are not followed (0.4999ms)
✔ draft mode uploads and verifies all assets without publishing (0.6949ms)
✔ publish becomes visible only after every remote digest and final draft are verified (0.3622ms)
✔ matching drafts resume without duplicate uploads; public releases are immutable (0.375ms)
✔ hash mismatch, foreign upload origin and duplicate draft assets block publication (0.3643ms)
ℹ tests 13
ℹ suites 0
ℹ pass 13
ℹ fail 0
ℹ cancelled 0
ℹ skipped 0
ℹ todo 0
ℹ duration_ms 50.8416
```

Scope: deterministic local fixtures, simulated APK inspection outputs, and a fake GitHub transport. No GitHub credentials were acquired and no release was uploaded or published during this run. The fixtures prove rejection of a mislabeled old APK and stale/private source files; manifest shape; pinned signing metadata; noninteractive authentication parameters; credential origin restrictions; no credential-bearing redirect following; draft-only uploads; read-back digest verification; explicit publication after all checks; safe matching draft resumption; and public release overwrite refusal.

This receipt does not establish successful GitHub publication or phone installation. Run the real local dry run against the final signed APK/source ZIP, then verify the published public feed and phone update flow separately as described in `UPDATER_PUBLISHING.md`.

## Actual APK metadata adapter probe

The initial fixture used only the legacy `sdkVersion` spelling. Real aapt2 35 output uses `minSdkVersion`, so the first adapter would have rejected the correct APK. The adapter now accepts either spelling, requires exactly one minimum-SDK declaration, and rejects duplicates, conflicts and malformed declarations. The central-directory loop was also reviewed: the parsed entries must end at the EOCD offset, which is the correct ZIP directory boundary; no change was needed there.

Verbatim invocation:

```powershell
node -e "const {spawnSync}=require('node:child_process'); const fs=require('node:fs'); const p=require('./android/tools/publish-update.cjs'); const apk='dist/android/Autopilot-Android-0.1.2.apk'; const run=(file,args)=>{const r=spawnSync(file,args,{encoding:'utf8',shell:false,windowsHide:true});if(r.status!==0)throw Error('tool failed');return r.stdout};const signer=run('.data/android-tools/jdk/jdk-17.0.20.1+1/bin/java.exe',['-jar','.data/android-tools/sdk/build-tools/35.0.0/lib/apksigner.jar','verify','--print-certs',apk]);const badging=run('.data/android-tools/sdk/build-tools/35.0.0/aapt2.exe',['dump','badging',apk]);p.verifyApkMetadata(signer,badging,p.parseVersion(fs.readFileSync('android/app/build.gradle','utf8')));console.log('Verified real signed APK metadata with actual aapt2 35 minSdkVersion output.');"
```

Verbatim output:

```text
Verified real signed APK metadata with actual aapt2 35 minSdkVersion output.
```

This probe reads the real local release APK and invokes actual signature verification and APK badging tools. It does not install the APK, acquire GitHub credentials, upload artifacts or change VPN state. Regenerate the source ZIP after this receipt change before running the complete publisher dry run.
