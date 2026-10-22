#!/usr/bin/env node
'use strict';

// No dependencies, shell commands, credential files or private signing-key access.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const zlib = require('node:zlib');
const { spawnSync } = require('node:child_process');

const OWNER = 'gohrayson';
const REPO = 'autopilot-android';
const PACKAGE = 'app.autopilot.android';
const CERTIFICATE = '6c6831265f4d5593b499b2bfc1bbcaf93f3eb4d143f49fef192d811d4b6060ab';
const API_ROOT = `https://api.github.com/repos/${OWNER}/${REPO}`;
const REPO_ROOT = path.resolve(__dirname, '../..');
const MAX_APK = 200 * 1024 * 1024;
const MAX_WINDOWS = 512 * 1024 * 1024;
const MAX_GEO = 12 * 1024 * 1024;
const GEO_ASSET = 'android/app/src/main/assets/geoip.metadb';
const GEO_MANIFEST = 'android/app/src/main/assets/geoip-manifest.json';
const PRODUCT_CONTRACT = 'android/app/src/main/assets/product-contract.json';
const MAX_NOTES = 4096;
const MANIFEST_FIELDS = ['schemaVersion', 'packageName', 'versionCode', 'versionName', 'minSdk', 'apkUrl', 'sha256', 'sizeBytes', 'notes', 'publishedAt'];
const WINDOWS_FIELDS = ['schemaVersion', 'packageName', 'arch', 'versionName', 'installerUrl', 'sha256', 'sizeBytes', 'notes', 'publishedAt'];

function fail(message) { throw new Error(message); }
function sha256(bytes) { return crypto.createHash('sha256').update(bytes).digest('hex'); }
function unifiedVersion(version) { const [major, minor] = version.split('.').map(Number); return major > 0 || minor >= 4; }
function parseVersion(gradle) {
  const get = (expression, name) => {
    const matches = [...gradle.matchAll(expression)];
    if (matches.length !== 1) fail(`Expected one ${name} in app/build.gradle.`);
    return matches[0][1];
  };
  const version = {
    packageName: get(/\bapplicationId\s+'([^']+)'/g, 'applicationId'),
    versionName: get(/\bversionName\s+'([^']+)'/g, 'versionName'),
    versionCode: Number(get(/\bversionCode\s+(\d+)\b/g, 'versionCode')),
    minSdk: Number(get(/\bminSdk\s+(\d+)\b/g, 'minSdk')),
  };
  if (version.packageName !== PACKAGE || version.minSdk !== 26) fail('Unexpected application ID or minimum SDK.');
  if (!/^\d+\.\d+\.\d+$/.test(version.versionName) || version.versionName.split('.').some(n => /^0\d/.test(n))) fail('Expected a plain semantic release version.');
  if (!Number.isSafeInteger(version.versionCode) || version.versionCode < 1 || version.versionCode > 2147483647) fail('Invalid Android version code.');
  return version;
}

function validateManifest(manifest) {
  if (Object.keys(manifest).some(key => !MANIFEST_FIELDS.includes(key)) || MANIFEST_FIELDS.some(key => !(key in manifest))) fail('Unexpected update manifest fields.');
  if (manifest.schemaVersion !== 1 || manifest.packageName !== PACKAGE || manifest.minSdk !== 26) fail('Unexpected update manifest schema/package/SDK.');
  if (!Number.isInteger(manifest.versionCode) || manifest.versionCode < 1 || manifest.versionCode > 2147483647) fail('Invalid update version code.');
  if (!/^(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)$/.test(manifest.versionName)) fail('Invalid update version name.');
  const expectedUrl = `https://github.com/${OWNER}/${REPO}/releases/download/android-v${manifest.versionName}/Autopilot-Android-${manifest.versionName}.apk`;
  if (manifest.apkUrl !== expectedUrl) fail('Unexpected APK download URL.');
  if (!/^[0-9a-f]{64}$/.test(manifest.sha256)) fail('Invalid APK SHA-256.');
  if (!Number.isSafeInteger(manifest.sizeBytes) || manifest.sizeBytes < 1 || manifest.sizeBytes > MAX_APK) fail('Invalid APK size.');
  if (typeof manifest.notes !== 'string' || Buffer.byteLength(manifest.notes, 'utf8') > MAX_NOTES || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/.test(manifest.notes)) fail('Unsafe or oversized release notes.');
  if (typeof manifest.publishedAt !== 'string' || manifest.publishedAt.length > 64 || !/^\d{4}-\d{2}-\d{2}T/.test(manifest.publishedAt) || !Number.isFinite(Date.parse(manifest.publishedAt))) fail('Invalid publication timestamp.');
  return manifest;
}

