const { app, BrowserWindow, shell, ipcMain } = require('electron');
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');
const { createTransferServer, PORT } = require('./server.cjs');
const { startDiscovery } = require('./discovery.cjs');

let transfer;
let window;
let stopDiscovery;
let pendingDirectory;
app.whenReady().then(() => {
  pendingDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'ziar-session-'));
  const settingsPath = path.join(app.getPath('userData'), 'settings.json');
  let initialMode = 'single';
  try { initialMode = JSON.parse(fs.readFileSync(settingsPath, 'utf8')).deviceMode; } catch { /* First launch. */ }
  transfer = createTransferServer({ pendingDirectory, receivedDirectory: path.join(app.getPath('downloads'), 'ZiAr Received'),
    initialMode, onModeChange: deviceMode => fs.writeFileSync(settingsPath, JSON.stringify({ deviceMode }), 'utf8') });
  ipcMain.handle('ziar:open-received-folder', event => {
    if (event.sender !== window?.webContents || event.senderFrame?.url !== `http://127.0.0.1:${PORT}/`) return 'Unavailable.';
    return shell.openPath(transfer.receivedDirectory);
  });
  transfer.server.listen(PORT, '0.0.0.0', () => {
    stopDiscovery = startDiscovery();
    window = new BrowserWindow({ width: 1000, height: 760, minWidth: 680, minHeight: 500, title: 'ZiAr',
      webPreferences: { nodeIntegration: false, contextIsolation: true, sandbox: true, preload: path.join(__dirname, 'preload.cjs') } });
    window.loadURL(`http://127.0.0.1:${PORT}/`);
    window.webContents.setWindowOpenHandler(({ url }) => {
      if (url.startsWith(`http://127.0.0.1:${PORT}/api/download`)) shell.openExternal(url);
      return { action: 'deny' };
    });
  });
});
app.on('window-all-closed', () => app.quit());
app.on('before-quit', () => { stopDiscovery?.(); transfer?.server.close(); if (pendingDirectory) fs.rmSync(pendingDirectory, { recursive: true, force: true }); });
