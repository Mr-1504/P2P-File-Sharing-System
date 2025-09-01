const { app, BrowserWindow, Menu, ipcMain, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const fetch = require('node-fetch');

function createWindow() {
    const preloadPath = path.join(__dirname, 'preload.js');
    if (!fs.existsSync(preloadPath)) {
        console.error('Preload file does not exist at:', preloadPath);
        return;
    }

    const win = new BrowserWindow({
        width: 1500,
        height: 700,
        webPreferences: {
            preload: preloadPath,
            contextIsolation: true,
            enableRemoteModule: false,
        },
    });

    win.webContents.openDevTools();

    if (process.env.ELECTRON_START_URL) {
        win.loadURL(process.env.ELECTRON_START_URL); // Dev server, e.g., http://localhost:3000
    } else {
        const indexPath = path.join(__dirname, 'build', 'index.html');
        if (!fs.existsSync(indexPath)) {
            console.error('Index file does not exist at:', indexPath);
            return;
        }
        win.loadFile(indexPath);
    }

    Menu.setApplicationMenu(null);
}

app.whenReady().then(() => {
    createWindow();

    ipcMain.handle('dialog:openFile', async () => {
        const { canceled, filePaths } = await dialog.showOpenDialog({
            properties: ['openFile'],
        });
        return canceled ? null : filePaths[0];
    });

    ipcMain.handle('dialog:saveFile', async (event, fileName) => {
        const { canceled, filePath } = await dialog.showSaveDialog({
            title: 'Chọn nơi lưu',
            defaultPath: path.join(require('os').homedir(), 'Downloads', fileName),
            filters: [{ name: 'All Files', extensions: ['*'] }],
        });
        return canceled ? null : filePath;
    });

    ipcMain.handle('share-file', async (event, filePath, isReplace) => {
        try {
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), 30000);
            const response = await fetch('http://localhost:8080/api/files', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ filePath, isReplace }),
                signal: controller.signal,
            });
            clearTimeout(timeoutId);
            if (response.ok) {
                return await response.json();
            } else {
                const errorData = await response.json();
                throw new Error(errorData.error || `Error share file: ${response.status}`);
            }
        } catch (error) {
            console.error('Error share file:', error);
            throw error;
        }
    });

    ipcMain.handle('download-file', async (event, { fileName, peerInfor, savePath }) => {
        try {
            const controller = new AbortController();
            const response = await fetch(
                `http://localhost:8080/api/files/download?fileName=${encodeURIComponent(fileName)}&peerInfor=${encodeURIComponent(peerInfor)}&savePath=${encodeURIComponent(savePath)}`,
                {
                    method: 'GET',
                    signal: controller.signal,
                }
            );
            if (response.ok) {
                return await response.json();
            } else {
                const errorData = await response.json();
                throw new Error(errorData.error || `Error download file: ${response.status}`);
            }
        } catch (error) {
            console.error('Error download file:', error);
            throw error;
        }
    });

    ipcMain.handle('cancel-download', async () => {
        try {
            const response = await fetch('http://localhost:8080/api/files/cancel', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
            });
            if (response.ok) {
                return true;
            } else {
                const errorData = await response.json();
                throw new Error(errorData.error || 'Error canceling download');
            }
        } catch (error) {
            console.error('Error canceling download:', error);
            throw error;
        }
    });
});

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
        app.quit();
    }
});