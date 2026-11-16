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
