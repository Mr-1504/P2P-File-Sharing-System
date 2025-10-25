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
      <div className={`container mx-auto p-8 max-w-7xl ${showSplash ? 'opacity-0' : 'opacity-100'} transition-opacity duration-700`}>
        <div className="flex items-center justify-between mb-8">
          <div className="flex items-center gap-3">
            <img src={logo} alt="logo" className="w-14 h-14 rounded-2xl shadow" />
            <h1 className="text-2xl font-extrabold tracking-wide text-sky-700">P2P FILE SHARING</h1>
          </div>

          <div className="flex items-center gap-2">
            <button
              type="button"
              title={t('tasks')}
              onClick={() => setActiveTab(activeTab === 'tasks' ? 'files' : 'tasks')}
              className="
                            h-10 w-10 rounded-full
                            grid place-items-center
                            border border-gray-200 bg-white hover:bg-gray-50 shadow-sm
                            focus:outline-none focus:ring-2 focus:ring-sky-300
                          "
              aria-label={t('tasks')}
            >
              <svg className="w-5 h-5 text-sky-700" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"
                  d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            </button>

            <button
              type="button"
              onClick={() => changeLanguage(language === 'vi' ? 'en' : 'vi')}
              className="
                        relative h-12 w-40 rounded-full p-1 text-white
                        shadow-[inset_0_-2px_6px_rgba(0,0,0,.25)]
                        bg-gradient-to-r from-[#1a74fd] via-[#23a1f0] to-[#3aceb2]
                        focus:outline-none focus:ring-2 focus:ring-white/70
                      "
              role="switch"
              aria-checked={language === 'en'}
              title={language === 'vi' ? 'Chuyển sang English' : 'Switch to Vietnamese'}
            >
              <span
                className={`
    absolute left-1 top-1 h-10 w-10 rounded-full bg-white
    ring-1 ring-black/10 shadow-md grid place-items-center
    transition-transform duration-300 ease-out
    ${language === 'en' ? 'translate-x-[113px]' : 'translate-x-0'}
  `}
              >
                {language === 'vi' ? (
                  /* Vietnam Flag */
                  <svg width="32" height="32" viewBox="0 0 32 32" xmlns="http://www.w3.org/2000/svg">
                    <circle cx="16" cy="16" r="16" fill="#DA251D" />
                    <polygon
                      points="16,6 18.09,13.26 26,13.26 19.45,17.74 21.55,25 16,20 10.45,25 12.55,17.74 6,13.26 13.91,13.26"
                      fill="#FFDD00"
                    />
                  </svg>
                ) : (
                  /* UK Flag in Circle */
                  <svg width="32" height="32" viewBox="0 0 32 32" xmlns="http://www.w3.org/2000/svg">
                    <defs>
                      <clipPath id="circleClip">
                        <circle cx="16" cy="16" r="16" />
                      </clipPath>
                    </defs>
                    <g clipPath="url(#circleClip)">
                      <rect width="32" height="32" fill="#012169" />
                      <path d="M0 0 L32 32 M32 0 L0 32" stroke="#fff" strokeWidth="4" />
                      <path d="M0 0 L32 32 M32 0 L0 32" stroke="#C8102E" strokeWidth="2" />
                      <rect x="13" y="0" width="6" height="32" fill="#fff" />
                      <rect x="0" y="13" width="32" height="6" fill="#fff" />
                      <rect x="14" y="0" width="4" height="32" fill="#C8102E" />
                      <rect x="0" y="14" width="32" height="4" fill="#C8102E" />
                    </g>
                  </svg>
                )}


              </span>


              <span
                className={`
                          absolute left-14 top-1/2 -translate-y-1/2 font-semibold tracking-wide
                          transition-opacity duration-200
                          ${language === 'en' ? 'opacity-0' : 'opacity-100'}
                        `}
              >
                Tiếng Việt
              </span>

              <span
                className={`
                          absolute right-14 top-1/2 -translate-y-1/2 font-semibold tracking-wide
                          transition-opacity duration-200
                          ${language === 'en' ? 'opacity-100' : 'opacity-0'}
                        `}
              >
                English
              </span>
            </button>

          </div>
        </div>

        <div className="flex justify-center mb-6">
          <div className="bg-sky-800 rounded-full p-1 w-[520px]">
            <div className="grid grid-cols-2 gap-1">
              <button
                onClick={() => setActiveTab('files')}
                className={`rounded-full py-2 font-semibold ${activeTab === 'files' ? 'bg-white text-[#196BAD]' : 'text-white hover:text-white'
                  }`}
              >
                File
              </button>
              <button
                onClick={() => setActiveTab('chat')}
                className={`rounded-full py-2 font-semibold ${activeTab === 'chat' ? 'bg-white/90 text-[#196BAD]' : 'text-white/90 hover:text-white'
                  }`}
              >
                Chat
              </button>
            </div>
          </div>
        </div>

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
