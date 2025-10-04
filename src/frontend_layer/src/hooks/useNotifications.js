import { useState } from 'react';

export const useNotifications = () => {
    const [notifications, setNotifications] = useState([]);

    const addNotification = (message, isError = false) => {
        const id = Date.now();
        // Xóa tất cả thông báo cũ trước khi thêm thông báo mới
        setNotifications([{ message, isError, id }]);
        setTimeout(() => {
            setNotifications(prev => prev.filter(n => n.id !== id));
        }, 3000);
    };

    const removeNotification = (id) => {
        setNotifications(prev => prev.filter(n => n.id !== id));
    };

    return {
        notifications,
        addNotification,
        removeNotification
    };
};
