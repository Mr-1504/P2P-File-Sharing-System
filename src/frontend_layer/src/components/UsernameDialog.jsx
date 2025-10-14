import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';

const UsernameDialog = ({ isOpen, onClose }) => {
    const { t } = useTranslation();
    const [username, setUsername] = useState('');
    const [isLoading, setIsLoading] = useState(false);

    useEffect(() => {
        const storedUsername = localStorage.getItem('p2p_username');
        if (storedUsername) {
            setUsername(storedUsername);
        }
    }, []);

    const handleSubmit = async (e) => {
        e.preventDefault();
        if (!username.trim()) {
            return;
        }

        setIsLoading(true);
        try {
            // Call backend API to set username
            const response = await fetch('http://localhost:8080/api/set-username', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username: username.trim() })
            });

            if (!response.ok) {
                throw new Error('Failed to set username');
            }

            const result = await response.json();
            if (result.status === 'success') {
                // Store in localStorage for future reference (cache)
                localStorage.setItem('p2p_username', username.trim());
                // Backend will now initialize and show main app
                onClose();
            } else {
                throw new Error('Backend rejected username');
            }
        } catch (error) {
            console.error('Error setting username:', error);
        } finally {
            setIsLoading(false);
        }
    };

    if (!isOpen) return null;

    return (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
            <div className="bg-white rounded-lg shadow-xl p-6 w-full max-w-md mx-4">
                <div className="mb-4">
                    <h2 className="text-xl font-bold text-gray-900">
                        {t('enter_username', 'Nhập tên người dùng')}
                    </h2>
                    <p className="text-sm text-gray-600 mt-2">
                        {t('username_description', 'Đặt tên để các peer khác có thể nhận biết bạn trong mạng P2P.')}
                    </p>
                </div>

                <form onSubmit={handleSubmit}>
                    <div className="mb-4">
                        <label htmlFor="username" className="block text-sm font-medium text-gray-700 mb-2">
                            {t('username', 'Tên người dùng')}
                        </label>
                        <input
                            type="text"
                            id="username"
                            value={username}
                            onChange={(e) => setUsername(e.target.value)}
                            className="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
                            placeholder={t('enter_username_placeholder', 'Nhập tên của bạn')}
                            required
                            disabled={isLoading}
                        />
                    </div>

                    <div className="flex justify-end space-x-3">
                        <button
                            type="button"
                            onClick={onClose}
                            disabled={isLoading}
                            className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
                        >
                            {t('cancel', 'Hủy')}
                        </button>
                        <button
                            type="submit"
                            disabled={!username.trim() || isLoading}
                            className="px-4 py-2 text-sm font-medium text-white bg-blue-600 border border-transparent rounded-md hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                            {isLoading ? t('saving', 'Đang lưu...') : t('save', 'Lưu')}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
};

export default UsernameDialog;
