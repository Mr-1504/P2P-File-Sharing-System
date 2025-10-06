import React, { useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import i18n from './utils/i18n';
import logo from './assets/logo.png';
import FilesPage from './pages/FilesPage';
import ChatPage from './pages/ChatPage';
import Tasks from './components/Tasks';
import Notification from './components/Notification';
import { useNotifications } from './hooks/useNotifications';
import { useTasks } from './hooks/useTasks';
// import './styles/App.css';

import { buildApiUrl } from './utils/config';

function App() {
    const { t } = useTranslation();
    const [activeTab, setActiveTab] = useState('files');
    const [isLoading, setIsLoading] = useState(false);
    const [showSplash, setShowSplash] = useState(false);
    const [files, setFiles] = useState([]);
    const [searchTerm, setSearchTerm] = useState('');
    const [language, setLanguage] = useState('vi');
    const [messages, setMessages] = useState({});
    const [selectedPeer, setSelectedPeer] = useState(null);
    const [isNewTask, setIsNewTask] = useState(false);

    const { notifications, addNotification, removeNotification } = useNotifications();
    const { tasks, setTasks, taskMap, handleResume } = useTasks(addNotification);

    const prevTasksLength = useRef(0);

    useEffect(() => {
        if (tasks.length > prevTasksLength.current) {
            addNotification(t('new_task_started'), false);
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
                    addNotification(t('files_loaded'), false);
                } else {
                    throw new Error('Server trả về dữ liệu không phải JSON');
                }
            } else {
                throw new Error(`Lỗi khi tải danh sách tệp: ${response.status}`);
            }
        } catch (error) {
            addNotification(t('error_loading_files'), true);
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
                <div className="fixed inset-0 bg-gradient-to-br from-blue-600 to-indigo-700 bg-opacity-95 flex flex-col items-center justify-center z-50 animate-fade-in backdrop-blur-sm">
                    <div className="relative">
                        <svg className="w-24 h-24 mb-6 text-white animate-pulse" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                        </svg>
                        <div className="absolute inset-0 rounded-full border-4 border-white border-opacity-30 animate-ping"></div>
                    </div>
                    <h2 className="text-3xl font-bold text-white mb-4 tracking-wider">{t('loading')}</h2>
                    <div className="w-16 h-16 border-4 border-t-transparent border-white rounded-full animate-spin shadow-lg"></div>
                </div>
            )}
            <div className={`container mx-auto p-8 max-w-7xl ${showSplash ? 'opacity-0' : 'opacity-100'} transition-opacity duration-700`}>
                <div className="flex justify-between items-center mb-10">
                    <div className="flex items-center space-x-4">
                        <img src={logo} alt="P2P File Sharing Logo" className="w-12 h-12 rounded-xl shadow-lg" />
                        <h1 className="text-4xl font-extrabold text-gray-800 tracking-tight">{t('title')}</h1>
                    </div>
                    <button
                        onClick={() => setActiveTab(activeTab === 'tasks' ? 'files' : 'tasks')}
                        className={`p-4 rounded-xl transition-all duration-300 relative ${activeTab === 'tasks' ? 'modern-button' : 'bg-white hover:bg-blue-50 shadow-md border border-gray-200'} ${isNewTask ? 'animate-pulse' : ''}`}
                        title={t('tasks')}
                    >
                        <svg className={`w-6 h-6 ${activeTab === 'tasks' ? 'text-white' : 'text-blue-600'} ${isNewTask ? 'animate-bounce' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                        </svg>
                        {tasks.length > 0 && (
                            <span className="absolute -top-1 -right-1 bg-red-500 text-white text-xs rounded-full h-6 w-6 flex items-center justify-center font-bold shadow-md animate-pulse">
                                {tasks.length}
                            </span>
                        )}
                    </button>
                </div>
                <div className="modern-tab flex space-x-2 mb-8 p-3">
                    <button
                        onClick={() => setActiveTab('files')}
                        className={`modern-tab-button flex-1 py-4 text-lg font-semibold flex items-center justify-center space-x-3 ${activeTab === 'files' ? 'active' : 'text-gray-700 hover:text-blue-600'}`}
                    >
                        <svg className={`w-5 h-5 ${activeTab === 'files' ? 'text-white' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"></path>
                        </svg>
                        <span>{t('files')}</span>
                    </button>
                    <button
                        onClick={() => setActiveTab('chat')}
                        className={`modern-tab-button flex-1 py-4 text-lg font-semibold flex items-center justify-center space-x-3 ${activeTab === 'chat' ? 'active' : 'text-gray-700 hover:text-blue-600'}`}
                    >
                        <svg className={`w-5 h-5 ${activeTab === 'chat' ? 'text-white' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
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
        </>
    );
}

export default App;
