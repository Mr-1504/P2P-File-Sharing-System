import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { buildApiUrl } from '../utils/config';

const ShareModal = ({ isOpen, onClose, onShareAll, onShareSelective, file }) => {
    const { t } = useTranslation();
    const [selectedPeers, setSelectedPeers] = useState([]);
    const [availablePeers, setAvailablePeers] = useState([]);
    const [sharedPeers, setSharedPeers] = useState([]);
    const [loading, setLoading] = useState(false);
    const [loadingCount, setLoadingCount] = useState(0);

    useEffect(() => {
        if (isOpen && file) {
            setLoading(true);
            setLoadingCount(2); // Two API calls
            setSelectedPeers([]); // Reset selection
            setSharedPeers([]);
            fetchKnownPeers();
            fetchSharedPeers();
        } else if (!isOpen) {
            // Reset states when modal closes
            setSelectedPeers([]);
            setAvailablePeers([]);
            setSharedPeers([]);
            setLoading(false);
            setLoadingCount(0);
        }
    }, [isOpen, file]);

    const fetchKnownPeers = async () => {
        try {
            const response = await fetch(buildApiUrl('/api/peers/known'));
            if (response.ok) {
                const peers = await response.json();
                setAvailablePeers(peers);
            }
        } catch (error) {
            console.error('Error fetching known peers:', error);
        } finally {
            setLoadingCount(prev => {
                const newCount = prev - 1;
                if (newCount === 0) setLoading(false);
                return newCount;
            });
        }
    };

    const fetchSharedPeers = async () => {
        if (!file?.fileName) {
            setLoadingCount(prev => {
                const newCount = prev - 1;
                if (newCount === 0) setLoading(false);
                return newCount;
            });
            return;
        }

        try {
            const response = await fetch(buildApiUrl(`/api/files/${encodeURIComponent(file.fileName)}/shared-peers`));
            if (response.ok) {
            const data = await response.json();
            setSharedPeers(data.peers);        
            setSelectedPeers(data.peers);
        } else {
            // Thêm xử lý khi response không ok
            setSharedPeers([]);
            setSelectedPeers([]);
        }
        } catch (error) {
            console.error('Error fetching shared peers:', error);
            setSharedPeers([]);
            setSelectedPeers([]);
        } finally {
            setLoadingCount(prev => {
                const newCount = prev - 1;
                if (newCount === 0) setLoading(false);
                return newCount;
            });
        }
    };

    const handlePeerToggle = (peer) => {
        const peerKey = peer.ip; // Use ip as unique identifier
        setSelectedPeers(prev => {
            const current = prev || [];
            return current.some(p => p.ip === peerKey)
                ? current.filter(p => p.ip !== peerKey)
                : [...current, peer];
        });
    };

    const handleShareAll = async () => {
        if (file.filePath) {
            // File mới - sử dụng logic cũ
            onShareAll(file);
        } else {
            // File đã chia sẻ - cập nhật permission thành PUBLIC
            try {
                const response = await fetch(buildApiUrl(`/api/files/${encodeURIComponent(file.fileName)}/permission`), {
                    method: 'PUT',
                    mode: 'cors',
                    headers: {
                        'Content-Type': 'application/json'
                    },
                    body: JSON.stringify({
                        permission: 'PUBLIC'
                    })
                });

                if (!response.ok) {
                    const errorData = await response.json();
                    throw new Error(errorData.error || `Lỗi: ${response.status}`);
                }

                alert('Đã chia sẻ file cho tất cả peers');
            } catch (error) {
                alert(`Lỗi khi chia sẻ file: ${error.message}`);
                return; // Don't close modal on error
            }
        }
        onClose();
    };

    const handleShareSelective = async () => {
        if (!(selectedPeers && selectedPeers.length > 0)) {
            alert('Vui lòng chọn ít nhất một peer');
            return;
        }

        if (file.filePath) {
            // File mới - sử dụng logic cũ
            onShareSelective(file, selectedPeers);
        } else {
            // File đã chia sẻ - cập nhật permission thành PRIVATE với peer list
            const peerList = selectedPeers.map(peer => ({
                ip: peer.ip,
                port: peer.port || 5000 // Default port if not specified
            }));

            try {
                const response = await fetch(buildApiUrl(`/api/files/${encodeURIComponent(file.fileName)}/permission`), {
                    method: 'PUT',
                    mode: 'cors',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        permission: 'PRIVATE',
                        peers: peerList
                    })
                });

                if (!response.ok) {
                    const errorData = await response.json();
                    throw new Error(errorData.error || `Lỗi: ${response.status}`);
                }

                alert(`Đã chia sẻ file cho ${selectedPeers.length} peer`);
            } catch (error) {
                alert(`Lỗi khi chia sẻ file: ${error.message}`);
                return; // Don't close modal on error
            }
        }
        onClose();
    };

    if (!isOpen) return null;

    return (
        <div className="fixed inset-0 bg-black bg-opacity-40 flex items-center justify-center z-50 p-4">
            <div className="bg-white rounded-2xl shadow-2xl max-w-lg w-full border border-gray-100">
                {/* Header */}
                <div className="px-8 py-6 border-b border-gray-100">
                    <div className="flex items-center space-x-4">
                        <div className="w-12 h-12 rounded-full bg-blue-100 flex items-center justify-center flex-shrink-0">
                            <svg className="w-6 h-6 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"></path>
                            </svg>
                        </div>
                        <div>
                            <h2 className="text-xl font-bold text-gray-900 mb-1">{t('share_file')}</h2>
                            <p className="text-sm text-gray-600 truncate max-w-md">{file?.fileName}</p>
                        </div>
                    </div>
                </div>

                {/* Content */}
                <div className="px-8 py-6">
                    <div className="space-y-6">
                        {/* Share All Button */}
                        <button
                            onClick={handleShareAll}
                            className="w-full bg-white border-2 border-blue-200 text-blue-700 py-4 px-6 rounded-xl hover:border-blue-300 hover:bg-blue-50 transition-all duration-200 font-semibold flex items-center justify-center space-x-3 shadow-sm hover:shadow-md"
                        >
                            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M3.055 11H5a2 2 0 012 2v1a2 2 0 002 2 2 2 0 012 2v2.945M8 3.935V5.5A2.5 2.5 0 0010.5 8h.5a2 2 0 012 2 2 2 0 104 0 2 2-.036 2h1.514M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                            </svg>
                            <span>{t('share_all')}</span>
                        </button>

                        {/* Selective Sharing Section */}
                        <div className="border-t border-gray-100 pt-6">
                            <h3 className="text-lg font-semibold text-gray-900 mb-4">{t('share_specific')}</h3>
                            <div className="bg-gray-50 rounded-xl border border-gray-200 max-h-48 overflow-y-auto p-4 mb-6">
                                {(!availablePeers || availablePeers.length === 0) ? (
                                    <div className="text-center py-6">
                                        <svg className="mx-auto h-12 w-12 text-gray-400 mb-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M3 13l3.5-8h11L21 13M3 13l3 5h12l3-5"></path>
                                        </svg>
                                        <p className="text-gray-500 font-medium">{t('no_available_peers')}</p>
                                    </div>
                                ) : (
                                    <div className="space-y-2">
                                        {availablePeers.map((peer, index) => (
                                            <label key={index} className="flex items-center space-x-3 cursor-pointer p-3 rounded-lg hover:bg-white hover:shadow-sm transition-all duration-150">
                                                <input
                                                    type="checkbox"
                                                    checked={(selectedPeers && selectedPeers.some(p => p.ip === peer.ip)) || false}
                                                    onChange={() => handlePeerToggle(peer)}
                                                    className="w-4 h-4 text-blue-600 bg-gray-100 border-gray-300 rounded focus:ring-blue-500 focus:ring-2"
                                                />
                                                <div className="w-8 h-8 bg-blue-100 rounded-full flex items-center justify-center flex-shrink-0">
                                                    <svg className="w-4 h-4 text-blue-600" fill="currentColor" viewBox="0 0 20 20">
                                                        <path fillRule="evenodd" d="M10 9a3 3 0 100-6 3 3 0 000 6zm-7 9a7 7 0 1114 0H3z" clipRule="evenodd"></path>
                                                    </svg>
                                                </div>
                                                <div className="flex-1 min-w-0">
                                                    <p className="text-sm font-medium text-gray-900 truncate">{peer.username}</p>
                                                    <p className="text-xs text-gray-500 truncate">{peer.ip}</p>
                                                </div>
                                            </label>
                                        ))}
                                    </div>
                                )}
                            </div>
                            <button
                                onClick={handleShareSelective}
                                disabled={!(selectedPeers && selectedPeers.length > 0)}
                                className="w-full bg-green-600 text-white py-3 px-6 rounded-xl hover:bg-green-700 disabled:bg-gray-300 disabled:cursor-not-allowed disabled:hover:bg-gray-300 transition-all duration-200 font-semibold flex items-center justify-center space-x-3 shadow-sm hover:shadow-md"
                            >
                                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 19l9 2-9-18-9 18 9-2zm0 0v-8"></path>
                                </svg>
                                <span>
                                    {!(selectedPeers && selectedPeers.length > 0)
                                        ? `${t('select_peer')}`
                                        : `${t('share_with')} ${selectedPeers.length} ${t('peers')}`
                                    }
                                </span>
                            </button>
                        </div>
                    </div>
                </div>

                {/* Footer */}
                <div className="px-8 py-4 border-t border-gray-100 flex justify-end space-x-3">
                    <button
                        onClick={onClose}
                        className="px-6 py-2.5 text-gray-700 hover:text-gray-900 font-medium hover:bg-gray-50 rounded-lg transition-colors duration-150"
                    >
                        {t('cancel')}
                    </button>
                </div>
            </div>
        </div>
    );
};

export default ShareModal;
