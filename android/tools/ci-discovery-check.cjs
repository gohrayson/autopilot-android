'use strict';
// Opt-in public TLS + synthetic SOCKS pool, on one fresh disposable hosted emulator.
// No provider account, real device, host VPN, certificate override, or selector mutation.
const fs = require('node:fs/promises');
const path = require('node:path');
const cp = require('node:child_process');
const { promisify } = require('node:util');
const execute = promisify(cp.execFile);
const project = path.resolve(process.argv[2] || path.join(__dirname, '..'));
const output = path.resolve(process.env.GITHUB_WORKSPACE || project, 'ci-native-results');
const app = 'app.autopilot.android.debug';
const runner = app + '.test/androidx.test.runner.AndroidJUnitRunner';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let serial, instrumentation, log = '', exited = false, selectedEmulator = false;
async function adb(...args) {
  return (await execute('adb', ['-s', serial, ...args], { timeout: 90000, maxBuffer: 8 * 1024 * 1024, windowsHide: true })).stdout;
}
async function readPrivate(name) {
  if (!/^[a-z0-9.-]+$/.test(name)) throw new Error('Invalid fixture evidence filename.');
  try { return await adb('shell', 'run-as', app, 'cat', 'files/' + name); } catch { return ''; }
}
async function savePrivateEvidence() {
  if (!selectedEmulator) return;
  for (const name of ['discovery-fixture.json', 'discovery-evidence.json', 'diagnostics.log']) {
    const text = await readPrivate(name);
    if (text) await fs.writeFile(path.join(output, name === 'diagnostics.log' ? 'discovery-diagnostics.log' : name), text);
  }
  await fs.writeFile(path.join(output, 'discovery-instrumentation.log'), log);
}
async function waitStage(stage, priorNonce, until) {
  while (Date.now() < until && !exited) {
    const raw = await readPrivate('discovery-ready.json');
    if (raw) {
      let ready;
      try { ready = JSON.parse(raw); } catch { throw new Error('Invalid atomic discovery readiness JSON.'); }
      if (ready.stage === stage) {
        if (!ready.tun || !Number.isInteger(ready.uid) || ready.uid <= 0
          || !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(ready.nonce || '') || ready.nonce === priorNonce
          || !/^AUTOPILOT_DISCOVERY_[a-f0-9-]{36}$/.test(ready.marker || '')) throw new Error('Discovery readiness identity invalid.');
        const evidence = ready.evidence;
        if (evidence?.poolSize !== 52 || JSON.stringify(evidence.required) !== '["chatgpt"]'
          || evidence.earlySelectionMs > 60000 || !Number.isInteger(evidence.earlySelectionMs)
          || !evidence.selectedBeforeTail || !evidence.watchdogAdvancedDuringDiscovery) throw new Error('Production scheduling readiness evidence invalid.');
        await fs.writeFile(path.join(output, 'discovery-' + stage + '-ready.json'), JSON.stringify(ready, null, 2));
        return ready;
      }
      if (ready.stage !== 'connected') throw new Error('Unexpected discovery handoff stage.');
    }
    await sleep(250);
  }
  throw new Error('Production discovery did not reach ' + stage + ' handoff: ' + log.slice(-8000));
}
async function probeStage(ready) {
  const reply = await adb('shell', 'am', 'instrument', '-w', '-e', 'url', 'http://203.0.113.7/autopilot-probe',
    '-e', 'marker', ready.marker, 'app.autopilot.probe/.TunnelProbe');
  process.stdout.write(reply);
  await fs.writeFile(path.join(output, 'discovery-' + ready.stage + '-other-uid.log'), reply);
  const uid = Number(/(?:^|\n)INSTRUMENTATION_RESULT: uid=(\d+)\s*(?:\r?\n|$)/.exec(reply)?.[1]);
  if (!/^INSTRUMENTATION_RESULT: result=PASS\s*$/m.test(reply) || !Number.isInteger(uid) || uid === ready.uid || uid <= 0
    || !/^INSTRUMENTATION_RESULT: network=handle=\d+,vpn=true,blocked=false\s*$/m.test(reply)
    || !/^INSTRUMENTATION_CODE: -1\s*$/m.test(reply)) throw new Error('Separate-UID ' + ready.stage + ' traffic failed: ' + reply);
  const encoded = Buffer.from(JSON.stringify({ stage: ready.stage, nonce: ready.nonce, result: 'PASS', uid }), 'utf8').toString('base64');
  // Only generated base64 reaches this fixed shell command; no URL, account or user input.
  await adb('shell', 'run-as', app, 'sh', '-c', "'printf %s " + encoded
    + " | base64 -d > files/discovery-complete.tmp && mv files/discovery-complete.tmp files/discovery-complete'");
  return uid;
}
async function main() {
  if (process.platform !== 'linux' || process.env.GITHUB_ACTIONS !== 'true') throw new Error('Require a disposable GitHub Linux runner.');
  await fs.mkdir(output, { recursive: true });
  const inventory = (await execute('adb', ['devices'])).stdout.trim().split(/\r?\n/).slice(1).filter(Boolean);
  if (inventory.length !== 1 || !/^emulator-\d+\s+device$/.test(inventory[0])) throw new Error('Require exactly one online emulator and no physical devices.');
  serial = inventory[0].split(/\s/)[0];selectedEmulator = true;
  for (const file of ['app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', 'probe/build/outputs/apk/debug/probe-debug.apk']) {
    if (!(await adb('install', '-r', '-t', path.join(project, file))).includes('Success')) throw new Error('Discovery fixture APK install failed.');
  }
  if (app !== 'app.autopilot.android.debug' || !/^emulator-\d+$/.test(serial)) throw new Error('Disposable debug package clear guard failed.');
  if (!(await adb('shell', 'pm', 'clear', app)).includes('Success')) throw new Error('Could not clear disposable debug package.');
  await adb('shell', 'appops', 'set', app, 'ACTIVATE_VPN', 'allow');
  await adb('shell', 'pm', 'grant', app, 'android.permission.POST_NOTIFICATIONS');
  instrumentation = cp.spawn('adb', ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'class',
    'app.autopilot.android.DiscoveryRuntimeTest', '-e', 'hostedAllowPublicTransport', 'true', runner]);
  const append = (bytes, stream) => { log += bytes;stream.write(bytes);if (log.length > 4 * 1024 * 1024) instrumentation.kill(); };
  instrumentation.stdout.on('data', bytes => append(bytes, process.stdout));
  instrumentation.stderr.on('data', bytes => append(bytes, process.stderr));
  const completion = new Promise((resolve, reject) => {
    instrumentation.once('error', reject);
    instrumentation.once('close', code => { exited = true;resolve(code); });
  });
  completion.catch(() => {}); // Observed below; prevents transient unhandled rejection during handoff polling.
  const until = Date.now() + 230000;
  const first = await waitStage('connected', '', Math.min(until, Date.now() + 110000));
  const firstUid = await probeStage(first);
  const retest = await waitStage('retest', first.nonce, Math.min(until, Date.now() + 65000));
  if (first.marker !== retest.marker || first.uid !== retest.uid || !retest.evidence?.retestCoalesced
    || !retest.evidence.manualRetestAcknowledged
    || retest.evidence.retestDiscovery?.lastAppliedRetestId !== retest.evidence.priorRetestId + 1) throw new Error('Retest identity or consumed manual-request acknowledgement invalid.');
  const secondUid = await probeStage(retest);
  if (firstUid !== secondUid) throw new Error('Separate-UID probe identity changed between handoff stages.');
  let deadlineTimer;
  const status = await Promise.race([completion, new Promise((_, reject) => {
    deadlineTimer = setTimeout(() => reject(new Error('Production discovery instrumentation exceeded its hosted deadline.')), Math.max(1, until - Date.now()));
  })]).finally(() => clearTimeout(deadlineTimer));
  await savePrivateEvidence();
  if (status !== 0 || !/^OK \(1 test\)\s*$/m.test(log)
    || /FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|There (?:was|were) \d+ failures?/.test(log)
    || !log.includes('AUTOPILOT_DISCOVERY_RUNTIME_ACCEPTED')) throw new Error('Production discovery instrumentation did not pass: ' + log.slice(-8000));
  const evidence = JSON.parse(await readPrivate('discovery-evidence.json'));
  const fixture = JSON.parse(await readPrivate('discovery-fixture.json'));
  for (const field of ['selectedBeforeTail', 'watchdogAdvancedDuringDiscovery', 'retestCoalesced', 'manualRetestAcknowledged', 'nativeSelectorPreserved',
    'separateUidConnected', 'separateUidRetest', 'platformTls', 'threeWatchdogOrigins', 'stopCleanup', 'fixtureClosed']) {
    if (evidence[field] !== true) throw new Error('Missing final production discovery evidence: ' + field);
  }
  const hosts = [...(fixture.tlsHosts || [])].sort();
  if (evidence.tunStarts !== 1 || evidence.openFixtureSockets !== 0 || fixture.openSockets !== 0 || fixture.closed !== true
    || fixture.markerHits !== 2 || fixture.badSessions <= 0 || fixture.tlsSent <= 0 || fixture.tlsReceived <= 0
    || fixture.unexpectedDestinations !== 0 || fixture.failureCode !== ''
    || JSON.stringify(hosts) !== JSON.stringify(['api.anthropic.com', 'api.openai.com', 'www.gstatic.com'])) throw new Error('Invalid discovery fixture/cleanup evidence.');
  const receipt = 'ANDROID_DISCOVERY_RUNTIME_RECEIPT tests=1 expected=1 failures=0 errors=0 skipped=0 pool=52 required=chatgpt'
    + ' selectedBeforeTail=true watchdogAdvancedDuringDiscovery=true retestCoalesced=true manualRetestAcknowledged=true nativeSelector=true tunStarts=1'
    + ' separateUidConnected=true separateUidRetest=true platformTls=true threeWatchdogOrigins=true stopCleanup=true fixtureCleanup=true\n';
  process.stdout.write(receipt);await fs.writeFile(path.join(output, 'discovery-receipt.txt'), receipt);
}
main().catch(async error => {
  console.error(error.stack || error.message);process.exitCode = 1;
  if (selectedEmulator) {
    try { await savePrivateEvidence(); } catch {}
  }
}).finally(async () => {
  if (selectedEmulator) {
    try { await adb('shell', 'am', 'force-stop', app); } catch {}
  }
  if (instrumentation && !instrumentation.killed) instrumentation.kill();
});
