import { useEffect, useState } from 'react';
import ProgressBar from './components/ProgressBar';
import FileTable from './components/FileTable';
import Notification from './components/Notification';
import ConfirmDialog from './components/ConfirmDialog';
import ShareModal from './components/ShareModal';
import Chat from './components/Chat';
import { useTranslation } from "react-i18next";
import './App.css';

function App() {
    const [dialogOpen, setDialogOpen] = useState(false);
    const [pendingFile, setPendingFile] = useState(null);
    const { t, i18n } = useTranslation();

    const changeLanguage = (lng) => {
        i18n.changeLanguage(lng === "Tiếng Việt" ? "vi" : "en");
        setLanguage(lng);
    };
    const [taskMap] = useState(new Map());
    const [activeTab, setActiveTab] = useState('files');
    const [files, setFiles] = useState([]);
    const [searchTerm, setSearchTerm] = useState('');
    const [language, setLanguage] = useState('Tiếng Việt');
    const [notifications, setNotifications] = useState([]);
    const [tasks, setTasks] = useState([]);
    const [peers, setPeers] = useState([
        { id: 1, name: 'Peer 1', ip: '192.168.1.1', status: 'Online' },
        { id: 2, name: 'Peer 2', ip: '192.168.1.2', status: 'Offline' }
    ]);
    const [messages, setMessages] = useState({
        1: [
            { sender: 'You', text: 'Xin chào!', timestamp: '10:00 AM' },
            { sender: 'Peer 1', text: 'Chào bạn!', timestamp: '10:01 AM' }
        ],
        2: [{ sender: 'You', text: 'Bạn có tệp nào không?', timestamp: '09:00 AM' }]
    });
    const [selectedPeer, setSelectedPeer] = useState(null);
    const [isLoading, setIsLoading] = useState(true);
    const [showSplash, setShowSplash] = useState(true);
    const [shareModalOpen, setShareModalOpen] = useState(false);
    const [selectedFileForSharing, setSelectedFileForSharing] = useState(null);

    const addNotification = (message, isError) => {
        const id = Date.now();
        setNotifications(prev => [...prev, { message, isError, id }]);
        setTimeout(() => setNotifications(prev => prev.filter(n => n.id !== id)), 3000);
    };

    const removeNotification = (id) => {
        setNotifications(prev => prev.filter(n => n.id !== id));
    };

    const fetchFiles = async () => {
        setIsLoading(true);
        try {
            const response = await fetch('http://localhost:8080/api/files/refresh', {
                method: 'GET',
                headers: { 'Content-Type': 'application/json' }
            });
            if (response.ok) {
                const data = await response.json();
                setFiles(data);
                addNotification('Đã tải danh sách tệp', false);
            } else {
                throw new Error(`Lỗi khi tải danh sách tệp: ${response.status}`);
            }
        } catch (error) {
            addNotification('Lỗi khi tải danh sách tệp', true);
            console.error(error);
        } finally {
            setIsLoading(false);
            setShowSplash(false);
        }
    };

    useEffect(() => {
        const timer = setTimeout(() => setShowSplash(false), 10000);
        fetchFiles();
        return () => clearTimeout(timer);
    }, []);

    const queryProgress = async () => {
        if (taskMap.size === 0) return;
        let completedTaskCount = 0;
        taskMap.forEach((info, id) => {
            if (['completed', 'failed', 'canceled'].includes(info.status)) {
                completedTaskCount++;
            }
        });

        if (!(completedTaskCount === taskMap.size)) {
            console.log('Task map size:', taskMap.size);
            try {
                const response = await fetch("http://localhost:8080/api/progress");
                if (response.ok) {
                    const data = await response.json();

                    if (data) {
                        // let completedTasks = [];
                        setTasks(prev => {
                            let updated = [...prev];

                            Object.entries(data).forEach(([id, info]) => {
                                const taskId = String(id);

                                const taskExists = updated.find(t => t.id === taskId);

                                if (!taskExists) {
                                    updated.push({
                                        id: taskId,
                                        taskName: info.fileName || "Unknown Task",
                                        progress: info.progressPercentage || 0,
                                        bytesTransferred: info.bytesTransferred || 0,
                                        totalBytes: info.totalBytes || 1,
                                        status: info.status
                                    });
                                } else {
                                    updated = updated.map(t =>
                                        t.id === taskId
                                            ? {
                                                ...t,
                                                taskName: info.fileName || "Unknown Task",
                                                progress: info.progressPercentage,
                                                bytesTransferred: info.bytesTransferred,
                                                totalBytes: info.totalBytes,
                                                status: info.status
                                            }
                                            : t
                                    );
                                }
                            });

                            return updated;
                        });

                        if (completedTasks.length > 0) {
                            try {
                                await fetch("http://localhost:8080/api/progress/cleanup", {
                                    method: "POST",
                                    headers: { "Content-Type": "application/json" },
                                    body: JSON.stringify({ taskIds: completedTasks })
                                });
                                console.log("Đã báo backend cleanup:", completedTasks);
                            } catch (err) {
                                console.error("Lỗi khi cleanup backend:", err);
                            }
                        }
                    }
                } else {
                    console.error("Lỗi khi truy vấn tiến trình:", response.status);
                }
            } catch (error) {
                console.error("Lỗi khi truy vấn tiến trình:", error);
            }
        }
    };


    useEffect(() => {
        const interval = setInterval(queryProgress, 2000);
        return () => clearInterval(interval);
    }, []);

    const handleFileUpload = async () => {
        if (!window.electronAPI || !window.electronAPI.selectFile) {
            addNotification(t('cannot_load_electronAPI'), true);
            return;
        }

        setIsLoading(true);
        try {
            const filePath = await window.electronAPI.selectFile();
            if (!filePath) {
                addNotification(t('no_file_selected'), true);
                return;
            }

            const fileName = filePath.split(/[\\/]/).pop();
            const existResponse = await fetch(`http://localhost:8080/api/files/exists?fileName=${encodeURIComponent(fileName)}`);
            if (existResponse.ok) {
                const { exists } = await existResponse.json();
                if (exists) {
                    setPendingFile({ filePath, fileName });
                    setDialogOpen(true);
                    setIsLoading(false);
                    return;
                }
            }

            // Create a file object for sharing
            const fileForSharing = {
                filePath,
                fileName,
                fileHash: '' // Will be computed by backend
            };

            setSelectedFileForSharing(fileForSharing);
            setShareModalOpen(true);
        } catch (error) {
            addNotification(t('error_sharing_file', { error: error.message }), true);
            console.error('Error in handleFileUpload:', error);
        } finally {
            setIsLoading(false);
        }
    };

    const handleDialogClose = async (action) => {
        setDialogOpen(false);
        if (action === -1 || !action) {
            addNotification(t('cancel_sharing_file'), false);
            setPendingFile(null);
            return;
        }

        if (!pendingFile) return;

        setIsLoading(true);
        try {
            const isReplace = action === 1 ? 1 : 0;

            const fileForSharing = {
                filePath: pendingFile.filePath,
                fileName: pendingFile.fileName,
                isReplace: isReplace
            };

            setSelectedFileForSharing(fileForSharing);
            setShareModalOpen(true);
        } catch (error) {
            addNotification(t('error_sharing_file', { error: error.message }), true);
            console.error('Error in handleDialogClose:', error);
        } finally {
            setPendingFile(null);
            setIsLoading(false);
        }
    };

    const handleDownload = async (file) => {
        if (!window.electronAPI) {
            addNotification(t('cannot_load_electronAPI'), true);
            return;
        }

        setIsLoading(true);
        try {
            const savePath = await window.electronAPI.saveFile(file.fileName);
            if (!savePath) {
                addNotification(t('no_file_selected'), true);
                return;
            }

            addNotification(t('start_download_file', { fileName: file.fileName }), false);
            const progressId = await window.electronAPI.downloadFile({
                fileName: file.fileName.trim(),
                peerInfo: `${file.peerInfo.ip}:${file.peerInfo.port}`,
                savePath
            });
            console.log('Progress ID:', progressId);
            taskMap.set(progressId, { fileName: file.fileName.trim(), status: 'starting' });
        } catch (error) {
            addNotification(t('error_downloading_file', { error: error.message }), true);
            console.error('Error in handleDownload:', error);
        } finally {
            setIsLoading(false);
        }
    };

    const handleStopSharing = async (file) => {
        setIsLoading(true);
        try {
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), 10000);
            const response = await fetch('http://localhost:8080/api/files/remove', {
                method: 'DELETE',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ fileName: file.fileName }),
                signal: controller.signal
            });
            clearTimeout(timeoutId);
            if (response.ok) {
                fetchFiles();
                addNotification(t('cancel_sharing_file', { fileName: file.fileName }), false);
            } else {
                const errorData = await response.json();
                throw new Error(t('error_cancel_sharing_file', { error: errorData.error || response.status }));
            }
        } catch (error) {
            addNotification(t('error_cancel_sharing_file', { error: error.message }), true);
        } finally {
            setIsLoading(false);
        }
    };

    const handleSearch = async () => {
        addNotification(t('search', { searchTerm }), false);
        if (searchTerm.trim()) {
            const filteredFiles = files.filter(file => file.fileName.toLowerCase().includes(searchTerm.toLowerCase()));
            setFiles(filteredFiles);
        } else {
            fetchFiles();
        }
    };

    const handleRefresh = () => {
        fetchFiles();
    };

    const handleMyFiles = () => {
        setFiles(files.filter(file => file.isSharedByMe));
        addNotification(t('show_my_files'), false);
    };

    const handleAllFiles = () => {
        fetchFiles();
    };

    const handleShareToPeers = async (file, isReplace) => {
        // For simplicity, we'll use a prompt to get peer IPs
        const peerInput = prompt('Nhập danh sách IP peer (cách nhau bằng dấu phẩy):', '192.168.1.1:8080,192.168.1.2:8080');
        if (!peerInput) return;

        const peerList = peerInput.split(',').map(peer => peer.trim()).filter(peer => peer);
        if (peerList.length === 0) {
            addNotification('Không có peer nào được chọn', true);
            return;
        }

        setIsLoading(true);
        try {
            const response = await fetch('http://localhost:8080/api/files/share-to-peers', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    filePath: file.filePath,
                    isReplace: isReplace,
                    peers: peerList
                })
            });

            console.log('progressId:', response.body);
            taskMap.set(response.body, { fileName: file.fileName, status: 'starting' });
            fetchFiles();

        } catch (error) {
            addNotification(`Lỗi khi chia sẻ file: ${error.message}`, true);
        } finally {
            setIsLoading(false);
        }
    };

    const handleShareAll = async (file) => {
        setIsLoading(true);
        try {
            const progressId = await window.electronAPI.shareFile(file.filePath, 1);
            console.log('Progress ID:', progressId);
            taskMap.set(progressId, { fileName: file.fileName, status: 'starting' });
            addNotification(t('start_sharing_file', { fileName: file.fileName }), false);
        } catch (error) {
            addNotification(t('error_sharing_file', { error: error.message }), true);
            console.error('Error in handleShareAll:', error);
        } finally {
            setIsLoading(false);
        }
    };

    const handleShareSelective = async (file, selectedPeers) => {
        setIsLoading(true);
        try {
            const response = await fetch('http://localhost:8080/api/files/share-to-peers', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    filePath: file.filePath,
                    isReplace: file.isReplace || 1,
                    peers: selectedPeers
                })
            });

            if (response.ok) {
                const progressId = await response.json();
                taskMap.set(progressId, { fileName: file.fileName, status: 'starting' });
                fetchFiles();
            } else {
                const errorData = await response.json();
                throw new Error(errorData.error || `Lỗi: ${response.status}`);
            }
        } catch (error) {
            addNotification(`Lỗi khi chia sẻ file: ${error.message}`, true);
        } finally {
            setIsLoading(false);
        }
    };

    const handleSendMessage = (peerId, text) => {
        const newMessage = { sender: 'You', text, timestamp: new Date().toLocaleTimeString() };
        setMessages(prev => ({
            ...prev,
            [peerId]: [...(prev[peerId] || []), newMessage]
        }));
        addNotification(t('message_sent'), false);
        setTimeout(() => {
            const responseMessage = { sender: `Peer ${peerId}`, text: 'Tin nhắn nhận được!', timestamp: new Date().toLocaleTimeString() };
            setMessages(prev => ({
                ...prev,
                [peerId]: [...prev[peerId], responseMessage]
            }));
        }, 1000);
    };

    return (
        <>
            {showSplash && (
                <div className="fixed inset-0 bg-blue-600 bg-opacity-90 flex flex-col items-center justify-center z-50 animate-fade-in">
                    <svg className="w-24 h-24 mb-6 text-white animate-pulse" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                    </svg>
                    <h2 className="text-2xl font-bold text-white mb-4">{t('loading')}</h2>
                    <div className="w-12 h-12 border-4 border-t-transparent border-white rounded-full animate-spin"></div>
                </div>
            )}
            <div className={`container mx-auto p-6 max-w-7xl ${showSplash ? 'opacity-0' : 'opacity-100'} transition-opacity duration-500`}>
                <h1 className="text-4xl font-extrabold text-center mb-8 text-blue-900 tracking-tight">{t('title')}</h1>
                <div className="flex space-x-4 mb-6 bg-white rounded-xl shadow-lg p-2 border border-gray-100">
                    <button
                        onClick={() => setActiveTab('files')}
                        className={`flex-1 py-3 text-lg font-semibold rounded-lg transition-all duration-200 ${activeTab === 'files' ? 'bg-blue-600 text-white shadow-md' : 'text-blue-600 hover:bg-blue-50'}`}
                    >
                        {t('files')}
                    </button>
                    <button
                        onClick={() => setActiveTab('chat')}
                        className={`flex-1 py-3 text-lg font-semibold rounded-lg transition-all duration-200 ${activeTab === 'chat' ? 'bg-blue-600 text-white shadow-md' : 'text-blue-600 hover:bg-blue-50'}`}
                    >
                        {t('chat')}
                    </button>
                </div>
                {activeTab === 'files' ? (
                    <div>
                        <div className="bg-white rounded-xl shadow-lg p-6 mb-6 border border-gray-100">
                            <div className="flex flex-col md:flex-row justify-between items-center mb-6 space-y-4 md:space-y-0 md:space-x-4">
                                <div className="flex items-center space-x-4 w-full md:w-auto">
                                    <button
                                        onClick={handleFileUpload}
                                        className="px-6 py-3 bg-blue-600 text-white rounded-lg hover:bg-blue-700 flex items-center transition-colors duration-200 disabled:opacity-50"
                                        disabled={isLoading}
                                    >
                                        <svg className="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"></path>
                                        </svg>
                                        {t('selectFile')}
                                    </button>
                                </div>
                                <div className="flex items-center space-x-4 w-full md:w-auto">
                                    <label className="text-sm font-semibold text-gray-700 whitespace-nowrap">{t('language')}:</label>
                                    <select
                                        value={language}
                                        onChange={(e) => changeLanguage(e.target.value)}
                                        className="border border-gray-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500 bg-white"
                                        disabled={isLoading}
                                    >
                                        <option>Tiếng Việt</option>
                                        <option>English</option>
                                    </select>
                                </div>
                            </div>
                            <div className="flex items-center space-x-4 mb-6">
                                <input
                                    type="text"
                                    placeholder="Tìm kiếm tệp..."
                                    value={searchTerm}
                                    onChange={(e) => setSearchTerm(e.target.value)}
                                    onKeyPress={(e) => e.key === 'Enter' && handleSearch()}
                                    className="border border-gray-200 rounded-lg px-4 py-3 flex-1 focus:outline-none focus:ring-2 focus:ring-blue-500"
                                    disabled={isLoading}
                                />
                                <button
                                    onClick={handleSearch}
                                    className="px-6 py-3 bg-blue-600 text-white rounded-lg hover:bg-blue-700 flex items-center transition-colors duration-200 disabled:opacity-50"
                                    disabled={isLoading}
                                >
                                    <svg className="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"></path>
                                    </svg>
                                    {t('search')}
                                </button>
                            </div>
                            <div className="flex flex-wrap gap-4">
                                <button
                                    onClick={handleMyFiles}
                                    className="px-6 py-3 bg-blue-600 text-white rounded-lg hover:bg-blue-700 flex items-center transition-colors duration-200 disabled:opacity-50"
                                    disabled={isLoading}
                                >
                                    <svg className="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z"></path>
                                    </svg>
                                    {t('myFiles')}
                                </button>
                                <button
                                    onClick={handleAllFiles}
                                    className="px-6 py-3 bg-blue-600 text-white rounded-lg hover:bg-blue-700 flex items-center transition-colors duration-200 disabled:opacity-50"
                                    disabled={isLoading}
                                >
                                    <svg className="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z"></path>
                                    </svg>
                                    {t('allFiles')}
                                </button>
                                <button
                                    onClick={handleRefresh}
                                    className="px-6 py-3 bg-blue-600 text-white rounded-lg hover:bg-blue-700 flex items-center transition-colors duration-200 disabled:opacity-50"
                                    disabled={isLoading}
                                >
                                    {isLoading ? (
                                        <svg className="w-5 h-5 mr-2 animate-spin" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"></path>
                                        </svg>
                                    ) : (
                                        <svg className="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"></path>
                                        </svg>
                                    )}
                                    {t('refresh')}
                                </button>
                            </div>
                        </div>
                        <FileTable files={files} onDownload={handleDownload} onStopSharing={handleStopSharing} onShareToPeers={handleShareToPeers} isLoading={isLoading} />

                        <div style={{ margin: '16px' }}></div>
                        <ProgressBar tasks={tasks} setTasks={setTasks} taskMap={taskMap} />
                    </div>
                ) : (
                    <Chat
                        peers={peers}
                        messages={messages}
                        onSendMessage={handleSendMessage}
                        selectedPeer={selectedPeer}
                        setSelectedPeer={setSelectedPeer}
                    />
                )}
                {notifications.map(notification => (
                    <Notification
                        key={notification.id}
                        message={notification.message}
                        isError={notification.isError}
                        onClose={() => removeNotification(notification.id)}
                    />
                ))}
                <ConfirmDialog open={dialogOpen} onClose={handleDialogClose} />
                <ShareModal
                    isOpen={shareModalOpen}
                    onClose={() => setShareModalOpen(false)}
                    onShareAll={handleShareAll}
                    onShareSelective={handleShareSelective}
                    file={selectedFileForSharing}
                />
            </div>
        </>
    );
}

export default App;
