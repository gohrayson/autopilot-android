# Android update publisher fixture receipt

Run on 2026-10-21, repository `D:\Github\onevpn-autopilot`.

Verbatim invocation:

```powershell
node --test android/tools/publish-update.test.cjs
```

Verbatim runner output:

```text
✔ default arguments are read-only and conflicting publication flags fail (0.9778ms)
✔ release preparation emits the exact app updater contract and hashes the bytes (1.7746ms)
✔ APK signer, package, version and SDK are pinned (0.306ms)
✔ dry-run preparation rejects a mislabeled old signed APK before touching publication (0.1809ms)
✔ source archives reject stale files, account/signing data, traversal and duplicates (0.5986ms)
✔ manifest rejects unsupported fields, unsafe notes and foreign download origins (0.353ms)
✔ credential lookup is noninteractive, shell-free and never printed (0.1805ms)
✔ credentials only reach exact GitHub API/upload origins and redirects are not followed (0.4945ms)
✔ draft mode uploads and verifies all assets without publishing (0.6229ms)
✔ publish becomes visible only after every remote digest and final draft are verified (0.4225ms)
✔ matching drafts resume without duplicate uploads; public releases are immutable (0.3985ms)
✔ hash mismatch, foreign upload origin and duplicate draft assets block publication (0.3904ms)
ℹ tests 12
ℹ suites 0
ℹ pass 12
ℹ fail 0
ℹ cancelled 0
ℹ skipped 0
ℹ todo 0
ℹ duration_ms 50.7104
```

Scope: deterministic local fixtures, simulated APK inspection outputs, and a fake GitHub transport. No GitHub credentials were acquired and no release was uploaded or published during this run. The fixtures prove rejection of a mislabeled old APK and stale/private source files; manifest shape; pinned signing metadata; noninteractive authentication parameters; credential origin restrictions; no credential-bearing redirect following; draft-only uploads; read-back digest verification; explicit publication after all checks; safe matching draft resumption; and public release overwrite refusal.

This receipt does not establish successful GitHub publication or phone installation. Run the real local dry run against the final signed APK/source ZIP, then verify the published public feed and phone update flow separately as described in `UPDATER_PUBLISHING.md`.
