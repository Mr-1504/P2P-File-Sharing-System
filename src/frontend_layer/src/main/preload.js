const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
    selectFile: () => ipcRenderer.invoke('dialog:openFile'),
    saveFile: (fileName) => ipcRenderer.invoke('dialog:saveFile', fileName),
    shareFile: (filePath, isReplace) => ipcRenderer.invoke('share-file', filePath, isReplace),
    downloadFile: (params) => ipcRenderer.invoke('download-file', params),
    cancelDownload: () => ipcRenderer.invoke('cancel-download'),
});
