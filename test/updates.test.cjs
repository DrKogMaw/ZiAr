const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { createUpdateSource, compareVersions } = require('../desktop/updates.cjs');

test('local update folders select only newer, correctly named packages', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ziar-updates-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const apks = path.join(root, 'APKs');
  const exes = path.join(root, 'EXEs');
  fs.mkdirSync(apks); fs.mkdirSync(exes);
  const contents = Buffer.alloc(2048, 7);
  fs.writeFileSync(path.join(apks, 'ZiAr-Android-0.3.0.apk'), contents);
  fs.writeFileSync(path.join(apks, 'unrelated.apk'), contents);
  fs.writeFileSync(path.join(exes, 'ZiAr-Windows-Setup-0.2.0.exe'), contents);
  fs.writeFileSync(path.join(exes, 'ZiAr-Windows-Setup-0.4.0.exe'), contents);
  fs.writeFileSync(path.join(exes, 'ZiAr-Windows-Portable-9.0.0.exe'), contents);
  const source = createUpdateSource({ apkDirectory: apks, exeDirectory: exes, currentVersion: '0.3.0' });
  assert.equal(source.android().version, '0.3.0');
  assert.equal(source.android().sha256, crypto.createHash('sha256').update(contents).digest('hex'));
  assert.equal(source.windows().version, '0.4.0');
  assert.equal(compareVersions('0.10.0', '0.9.9'), 1);
  assert.equal(compareVersions('invalid', '0.1.0'), null);
});
