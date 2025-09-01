import { useEffect } from 'react';

const Notification = ({ message, isError, onClose }) => {
    useEffect(() => {
        const timer = setTimeout(() => onClose(), 1500);
        return () => clearTimeout(timer);
    }, [onClose]);

    return (
        <div className={`fixed top-6 right-6 p-4 rounded-xl shadow-2xl max-w-sm ${isError ? 'bg-red-600' : 'bg-green-600'} text-white animate-fade-in z-50`}>
            <div className="flex items-center">
                <svg className="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d={isError ? "M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" : "M5 13l4 4L19 7"} />
                </svg>
                {message}
            </div>
        </div>
    );
};

export default Notification;