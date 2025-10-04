require('dotenv').config();
// Normalize and fallback for API base URL in Electron main process
const API_BASE_URL = (process.env.REACT_APP_API_BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
function buildApiUrl(path = '') { return API_BASE_URL + (path.startsWith('/') ? path : '/' + path); }
const { app, BrowserWindow, Menu, ipcMain, dialog, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const fetch = require('node-fetch');

function createWindow() {
    const preloadPath = path.join(__dirname, 'preload.js');
    if (!fs.existsSync(preloadPath)) {
        console.error('Preload file does not exist at:', preloadPath);
        return;
    }

    const iconPath = path.join(__dirname, '../../public/logo.ico');
    const win = new BrowserWindow({
    width: 1500,
    height: 700,
    icon: iconPath,
    webPreferences: { preload: preloadPath }
    });


    // const win = new BrowserWindow({
    //     width: 1500,
    //     height: 700,
    //     ...(iconPath && { icon: iconPath }), // Chỉ set icon khi có path (production)
    //     webPreferences: {
    //         preload: preloadPath,
    //         contextIsolation: true,
    //         enableRemoteModule: false,
    //     },
    // });

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

    ipcMain.handle('dialog:openFile', async (event) => {
        const parentWin = BrowserWindow.fromWebContents(event.sender);

        return new Promise((resolve) => {
            const modalWin = new BrowserWindow({
                width: 600,
                height: 400,
                parent: parentWin,
                modal: true,
                alwaysOnTop: true,
                resizable: false,
                minimizable: false,
                maximizable: false,
                show: false,
                webPreferences: {
                    nodeIntegration: true,
                    contextIsolation: false,
                },
                title: 'Chọn tệp để chia sẻ',
            });

            // Disable parent window interaction
            parentWin.setEnabled(false);

            // Load HTML content
            const htmlContent = `
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>Chọn tệp để chia sẻ</title>
                    <style>
                        body { font-family: Arial, sans-serif; padding: 20px; background: #f5f5f5; margin: 0; }
                        .container { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); max-width: 500px; margin: 0 auto; }
                        .title { font-size: 18px; font-weight: bold; margin-bottom: 20px; color: #333; text-align: center; }
                        .file-input { margin: 20px 0; }
                        .buttons { display: flex; gap: 10px; justify-content: flex-end; margin-top: 20px; }
                        button { padding: 8px 16px; border: none; border-radius: 4px; cursor: pointer; font-size: 14px; }
                        .select-btn { background: #007bff; color: white; }
                        .select-btn:hover { background: #0056b3; }
                        .cancel-btn { background: #6c757d; color: white; }
                        .cancel-btn:hover { background: #545b62; }
                        input[type="file"] { width: 100%; padding: 8px; border: 1px solid #ddd; border-radius: 4px; box-sizing: border-box; }
                        .file-info { margin-top: 10px; font-size: 12px; color: #666; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="title">Chọn tệp để chia sẻ</div>
                        <div class="file-input">
                            <input type="file" id="fileInput" accept="*/*">
                            <div id="fileInfo" class="file-info">Chưa chọn tệp nào</div>
                        </div>
                        <div class="buttons">
                            <button class="cancel-btn" onclick="cancel()">Hủy</button>
                            <button class="select-btn" onclick="selectFile()" id="selectBtn" disabled>Chọn</button>
                        </div>
                    </div>
                    <script>
                        const { ipcRenderer } = require('electron');

                        const fileInput = document.getElementById('fileInput');
                        const fileInfo = document.getElementById('fileInfo');
                        const selectBtn = document.getElementById('selectBtn');

                        fileInput.addEventListener('change', (e) => {
                            if (e.target.files.length > 0) {
                                const file = e.target.files[0];
                                fileInfo.textContent = \`Tệp đã chọn: \${file.name} (\${(file.size / 1024 / 1024).toFixed(2)} MB)\`;
                                selectBtn.disabled = false;
                            } else {
                                fileInfo.textContent = 'Chưa chọn tệp nào';
                                selectBtn.disabled = true;
                            }
                        });

                        function cancel() {
                            ipcRenderer.send('modal-cancel');
                        }

                        function selectFile() {
                            const fileInput = document.getElementById('fileInput');
                            if (fileInput.files.length > 0) {
                                ipcRenderer.send('modal-select', fileInput.files[0].path);
                            } else {
                                cancel();
                            }
                        }

                        // Handle Enter key
                        document.addEventListener('keydown', (e) => {
                            if (e.key === 'Enter' && !selectBtn.disabled) {
                                selectFile();
                            } else if (e.key === 'Escape') {
                                cancel();
                            }
                        });

                        // Focus on file input when window loads
                        window.addEventListener('load', () => {
                            fileInput.focus();
                        });
                    </script>
                </body>
                </html>
            `;

            modalWin.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(htmlContent));

            modalWin.once('ready-to-show', () => {
                modalWin.show();
                modalWin.focus();
            });

            // Handle modal responses
            ipcMain.once('modal-select', (event, filePath) => {
                modalWin.close();
                parentWin.setEnabled(true);
                resolve(filePath);
            });

            ipcMain.once('modal-cancel', () => {
                modalWin.close();
                parentWin.setEnabled(true);
                resolve(null);
            });

            modalWin.on('closed', () => {
                parentWin.setEnabled(true);
                resolve(null);
            });
        });
    });

    ipcMain.handle('dialog:saveFile', async (event, fileName) => {
        const parentWin = BrowserWindow.fromWebContents(event.sender);
        const defaultPath = path.join(require('os').homedir(), 'Downloads', fileName);

        return new Promise((resolve) => {
            const modalWin = new BrowserWindow({
                width: 700,
                height: 450,
                parent: parentWin,
                modal: true,
                alwaysOnTop: true,
                resizable: false,
                minimizable: false,
                maximizable: false,
                show: false,
                webPreferences: {
                    nodeIntegration: true,
                    contextIsolation: false,
                },
                title: 'Chọn nơi lưu tệp',
            });

            // Disable parent window interaction
            parentWin.setEnabled(false);

            // Load HTML for save location selection
            const saveHtmlContent = `
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>Chọn nơi lưu tệp</title>
                    <style>
                        body { font-family: Arial, sans-serif; padding: 20px; background: #f5f5f5; margin: 0; }
                        .container { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); max-width: 600px; margin: 0 auto; }
                        .title { font-size: 18px; font-weight: bold; margin-bottom: 20px; color: #333; text-align: center; }
                        .path-input { margin: 20px 0; }
                        .label { display: block; margin-bottom: 5px; font-weight: bold; color: #555; }
                        .input-group { display: flex; gap: 10px; align-items: center; }
                        input[type="text"] { flex: 1; padding: 8px; border: 1px solid #ddd; border-radius: 4px; box-sizing: border-box; font-family: monospace; font-size: 12px; }
                        button { padding: 8px 16px; border: none; border-radius: 4px; cursor: pointer; font-size: 14px; }
                        .browse-btn { background: #28a745; color: white; }
                        .browse-btn:hover { background: #218838; }
                        .buttons { display: flex; gap: 10px; justify-content: flex-end; margin-top: 20px; }
                        .save-btn { background: #007bff; color: white; }
                        .save-btn:hover { background: #0056b3; }
                        .cancel-btn { background: #6c757d; color: white; }
                        .cancel-btn:hover { background: #545b62; }
                        .path-display { background: #f8f9fa; padding: 10px; border-radius: 4px; margin-bottom: 10px; word-break: break-all; font-family: monospace; font-size: 11px; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="title">Chọn nơi lưu tệp</div>
                        <div class="path-input">
                            <label class="label" for="pathInput">Đường dẫn lưu tệp:</label>
                            <div class="path-display" id="pathDisplay">${defaultPath.replace(/\\/g, '\\\\')}</div>
                            <div class="input-group">
                                <input type="text" id="pathInput" value="${defaultPath.replace(/\\/g, '\\\\')}" readonly>
                                <button class="browse-btn" onclick="browseFolder()">Duyệt...</button>
                            </div>
                        </div>
                        <div class="buttons">
                            <button class="cancel-btn" onclick="cancel()">Hủy</button>
                            <button class="save-btn" onclick="saveFile()">Lưu</button>
                        </div>
                    </div>
                    <script>
                        const { ipcRenderer, dialog } = require('electron');
                        const fs = require('fs');
                        const path = require('path');

                        function cancel() {
                            ipcRenderer.send('save-modal-cancel');
                        }

                        function saveFile() {
                            const pathInput = document.getElementById('pathInput');
                            const filePath = pathInput.value;
                            if (filePath) {
                                ipcRenderer.send('save-modal-select', filePath);
                            } else {
                                cancel();
                            }
                        }

                        async function browseFolder() {
                            ipcRenderer.send('browse-save-location', document.getElementById('pathInput').value);
                        }

                        ipcRenderer.on('browse-save-result', (event, result) => {
                            if (!result.canceled) {
                                const newPath = result.filePath;
                                document.getElementById('pathInput').value = newPath;
                                document.getElementById('pathDisplay').textContent = newPath;
                            }
                        });

                        // Handle Enter key
                        document.addEventListener('keydown', (e) => {
                            if (e.key === 'Enter') {
                                saveFile();
                            } else if (e.key === 'Escape') {
                                cancel();
                            }
                        });

                        // Focus on browse button when window loads
                        window.addEventListener('load', () => {
                            document.querySelector('.browse-btn').focus();
                        });
                    </script>
                </body>
                </html>
            `;

            modalWin.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(saveHtmlContent));

            modalWin.once('ready-to-show', () => {
                modalWin.show();
                modalWin.focus();
            });

            // Handle modal responses
            ipcMain.once('save-modal-select', (event, filePath) => {
                modalWin.close();
                parentWin.setEnabled(true);
                resolve(filePath);
            });

            ipcMain.once('save-modal-cancel', () => {
                modalWin.close();
                parentWin.setEnabled(true);
                resolve(null);
            });

            modalWin.on('closed', () => {
                parentWin.setEnabled(true);
                resolve(null);
            });
        });
    });


    ipcMain.handle('share-file', async (event, filePath, isReplace) => {
        try {
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), 30000);
            const response = await fetch(buildApiUrl('/api/files'), {
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

    ipcMain.handle('download-file', async (event, { fileName, peerInfo, savePath }) => {
        try {
            const controller = new AbortController();
            const response = await fetch(
                buildApiUrl(`/api/files/download?fileName=${encodeURIComponent(fileName)}&peerInfo=${encodeURIComponent(peerInfo)}&savePath=${encodeURIComponent(savePath)}`),
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
            const response = await fetch(buildApiUrl('/api/files/cancel'), {
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

    // Handle browse save location from modal
    ipcMain.on('browse-save-location', async (event, defaultPath) => {
        const modalWin = BrowserWindow.fromWebContents(event.sender);
        const result = await dialog.showSaveDialog(modalWin, {
            title: 'Chọn nơi lưu',
            defaultPath: defaultPath,
            filters: [{ name: 'All Files', extensions: ['*'] }],
        });
        event.sender.send('browse-save-result', result);
    });
});

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
        app.quit();
    }
});
