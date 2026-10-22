'use strict';
// Hosted Linux disposable emulator only. No real device, provider account or host VPN is touched.
const fs = require('node:fs/promises');
const path = require('node:path');
const cp = require('node:child_process');
const { promisify } = require('node:util');
const { startFixture } = require('./ci-tunnel-fixture.cjs');
const execute = promisify(cp.execFile);
const project = path.resolve(process.argv[2] || path.join(__dirname, '..'));
const output = path.resolve(process.env.GITHUB_WORKSPACE || project, 'ci-native-results');
const app = 'app.autopilot.android.debug';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let serial, fixture, instrumentation;
async function adb(...args) { return (await execute('adb', ['-s', serial, ...args], { timeout: 180000, maxBuffer: 8 * 1024 * 1024, windowsHide: true })).stdout; }
async function readPrivate(name) { try { return await adb('shell', 'run-as', app, 'cat', 'files/' + name); } catch { return ''; } }
async function captureProbePolicy() {
  if(!serial)return;
  for(const [name,command] of [['probe-connectivity.txt',['dumpsys','connectivity']],['probe-netpolicy.txt',['dumpsys','netpolicy']],
    ['probe-uid-state.txt',['dumpsys','activity','processes']],['probe-package.txt',['dumpsys','package','app.autopilot.probe']]]){
    try{await fs.writeFile(path.join(output,name),await adb('shell',...command));}catch(error){await fs.writeFile(path.join(output,name),String(error));}
  }
}
async function main() {
  if (process.platform !== 'linux' || process.env.GITHUB_ACTIONS !== 'true') throw new Error('This harness runs only on disposable GitHub Linux emulators.');
  await fs.mkdir(output, { recursive: true });
  const inventory = (await execute('adb', ['devices'])).stdout.trim().split(/\r?\n/).slice(1).filter(Boolean);
  if (inventory.length !== 1 || !/^emulator-\d+\s+device$/.test(inventory[0])) throw new Error('Require exactly one online emulator and no physical devices.');
  serial = inventory[0].split(/\s/)[0];
  fixture = await startFixture(path.join(output, 'tunnel-fixture.json'));
  for (const file of ['app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', 'probe/build/outputs/apk/debug/probe-debug.apk']) {
    const result = await adb('install', '-r', '-t', path.join(project, file));
    if (!result.includes('Success')) throw new Error('Fixture APK install failed: ' + result);
  }
  await adb('shell', 'pm', 'clear', app);
  await adb('shell', 'appops', 'set', app, 'ACTIVATE_VPN', 'allow');
  await adb('shell', 'pm', 'grant', app, 'android.permission.POST_NOTIFICATIONS');
  let log = '', exited = false;
  instrumentation = cp.spawn('adb', ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'class',
    'app.autopilot.android.NativeTunnelRuntimeTest', app + '.test/androidx.test.runner.AndroidJUnitRunner']);
  instrumentation.stdout.on('data', b => { log += b;process.stdout.write(b); });
  instrumentation.stderr.on('data', b => { log += b;process.stderr.write(b); });
  const completion = new Promise((resolve, reject) => { instrumentation.once('error', reject);instrumentation.once('exit', code => { exited = true;resolve(code); }); });
  let ready;
  const deadline = Date.now() + 120000;
  while (Date.now() < deadline && !exited) {
    const text = await readPrivate('tunnel-probe-ready.json');
    if (text) { ready = JSON.parse(text);break; }
    await sleep(300);
  }
  if (!ready) { await fs.writeFile(path.join(output, 'tunnel-instrumentation.log'), log);throw new Error('Production TUN setup failed: ' + log.slice(-8000)); }
  await fs.writeFile(path.join(output, 'tunnel-ready.json'), JSON.stringify(ready, null, 2));
  if (!ready.tun || !Number.isInteger(ready.uid)) throw new Error('Production TUN readiness receipt invalid.');
  const replies = [];
  for (const url of ['http://203.0.113.7/autopilot-probe', 'http://[2001:db8::7]/autopilot-probe', 'http://tunnel-fixture.autopilot.test/autopilot-probe', 'http://fakeip-fixture.autopilot.test/autopilot-probe']) {
    const reply = await adb('shell', 'am', 'instrument', '-w', '-e', 'url', url, '-e', 'marker', fixture.state.marker, 'app.autopilot.probe/.TunnelProbe');
    replies.push(reply);process.stdout.write(reply);
    await fs.writeFile(path.join(output, 'tunnel-other-uid.log'), replies.join('\n'));
    const uid = /uid=(\d+)/.exec(reply);
    if (!/result=PASS/.test(reply) || !uid || Number(uid[1]) === ready.uid) {
      await captureProbePolicy();throw new Error('Separate UID TUN request failed: ' + reply);
    }
    if(!/network=handle=\d+,vpn=true,blocked=false/.test(reply))throw new Error('Probe did not observe an unblocked VPN network: '+reply);
    if (url.includes('fakeip-fixture.autopilot.test')) {
      const resolved = /resolved=([^\r\n]+)/.exec(reply)?.[1].trim().split(',') ?? [];
      if (resolved.length === 0 || !resolved.every(address => {
        const parts = address.split('.');
        return parts.length === 4 && parts[0] === '198' && parts[1] === '18' && parts.slice(2).every(part => /^\d{1,3}$/.test(part) && Number(part) <= 255);
      })) throw new Error('Unfiltered application domain did not resolve inside configured 198.18.0.0/16 fake-IP pool: ' + reply);
    }
  }
  const queries = fixture.state.dnsQueries;
  for (const domain of ['proxy-fixture.autopilot.test', 'tunnel-fixture.autopilot.test']) {
    if (!queries.some(query => query.name === domain && query.answered)) throw new Error('Actual DNS fixture was not queried for ' + domain);
  }
  for (const host of ['203.0.113.7', '20010db8000000000000000000000007']) {
    if (!fixture.state.connections.some(connection => connection.host === host)) throw new Error('Missing TUN SOCKS destination ' + host);
  }
  if (!fixture.state.connections.some(connection => connection.host === 'fakeip-fixture.autopilot.test')) throw new Error('Native fake-IP reverse mapping did not restore the hostname at the SOCKS outbound.');
  if (queries.some(query => query.name === 'fakeip-fixture.autopilot.test')) throw new Error('Unfiltered fake-IP domain unexpectedly reached the real DNS fixture.');
  if (fixture.state.markerHits < 5 || fixture.state.unexpectedDestinations.length !== 0) throw new Error('Incomplete fixture traffic receipt.');
  await adb('shell', 'run-as', app, 'sh', '-c', "'printf PASS > files/tunnel-probe-complete'");
  const status = await completion;
  await fs.writeFile(path.join(output, 'tunnel-instrumentation.log'), log);
  if (status !== 0 || !/OK \(1 test\)/.test(log) || /FAILURES!!!|INSTRUMENTATION_FAILED/.test(log)) throw new Error('Production TUN instrumentation did not pass: ' + log.slice(-8000));
  const receipt = 'ANDROID_TUN_RUNTIME_RECEIPT tests=1 expected=1 failures=0 separateUid=true ipv4=true ipv6=true endpointBootstrapDns=true appDns=true fakeIpPool=true fakeIpReverseMapping=true stopCleanup=true\n';
  process.stdout.write(receipt);await fs.writeFile(path.join(output, 'tunnel-receipt.txt'), receipt);
}
main().catch(error => { console.error(error.stack);process.exitCode = 1; }).finally(async () => {
  if (serial) { try { await adb('shell', 'am', 'force-stop', app); } catch {} }
  if (instrumentation && !instrumentation.killed) instrumentation.kill();
  if (fixture) await fixture.close();
});
