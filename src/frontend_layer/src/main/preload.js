const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
    selectFile: () => ipcRenderer.invoke('dialog:openFile'),
    saveFile: (fileName) => ipcRenderer.invoke('dialog:saveFile', fileName),
    shareFile: (filePath, isReplace) => ipcRenderer.invoke('share-file', filePath, isReplace),
    downloadFile: (args) => ipcRenderer.invoke('download-file', args),
    cancelDownload: () => ipcRenderer.invoke('cancel-download'),
});