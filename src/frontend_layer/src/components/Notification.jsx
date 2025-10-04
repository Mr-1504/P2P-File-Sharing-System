import { useEffect } from 'react';

const Notification = ({ message, isError, onClose }) => {
    useEffect(() => {
        const timer = setTimeout(() => onClose(), 1500);
        return () => clearTimeout(timer);
    }, [onClose]);

    return (
        <div className={`modern-notification max-w-sm ${isError ? 'border-red-500' : 'border-green-500'} notification-enter`}>
            <div className="flex items-center p-4">
                <div className={`w-10 h-10 rounded-full flex items-center justify-center mr-3 ${isError ? 'bg-red-100' : 'bg-green-100'}`}>
                    <svg className={`w-5 h-5 ${isError ? 'text-red-600' : 'text-green-600'}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d={isError ? "M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" : "M5 13l4 4L19 7"} />
                    </svg>
                </div>
                <div className="flex-1">
                    <p className="text-gray-800 font-medium">{message}</p>
                </div>
                <button onClick={onClose} className="ml-3 text-gray-400 hover:text-gray-600 transition-colors">
                    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M6 18L18 6M6 6l12 12" />
                    </svg>
                </button>
            </div>
        </div>
    );
};

export default Notification;
