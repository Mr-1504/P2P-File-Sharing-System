import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import FileTable from '../components/FileTable';
import ShareModal from '../components/ShareModal';
import ConfirmDialog from '../components/ConfirmDialog';
import { buildApiUrl } from '../utils/config';

const FilesPage = ({ isLoading, setIsLoading, addNotification, taskMap }) => {
    const { t, i18n } = useTranslation();
    const [files, setFiles] = useState([]);
    const [searchTerm, setSearchTerm] = useState('');
    const [language, setLanguage] = useState('Tiếng Việt');
    const [dialogOpen, setDialogOpen] = useState(false);
    const [pendingFile, setPendingFile] = useState(null);
    const [shareModalOpen, setShareModalOpen] = useState(false);
    const [selectedFileForSharing, setSelectedFileForSharing] = useState(null);

    const changeLanguage = (lng) => {
        i18n.changeLanguage(lng === "Tiếng Việt" ? "vi" : "en");
        setLanguage(lng);
    };

    const fetchFiles = async () => {
        setIsLoading(true);
        try {
            const response = await fetch(buildApiUrl('/api/files/refresh'), {
                method: 'GET',
                headers: { 'Content-Type': 'application/json' }
            });
            
            if (!response.ok) {
                throw new Error(`Lỗi khi tải danh sách tệp: ${response.status}`);
            }
            
            const contentType = response.headers.get('content-type');
            if (!contentType || !contentType.includes('application/json')) {
                throw new Error('Server trả về dữ liệu không phải JSON');
            }
            
            const data = await response.json();
            setFiles(data);
            addNotification('Đã tải danh sách tệp', false);
        } catch (error) {
            addNotification('Lỗi khi tải danh sách tệp', true);
            console.error(error);
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => {
        fetchFiles();
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
            
            // Check if file already exists
            const response = await fetch(buildApiUrl(`/api/files/exists?fileName=${encodeURIComponent(fileName)}`));
            const result = response.ok ? await response.json() : { exists: false };
            
            if (result.exists) {
                setPendingFile({ filePath, fileName });
                setDialogOpen(true);
                setIsLoading(false);
                return;
            }

            const fileForSharing = {
                filePath,
                fileName,
                fileHash: '',
                isReplace: 1
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
        if (action === -1) {
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
            // ✅ Thêm taskType: 'download'
            taskMap.set(progressId, { 
                fileName: file.fileName.trim(), 
                status: 'starting',
                taskType: 'download'
            });
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
            
            const response = await fetch(buildApiUrl('/api/files/remove'), {
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
                throw new Error(errorData.error || response.status);
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
            const filteredFiles = files.filter(file => 
                file.fileName.toLowerCase().includes(searchTerm.toLowerCase())
            );
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

    const handleShareAll = async (file) => {
        setIsLoading(true);
        try {
            console.log('Sharing file to all peers:', file);
            console.log('Replace option:', file.isReplace);
            const progressId = await window.electronAPI.shareFile(file.filePath, file.isReplace);
            console.log('Progress ID:', progressId);
            // ✅ Thêm taskType: 'share'
            taskMap.set(progressId, { 
                fileName: file.fileName, 
                status: 'starting',
                taskType: 'share'
            });
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
            console.log('Sharing file to selected peers:', file);
            console.log('Replace option:', file.isReplace);
            const response = await fetch(buildApiUrl('/api/files/share-to-peers'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    filePath: file.filePath,
                    isReplace: file.isReplace,
                    peers: selectedPeers
                })
            });

            if (response.ok) {
                const progressId = await response.json();
                // ✅ Thêm taskType: 'share'
                taskMap.set(progressId, { 
                    fileName: file.fileName, 
                    status: 'starting',
                    taskType: 'share'
                });
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

    const handleShareToPeers = async (file) => {
        setSelectedFileForSharing(file);
        setShareModalOpen(true);
    };

    return (
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
            
            <FileTable 
                files={files} 
                onDownload={handleDownload} 
                onStopSharing={handleStopSharing} 
                onShareToPeers={handleShareToPeers} 
                isLoading={isLoading} 
            />

            <ConfirmDialog open={dialogOpen} onClose={handleDialogClose} />
            <ShareModal
                isOpen={shareModalOpen}
                onClose={() => setShareModalOpen(false)}
                onShareAll={handleShareAll}
                onShareSelective={handleShareSelective}
                file={selectedFileForSharing}
            />
        </div>
    );
};

export default FilesPage;