function validateWindowsManifest(manifest) {
  if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest) || Object.keys(manifest).some(key => !WINDOWS_FIELDS.includes(key)) || WINDOWS_FIELDS.some(key => !(key in manifest))) fail('Unexpected Windows update manifest fields.');
  if (manifest.schemaVersion !== 1 || manifest.packageName !== 'local.autopilot.proxy' || manifest.arch !== 'x64') fail('Unexpected Windows update manifest identity.');
  if (!/^(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)$/.test(manifest.versionName)) fail('Invalid Windows update version name.');
  const expected = `https://github.com/${OWNER}/${REPO}/releases/download/android-v${manifest.versionName}/Autopilot-${manifest.versionName}-setup.exe`;
  if (manifest.installerUrl !== expected) fail('Unexpected Windows installer download URL.');
  if (!/^[0-9a-f]{64}$/.test(manifest.sha256) || !Number.isSafeInteger(manifest.sizeBytes) || manifest.sizeBytes < 1 || manifest.sizeBytes > MAX_WINDOWS) fail('Invalid Windows installer hash or size.');
  // Android notes are the shared release notes and retain their stricter original limit.
  if (typeof manifest.notes !== 'string' || Buffer.byteLength(manifest.notes, 'utf8') > MAX_NOTES || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/.test(manifest.notes)) fail('Unsafe or oversized Windows release notes.');
  if (typeof manifest.publishedAt !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(manifest.publishedAt) || !Number.isFinite(Date.parse(manifest.publishedAt))) fail('Invalid Windows publication timestamp.');
  return manifest;
}

function verifyUnifiedMetadata(version, desktop, contract, desktopContract) {
  if (!desktop || desktop.version !== version.versionName || desktop.productName !== 'Autopilot' || desktop.build?.appId !== 'local.autopilot.proxy' || desktop.build?.productName !== 'Autopilot') fail('Desktop package version/product identity does not match the unified Android release.');
  const targets = desktop.build?.win?.target;
  if (!Array.isArray(targets) || !targets.includes('nsis') || !targets.includes('portable')) fail('Unified release requires Windows setup and portable build targets.');
  let value; try { value = JSON.parse(Buffer.from(contract).toString('utf8')); } catch { fail('Invalid unified product contract.'); }
  if (value.schemaVersion !== 1 || value.productVersion !== version.versionName || value.androidVersionCode !== version.versionCode || !Buffer.from(contract).equals(Buffer.from(desktopContract))) fail('Unified product contract/version differs between Android and desktop.');
}

// Read NSIS VERSIONINFO string entries from the PE resources without running the EXE.
// These fields are generated by electron-builder and are not compressed with its payload.
function windowsResource(bytes, name) {
  const key = Buffer.from(`${name}\0`, 'utf16le'), values = [];
  for (let at = bytes.indexOf(key); at >= 0; at = bytes.indexOf(key, at + key.length)) {
    if (at < 6) continue;
    const start = at - 6, size = bytes.readUInt16LE(start), chars = bytes.readUInt16LE(start + 2), type = bytes.readUInt16LE(start + 4);
    const valueAt = Math.ceil((at + key.length) / 4) * 4;
    if (type !== 1 || chars < 1 || chars > 256 || size < valueAt - start + chars * 2 || start + size > bytes.length) continue;
    const text = bytes.subarray(valueAt, valueAt + chars * 2).toString('utf16le');
    if (text.endsWith('\0') && !text.slice(0, -1).includes('\0')) values.push(text.slice(0, -1));
  }
  if (values.length !== 1) fail(`Windows executable has missing or ambiguous ${name} identity.`);
  return values[0];
}

