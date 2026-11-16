const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const { createTransferServer, safeName } = require('../desktop/server.cjs');

test('upload, list, download, and duplicate filenames', async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'ziar-test-'));
  const { server } = createTransferServer({ directory, port: 0, code: '123456' });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.close(); fs.rmSync(directory, { recursive: true, force: true }); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const first = await fetch(base + '/api/upload?name=notes.txt', { method: 'POST', body: Buffer.from('hello') });
  assert.equal(first.status, 201);
  const second = await fetch(base + '/api/upload?name=notes.txt', { method: 'POST', body: Buffer.from('second') });
  assert.equal((await second.json()).name, 'notes (1).txt');
  const files = await (await fetch(base + '/api/files')).json();
  assert.equal(files.length, 2);
  assert.equal(await (await fetch(base + '/api/download?name=notes.txt')).text(), 'hello');
  assert.equal((await fetch(base + '/api/download?name=..%2Fsecret')).status, 400);
});

test('sanitizes upload names and rejects oversized declarations', async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'ziar-test-'));
  const { server } = createTransferServer({ directory, port: 0 });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.close(); fs.rmSync(directory, { recursive: true, force: true }); });
  assert.equal(safeName('..\\evil?.txt'), 'evil_.txt');
  const base = `http://127.0.0.1:${server.address().port}`;
  const status = await new Promise((resolve, reject) => {
    const request = http.request(base + '/api/upload?name=x', { method: 'POST', headers: { 'Content-Length': '1073741825' } }, response => { resolve(response.statusCode); response.resume(); });
    request.on('error', reject); request.end();
  });
  assert.equal(status, 413);
});
