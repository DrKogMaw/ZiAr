const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const { createTransferServer, safeName } = require('../desktop/server.cjs');
const { broadcastAddresses, MESSAGE } = require('../desktop/discovery.cjs');

test('advertises a marker without the pairing code on local broadcasts', () => {
  assert.equal(MESSAGE.toString(), 'ZIAR/1');
  assert.deepEqual(broadcastAddresses({ wifi: [{ family: 'IPv4', internal: false, address: '192.168.1.9', netmask: '255.255.255.0' }] }), ['255.255.255.255', '192.168.1.255']);
});

test('PC shows connected devices and enforces one-device or multiple-device pairing', async t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ziar-test-'));
  const { server } = createTransferServer({ pendingDirectory: path.join(root, 'pending'), receivedDirectory: path.join(root, 'received'), port: 0, code: '123456',
    isDesktopRequest: (_peer, request) => request.headers['x-test-desktop'] === 'yes' });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.close(); fs.rmSync(root, { recursive: true, force: true }); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const pcHeaders = { 'X-Test-Desktop': 'yes' };
  const pair = id => fetch(base + '/api/pair', { method: 'POST', body: JSON.stringify({ code: '123456', deviceId: id, deviceName: id }) });
  const first = await pair('device-one');
  const firstCookie = first.headers.get('set-cookie').split(';')[0];
  assert.equal(first.status, 200);
  assert.equal((await pair('device-two')).status, 409);
  assert.equal((await (await fetch(base + '/api/devices', { headers: pcHeaders })).json()).devices.length, 1);
  assert.equal((await fetch(base + '/api/devices', { headers: { Cookie: firstCookie } })).status, 403);
  assert.equal((await fetch(base + '/api/mode', { method: 'POST', headers: pcHeaders, body: 'multiple' })).status, 200);
  const second = await pair('device-two');
  const secondCookie = second.headers.get('set-cookie').split(';')[0];
  assert.equal(second.status, 200);
  assert.equal((await (await fetch(base + '/api/devices', { headers: pcHeaders })).json()).devices.length, 2);
  assert.equal((await fetch(base + '/api/mode', { method: 'POST', headers: pcHeaders, body: 'single' })).status, 200);
  assert.equal((await (await fetch(base + '/api/devices', { headers: pcHeaders })).json()).devices.length, 1);
  const statuses = await Promise.all([firstCookie, secondCookie].map(cookie => fetch(base + '/api/files', { headers: { Cookie: cookie } }).then(response => response.status)));
  assert.deepEqual(statuses.sort(), [200, 401]);
});

test('a phone can finish choosing files after its presence indicator times out', async t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ziar-test-'));
  let clock = 100000;
  const { server } = createTransferServer({ pendingDirectory: path.join(root, 'pending'), receivedDirectory: path.join(root, 'received'), port: 0, code: '123456', now: () => clock,
    isDesktopRequest: (_peer, request) => request.headers['x-test-desktop'] === 'yes' });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.close(); fs.rmSync(root, { recursive: true, force: true }); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const pair = id => fetch(base + '/api/pair', { method: 'POST', body: JSON.stringify({ code: '123456', deviceId: id, deviceName: id }) });
  const first = await pair('device-one');
  const firstCookie = first.headers.get('set-cookie').split(';')[0];
  clock += 21000;
  const devices = await (await fetch(base + '/api/devices', { headers: { 'X-Test-Desktop': 'yes' } })).json();
  assert.equal(devices.devices.length, 0);
  const upload = await fetch(base + '/api/upload?name=selected.txt', { method: 'POST', headers: { Cookie: firstCookie }, body: 'selected' });
  assert.equal(upload.status, 201);
  assert.equal(fs.readFileSync(path.join(root, 'received', 'selected.txt'), 'utf8'), 'selected');
  const chunkedStatus = await new Promise((resolve, reject) => {
    const request = http.request(base + '/api/upload?name=chunked.txt', { method: 'POST', headers: { Cookie: firstCookie } }, response => { resolve(response.statusCode); response.resume(); });
    request.on('error', reject); request.write('from '); request.end('Android');
  });
  assert.equal(chunkedStatus, 201);
  assert.equal(fs.readFileSync(path.join(root, 'received', 'chunked.txt'), 'utf8'), 'from Android');
  clock += 21000;
  assert.equal((await pair('device-two')).status, 200);
  assert.equal((await fetch(base + '/api/files', { headers: { Cookie: firstCookie } })).status, 401);
});

test('PC sends once to phone, and phone saves received files without a shared history', async t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ziar-test-'));
  const pendingDirectory = path.join(root, 'pending');
  const receivedDirectory = path.join(root, 'received');
  const pc = createTransferServer({ pendingDirectory, receivedDirectory, port: 0, code: '123456' });
  const phone = createTransferServer({ pendingDirectory, receivedDirectory, port: 0, code: '123456', isDesktopRequest: () => false });
  await Promise.all([new Promise(resolve => pc.server.listen(0, '127.0.0.1', resolve)), new Promise(resolve => phone.server.listen(0, '127.0.0.1', resolve))]);
  t.after(() => { pc.server.close(); phone.server.close(); fs.rmSync(root, { recursive: true, force: true }); });
  const pcUrl = `http://127.0.0.1:${pc.server.address().port}`;
  const phoneUrl = `http://127.0.0.1:${phone.server.address().port}`;
  assert.equal((await fetch(phoneUrl + '/api/files')).status, 401);
  const pair = await fetch(phoneUrl + '/api/pair', { method: 'POST', body: '123456' });
  const headers = { Cookie: pair.headers.get('set-cookie').split(';')[0] };
  assert.equal(pair.status, 200);
  assert.equal((await fetch(pcUrl + '/api/upload?name=notes.txt', { method: 'POST', body: Buffer.from('hello') })).status, 201);
  assert.equal((await (await fetch(phoneUrl + '/api/files', { headers })).json()).length, 1);
  assert.equal(await (await fetch(phoneUrl + '/api/download?name=notes.txt', { headers })).text(), 'hello');
  for (let i = 0; i < 20 && fs.existsSync(path.join(pendingDirectory, 'notes.txt')); i++) await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal((await (await fetch(phoneUrl + '/api/files', { headers })).json()).length, 0);
  assert.equal((await fetch(phoneUrl + '/api/download?name=notes.txt', { headers })).status, 404);
  assert.equal((await fetch(phoneUrl + '/api/download?name=..%2Fsecret', { headers })).status, 400);
  const received = await fetch(phoneUrl + '/api/upload?name=from-phone.txt', { method: 'POST', headers, body: Buffer.from('mobile') });
  assert.equal(received.status, 201);
  assert.equal(fs.readFileSync(path.join(receivedDirectory, 'from-phone.txt'), 'utf8'), 'mobile');
  assert.equal((await (await fetch(phoneUrl + '/api/files', { headers })).json()).length, 0);
});

test('sanitizes upload names and rejects oversized declarations', async t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ziar-test-'));
  const { server } = createTransferServer({ pendingDirectory: path.join(root, 'pending'), receivedDirectory: path.join(root, 'received'), port: 0 });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.close(); fs.rmSync(root, { recursive: true, force: true }); });
  assert.equal(safeName('..\\evil?.txt'), 'evil_.txt');
  const base = `http://127.0.0.1:${server.address().port}`;
  const status = await new Promise((resolve, reject) => {
    const request = http.request(base + '/api/upload?name=x', { method: 'POST', headers: { 'Content-Length': '1073741825' } }, response => { resolve(response.statusCode); response.resume(); });
    request.on('error', reject); request.end();
  });
  assert.equal(status, 413);
});
