const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('ziarDesktop', {
  openReceivedFolder: () => ipcRenderer.invoke('ziar:open-received-folder'),
});