function verifyWindowsExecutable(bytes, version, kind) {
  if (!Buffer.isBuffer(bytes) || bytes.length < 64 || bytes.length > MAX_WINDOWS || bytes.subarray(0, 2).toString('ascii') !== 'MZ') fail(`Invalid Windows ${kind} executable header or size.`);
  const pe = bytes.readUInt32LE(0x3c);
  if (pe < 64 || pe + 24 > bytes.length || bytes.readUInt32LE(pe) !== 0x00004550 || bytes.indexOf(Buffer.from('efbeadde4e756c6c736f6674496e7374', 'hex')) < 0) fail(`Windows ${kind} is not a verified PE/NSIS package.`);
  if (windowsResource(bytes, 'ProductName') !== 'Autopilot' || windowsResource(bytes, 'FileVersion') !== version || windowsResource(bytes, 'ProductVersion') !== version) fail(`Windows ${kind} product/version identity does not match the unified release.`);
  return { sha256: sha256(bytes), sizeBytes: bytes.length };
}

function pinnedGeo(readLocal) {
  let value; try { value = JSON.parse(Buffer.from(readLocal(GEO_MANIFEST)).toString('utf8')); } catch { fail('Source ZIP geodata requires a valid local pinned manifest.'); }
  if (value.name !== 'geoip.metadb' || !Number.isSafeInteger(value.size) || value.size < 1 || value.size > MAX_GEO || !/^[a-f0-9]{64}$/.test(value.sha256)) fail('Invalid pinned source geodata size/hash/name.');
  return value;
}

