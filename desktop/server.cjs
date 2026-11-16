const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');

const PORT = 47831;
const MAX_FILE_BYTES = 1024 * 1024 * 1024;
const MAX_FILES = 1000;
const SESSION_IDLE_MS = 20000;
const INDEX = fs.readFileSync(path.join(__dirname, 'index.html'));

function localAddresses() {
  return Object.values(os.networkInterfaces()).flat().filter(a => a && a.family === 'IPv4' && !a.internal).map(a => a.address);
}

function safeName(name) {
  const cleaned = path.basename(String(name).replaceAll('\\', '/')).replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').replace(/[. ]+$/g, '').slice(0, 180);
  return cleaned && cleaned !== '.' && cleaned !== '..' ? cleaned : 'file';
}

function uniquePath(dir, name) {
  const parsed = path.parse(safeName(name));
  let candidate = path.join(dir, parsed.base);
  for (let i = 1; fs.existsSync(candidate); i++) candidate = path.join(dir, `${parsed.name} (${i})${parsed.ext}`);
  return candidate;
}

function isLoopback(peer) {
  return peer === '127.0.0.1' || peer === '::1' || peer === '::ffff:127.0.0.1';
}

function createTransferServer({ pendingDirectory, receivedDirectory, port = PORT, code = String(crypto.randomInt(100000, 1000000)), isDesktopRequest = isLoopback, initialMode = 'single', onModeChange = () => {} }) {
  fs.mkdirSync(pendingDirectory, { recursive: true });
  fs.mkdirSync(receivedDirectory, { recursive: true });
  let mode = initialMode === 'multiple' ? 'multiple' : 'single';
  const sessions = new Map();
  const attempts = new Map();
  const pruneSessions = () => {
    for (const [token, device] of sessions) if (Date.now() - device.lastSeen > SESSION_IDLE_MS) sessions.delete(token);
  };
  const activeDevices = () => [...sessions.values()].sort((a, b) => b.lastSeen - a.lastSeen);
  const sendJson = (res, status, value) => {
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(value));
  };
  const server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://localhost');
      const peer = req.socket.remoteAddress || '';
      const loopback = isDesktopRequest(peer, req);
      pruneSessions();
      const session = /(?:^|; )ziar=([a-f0-9]{64})(?:;|$)/.exec(req.headers.cookie || '')?.[1];
      const device = session && sessions.get(session);
      const authorized = loopback || !!device;
      if (device) device.lastSeen = Date.now();
      if (req.method === 'GET' && url.pathname === '/') {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
        return res.end(INDEX);
      }
      if (req.method === 'POST' && url.pathname === '/api/pair') {
        const prior = attempts.get(peer) || { count: 0, until: 0 };
        if (Date.now() < prior.until) return sendJson(res, 429, { error: 'Too many attempts. Try again in one minute.' });
        let input = '';
        for await (const chunk of req) {
          input += chunk;
          if (input.length > 512) return sendJson(res, 413, { error: 'Invalid pairing request.' });
        }
        let pairing;
        try { pairing = JSON.parse(input); } catch { /* Older clients send the code as plain text. */ }
        if (!pairing || typeof pairing !== 'object') pairing = { code: input.trim(), deviceId: `legacy-${peer}`, deviceName: 'Android device' };
        if (pairing.code !== code) {
          prior.count++;
          if (prior.count >= 5) { prior.count = 0; prior.until = Date.now() + 60000; }
          attempts.set(peer, prior);
          return sendJson(res, 401, { error: 'Incorrect pairing code.' });
        }
        attempts.delete(peer);
        const deviceId = typeof pairing.deviceId === 'string' && /^[a-zA-Z0-9_-]{8,80}$/.test(pairing.deviceId) ? pairing.deviceId : `legacy-${peer}`;
        const deviceName = typeof pairing.deviceName === 'string' ? pairing.deviceName.replace(/[\x00-\x1f]/g, '').slice(0, 40) || 'Android device' : 'Android device';
        if (mode === 'single' && activeDevices().some(active => active.id !== deviceId)) return sendJson(res, 409, { error: 'Another device is connected. Ask the PC to choose Multiple devices or wait for it to disconnect.' });
        for (const [oldToken, active] of sessions) if (active.id === deviceId) sessions.delete(oldToken);
        const token = crypto.randomBytes(32).toString('hex');
        sessions.set(token, { id: deviceId, name: deviceName, address: peer, lastSeen: Date.now() });
        res.setHeader('Set-Cookie', `ziar=${token}; HttpOnly; SameSite=Strict; Path=/`);
        return sendJson(res, 200, { ok: true });
      }
      if (!authorized) return sendJson(res, 401, { error: 'Pair with your PC first.' });
      if (url.pathname === '/api/devices' && req.method === 'GET') {
        if (!loopback) return sendJson(res, 403, { error: 'PC only.' });
        return sendJson(res, 200, { mode, devices: activeDevices().map(({ name, address, lastSeen }) => ({ name, address, lastSeen })) });
      }
      if (url.pathname === '/api/mode' && req.method === 'POST') {
        if (!loopback) return sendJson(res, 403, { error: 'PC only.' });
        let selection = '';
        for await (const chunk of req) {
          selection += chunk;
          if (selection.length > 16) return sendJson(res, 400, { error: 'Invalid mode.' });
        }
        if (selection !== 'single' && selection !== 'multiple') return sendJson(res, 400, { error: 'Invalid mode.' });
        if (selection !== mode) onModeChange(selection);
        mode = selection;
        if (mode === 'single' && sessions.size > 1) {
          const keep = activeDevices()[0];
          for (const [token, active] of sessions) if (active !== keep) sessions.delete(token);
        }
        return sendJson(res, 200, { mode });
      }
      if (req.method === 'GET' && url.pathname === '/api/info') return sendJson(res, 200, { name: os.hostname(), addresses: localAddresses(), port, local: loopback, receivedDirectory: loopback ? receivedDirectory : undefined, code: loopback ? code : undefined });
      if (req.method === 'GET' && url.pathname === '/api/files') {
        const files = fs.readdirSync(pendingDirectory, { withFileTypes: true }).filter(f => f.isFile() && !f.name.endsWith('.part')).map(f => {
          const stat = fs.statSync(path.join(pendingDirectory, f.name));
          return { name: f.name, size: stat.size, modified: stat.mtime.toISOString() };
        }).sort((a, b) => b.modified.localeCompare(a.modified));
        return sendJson(res, 200, files);
      }
      if (req.method === 'POST' && url.pathname === '/api/upload') {
        const name = url.searchParams.get('name');
        const length = Number(req.headers['content-length']);
        if (!name || !Number.isFinite(length) || length < 0 || length > MAX_FILE_BYTES) return sendJson(res, 413, { error: 'File must be 1 GB or smaller.' });
        const destination = loopback ? pendingDirectory : receivedDirectory;
        if (loopback && fs.readdirSync(pendingDirectory).length >= MAX_FILES) return sendJson(res, 507, { error: 'Too many files waiting for the phone.' });
        const target = uniquePath(destination, name);
        const temp = path.join(destination, `.${crypto.randomUUID()}.part`);
        let bytes = 0;
        try {
          const output = fs.createWriteStream(temp, { flags: 'wx' });
          for await (const chunk of req) {
            bytes += chunk.length;
            if (bytes > MAX_FILE_BYTES || bytes > length) throw new Error('File exceeds the size limit.');
            if (!output.write(chunk)) await new Promise(resolve => output.once('drain', resolve));
          }
          await new Promise((resolve, reject) => output.end(error => error ? reject(error) : resolve()));
          if (bytes !== length) throw new Error('Incomplete upload.');
          fs.renameSync(temp, target);
          return sendJson(res, 201, { name: path.basename(target), received: !loopback });
        } catch (error) {
          fs.rmSync(temp, { force: true });
          return sendJson(res, 400, { error: error.message });
        }
      }
      if (req.method === 'GET' && url.pathname === '/api/download') {
        const name = url.searchParams.get('name');
        if (!name || safeName(name) !== name) return sendJson(res, 400, { error: 'Invalid filename.' });
        if (loopback) return sendJson(res, 403, { error: 'Download on your phone instead.' });
        const file = path.join(pendingDirectory, name);
        if (!fs.existsSync(file) || !fs.statSync(file).isFile()) return sendJson(res, 404, { error: 'File not found.' });
        res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': fs.statSync(file).size,
          'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent(name)}`, 'Cache-Control': 'no-store' });
        res.once('finish', () => fs.rm(file, { force: true }, error => { if (error) console.error('Could not clear sent file:', error); }));
        return fs.createReadStream(file).pipe(res);
      }
      sendJson(res, 404, { error: 'Not found.' });
    } catch (error) { sendJson(res, 500, { error: error.message }); }
  });
  return { server, code, port, pendingDirectory, receivedDirectory };
}

module.exports = { createTransferServer, localAddresses, safeName, uniquePath, PORT, MAX_FILE_BYTES };
