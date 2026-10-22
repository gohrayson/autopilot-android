'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const net = require('node:net');
const dgram = require('node:dgram');
const { startFixture } = require('./ci-tunnel-fixture.cjs');

function dnsQuery(name, port) {
  return new Promise((resolve, reject) => {
    const socket = dgram.createSocket('udp4'), header = Buffer.from([0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0]);
    const labels = name.split('.').map(label => Buffer.concat([Buffer.from([label.length]), Buffer.from(label)]));
    const query = Buffer.concat([header, ...labels, Buffer.from([0, 0, 1, 0, 1])]);
    const timer = setTimeout(() => { socket.close();reject(new Error('DNS fixture timeout')); }, 3000);
    socket.once('error', error => { clearTimeout(timer);socket.close();reject(error); });
    socket.once('message', response => { clearTimeout(timer);socket.close();resolve(response); });
    socket.send(query, port, '127.0.0.1');
  });
}
function socksRequest(address, port, denied = false) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, '127.0.0.1');let stage = 0, buffer = Buffer.alloc(0);
    socket.setTimeout(3000, () => socket.destroy(new Error('SOCKS fixture timeout')));socket.once('error', reject);
    socket.once('connect', () => socket.write(Buffer.from([5, 1, 0])));
    socket.on('data', chunk => {
      buffer = Buffer.concat([buffer, chunk]);
      if (stage === 0 && buffer.length >= 2) {
        assert.deepEqual([...buffer.subarray(0, 2)], [5, 0]);buffer = buffer.subarray(2);stage = 1;
        socket.write(Buffer.concat([Buffer.from([5, 1, 0]), address, Buffer.from([0, 80])]));
      }
      if (stage === 1 && buffer.length >= 10) {
        if (denied) { assert.equal(buffer[1], 2);socket.destroy();resolve('DENIED');return; }
        assert.equal(buffer[1], 0);buffer = buffer.subarray(10);stage = 2;
        socket.write('GET /autopilot-probe HTTP/1.1\r\nHost: tunnel-fixture.autopilot.test\r\nConnection: close\r\n\r\n');
      }
    });
    socket.once('end', () => { socket.destroy();if (!denied) resolve(buffer.toString()); });
  });
}

test('hosted TUN fixture resolves fixed DNS names and serves reserved IPv4/IPv6 and domain SOCKS markers', async t => {
  const fixture = await startFixture(null, { socksPort: 39480, dnsPort: 39553 });t.after(() => fixture.close());
  const endpoint = await dnsQuery('proxy-fixture.autopilot.test', 39553);
  assert.equal(endpoint.readUInt16BE(0), 0x1234);assert.equal(endpoint.readUInt16BE(6), 1);assert.deepEqual([...endpoint.subarray(-4)], [10, 0, 2, 2]);
  const application = await dnsQuery('tunnel-fixture.autopilot.test', 39553);
  assert.deepEqual([...application.subarray(-4)], [203, 0, 113, 7]);
  const unknown = await dnsQuery('never-forward.example', 39553);assert.equal(unknown.readUInt16BE(2) & 15, 3);assert.equal(unknown.readUInt16BE(6), 0);
  const ipv4 = await socksRequest(Buffer.from([1, 203, 0, 113, 7]), 39480);
  const ipv6 = await socksRequest(Buffer.concat([Buffer.from([4]), Buffer.from('20010db8000000000000000000000007', 'hex')]), 39480);
  const hostname = Buffer.from('fakeip-fixture.autopilot.test');
  const domain = await socksRequest(Buffer.concat([Buffer.from([3, hostname.length]), hostname]), 39480);
  for (const result of [ipv4, ipv6, domain]) { assert.match(result, /^HTTP\/1.1 200 OK/);assert.ok(result.endsWith(fixture.state.marker + '\n')); }
  assert.equal(await socksRequest(Buffer.from([1, 1, 1, 1, 1]), 39480, true), 'DENIED');
  assert.equal(fixture.state.markerHits, 3);assert.equal(fixture.state.rejectedConnections, 1);assert.equal(fixture.state.dnsQueries.length, 3);
  assert.ok(fixture.state.connections.some(connection => connection.host === 'fakeip-fixture.autopilot.test'));
});