function verifyApkMetadata(apksignerOutput, badging, version) {
  const certificates = [...apksignerOutput.matchAll(/Signer #(\d+) certificate SHA-256 digest:\s*([a-fA-F0-9]+)/g)];
  if (certificates.length !== 1 || certificates[0][2].toLowerCase() !== CERTIFICATE) fail('APK signer differs from the installed Autopilot release certificate.');
  const info = badging.match(/^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/m);
  // aapt2 35 emits minSdkVersion; older aapt emits sdkVersion. Require one
  // declaration across both spellings so duplicate/conflicting metadata fails.
  const sdkLines = badging.split(/\r?\n/).filter(line => /^(?:minSdkVersion|sdkVersion):/.test(line));
  const min = sdkLines.length === 1 ? sdkLines[0].match(/^(?:minSdkVersion|sdkVersion):'(\d+)'$/) : null;
  if (!info || !min || info[1] !== version.packageName || Number(info[2]) !== version.versionCode || info[3] !== version.versionName || Number(min[1]) !== version.minSdk) fail('Signed APK package/version/minSdk does not match app/build.gradle.');
  if (/^application-debuggable/m.test(badging)) fail('Refusing a debuggable APK.');
}

function runFile(file, args) {
  const result = spawnSync(file, args, { encoding: 'utf8', shell: false, windowsHide: true, maxBuffer: 2 * 1024 * 1024 });
  if (result.error || result.status !== 0) fail(`APK verification command failed: ${path.basename(file)} (exit ${result.status ?? 'unavailable'}).`);
  return result.stdout;
}

function findTools(root) {
  const local = path.join(root, '.data/android-tools');
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || path.join(local, 'sdk');
  let javaHome = process.env.JAVA_HOME;
  if (!javaHome) {
    const parents = fs.readdirSync(path.join(local, 'jdk'), { withFileTypes: true }).filter(entry => entry.isDirectory() && /^jdk-17\./.test(entry.name));
    if (parents.length !== 1) fail('Set JAVA_HOME to the Android build JDK.');
    javaHome = path.join(local, 'jdk', parents[0].name);
  }
  const windows = process.platform === 'win32';
  const toolDir = path.join(sdk, 'build-tools', '35.0.0');
  const tools = { java: path.join(javaHome, 'bin', windows ? 'java.exe' : 'java'), signer: path.join(toolDir, 'lib/apksigner.jar'), aapt: path.join(toolDir, windows ? 'aapt2.exe' : 'aapt2') };
  for (const file of Object.values(tools)) if (!fs.existsSync(file)) fail(`Required Android verification tool is missing: ${path.basename(file)}.`);
  return tools;
}

// Inspect central directory and inflate only source files; never extract the ZIP.
// The exporter excludes these names too. Rejecting them here prevents accidental
// publication even when someone supplies a hand-made source archive.
function verifySourceZip(bytes, version, readLocal) {
  if (bytes.length > 40 * 1024 * 1024) fail('Source ZIP exceeds the clean source limit.');
  let end = -1;
  for (let at = bytes.length - 22; at >= Math.max(0, bytes.length - 65557); at--) {
    if (bytes.readUInt32LE(at) === 0x06054b50 && at + 22 + bytes.readUInt16LE(at + 20) === bytes.length) { end = at; break; }
  }
  if (end < 0 || bytes.readUInt16LE(end + 4) !== 0 || bytes.readUInt16LE(end + 6) !== 0) fail('Invalid or multipart source ZIP.');
  const count = bytes.readUInt16LE(end + 10);
  const directorySize = bytes.readUInt32LE(end + 12);
  let at = bytes.readUInt32LE(end + 16);
  if (!count || count === 65535 || at + directorySize !== end) fail('Invalid source ZIP directory.');
  const names = new Set();
  let total = 0;
  for (let index = 0; index < count; index++) {
    if (at + 46 > end || bytes.readUInt32LE(at) !== 0x02014b50) fail('Invalid source ZIP entry.');
    const flags = bytes.readUInt16LE(at + 8), method = bytes.readUInt16LE(at + 10);
    const compressed = bytes.readUInt32LE(at + 20), size = bytes.readUInt32LE(at + 24);
    const length = bytes.readUInt16LE(at + 28), extra = bytes.readUInt16LE(at + 30), comment = bytes.readUInt16LE(at + 32);
    const localOffset = bytes.readUInt32LE(at + 42);
    const next = at + 46 + length + extra + comment;
    const name = bytes.subarray(at + 46, at + 46 + length).toString('utf8');
    if ([GEO_ASSET, GEO_MANIFEST].some(expected => name.toLowerCase() === expected && name !== expected)) fail('Unexpected source geodata path casing.');
    const entryLimit = name === GEO_ASSET ? MAX_GEO : 4 * 1024 * 1024;
    if (next > end || flags & 1 || ![0, 8].includes(method) || size > entryLimit || (total += size) > 40 * 1024 * 1024) fail('Unsafe source ZIP entry.');
    if (!name.startsWith('android/') || name.includes('\\') || name.split('/').some(part => !part || part === '.' || part === '..') || names.has(name.toLowerCase()) || /(?:^|\/)(?:build|\.gradle|\.data|\.git|node_modules)(?:\/|$)|(?:^|\/)local\.properties$|\.(?:aar|jks|keystore|enc|dpapi|log|apk|pem|p12|pfx)$|(?:^|\/)(?:\.env(?:\..*)?|settings\.json|credentials[^/]*|diagnostics[^/]*|probe-ready)$/i.test(name)) fail('Private, duplicate or unexpected file in source ZIP.');
    names.add(name.toLowerCase());
    if (localOffset + 30 > bytes.length || bytes.readUInt32LE(localOffset) !== 0x04034b50) fail('Invalid source ZIP local header.');
    const localNameSize = bytes.readUInt16LE(localOffset + 26), localExtraSize = bytes.readUInt16LE(localOffset + 28);
    const start = localOffset + 30 + localNameSize + localExtraSize;
    if (bytes.subarray(localOffset + 30, localOffset + 30 + localNameSize).toString('utf8') !== name || start + compressed > bytes.readUInt32LE(end + 16)) fail('Inconsistent source ZIP entry.');
    const body = bytes.subarray(start, start + compressed);
    const content = method === 0 ? body : zlib.inflateRawSync(body, { maxOutputLength: entryLimit });
    if (content.length !== size) fail('Incorrect source ZIP entry size.');
    const local = readLocal(name);
    if (!local || !Buffer.from(local).equals(content)) fail(`Source ZIP is stale or contains an unknown file: ${name}. Regenerate it with package-source.ps1.`);
    if (name === GEO_ASSET) { const pinned = pinnedGeo(readLocal); if (size !== pinned.size || sha256(content) !== pinned.sha256) fail('Source ZIP geodata differs from the pinned manifest.'); }
    if (name === 'android/app/build.gradle' && JSON.stringify(parseVersion(content.toString('utf8'))) !== JSON.stringify(version)) fail('Source ZIP version does not match the release.');
    at = next;
  }
  if (at !== end || !names.has('android/app/build.gradle') || !names.has('android/app/src/main/java/app/autopilot/android/vpnengine.java')) fail('Source ZIP is missing the Android application sources.');
  if ((unifiedVersion(version.versionName) || names.has(GEO_ASSET.toLowerCase())) && (!names.has(GEO_ASSET.toLowerCase()) || !names.has(GEO_MANIFEST.toLowerCase()))) fail('Unified source ZIP must include pinned geodata and its manifest.');
  return names.size;
}

function prepareRelease({ root = REPO_ROOT, notes = '', now, verifyApk, read = fs.readFileSync } = {}) {
  const version = parseVersion(read(path.join(root, 'android/app/build.gradle'), 'utf8'));
  const tag = `android-v${version.versionName}`;
  const apkName = `Autopilot-Android-${version.versionName}.apk`;
  const sourceName = `Autopilot-Android-${version.versionName}-source.zip`;
  const apkPath = path.join(root, 'dist/android', apkName);
  const apk = read(apkPath);
  if (!apk.length || apk.length > MAX_APK) fail('APK size is outside the updater limit.');
  if (verifyApk) verifyApk(apkPath, version);
  else {
    const tools = findTools(root);
    verifyApkMetadata(runFile(tools.java, ['-jar', tools.signer, 'verify', '--verbose', '--print-certs', apkPath]), runFile(tools.aapt, ['dump', 'badging', apkPath]), version);
  }
  const source = read(path.join(root, 'dist/android', sourceName));
  const sourceEntries = verifySourceZip(source, version, name => { try { return read(path.join(root, ...name.split('/'))); } catch { return null; } });
  // Stable across draft retries, without writing mutable manifest files locally.
  const publishedAt = (now || new Date(fs.statSync(apkPath).mtimeMs)).toISOString();
  const manifest = validateManifest({ schemaVersion: 1, ...version, apkUrl: `https://github.com/${OWNER}/${REPO}/releases/download/${tag}/${apkName}`, sha256: sha256(apk), sizeBytes: apk.length, notes, publishedAt });
  const manifestBytes = Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`);
  const assets = [
    { name: apkName, bytes: apk, type: 'application/vnd.android.package-archive' },
    { name: 'latest.json', bytes: manifestBytes, type: 'application/json' },
    { name: sourceName, bytes: source, type: 'application/zip' },
  ];
  let windowsManifest;
  if (unifiedVersion(version.versionName)) {
    verifyUnifiedMetadata(version, JSON.parse(read(path.join(root, 'package.json'), 'utf8')), read(path.join(root, ...PRODUCT_CONTRACT.split('/'))), read(path.join(root, 'src/shared/product-contract.json')));
    const setupName = `Autopilot-${version.versionName}-setup.exe`, portableName = `Autopilot-${version.versionName}-portable.exe`;
    const setup = read(path.join(root, 'dist', setupName)), portable = read(path.join(root, 'dist', portableName));
    const setupInfo = verifyWindowsExecutable(setup, version.versionName, 'setup');
    verifyWindowsExecutable(portable, version.versionName, 'portable');
    windowsManifest = validateWindowsManifest({ schemaVersion: 1, packageName: 'local.autopilot.proxy', arch: 'x64', versionName: version.versionName,
      installerUrl: `https://github.com/${OWNER}/${REPO}/releases/download/${tag}/${setupName}`, ...setupInfo, notes, publishedAt });
    assets.push({ name: setupName, bytes: setup, type: 'application/vnd.microsoft.portable-executable' },
      { name: portableName, bytes: portable, type: 'application/vnd.microsoft.portable-executable' },
      { name: 'windows-latest.json', bytes: Buffer.from(`${JSON.stringify(windowsManifest, null, 2)}\n`), type: 'application/json' });
  }
  const checksums = assets.map(asset => `${sha256(asset.bytes)}  ${asset.name}`).join('\n') + '\n';
  assets.push({ name: 'SHA256SUMS', bytes: Buffer.from(checksums), type: 'text/plain' });
  for (const asset of assets) { asset.sha256 = sha256(asset.bytes); asset.size = asset.bytes.length; }
  return { tag, manifest, ...(windowsManifest ? { windowsManifest } : {}), assets, sourceEntries };
}

function credentials(env = process.env, execute = spawnSync) {
  const token = env.GH_TOKEN || env.GITHUB_TOKEN;
  if (token) return token;
  const result = execute('git', ['credential', 'fill'], { input: 'protocol=https\nhost=github.com\n\n', encoding: 'utf8', shell: false, windowsHide: true, env: { ...env, GIT_TERMINAL_PROMPT: '0', GCM_INTERACTIVE: 'Never' }, maxBuffer: 1024 * 1024 });
  if (result.error || result.status !== 0) fail('No noninteractive GitHub credential is available. Set GH_TOKEN or sign in with Git Credential Manager.');
  const password = result.stdout.split(/\r?\n/).find(line => line.startsWith('password='));
  if (!password || !password.slice(9)) fail('Git Credential Manager returned no GitHub credential.');
  return password.slice(9);
}

function trustedApiUrl(input) {
  const url = new URL(input);
  if (url.protocol !== 'https:' || url.hostname !== 'api.github.com' || url.port || url.username || url.password || url.hash || !url.pathname.startsWith(`/repos/${OWNER}/${REPO}/`)) fail('Refusing an untrusted GitHub API URL.');
  return url;
}

function trustedUploadUrl(template, releaseId, assetName) {
  if (!Number.isSafeInteger(releaseId) || releaseId < 1) fail('Invalid GitHub release ID.');
  const clean = template.replace(/\{\?name,label\}$/, '');
  const url = new URL(clean);
  if (url.protocol !== 'https:' || url.hostname !== 'uploads.github.com' || url.port || url.username || url.password || url.search || url.hash || url.pathname !== `/repos/${OWNER}/${REPO}/releases/${releaseId}/assets`) fail('Refusing an untrusted GitHub upload URL.');
  url.searchParams.set('name', assetName);
  return url;
}

function createGitHubApi(token, transport = fetch) {
  async function request(url, options = {}, upload = false) {
    if (upload) {
      const parsed = new URL(url);
      if (parsed.hostname !== 'uploads.github.com' || parsed.protocol !== 'https:' || parsed.username || parsed.password || parsed.port || !parsed.pathname.startsWith(`/repos/${OWNER}/${REPO}/releases/`)) fail('Refusing an untrusted asset upload.');
    } else trustedApiUrl(url);
    const response = await transport(url.toString(), { ...options, headers: { Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'Autopilot-Android-Release', ...(options.headers || {}), Authorization: `Bearer ${token}` }, redirect: 'manual', signal: AbortSignal.timeout(upload ? 180000 : 60000) });
    if (response.status === 404 && options.allow404) return null;
    if (!response.ok) fail(`GitHub request failed (HTTP ${response.status}); release remains unpublished unless publication had already succeeded.`);
    return response.json();
  }
  return {
    get: suffix => request(`${API_ROOT}${suffix}`, { allow404: true }),
    post: (suffix, value) => request(`${API_ROOT}${suffix}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) }),
    patch: (suffix, value) => request(`${API_ROOT}${suffix}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) }),
    upload: (template, releaseId, asset) => request(trustedUploadUrl(template, releaseId, asset.name), { method: 'POST', headers: { 'Content-Type': asset.type, 'Content-Length': String(asset.size) }, body: asset.bytes }, true),
  };
}

