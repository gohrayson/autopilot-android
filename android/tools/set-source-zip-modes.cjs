'use strict';

// Only ZIP metadata changes. Validate the complete single-disk, non-ZIP64 layout
// produced by package-source.ps1 before writing any bytes back to the archive.
const fs = require('node:fs');

function setSourceZipModes(filename) {
  if (fs.statSync(filename).size > 0xffffffff) throw new Error('ZIP64 source archives are unsupported');
  const zip = fs.readFileSync(filename);
  const reject = reason => { throw new Error(`Invalid source ZIP: ${reason}`); };
  const within = (offset, length, end = zip.length) => {
    if (!Number.isSafeInteger(offset) || offset < 0 || length < 0 || offset + length > end) reject('truncated record');
  };

  let eocd = -1;
  for (let offset = zip.length - 22; offset >= Math.max(0, zip.length - 22 - 0xffff); offset--) {
    if (zip.readUInt32LE(offset) === 0x06054b50 && offset + 22 + zip.readUInt16LE(offset + 20) === zip.length) {
      eocd = offset;
      break;
    }
  }
  if (eocd < 0) reject('missing end-of-central-directory record');
  const count = zip.readUInt16LE(eocd + 10);
  const centralSize = zip.readUInt32LE(eocd + 12);
  const centralOffset = zip.readUInt32LE(eocd + 16);
  if (zip.readUInt16LE(eocd + 4) !== 0 || zip.readUInt16LE(eocd + 6) !== 0 || zip.readUInt16LE(eocd + 8) !== count) reject('multi-disk archive');
  if (count === 0xffff || centralSize === 0xffffffff || centralOffset === 0xffffffff) reject('ZIP64 archive');
  if (count === 0 || centralOffset + centralSize !== eocd) reject('unexpected central-directory layout');
  within(centralOffset, centralSize, eocd);

  const entries = [];
  const names = new Set();
  let cursor = centralOffset;
  for (let index = 0; index < count; index++) {
    within(cursor, 46, eocd);
    if (zip.readUInt32LE(cursor) !== 0x02014b50) reject('unexpected central-directory record');
    const flags = zip.readUInt16LE(cursor + 8);
    const compressedSize = zip.readUInt32LE(cursor + 20);
    const size = zip.readUInt32LE(cursor + 24);
    const nameLength = zip.readUInt16LE(cursor + 28);
    const recordLength = 46 + nameLength + zip.readUInt16LE(cursor + 30) + zip.readUInt16LE(cursor + 32);
    const localOffset = zip.readUInt32LE(cursor + 42);
    within(cursor, recordLength, eocd);
    if (compressedSize === 0xffffffff || size === 0xffffffff || localOffset === 0xffffffff) reject('ZIP64 entry');
    if (zip.readUInt16LE(cursor + 34) !== 0 || (flags & 1)) reject('split or encrypted entry');
    const nameBytes = zip.subarray(cursor + 46, cursor + 46 + nameLength);
    const name = new TextDecoder('utf-8', { fatal: true }).decode(nameBytes);
    if (!name.startsWith('android/') || name.includes('\\') || name.split('/').some(part => !part || part === '.' || part === '..') || names.has(name.toLowerCase())) reject('unexpected or duplicate filename');
    names.add(name.toLowerCase());

    within(localOffset, 30, centralOffset);
    if (zip.readUInt32LE(localOffset) !== 0x04034b50) reject('missing local file header');
    const localNameLength = zip.readUInt16LE(localOffset + 26);
    const payloadOffset = localOffset + 30 + localNameLength + zip.readUInt16LE(localOffset + 28);
    within(localOffset, payloadOffset - localOffset + compressedSize, centralOffset);
    if (zip.readUInt16LE(localOffset + 6) !== flags || zip.readUInt16LE(localOffset + 8) !== zip.readUInt16LE(cursor + 10) || !zip.subarray(localOffset + 30, localOffset + 30 + localNameLength).equals(nameBytes)) reject('local and central headers differ');
    let localEnd = payloadOffset + compressedSize;
    if (flags & 8) {
      // Streaming ZIP writers can append a data descriptor with an optional signature.
      within(localEnd, 12, centralOffset);
      const descriptor = zip.readUInt32LE(localEnd) === 0x08074b50 ? localEnd + 4 : localEnd;
      within(descriptor, 12, centralOffset);
      if (zip.readUInt32LE(descriptor) !== zip.readUInt32LE(cursor + 16) || zip.readUInt32LE(descriptor + 4) !== compressedSize || zip.readUInt32LE(descriptor + 8) !== size) reject('data descriptor differs');
      localEnd = descriptor + 12;
    } else if (zip.readUInt32LE(localOffset + 14) !== zip.readUInt32LE(cursor + 16) || zip.readUInt32LE(localOffset + 18) !== compressedSize || zip.readUInt32LE(localOffset + 22) !== size) {
      reject('local file sizes or checksum differ');
    }
    entries.push({ offset: cursor, localOffset, localEnd, mode: name === 'android/gradlew' || name.endsWith('.sh') ? 0o100755 : 0o100644 });
    cursor += recordLength;
  }
  if (cursor !== eocd) reject('extra central-directory records');
  const localEntries = [...entries].sort((left, right) => left.localOffset - right.localOffset);
  let localCursor = 0;
  for (const entry of localEntries) {
    if (entry.localOffset !== localCursor) reject('unexpected or overlapping local records');
    localCursor = entry.localEnd;
  }
  if (localCursor !== centralOffset) reject('unexpected data before central directory');

  for (const entry of entries) {
    zip[entry.offset + 5] = 3; // Unix creator OS; retain the ZIP writer version.
    zip.writeUInt32LE((entry.mode * 0x10000) >>> 0, entry.offset + 38);
  }
  fs.writeFileSync(filename, zip);
  console.log(`Source ZIP Unix modes verified and set: ${entries.length} files`);
}

if (require.main === module) {
  if (process.argv.length !== 3) throw new Error('Usage: node set-source-zip-modes.cjs <source.zip>');
  setSourceZipModes(process.argv[2]);
}
