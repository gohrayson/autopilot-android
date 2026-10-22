'use strict';
// Deterministic hosted-emulator fixture. It never forwards traffic to the Internet or host VPN.
const net = require('node:net');
const dgram = require('node:dgram');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

async function startFixture(output, options = {}) {
  const socksPort = options.socksPort ?? 29480;
  const dnsPort = options.dnsPort ?? 29553;
  const state = { marker: 'AUTOPILOT_TUNNEL_OK_' + crypto.randomBytes(12).toString('hex'), socksPort, dnsPort,
    markerHits: 0, connections: [], dnsQueries: [], rejectedConnections: 0 };
  const sockets = new Set();
  function save() {
    if (!output) return;
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(output + '.tmp', JSON.stringify(state, null, 2));
    fs.renameSync(output + '.tmp', output);
  }
  const markerHosts = new Set(['203.0.113.7', '20010db8000000000000000000000007', 'tunnel-fixture.autopilot.test', 'fakeip-fixture.autopilot.test']);
  const socks = net.createServer(client => {
    sockets.add(client);client.on('close', () => sockets.delete(client));client.on('error', () => {});
    client.setTimeout(15000, () => client.destroy());
    let buffer = Buffer.alloc(0), stage = 'hello';
    function consume(chunk) {
      buffer = Buffer.concat([buffer, chunk]);
      if (stage === 'hello') {
        if (buffer.length < 2 || buffer.length < 2 + buffer[1]) return;
        if (buffer[0] !== 5 || !buffer.subarray(2, 2 + buffer[1]).includes(0)) return client.destroy();
        buffer = buffer.subarray(2 + buffer[1]);client.write(Buffer.from([5, 0]));stage = 'request';
      }
      if (stage === 'request') {
        if (buffer.length < 4) return;
        let end, host;
        if (buffer[3] === 1) { end = 10;if (buffer.length < end) return;host = [...buffer.subarray(4, 8)].join('.'); }
        else if (buffer[3] === 3) { if (buffer.length < 5) return;end = 7 + buffer[4];if (buffer.length < end) return;host = buffer.subarray(5, 5 + buffer[4]).toString(); }
        else if (buffer[3] === 4) { end = 22;if (buffer.length < end) return;host = buffer.subarray(4, 20).toString('hex'); }
        else return client.destroy();
        const port = buffer.readUInt16BE(end - 2);
        if (buffer[0] !== 5 || buffer[1] !== 1 || port !== 80 || !markerHosts.has(host)) {
          state.rejectedConnections++;save();client.end(Buffer.from([5, 2, 0, 1, 0, 0, 0, 0, 0, 0]));return;
        }
        state.connections.push({ host, port });save();
        buffer = buffer.subarray(end);stage = 'http';client.write(Buffer.from([5, 0, 0, 1, 127, 0, 0, 1, 0, 0]));
      }
      if (stage !== 'http' || !buffer.includes('\r\n\r\n')) return;
      stage = 'done';
      if (!/^(?:GET|HEAD) \/autopilot-probe(?:\?|\s)/.test(buffer.toString('ascii'))) return client.destroy();
      // A positive nonzero delay makes the core latency observable deterministic even on localhost.
      setTimeout(() => {
        if (client.destroyed) return;
        state.markerHits++;save();const body = Buffer.from(state.marker + '\n');
        client.end('HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ' + body.length + '\r\nConnection: close\r\n\r\n' + body);
      }, 20);
    }
    client.on('data', consume);
  });
  function dnsResponse(query, transport) {
    if (query.length < 17 || query.readUInt16BE(4) !== 1) throw new Error('Invalid DNS fixture question');
    let pos = 12;const labels = [];
    while (pos < query.length && query[pos] !== 0) {
      const size = query[pos++];if (size > 63 || pos + size > query.length) throw new Error('Invalid DNS fixture label');
      labels.push(query.subarray(pos, pos + size).toString('ascii').toLowerCase());pos += size;
    }
    pos++;if (pos + 4 > query.length) throw new Error('Truncated DNS fixture question');
    const type = query.readUInt16BE(pos), name = labels.join('.'), question = query.subarray(12, pos + 4);
    const known = name === 'proxy-fixture.autopilot.test' || name === 'tunnel-fixture.autopilot.test';
    const address = name === 'proxy-fixture.autopilot.test' ? [10, 0, 2, 2] : [203, 0, 113, 7];
    const answered = known && type === 1;
    state.dnsQueries.push({ name, type, transport, answered });save();
    const header = Buffer.alloc(12);query.copy(header, 0, 0, 2);header.writeUInt16BE(known ? 0x8180 : 0x8183, 2);
    header.writeUInt16BE(1, 4);header.writeUInt16BE(answered ? 1 : 0, 6);
    const answer = answered ? Buffer.from([0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 1, 0, 4, ...address]) : Buffer.alloc(0);
    return Buffer.concat([header, question, answer]);
  }
  const udp = dgram.createSocket('udp4');
  udp.on('message', (query, remote) => { try { udp.send(dnsResponse(query, 'udp'), remote.port, remote.address); } catch {} });
  const tcp = net.createServer(client => {
    sockets.add(client);client.on('close', () => sockets.delete(client));client.on('error', () => {});
    let buffer = Buffer.alloc(0);
    client.on('data', chunk => {
      buffer = Buffer.concat([buffer, chunk]);
      while (buffer.length >= 2 && buffer.length >= buffer.readUInt16BE(0) + 2) {
        const size = buffer.readUInt16BE(0), query = buffer.subarray(2, 2 + size);buffer = buffer.subarray(2 + size);
        try { const response = dnsResponse(query, 'tcp'), length = Buffer.alloc(2);length.writeUInt16BE(response.length);client.write(Buffer.concat([length, response])); }
        catch { client.destroy();return; }
      }
    });
  });
  function listen(server, port) { return new Promise((resolve, reject) => { server.once('error', reject);server.listen(port, '127.0.0.1', resolve); }); }
  try {
    await listen(socks, socksPort);
    await new Promise((resolve, reject) => { udp.once('error', reject);udp.bind(dnsPort, '127.0.0.1', resolve); });
    await listen(tcp, dnsPort);save();
  } catch (error) { socks.close();udp.close();tcp.close();throw error; }
  return { state, close: async () => { for (const socket of sockets) socket.destroy();await Promise.all([
    new Promise(resolve => socks.close(resolve)), new Promise(resolve => udp.close(resolve)), new Promise(resolve => tcp.close(resolve)),
  ]); } };
}
module.exports = { startFixture };
if (require.main === module) {
  startFixture(process.argv[2]).then(fixture => {
    console.log('HOSTED_TUN_FIXTURE_READY socks=29480 dns=29553 internetForwarding=false');
    for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => fixture.close().then(() => process.exit(0)));
  }).catch(error => { console.error(error.stack);process.exitCode = 1; });
}