function verifyAsset(remote, local, releaseId) {
  if (!remote || !Number.isSafeInteger(remote.id) || remote.id < 1 || remote.name !== local.name || remote.state !== 'uploaded' || remote.size !== local.size || remote.digest !== `sha256:${local.sha256}`) fail(`GitHub asset is missing, incomplete or differs from the verified local file: ${local.name}. Nothing was published.`);
  const expected = `https://github.com/${OWNER}/${REPO}/releases/download/`;
  if (remote.browser_download_url && !remote.browser_download_url.startsWith(expected)) fail('Unexpected uploaded asset download origin.');
  return remote;
}

async function publishRelease(prepared, api, { publish = false } = {}) {
  let release = await api.get(`/releases/tags/${encodeURIComponent(prepared.tag)}`);
  if (release && release.draft !== true) fail(`Refusing to overwrite existing public release ${prepared.tag}. Increment the version before publishing.`);
  if (!release) release = await api.post('/releases', { tag_name: prepared.tag, name: `Autopilot${prepared.windowsManifest ? '' : ' Android'} ${prepared.manifest.versionName}`, body: prepared.manifest.notes, draft: true, prerelease: false });
  if (release.draft !== true || release.tag_name !== prepared.tag || !Number.isSafeInteger(release.id) || release.id < 1 || release.url !== `${API_ROOT}/releases/${release.id}`) fail('Unexpected GitHub draft release identity.');
  trustedUploadUrl(release.upload_url, release.id, 'validation');
  const existing = await api.get(`/releases/${release.id}/assets?per_page=100`);
  if (!Array.isArray(existing) || existing.length >= 100) fail('Cannot safely enumerate draft assets.');
  const wanted = new Set(prepared.assets.map(asset => asset.name));
  if (existing.some(asset => !wanted.has(asset.name)) || new Set(existing.map(asset => asset.name)).size !== existing.length) fail('Draft contains unexpected or duplicate assets; inspect it manually before continuing.');
  for (const asset of prepared.assets) {
    let remote = existing.find(item => item.name === asset.name);
    if (!remote) remote = await api.upload(release.upload_url, release.id, asset);
    verifyAsset(remote, asset, release.id);
    verifyAsset(await api.get(`/releases/assets/${remote.id}`), asset, release.id);
  }
  const completed = await api.get(`/releases/${release.id}`);
  if (!completed || completed.draft !== true || completed.tag_name !== prepared.tag || !Array.isArray(completed.assets) || completed.assets.length !== prepared.assets.length) fail('Draft changed before final verification.');
  for (const asset of prepared.assets) verifyAsset(completed.assets.find(remote => remote.name === asset.name), asset, release.id);
  if (publish) {
    release = await api.patch(`/releases/${release.id}`, { draft: false, make_latest: 'true', body: prepared.manifest.notes });
    if (release.draft !== false || release.tag_name !== prepared.tag) fail('Unexpected GitHub publication response; inspect the release before retrying.');
  }
  return { published: publish, releaseUrl: `https://github.com/${OWNER}/${REPO}/releases/tag/${prepared.tag}`, updateUrl: `https://github.com/${OWNER}/${REPO}/releases/latest/download/latest.json`,
    ...(prepared.windowsManifest ? { windowsUpdateUrl: `https://github.com/${OWNER}/${REPO}/releases/latest/download/windows-latest.json` } : {}) };
}

