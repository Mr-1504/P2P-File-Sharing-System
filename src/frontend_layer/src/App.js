import React, { useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import i18n from './i18n'; // Giả định file i18n.js tồn tại
import FilesPage from './pages/FilesPage';
import ChatPage from './pages/ChatPage';
import Tasks from './components/Tasks';
import Notification from './components/Notification';
import ConfirmDialog from './components/ConfirmDialog'; // Giả định import
import ShareModal from './components/ShareModal'; // Giả định import
import { useNotifications } from './hooks/useNotifications';
import { useTasks } from './hooks/useTasks';
import './App.css';

// Giả định hàm buildApiUrl nếu chưa có
const buildApiUrl = (path) => `http://localhost:3000${path}`; // Thay đổi base URL nếu cần

function App() {
    const { t } = useTranslation();
    const [activeTab, setActiveTab] = useState('files');
    const [isLoading, setIsLoading] = useState(false);
    const [showSplash, setShowSplash] = useState(false);
    const [files, setFiles] = useState([]);
    const [searchTerm, setSearchTerm] = useState('');
    const [language, setLanguage] = useState('vi');
    const [dialogOpen, setDialogOpen] = useState(false);
    const [pendingFile, setPendingFile] = useState(null);
    const [shareModalOpen, setShareModalOpen] = useState(false);
    const [selectedFileForSharing, setSelectedFileForSharing] = useState(null);
    const [messages, setMessages] = useState({});
    const [selectedPeer, setSelectedPeer] = useState(null);
    const [peers, setPeers] = useState([]);
    const [taskTimeouts, setTaskTimeouts] = useState({});
    const [isNewTask, setIsNewTask] = useState(false);

    const { notifications, addNotification, removeNotification } = useNotifications();
    const { tasks, setTasks, taskMap } = useTasks(addNotification);

    const prevTasksLength = useRef(0);

    useEffect(() => {
        if (tasks.length > prevTasksLength.current) {
            addNotification('Có tiến trình mới bắt đầu', false);
            setIsNewTask(true);
            setTimeout(() => setIsNewTask(false), 2000);
        }
        prevTasksLength.current = tasks.length;
    }, [tasks.length]);

    const changeLanguage = (lang) => {
        i18n.changeLanguage(lang);
        setLanguage(lang);
    };

    const fetchFiles = async () => {
        setIsLoading(true);
        try {
            const response = await fetch(buildApiUrl('/api/files/refresh'), {
                method: 'GET',
                headers: { 'Content-Type': 'application/json' }
            });
            if (response.ok) {
                const contentType = response.headers.get('content-type');
                if (contentType && contentType.includes('application/json')) {
                    const data = await response.json();
                    setFiles(data);
                    addNotification('Đã tải danh sách tệp', false);
                } else {
                    throw new Error('Server trả về dữ liệu không phải JSON');
                }
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

    // const queryProgress = async () => {
    //     if (taskMap.size === 0) return;
    //     let completedTaskCount = 0;
    //     taskMap.forEach((info, id) => {
    //         if (['completed', 'failed', 'canceled', 'timeout'].includes(info.status)) {
    //             completedTaskCount++;
    //         }
    //     });

    //     if (!(completedTaskCount === taskMap.size)) {
    //         try {
    //             const response = await fetch(buildApiUrl('/api/progress'));
    //             if (response.ok) {
    //                 const data = await response.json();
    //                 if (data) {
    //                     let completedTasks = [];
    //                     const now = Date.now();
    //                     const timeoutThreshold = 2 * 60 * 1000; // 2 minutes

    //                     setTasks(prev => {
    //                         let updated = [...prev];
    //                         Object.entries(data).forEach(([id, info]) => {
    //                             const taskId = String(id);
    //                             const taskIndex = updated.findIndex(t => t.id === taskId);
    //                             // Lấy taskType từ taskMap
    //                             const taskMapInfo = taskMap.get(taskId);
    //                             const taskType = taskMapInfo?.taskType || 'download'; // default là download

    //                             if (taskIndex === -1) {
    //                                 // Task mới - thêm vào
    //                                 updated.push({
    //                                     id: taskId,
    //                                     taskName: info.fileName || "Unknown Task",
    //                                     progress: info.progressPercentage || 0,
    //                                     bytesTransferred: info.bytesTransferred || 0,
    //                                     totalBytes: info.totalBytes || 1,
    //                                     status: info.status,
    //                                     taskType: taskType // Thêm taskType
    //                                 });
    //                             } else {
    //                                 // Task đã tồn tại - cập nhật
    //                                 const currentTask = updated[taskIndex];
    //                                 let newStatus = (currentTask.status === 'canceled')
    //                                     ? currentTask.status
    //                                     : info.status;

    //                                 // Check for timeout/stalled downloads (chỉ áp dụng cho download)
    //                                 if (taskType === 'download') {
    //                                     const lastUpdate = taskTimeouts[taskId] || now;
    //                                     const timeSinceLastUpdate = now - lastUpdate;

    //                                     if (newStatus === 'downloading' || newStatus === 'starting') {
    //                                         if (timeSinceLastUpdate >= timeoutThreshold) {
    //                                             newStatus = 'timeout';
    //                                             addNotification(`Tải xuống ${currentTask.taskName} đã bị timeout`, true);
    //                                         } else if (timeSinceLastUpdate >= 30 * 1000) {
    //                                             newStatus = 'stalled';
    //                                         }
    //                                     }
    //                                 }

    //                                 // Update timeout tracking
    //                                 if (currentTask.progress !== info.progressPercentage ||
    //                                     currentTask.bytesTransferred !== info.bytesTransferred) {
    //                                     setTaskTimeouts(prev => ({
    //                                         ...prev,
    //                                         [taskId]: now
    //                                     }));
    //                                 } else {
    //                                     setTaskTimeouts(prev => ({
    //                                         ...prev,
    //                                         [taskId]: taskTimeouts[taskId] || now
    //                                     }));
    //                                 }

    //                                 // Cập nhật task trong mảng
    //                                 updated[taskIndex] = {
    //                                     ...currentTask,
    //                                     taskName: info.fileName || "Unknown Task",
    //                                     progress: info.progressPercentage,
    //                                     bytesTransferred: info.bytesTransferred,
    //                                     totalBytes: info.totalBytes,
    //                                     status: newStatus,
    //                                     taskType: taskType // Giữ nguyên taskType
    //                                 };

    //                                 if (['completed', 'failed', 'canceled', 'timeout'].includes(newStatus)) {
    //                                     completedTasks.push(taskId);
    //                                 }
    //                             }
    //                         });

    //                         return updated;
    //                     });

    //                     // Cleanup completed tasks
    //                     if (completedTasks.length > 0) {
    //                         try {
    //                             await fetch(buildApiUrl('/api/progress/cleanup'), {
    //                                 method: "POST",
    //                                 headers: { "Content-Type": "application/json" },
    //                                 body: JSON.stringify({ taskIds: completedTasks })
    //                             });
    //                             console.log("Đã báo backend cleanup:", completedTasks);
    //                         } catch (err) {
    //                             console.error("Lỗi khi cleanup backend:", err);
    //                         }
    //                     }
    //                 }
    //             }
    //         } catch (error) {
    //             console.error("Lỗi khi truy vấn tiến trình:", error);
    //         }
    //     }
    // };

    useEffect(() => {
        const timer = setTimeout(() => setShowSplash(false), 10000);
        fetchFiles();
        return () => clearTimeout(timer);
    }, []);

    // useEffect(() => {
    //     const interval = setInterval(queryProgress, 2000);
    //     return () => clearInterval(interval);
    // }, [taskMap]); // Dependency trên taskMap thay vì []

    useEffect(() => {
        if (tasks.length > prevTasksLength.current) {
            addNotification('Có tiến trình mới bắt đầu', false);
            setIsNewTask(true);
            setTimeout(() => setIsNewTask(false), 2000); // Reset animation after 2 seconds
        }
        prevTasksLength.current = tasks.length;
    }, [tasks.length]);

    // const handleFileUpload = async () => {
    //     if (!window.electronAPI || !window.electronAPI.selectFile) {
    //         addNotification(t('cannot_load_electronAPI'), true);
    //         return;
    //     }

    //     setIsLoading(true);
    //     try {
    //         const filePath = await window.electronAPI.selectFile();
    //         if (!filePath) {
    //             addNotification(t('no_file_selected'), true);
    //             return;
    //         }

    //         const fileName = filePath.split(/[\\/]/).pop();
    //         const existResponse = await fetch(buildApiUrl(`/api/files/exists?fileName=${encodeURIComponent(fileName)}`));
    //         if (existResponse.ok) {
    //             const { exists } = await existResponse.json();
    //             if (exists) {
    //                 setPendingFile({ filePath, fileName });
    //                 setDialogOpen(true);
    //                 setIsLoading(false);
    //                 return;
    //             }
    //         }

    //         const fileForSharing = {
    //             filePath,
    //             fileName,
    //             fileHash: '',
    //             isReplace: 1
    //         };

    //         setSelectedFileForSharing(fileForSharing);
    //         setShareModalOpen(true);
    //     } catch (error) {
    //         addNotification(t('error_sharing_file', { error: error.message }), true);
    //         console.error('Error in handleFileUpload:', error);
    //     } finally {
    //         setIsLoading(false);
    //     }
    // };

    // const handleDialogClose = async (action) => {
    //     setDialogOpen(false);
    //     console.log('Dialog action:', action);
    //     if (action === -1) {
    //         addNotification(t('cancel_sharing_file'), false);
    //         setPendingFile(null);
    //         return;
    //     }

    //     if (!pendingFile) return;

    //     setIsLoading(true);
    //     try {
    //         const isReplace = action === 1 ? 1 : 0;

    //         const fileForSharing = {
    //             filePath: pendingFile.filePath,
    //             fileName: pendingFile.fileName,
    //             isReplace: isReplace
    //         };

    //         setSelectedFileForSharing(fileForSharing);
    //         setShareModalOpen(true);
    //     } catch (error) {
    //         addNotification(t('error_sharing_file', { error: error.message }), true);
    //         console.error('Error in handleDialogClose:', error);
    //     } finally {
    //         setPendingFile(null);
    //         setIsLoading(false);
    //     }
    // };

    // const handleDownload = async (file) => {
    //     if (!window.electronAPI) {
    //         addNotification(t('cannot_load_electronAPI'), true);
    //         return;
    //     }

    //     setIsLoading(true);
    //     try {
    //         const savePath = await window.electronAPI.saveFile(file.fileName);
    //         if (!savePath) {
    //             addNotification(t('no_file_selected'), true);
    //             return;
    //         }

    //         addNotification(t('start_download_file', { fileName: file.fileName }), false);
    //         const progressId = await window.electronAPI.downloadFile({
    //             fileName: file.fileName.trim(),
    //             peerInfo: `${file.peerInfo.ip}:${file.peerInfo.port}`,
    //             savePath
    //         });
    //         console.log('Progress ID:', progressId);
    //         // Thêm taskType: 'download'
    //         taskMap.set(progressId, { 
    //             fileName: file.fileName.trim(), 
    //             status: 'starting',
    //             taskType: 'download'
    //         });
    //     } catch (error) {
    //         addNotification(t('error_downloading_file', { error: error.message }), true);
    //         console.error('Error in handleDownload:', error);
    //     } finally {
    //         setIsLoading(false);
    //     }
    // };

    // const handleStopSharing = async (file) => {
    //     setIsLoading(true);
    //     try {
    //         const controller = new AbortController();
    //         const timeoutId = setTimeout(() => controller.abort(), 10000);
    //         const response = await fetch(buildApiUrl('/api/files/remove'), {
    //             method: 'DELETE',
    //             headers: { 'Content-Type': 'application/json' },
    //             body: JSON.stringify({ fileName: file.fileName }),
    //             signal: controller.signal
    //         });
    //         clearTimeout(timeoutId);
    //         if (response.ok) {
    //             fetchFiles();   
    //             addNotification(t('cancel_sharing_file', { fileName: file.fileName }), false);
    //         } else {
    //             const errorData = await response.json();
    //             throw new Error(t('error_cancel_sharing_file', { error: errorData.error || response.status }));
    //         }
    //     } catch (error) {
    //         addNotification(t('error_cancel_sharing_file', { error: error.message }), true);
    //     } finally {
    //         setIsLoading(false);
    //     }
    // };

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

//     const handleShareToPeers = async (file, isReplace) => {
//         // For simplicity, we'll use a prompt to get peer IPs
//         const peerInput = prompt('Nhập danh sách IP peer (cách nhau bằng dấu phẩy):', '192.168.1.1:8080,192.168.1.2:8080');
//         if (!peerInput) return;

//         const peerList = peerInput.split(',').map(peer => peer.trim()).filter(peer => peer);
//         if (peerList.length === 0) {
//             addNotification('Không có peer nào được chọn', true);
//             return;
//         }

//         setIsLoading(true);
//         try {
//             const response = await fetch(buildApiUrl('/api/files/share-to-peers'), {
//                 method: 'POST',
//                 headers: { 'Content-Type': 'application/json' },
//                 body: JSON.stringify({
//                     filePath: file.filePath,
//                     isReplace: isReplace,
//                     peers: peerList
//                 })
//             });

//             console.log('progressId:', response.body);
//             taskMap.set(response.body, { fileName: file.fileName, status: 'starting' });
//             fetchFiles();

//         } catch (error) {
//             addNotification(`Lỗi khi chia sẻ file: ${error.message}`, true);
//         } finally {
//             setIsLoading(false);
//         }
//     };

//     const handleShareAll = async (file) => {
//     setIsLoading(true);
//     try {
//         console.log('handleShareAll started'); // Log đầu tiên để confirm hàm chạy
//         console.log('file:', file);
//         console.log('window.electronAPI exists?', !!window.electronAPI); // Check API

//         if (!window.electronAPI) {
//             throw new Error('electronAPI not available - running in browser?');
//         }

//         const progressId = await window.electronAPI.shareFile(file.filePath, file.isReplace);
//         console.log('progressId received:', progressId); // Log ngay sau await

//         // Thêm taskType: 'share'
//         taskMap.set(progressId, { 
//             fileName: file.fileName, 
//             status: 'starting',
//             taskType: 'share'
//         });
        
//         console.log('task:', taskMap.get(progressId));
//         console.log('taskMap:', taskMap);
//         console.log('taskMap size:', taskMap.size);
//         console.log('tasktype:', taskMap.get(progressId)?.taskType);
//         console.log('taskMap entries:', Array.from(taskMap.entries())); // Fix: Log entries đúng cách

//         addNotification(t('start_sharing_file', { fileName: file.fileName }), false);
//     } catch (error) {
//         console.error('Error in handleShareAll:', error); // Đã có
//         addNotification(t('error_sharing_file', { error: error.message }), true);
//     } finally {
//         setIsLoading(false);
//     }
// };

//     const handleShareSelective = async (file, selectedPeers) => {
//         setIsLoading(true);
//         try {
//             console.log('Sharing file to selected peers:', file);
//             console.log('Replace option:', file.isReplace);
//             const response = await fetch(buildApiUrl('/api/files/share-to-peers'), {
//                 method: 'POST',
//                 headers: { 'Content-Type': 'application/json' },
//                 body: JSON.stringify({
//                     filePath: file.filePath,
//                     isReplace: file.isReplace,
//                     peers: selectedPeers
//                 })
//             });

//             if (response.ok) {
//                 const progressId = await response.json();
//                 // Thêm taskType: 'share'
//                 taskMap.set(progressId, { 
//                     fileName: file.fileName, 
//                     status: 'starting',
//                     taskType: 'share'
//                 });
//                 fetchFiles();
//             } else {
//                 const errorData = await response.json();
//                 throw new Error(errorData.error || `Lỗi: ${response.status}`);
//             }
//         } catch (error) {
//             addNotification(`Lỗi khi chia sẻ file: ${error.message}`, true);
//         } finally {
//             setIsLoading(false);
//         }
//     };

    const handleResume = async (taskId) => {
        try {
            const response = await fetch(buildApiUrl(`/api/resume?taskId=${taskId}`), {
                method: 'POST'
            });

            if (response.ok) {
                setTasks(prev =>
                    prev.map(t =>
                        t.id === taskId ? { ...t, status: 'downloading' } : t
                    )
                );
                addNotification('Đã tiếp tục tải xuống', false);
            } else {
                addNotification('Lỗi khi tiếp tục tải xuống', true);
            }
        } catch (err) {
            console.error('Lỗi khi resume task:', err);
            addNotification('Lỗi khi tiếp tục tải xuống', true);
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
                <div className="flex justify-between items-center mb-8">
                    <h1 className="text-4xl font-extrabold text-blue-900 tracking-tight">{t('title')}</h1>
                    <button
                        onClick={() => setActiveTab(activeTab === 'tasks' ? 'files' : 'tasks')}
                        className={`p-3 rounded-lg transition-all duration-200 relative ${activeTab === 'tasks' ? 'bg-blue-600 text-white shadow-md' : 'bg-white text-blue-600 hover:bg-blue-50 shadow-lg border border-gray-100'} ${isNewTask ? 'animate-pulse bg-green-500' : ''}`}
                        title={t('tasks')}
                    >
                        <svg className={`w-6 h-6 ${isNewTask ? 'animate-bounce' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                        </svg>
                        {tasks.length > 0 && (
                            <span className="absolute -top-1 -right-1 bg-red-500 text-white text-xs rounded-full h-5 w-5 flex items-center justify-center">
                                {tasks.length}
                            </span>
                        )}
                    </button>
                </div>
                <div className="flex space-x-4 mb-6 bg-white rounded-xl shadow-lg p-2 border border-gray-100">
                    <button
                        onClick={() => setActiveTab('files')}
                        className={`flex-1 py-3 text-lg font-semibold rounded-lg transition-all duration-200 flex items-center justify-center space-x-2 ${activeTab === 'files' ? 'bg-blue-600 text-white shadow-md' : 'text-gray-600 hover:bg-gray-50'}`}
                    >
                        <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"></path>
                        </svg>
                        <span>{t('files')}</span>
                    </button>
                    <button
                        onClick={() => setActiveTab('chat')}
                        className={`flex-1 py-3 text-lg font-semibold rounded-lg transition-all duration-200 flex items-center justify-center space-x-2 ${activeTab === 'chat' ? 'bg-blue-600 text-white shadow-md' : 'text-gray-600 hover:bg-gray-50'}`}
                    >
                        <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"></path>
                        </svg>
                        <span>{t('chat')}</span>
                    </button>
                </div>
                {/* Tab Content */}
                {activeTab === 'files' && (
                    <FilesPage
                        isLoading={isLoading}
                        setIsLoading={setIsLoading}
                        addNotification={addNotification}
                        taskMap={taskMap}
                        files={files}
                        setFiles={setFiles}
                        searchTerm={searchTerm}
                        setSearchTerm={setSearchTerm}
                        onSearch={handleSearch}
                        onRefresh={handleRefresh}
                        onMyFiles={handleMyFiles}
                        onAllFiles={handleAllFiles}
                        language={language}
                        changeLanguage={changeLanguage}
                    />
                )}
                {activeTab === 'chat' && (
                    <ChatPage 
                        addNotification={addNotification}
                        peers={peers}
                        messages={messages}
                        onSendMessage={handleSendMessage}
                        selectedPeer={selectedPeer}
                        setSelectedPeer={setSelectedPeer}
                    />
                )}
                {activeTab === 'tasks' && (
                    <Tasks
                        tasks={tasks}
                        setTasks={setTasks}
                        onResume={handleResume}
                    />
                )}
            </div>
            {/* Notifications */}
            <div className="fixed top-4 right-4 z-50 space-y-2">
                {notifications.map((notification) => (
                    <Notification
                        key={notification.id}
                        message={notification.message}
                        isError={notification.isError}
                        onClose={() => removeNotification(notification.id)}
                    />
                ))}
            </div>
            {/* Các modal khác nếu cần
            <ConfirmDialog open={dialogOpen} onClose={FilesPage.handleDialogClose} />
            <ShareModal
                isOpen={shareModalOpen}
                onClose={() => setShareModalOpen(false)}
                onShareAll={handleShareAll}
                onShareSelective={handleShareSelective}
                file={selectedFileForSharing}
            /> */}
        </>
    );
}

export default App;