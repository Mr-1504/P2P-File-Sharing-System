import React, { useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import i18n from './utils/i18n';
import logo from './assets/logo.png';
import FilesPage from './pages/FilesPage';
import ChatPage from './pages/ChatPage';
import Tasks from './components/Tasks';
import Notification from './components/Notification';
import UsernameDialog from './components/UsernameDialog';
import { useNotifications } from './hooks/useNotifications';
import { useTasks } from './hooks/useTasks';
import { Globe2, Clock } from 'lucide-react';
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
    const [isNewTask, setIsNewTask] = useState(false);
    const [showUsernameDialog, setShowUsernameDialog] = useState(false);

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
        const initApp = async () => {
            try {
                // Check if backend has username set
                const checkResponse = await fetch(buildApiUrl('/api/check-username'), {
                    method: 'GET',
                    headers: { 'Content-Type': 'application/json' }
                });

                if (!checkResponse.ok) {
                    throw new Error('Failed to check username');
                }

                const checkData = await checkResponse.json();
                const hasUsername = checkData.hasUsername;

                if (!hasUsername) {
                    // No username set, show dialog
                    setShowUsernameDialog(true);
                    setShowSplash(false); // Hide splash while showing dialog
                    return;
                }

                // Username exists, proceed with normal init
                await fetchFiles();

            } catch (error) {
                console.error('Error during app initialization:', error);
                addNotification('Lỗi khởi tạo ứng dụng', true);
                // Show dialog anyway if can't check
                setShowUsernameDialog(true);
                setShowSplash(false);
            }
        };

        const timer = setTimeout(() => setShowSplash(false), 10000);
        initApp();

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

    return (
        <>
            {showSplash && (
                <div className="fixed inset-0 bg-gradient-to-br from-blue-600 to-indigo-700 bg-opacity-95 flex flex-col items-center justify-center z-50 animate-fade-in backdrop-blur-sm">
                    <div className="relative">
                        <svg className="w-[55px] h-[55px] mb-6 text-white animate-pulse" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="4" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                        </svg>
                        <div className="absolute inset-0 rounded-full border-4 border-white border-opacity-30 animate-ping"></div>
                    </div>
                    <h2 className="text-3xl font-bold text-white mb-4 tracking-wider">{t('loading')}</h2>
                    <div className="w-16 h-16 border-4 border-t-transparent border-white rounded-full animate-spin shadow-lg"></div>
                </div>
            )}
            <div className={`container mx-auto p-8 max-w-7xl bg-white ${showSplash ? 'opacity-0' : 'opacity-100'} transition-opacity duration-700`}>
                <div className="flex justify-between items-center mb-10">
                    <div className="flex items-center space-x-4 ml-[62px]">
                        <img src={logo} alt="P2P File Sharing Logo" className="w-12 h-12 rounded-xl shadow-lg ml-[-4px]" />
                        <h1
                            className="
                                text-[28px] leading-[125%]
                                font-semibold italic
                                bg-gradient-to-r from-[#1A73FF] via-[#27A1DD] to-[#35D0BA]
                                bg-clip-text text-transparent
                                tracking-tight
                            "
                            style={{ fontFamily: 'Inter, -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, sans-serif' }}
                        >
                            {t('title')}
                        </h1>
                    </div>
                    <div className="flex items-center gap-4">
                        <button
                            onClick={() => setActiveTab(activeTab === 'tasks' ? 'files' : 'tasks')}
                            className={`
                                rounded-md bg-[#C9DEEF] border border-[#1A73FF33] flex items-center justify-center shadow-sm hover:brightness-105 focus:outline-none focus-visible:ring-2 focus-visible:ring-[#1A73FF]
                                relative
                                transition-all duration-300
                                ${isNewTask ? 'animate-pulse' : ''}
                            `}
                            title={t('tasks')}
                            aria-label="Tasks"
                            style={{ width: '55px', height: '55px' }}
                        >
                            <Clock className="w-5 h-5 text-[#1A73FF]" aria-hidden />
                            {tasks.length > 0 && (
                                <span className="absolute -top-1 -right-1 bg-red-500 text-white text-xs rounded-full h-6 w-6 flex items-center justify-center font-bold shadow-md animate-pulse">
                                    {tasks.length}
                                </span>
                            )}
                        </button>
                        <button
                            onClick={() => changeLanguage(language === 'vi' ? 'en' : 'vi')}
                            className="p-3 rounded-xl bg-white hover:bg-blue-50 shadow-md border border-gray-200 flex items-center justify-center gap-2"
                            title={language === 'vi' ? 'Switch to English' : 'Chuyển sang Tiếng Việt'}
                            aria-label="Toggle language"
                            style={{ width: '150px' }}
                        >
                            <span className="whitespace-nowrap" style={{ color: '#196BAD', fontFamily: 'DM Sans', fontWeight: 'bold', fontSize: '15px' }}>
                                {language === 'vi' ? 'Tiếng Việt' : 'English'}
                            </span>
                            <Globe2 className="w-9 h-9 text-[#1A73FF]" />
                        </button>
                    </div>
                </div>
                {/* Tabs (Pill style) */}
                <div className="w-full max-w-[763px] mx-auto mb-8">
                  {/* Nền xanh pill */}
                  <div className="bg-[#196BAD] rounded-full p-1 border-2 border-[#196BAD] shadow-sm">
                    <div className="flex">
                      {/* FILE */}
                      <button
                        onClick={() => setActiveTab('files')}
                        aria-pressed={activeTab === 'files'}
                        className={`flex-1 py-[9px] text-[18px] font-semibold tracking-tight rounded-full transition-all duration-200 focus:outline-none ${
                          activeTab === 'files'
                            ? 'bg-white text-[#196BAD] ring-2 ring-[#196BAD]' // ACTIVE: nền trắng viền xanh
                            : 'text-white'                            // INACTIVE: chữ trắng trên nền xanh
                        }`}
                      >
                        {t('files')}
                      </button>

                      {/* CHAT */}
                      <button
                        onClick={() => setActiveTab('chat')}
                        aria-pressed={activeTab === 'chat'}
                        className={`flex-1 py-[9px] text-[18px] font-semibold tracking-tight rounded-full transition-all duration-200 focus:outline-none ${
                          activeTab === 'chat'
                            ? 'bg-white text-[#196BAD] ring-2 ring-[#196BAD]'
                            : 'text-white'
                        }`}
                      >
                        {t('chat')}
                      </button>
                    </div>
                  </div>
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
            {/* Username Dialog */}
            <UsernameDialog
                isOpen={showUsernameDialog}
                onClose={() => {
                    setShowUsernameDialog(false);
                    // Trigger app reinitialization when dialog closes
                    // Backend should now be initialized, so fetch files
                    fetchFiles();
                }}
            />
        </>
    );
}

export default App;
