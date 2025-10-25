import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import FileTable from '../components/FileTable';
import ShareModal from '../components/ShareModal';
import ConfirmDialog from '../components/ConfirmDialog';
import { buildApiUrl } from '../utils/config';
import IconClip from '../assets/link_icon.svg';
import IconSearch from '../assets/search_icon.svg';
import IconRefresh from '../assets/refresh_icon.svg';

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
        if (!response.ok) throw new Error(`Lỗi khi tải danh sách tệp: ${response.status}`);

        const contentType = response.headers.get('content-type');
        if (!contentType || !contentType.includes('application/json')) {
          throw new Error('Server trả về dữ liệu không phải JSON');
        }

        const data = await response.json();

        setAllFiles(data);
        setFiles(activeFilter === 'my' ? data.filter(f => f.isSharedByMe) : data);

        addNotification('Đã tải danh sách tệp', false);
      } catch (error) {
        addNotification('Lỗi khi tải danh sách tệp', true);
        console.error(error);
      } finally {
        setIsLoading(false);
      }
    };


    // thêm
    const [activeFilter, setActiveFilter] = useState('all'); // 'my' | 'all'
    const [allFiles, setAllFiles] = useState([]);


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
        setActiveFilter('my');
        setFiles(allFiles.filter(f => f.isSharedByMe));
        addNotification(t('show_my_files'), false);
        };

    const handleAllFiles = () => {
        setActiveFilter('all');
        setFiles(allFiles);
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

            <div className="text-center mb-6">
              <div
                role="button"
                onClick={handleFileUpload}
                className="inline-block rounded-2xl border-2 border-dashed border-[#196BAD] px-10 py-6 hover:bg-sky-50 transition"
                style={{ minWidth: 520 }}
                title="Chọn tệp để tải lên"
              >
                <img src={IconClip} alt="" className="inline-block h-4 w-5 mr-2 align-[-2px]" />
                <span className="font-semibold text-sky-700">{t('selectFile')}</span>
              </div>
            </div>

            {/* Ô tìm kiếm + 2 nút */}
            <div className="flex gap-3 mb-4">
              <input
                type="text"
                placeholder="Nhập tên file cần tìm"
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
                className="flex-1 h-11 rounded-xl border border-gray-200 px-4 outline-none focus:border-sky-400"
                disabled={isLoading}
              />
              <button
                 onClick={handleSearch}
                 className="h-11 px-4 rounded-xl border border-gray-200 bg-white hover:bg-gray-50 flex items-center gap-2"
                 disabled={isLoading}
              >
                 <img src={IconSearch} alt="" className="h-5 w-5" />
                 <span className="font-semibold">{t('search')}</span>
              </button>
              <button
                onClick={handleRefresh}
                className="h-11 px-4 rounded-xl border border-gray-200 bg-white hover:bg-gray-50 flex items-center gap-2"
                disabled={isLoading}
              >
                 {isLoading ? (
                    <img src={IconRefresh} alt="" className="h-5 w-5 animate-spin" />
                 ) : (
                    <img src={IconRefresh} alt="" className="h-5 w-5" />
                 )}
                <span className="font-semibold">{t('refresh')}</span>
              </button>
            </div>

            <div className="mb-4 flex justify-end">
              <div className="inline-flex items-center rounded-2xl bg-slate-200 p-1">
                <button
                  onClick={handleMyFiles}
                  disabled={isLoading}
                  className={`px-4 py-2 text-sm font-semibold rounded-xl transition
                    ${activeFilter === 'my'
                      ? 'bg-white text-slate-800 shadow ring-1 ring-slate-300'
                      : 'text-slate-500 hover:text-slate-700'}`}
                >
                  <span className="font-semibold">{t('myFiles')}</span>
                </button>

                <button
                  onClick={handleAllFiles}
                  disabled={isLoading}
                  className={`ml-1 px-4 py-2 text-sm font-semibold rounded-xl transition
                    ${activeFilter === 'all'
                      ? 'bg-white text-slate-800 shadow ring-1 ring-slate-300'
                      : 'text-slate-500 hover:text-slate-700'}`}
                >
                  <span className="font-semibold">{t('allFiles')}</span>
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
