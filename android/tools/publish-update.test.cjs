'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const crypto = require('node:crypto');
const publisher = require('./publish-update.cjs');

const GRADLE = "applicationId 'app.autopilot.android'\nversionName '0.1.2'\nversionCode 3\nminSdk 26\n";
const VERSION = publisher.parseVersion(GRADLE);
const ENGINE = 'package app.autopilot.android; class VpnEngine {}';
const HASH = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const ROOT = path.resolve('/fixture');
const BASE = 'https://api.github.com/repos/gohrayson/autopilot-android';

// Minimal uncompressed ZIP fixture; central-directory/local names are exercised.
function zip(entries) {
  const locals = [], central = []; let offset = 0;
  for (const [name, value] of entries) {
    const filename = Buffer.from(name), bytes = Buffer.from(value);
    const header = Buffer.alloc(30); header.writeUInt32LE(0x04034b50); header.writeUInt16LE(20, 4);
    header.writeUInt32LE(bytes.length, 18); header.writeUInt32LE(bytes.length, 22); header.writeUInt16LE(filename.length, 26);
    const directory = Buffer.alloc(46); directory.writeUInt32LE(0x02014b50); directory.writeUInt16LE(20, 6);
    directory.writeUInt32LE(bytes.length, 20); directory.writeUInt32LE(bytes.length, 24); directory.writeUInt16LE(filename.length, 28); directory.writeUInt32LE(offset, 42);
    locals.push(header, filename, bytes); central.push(directory, filename); offset += header.length + filename.length + bytes.length;
  }
  const directory = Buffer.concat(central), end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10); end.writeUInt32LE(directory.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, directory, end]);
}
const SOURCES = [['android/app/build.gradle', GRADLE], ['android/app/src/main/java/app/autopilot/android/VpnEngine.java', ENGINE]];
function fixture() {
  const apk = Buffer.from('signed APK fixture bytes');
  const files = new Map(SOURCES.map(([name, content]) => [path.join(ROOT, ...name.split('/')), Buffer.from(content)]));
  files.set(path.join(ROOT, 'dist/android/Autopilot-Android-0.1.2.apk'), apk);
  files.set(path.join(ROOT, 'dist/android/Autopilot-Android-0.1.2-source.zip'), zip(SOURCES));
  let verified = false;
  const read = (name, encoding) => {
    const bytes = files.get(name); if (!bytes) throw new Error('not found');
    return encoding ? bytes.toString(encoding) : bytes;
  };
  const prepared = publisher.prepareRelease({ root: ROOT, read, now: new Date('2026-10-21T12:00:00Z'), notes: 'Fix native configuration and add updates.', verifyApk: (file, version) => { assert.equal(file, path.join(ROOT, 'dist/android/Autopilot-Android-0.1.2.apk')); assert.deepEqual(version, VERSION); verified = true; } });
  assert.equal(verified, true);
  return prepared;
}

function fakeApi(prepared, { existingPublic = false, draftExisting = false, mismatch = false, badUploadOrigin = false, duplicate = false } = {}) {
  const calls = [];
  let release = (draftExisting || existingPublic) ? makeRelease(!existingPublic) : null;
  let assets = [];
  function makeRelease(draft = true) { return { id: 71, draft, tag_name: prepared.tag, url: `${BASE}/releases/71`, upload_url: `${badUploadOrigin ? 'https://attacker.invalid' : 'https://uploads.github.com'}/repos/gohrayson/autopilot-android/releases/71/assets{?name,label}` }; }
  function remote(asset, index) { return { id: 100 + index, name: asset.name, state: 'uploaded', size: asset.size, digest: `sha256:${mismatch ? '0'.repeat(64) : asset.sha256}`, browser_download_url: `https://github.com/gohrayson/autopilot-android/releases/download/${prepared.tag}/${asset.name}` }; }
  if (draftExisting) assets = prepared.assets.map(remote);
  if (duplicate && assets.length) assets.push({ ...assets[0], id: 999 });
  return { calls, api: {
    async get(suffix) {
      calls.push(['GET', suffix]);
      if (suffix.startsWith('/releases/tags/')) return release;
      if (suffix === '/releases/71/assets?per_page=100') return assets;
      if (suffix.startsWith('/releases/assets/')) return assets.find(asset => asset.id === Number(suffix.split('/').at(-1)));
      if (suffix === '/releases/71') return { ...release, assets };
      throw new Error(`Unexpected fake GET ${suffix}`);
    },
    async post(suffix, value) { calls.push(['POST', suffix, value]); release = makeRelease(); return release; },
    async upload(template, id, asset) { calls.push(['UPLOAD', asset.name]); const value = remote(asset, assets.length); assets.push(value); return value; },
    async patch(suffix, value) { calls.push(['PATCH', suffix, value]); release = { ...release, ...value }; return release; },
  } };
}

