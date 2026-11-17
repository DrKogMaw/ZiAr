const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('ziarDesktop', {
  openReceivedFolder: () => ipcRenderer.invoke('ziar:open-received-folder'),
  openUpdateFolder: kind => ipcRenderer.invoke('ziar:open-update-folder', kind),
  installWindowsUpdate: () => ipcRenderer.invoke('ziar:install-windows-update'),
});
