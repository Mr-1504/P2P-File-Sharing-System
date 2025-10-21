import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';

const ShareModal = ({ isOpen, onClose, onShareAll, onShareSelective, file }) => {
    const { t } = useTranslation();
    const [selectedPeers, setSelectedPeers] = useState([]);
    const [availablePeers, setAvailablePeers] = useState([]);
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        if (isOpen) {
            fetchKnownPeers();
        }
    }, [isOpen]);

    const fetchKnownPeers = async () => {
        try {
            const response = await fetch('http://localhost:8080/api/peers/known');
            if (response.ok) {
                const peers = await response.json();
                setAvailablePeers(peers);
            }
        } catch (error) {
            console.error('Error fetching known peers:', error);
        }
    };

    const handlePeerToggle = (peer) => {
        setSelectedPeers(prev =>
            prev.includes(peer)
                ? prev.filter(p => p !== peer)
                : [...prev, peer]
        );
    };

    const handleShareAll = () => {
        onShareAll(file);
        onClose();
    };

    const handleShareSelective = () => {
        if (selectedPeers.length === 0) {
            alert('Vui lòng chọn ít nhất một peer');
            return;
        }
        onShareSelective(file, selectedPeers);
        onClose();
    };

    if (!isOpen) return null;

    return (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
            <div className="bg-white rounded-lg p-6 max-w-md w-full mx-4">
                <h2 className="text-xl font-bold mb-4">Chọn cách chia sẻ file</h2>
                <p className="mb-4 text-gray-600">File: {file?.fileName}</p>

                <div className="space-y-4">
                    <button
                        onClick={handleShareAll}
                        className="w-full bg-blue-600 text-white py-3 px-4 rounded-lg hover:bg-blue-700 transition-colors"
                    >
                        Chia sẻ cho tất cả peers
                    </button>

                    <div className="border-t pt-4">
                        <h3 className="font-semibold mb-2">Chia sẻ cho peers cụ thể:</h3>
                        <div className="max-h-40 overflow-y-auto border rounded p-2 mb-3">
                            {availablePeers.length === 0 ? (
                                <p className="text-gray-500 text-sm">Không có peers nào</p>
                            ) : (
                                availablePeers.map((peer, index) => (
                                    <label key={index} className="flex items-center space-x-2 py-1">
                                        <input
                                            type="checkbox"
                                            checked={selectedPeers.includes(peer)}
                                            onChange={() => handlePeerToggle(peer)}
                                            className="rounded"
                                        />
                                        <span className="text-sm">{peer.username} ({peer.ip})</span>
                                    </label>
                                ))
                            )}
                        </div>
                        <button
                            onClick={handleShareSelective}
                            disabled={selectedPeers.length === 0}
                            className="w-full bg-green-600 text-white py-3 px-4 rounded-lg hover:bg-green-700 disabled:bg-gray-400 disabled:cursor-not-allowed transition-colors"
                        >
                            Chia sẻ cho {selectedPeers.length} peer{selectedPeers.length !== 1 ? 's' : ''} đã chọn
                        </button>
                    </div>
                </div>

                <div className="flex justify-end mt-6">
                    <button
                        onClick={onClose}
                        className="px-4 py-2 text-gray-600 hover:text-gray-800"
                    >
                        Hủy
                    </button>
                </div>
            </div>
        </div>
    );
};

export default ShareModal;