test('default arguments are read-only and conflicting publication flags fail', () => {
  assert.deepEqual(publisher.parseArgs([]), { mode: 'dry-run', notesFile: null });
  assert.equal(publisher.parseArgs(['--draft', '--notes-file', 'notes with spaces.txt']).mode, 'draft');
  assert.throws(() => publisher.parseArgs(['--draft', '--publish']), /only one/);
  assert.throws(() => publisher.parseArgs(['--notes-file']), /incomplete/);
});
test('release preparation emits the exact app updater contract and hashes the bytes', () => {
  const prepared = fixture();
  assert.equal(prepared.manifest.apkUrl, 'https://github.com/gohrayson/autopilot-android/releases/download/android-v0.1.2/Autopilot-Android-0.1.2.apk');
  assert.equal(prepared.manifest.sha256, HASH(prepared.assets[0].bytes));
  assert.equal(prepared.sourceEntries, 2);
  assert.deepEqual(JSON.parse(prepared.assets[1].bytes), prepared.manifest);
  assert.equal(prepared.assets[3].bytes.toString().split('\n').filter(Boolean).length, 3);
});
test('APK signer, package, version and SDK are pinned', () => {
  const signer = `Signer #1 certificate SHA-256 digest: ${publisher.CERTIFICATE}`;
  const badging = "package: name='app.autopilot.android' versionCode='3' versionName='0.1.2'\nsdkVersion:'26'\n";
  publisher.verifyApkMetadata(signer, badging, VERSION);
  assert.throws(() => publisher.verifyApkMetadata(signer.replace(publisher.CERTIFICATE, '0'.repeat(64)), badging, VERSION), /signer/);
  for (const bad of [badging.replace("versionCode='3'", "versionCode='2'"), badging.replace("name='app.autopilot.android'", "name='app.autopilot.android.debug'"), badging.replace("sdkVersion:'26'", "sdkVersion:'29'")]) assert.throws(() => publisher.verifyApkMetadata(signer, bad, VERSION), /does not match/);
  assert.throws(() => publisher.verifyApkMetadata(signer, badging + 'application-debuggable', VERSION), /debuggable/);
});
test('dry-run preparation rejects a mislabeled old signed APK before touching publication', () => {
  const files = new Map([[path.join(ROOT, 'android/app/build.gradle'), GRADLE], [path.join(ROOT, 'dist/android/Autopilot-Android-0.1.2.apk'), Buffer.from('old APK')]]);
  const signer = `Signer #1 certificate SHA-256 digest: ${publisher.CERTIFICATE}`;
  const oldBadging = "package: name='app.autopilot.android' versionCode='2' versionName='0.1.1'\nsdkVersion:'26'\n";
  assert.throws(() => publisher.prepareRelease({ root: ROOT, read: name => files.get(name), verifyApk: (file, version) => publisher.verifyApkMetadata(signer, oldBadging, version) }), /does not match/);
});
test('source archives reject stale files, account/signing data, traversal and duplicates', () => {
  const readLocal = name => Buffer.from(new Map(SOURCES).get(name) || 'private');
  assert.equal(publisher.verifySourceZip(zip(SOURCES), VERSION, readLocal), 2);
  assert.throws(() => publisher.verifySourceZip(zip([[SOURCES[0][0], GRADLE.replace('0.1.2', '0.1.1')], SOURCES[1]]), VERSION, readLocal), /stale/);
  for (const name of ['android/private.jks', 'android/provider.enc', 'android/.env', 'android/.data/account.json', 'android/../secrets.txt', 'android/build/config.json']) assert.throws(() => publisher.verifySourceZip(zip([...SOURCES, [name, 'private']]), VERSION, readLocal), /Private/);
  assert.throws(() => publisher.verifySourceZip(zip([...SOURCES, SOURCES[0]]), VERSION, readLocal), /duplicate/);
});
test('manifest rejects unsupported fields, unsafe notes and foreign download origins', () => {
  const manifest = fixture().manifest;
  assert.throws(() => publisher.validateManifest({ ...manifest, token: 'secret' }), /fields/);
  assert.throws(() => publisher.validateManifest({ ...manifest, notes: '\u202e hidden' }), /Unsafe/);
  assert.throws(() => publisher.validateManifest({ ...manifest, notes: 'x'.repeat(4097) }), /oversized/);
  assert.throws(() => publisher.validateManifest({ ...manifest, apkUrl: 'https://attacker.invalid/app.apk' }), /URL/);
});
test('credential lookup is noninteractive, shell-free and never printed', () => {
  let invoked = false;
  const token = publisher.credentials({}, (file, args, options) => {
    invoked = true; assert.equal(file, 'git'); assert.deepEqual(args, ['credential', 'fill']); assert.equal(options.shell, false);
    assert.equal(options.env.GIT_TERMINAL_PROMPT, '0'); assert.equal(options.env.GCM_INTERACTIVE, 'Never');
    assert.equal(options.input, 'protocol=https\nhost=github.com\n\n');
    return { status: 0, stdout: 'protocol=https\nhost=github.com\nusername=test\npassword=fixture-only\n' };
  });
  assert.equal(invoked, true); assert.equal(token, 'fixture-only');
  assert.equal(publisher.credentials({ GH_TOKEN: 'environment-only' }, () => assert.fail('must not call git')), 'environment-only');
});
test('credentials only reach exact GitHub API/upload origins and redirects are not followed', async () => {
  const requests = [];
  const api = publisher.createGitHubApi('fixture-token', async (url, options) => { requests.push({ url, options }); return { ok: true, status: 200, json: async () => ({}) }; });
  await api.get('/releases/tags/android-v0.1.2');
  assert.equal(requests[0].options.redirect, 'manual');
  assert.equal(requests[0].options.headers.Authorization, 'Bearer fixture-token');
  for (const url of ['https://attacker.invalid/repos/gohrayson/autopilot-android/releases', 'https://api.github.com.attacker.invalid/repos/gohrayson/autopilot-android/releases', 'https://api.github.com/repos/somebody/other/releases']) assert.throws(() => publisher.trustedApiUrl(url), /untrusted/);
  assert.throws(() => publisher.trustedUploadUrl('https://uploads.github.com/repos/gohrayson/other/releases/71/assets{?name,label}', 71, 'a.apk'), /untrusted/);
  await assert.rejects(publisher.createGitHubApi('fixture-token', async () => ({ ok: false, status: 302 })).get('/releases/tags/v1'), /HTTP 302/);
});
test('draft mode uploads and verifies all assets without publishing', async () => {
  const prepared = fixture(), fake = fakeApi(prepared);
  const result = await publisher.publishRelease(prepared, fake.api);
  assert.equal(result.published, false);
  assert.equal(fake.calls.filter(call => call[0] === 'UPLOAD').length, 4);
  assert.equal(fake.calls.some(call => call[0] === 'PATCH'), false);
  assert.equal(fake.calls.filter(call => call[0] === 'GET' && call[1].startsWith('/releases/assets/')).length, 4);
});
test('publish becomes visible only after every remote digest and final draft are verified', async () => {
  const prepared = fixture(), fake = fakeApi(prepared);
  const result = await publisher.publishRelease(prepared, fake.api, { publish: true });
  assert.equal(result.published, true);
  assert.deepEqual(fake.calls.at(-1), ['PATCH', '/releases/71', { draft: false, make_latest: 'true', body: prepared.manifest.notes }]);
  assert.deepEqual(fake.calls.at(-2), ['GET', '/releases/71']);
});
test('matching drafts resume without duplicate uploads; public releases are immutable', async () => {
  const prepared = fixture(), resumed = fakeApi(prepared, { draftExisting: true });
  await publisher.publishRelease(prepared, resumed.api, { publish: true });
  assert.equal(resumed.calls.some(call => call[0] === 'UPLOAD' || call[0] === 'POST'), false);
  const existing = fakeApi(prepared, { existingPublic: true });
  await assert.rejects(publisher.publishRelease(prepared, existing.api, { publish: true }), /overwrite existing public/);
  assert.equal(existing.calls.length, 1);
});
test('hash mismatch, foreign upload origin and duplicate draft assets block publication', async () => {
  const prepared = fixture();
  for (const options of [{ mismatch: true }, { badUploadOrigin: true }, { draftExisting: true, duplicate: true }]) {
    const fake = fakeApi(prepared, options);
    await assert.rejects(publisher.publishRelease(prepared, fake.api, { publish: true }));
    assert.equal(fake.calls.some(call => call[0] === 'PATCH'), false);
  }
});