function parseArgs(args) {
  const options = { mode: 'dry-run', notesFile: null };
  let modeSeen = false;
  for (let i = 0; i < args.length; i++) {
    if (['--dry-run', '--draft', '--publish'].includes(args[i])) {
      if (modeSeen) fail('Choose only one of --dry-run, --draft or --publish.');
      options.mode = args[i].slice(2); modeSeen = true;
    } else if (args[i] === '--notes-file' && args[i + 1] && !options.notesFile) options.notesFile = args[++i];
    else fail(`Unknown or incomplete argument: ${args[i]}.`);
  }
  return options;
}

async function main(args = process.argv.slice(2)) {
  const options = parseArgs(args);
  const notes = options.notesFile ? fs.readFileSync(path.resolve(options.notesFile), 'utf8').replace(/^\ufeff/, '') : '';
  const prepared = prepareRelease({ notes });
  const summary = { mode: options.mode, manifest: prepared.manifest, ...(prepared.windowsManifest ? { windowsManifest: prepared.windowsManifest } : {}), sourceEntries: prepared.sourceEntries, assets: prepared.assets.map(({ name, size, sha256: hash }) => ({ name, sizeBytes: size, sha256: hash })) };
  if (options.mode !== 'dry-run') {
    const result = await publishRelease(prepared, createGitHubApi(credentials()), { publish: options.mode === 'publish' });
    Object.assign(summary, result);
  }
  process.stdout.write(`${JSON.stringify(summary, null, 2)}\n`);
}

module.exports = { parseVersion, validateManifest, validateWindowsManifest, verifyUnifiedMetadata, verifyWindowsExecutable, verifyApkMetadata, verifySourceZip, prepareRelease, credentials, trustedApiUrl, trustedUploadUrl, createGitHubApi, verifyAsset, publishRelease, parseArgs, main, CERTIFICATE };
if (require.main === module) main().catch(error => { process.stderr.write(`Release publisher: ${error.message}\n`); process.exitCode = 1; });
