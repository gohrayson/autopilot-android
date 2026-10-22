#!/usr/bin/env node
'use strict';

// Local cross-product probe: execute both production DNS builders, rather than
// asserting two separately copied expected lists. The standalone Android checkout
// intentionally does not contain the desktop product and does not run this probe.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const root = path.resolve(__dirname, '../..');
const javaHome = process.env.JAVA_HOME;
const jsonJar = process.argv[2];
if (!javaHome || !jsonJar || !fs.statSync(jsonJar).isFile()) {
  throw new Error('Set JAVA_HOME and pass the org.json test dependency JAR as the only argument.');
}
const coreFile = path.join(root, 'src/main/core.js');
if (!fs.existsSync(coreFile)) throw new Error('Run this cross-product probe in the combined desktop/Android source tree.');
const { buildConfig } = require(coreFile);
const temporaryParent = fs.realpathSync(os.tmpdir());
const temporary = fs.mkdtempSync(path.join(temporaryParent, 'autopilot-dns-parity-'));
try {
  const bridge = path.join(temporary, 'DnsParityProbe.java');
  fs.writeFileSync(bridge, `package app.autopilot.android;
public final class DnsParityProbe {
  public static void main(String[] args) throws Exception {
    System.out.println(new org.json.JSONObject().put("ipv6",CoreDnsConfig.IPV6_ENABLED).put("dns",CoreDnsConfig.DEFAULT.build()));
  }
}
`);
  const executable = name => path.join(javaHome, 'bin', name + (process.platform === 'win32' ? '.exe' : ''));
  execFileSync(executable('javac'), ['--release', '17', '-cp', jsonJar, '-d', temporary,
    path.join(root, 'android/app/src/main/java/app/autopilot/android/CoreDnsConfig.java'), bridge], { stdio: 'pipe' });
  const android = JSON.parse(execFileSync(executable('java'), ['-cp', temporary + path.delimiter + jsonJar,
    'app.autopilot.android.DnsParityProbe'], { encoding: 'utf8' }));
  const desktop = buildConfig([], { port: 7890, ctrlPort: 9090, secret: '' });
  assert.equal(android.dns.listen, '127.0.0.1:0');
  delete android.dns.listen; // Android's JNI DNS listener has no public controller port.
  assert.equal(android.ipv6, desktop.ipv6);
  assert.deepEqual(android.dns, desktop.dns);
  console.log('DESKTOP_ANDROID_DNS_PARITY_OK ' + JSON.stringify(android));
} finally {
  // This task creates the temporary directory directly under the OS temp folder.
  assert.equal(path.dirname(path.resolve(temporary)), temporaryParent);
  assert.ok(path.basename(temporary).startsWith('autopilot-dns-parity-'));
  fs.rmSync(temporary, { recursive: true, force: true });
}
