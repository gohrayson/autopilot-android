'use strict';
// Public HTTPS only, on a disposable hosted emulator. No provider account or VPN change.
const fs = require('node:fs/promises');
const path = require('node:path');
const cp = require('node:child_process');
const { promisify } = require('node:util');
const execute = promisify(cp.execFile);
const project = path.resolve(process.argv[2] || path.join(__dirname, '..'));
const output = path.resolve(process.env.GITHUB_WORKSPACE || process.cwd(), 'ci-native-results');

async function main() {
  if (process.platform !== 'linux' || process.env.GITHUB_ACTIONS !== 'true') throw new Error('Require a disposable GitHub Linux runner.');
  await fs.mkdir(output, { recursive: true });
  const inventory = (await execute('adb', ['devices'])).stdout.trim().split(/\r?\n/).slice(1).filter(Boolean);
  if (inventory.length !== 1 || !/^emulator-\d+\s+device$/.test(inventory[0])) throw new Error('Require exactly one online emulator and no physical devices.');
  const serial = inventory[0].split(/\s/)[0];
  // Gradle's connected test runner uninstalls its APKs when it finishes.
  // This independent instrumentation invocation owns its own installation.
  for (const file of ['app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']) {
    const result = await execute('adb', ['-s', serial, 'install', '-r', '-t', path.join(project, file)],
      { timeout: 90000, maxBuffer: 1024 * 1024, windowsHide: true });
    if (!result.stdout.includes('Success')) throw new Error('Updater fixture APK install failed.');
  }
  let log;
  try {
    const result = await execute('adb', ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'class',
      'app.autopilot.android.UpdateHttpsTransportRuntimeTest', '-e', 'updateTransportLive', 'true',
      'app.autopilot.android.debug.test/androidx.test.runner.AndroidJUnitRunner'],
    { timeout: 150000, maxBuffer: 2 * 1024 * 1024, windowsHide: true });
    log = result.stdout + result.stderr;
  } catch (failure) {
    log = String(failure.stdout || '') + String(failure.stderr || '');
    await fs.writeFile(path.join(output, 'update-https-route.log'), log);
    throw new Error('Updater HTTPS instrumentation could not finish: ' + log.slice(-4000));
  }
  await fs.writeFile(path.join(output, 'update-https-route.log'), log);
  process.stdout.write(log);
  if (!/^OK \(1 test\)\s*$/m.test(log) || /FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|There (?:was|were) \d+ failures?/.test(log)
      || !log.includes('AUTOPILOT_UPDATE_HTTPS_ACCEPTED')) throw new Error('Updater HTTPS route was not verified.');
  console.log('ANDROID_UPDATE_HTTPS_RECEIPT tests=1 expected=1 failures=0 explicitProxy=true trustedTls=true liveManifest=true');
}
main().catch(failure => { console.error(failure.message); process.exitCode = 1; });
