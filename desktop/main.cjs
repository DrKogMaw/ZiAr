const { app, BrowserWindow, shell } = require('electron');
const path = require('node:path');
const { createTransferServer, PORT } = require('./server.cjs');

let transfer;
let window;
app.whenReady().then(() => {
  transfer = createTransferServer({ directory: path.join(app.getPath('documents'), 'ZiAr Shared') });
  transfer.server.listen(PORT, '0.0.0.0', () => {
    window = new BrowserWindow({ width: 1000, height: 760, minWidth: 680, minHeight: 500, title: 'ZiAr',
      webPreferences: { nodeIntegration: false, contextIsolation: true, sandbox: true } });
    window.loadURL(`http://127.0.0.1:${PORT}/`);
    window.webContents.setWindowOpenHandler(({ url }) => {
      if (url.startsWith(`http://127.0.0.1:${PORT}/api/download`)) shell.openExternal(url);
      return { action: 'deny' };
    });
  });
});
app.on('window-all-closed', () => app.quit());
app.on('before-quit', () => transfer?.server.close());
