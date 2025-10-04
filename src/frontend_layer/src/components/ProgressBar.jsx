import React, { useEffect, useState } from 'react';
import ConfirmCancelDialog from './ConfirmCancelDiaglog';
import { useTranslation } from 'react-i18next';

const ProgressBar = ({ tasks, setTasks, onResume }) => {
    const { t } = useTranslation();
    const [isDialogOpen, setIsDialogOpen] = useState(false);
    const [taskToCancel, setTaskToCancel] = useState(null);
    const [taskSpeeds, setTaskSpeeds] = useState({});
    const [previousTasks, setPreviousTasks] = useState({});

    // Calculate speed and ETA
    useEffect(() => {
        const now = Date.now();
        const newSpeeds = {};

        tasks.forEach(task => {
            const prev = previousTasks[task.id];
            if (prev && prev.bytesTransferred !== task.bytesTransferred && (task.status === 'downloading' || task.status === 'sharing')) {
                const timeDiff = now - (prev.timestamp || now);
                const bytesDiff = task.bytesTransferred - prev.bytesTransferred;
                if (timeDiff > 0) {
                    const speed = (bytesDiff / timeDiff) * 1000; // bytes per second
                    newSpeeds[task.id] = speed;
                }
            }
        });

        setTaskSpeeds(prev => ({ ...prev, ...newSpeeds }));

        const updatedPrevious = {};
        tasks.forEach(task => {
            updatedPrevious[task.id] = { ...task, timestamp: now };
        });
        setPreviousTasks(updatedPrevious);
    }, [tasks]);

    const formatSpeed = (bytesPerSecond) => {
        if (!bytesPerSecond) return '0 B/s';
        const units = ["B/s", "KB/s", "MB/s", "GB/s"];
        const k = 1024;
        const i = Math.floor(Math.log(bytesPerSecond) / Math.log(k));
        const speed = bytesPerSecond / Math.pow(k, i);
        return `${speed.toFixed(2)} ${units[i]}`;
    };

    const calculateETA = (task) => {
        const speed = taskSpeeds[task.id];
        if (!speed || speed === 0 || task.progress >= 100) return null;

        const remainingBytes = task.totalBytes - task.bytesTransferred;
        const etaSeconds = remainingBytes / speed;

        if (etaSeconds < 60) return `${Math.ceil(etaSeconds)}s`;
        if (etaSeconds < 3600) return `${Math.ceil(etaSeconds / 60)}m`;
        return `${Math.ceil(etaSeconds / 3600)}h`;
    };

    const getTaskIcon = (status) => {
        switch (status) {
            case 'downloading':
                return (
                    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"></path>
                    </svg>
                );
            case 'sharing':
            case 'starting':
                return (
                    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"></path>
                    </svg>
                );
            case 'completed':
                return (
                    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                    </svg>
                );
            case 'failed':
            case 'canceled':
            case 'timeout':
                return (
                    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M10 14l2-2m0 0l2-2m-2 2l-2-2m2 2l2 2m7-2a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                    </svg>
                );
            case 'resumable':
                return (
                    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M14.828 14.828a4 4 0 01-5.656 0M9 10h1.586a1 1 0 01.707.293l.707.707A1 1 0 0012.414 11H15m2 0h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                    </svg>
                );
            case 'stalled':
                return (
                    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L3.732 16.5c-.77.833.192 2.5 1.732 2.5z"></path>
                    </svg>
                );
            default:
                return (
                    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                    </svg>
                );
        }
    };

    const getStatusColor = (status) => {
        switch (status) {
            case 'canceled':
            case 'failed':
            case 'timeout':
                return { text: 'text-red-600', bg: 'bg-red-50', border: 'border-red-200' };
            case 'starting':
            case 'downloading':
            case 'sharing':
                return { text: 'text-blue-600', bg: 'bg-blue-50', border: 'border-blue-200' };
            case 'completed':
                return { text: 'text-green-600', bg: 'bg-green-50', border: 'border-green-200' };
            case 'resumable':
                return { text: 'text-orange-600', bg: 'bg-orange-50', border: 'border-orange-200' };
            case 'stalled':
                return { text: 'text-yellow-600', bg: 'bg-yellow-50', border: 'border-yellow-200' };
            default:
                return { text: 'text-gray-600', bg: 'bg-gray-50', border: 'border-gray-200' };
        }
    };

    const handleCancelTask = (taskId) => {
        setTaskToCancel(taskId);
        setIsDialogOpen(true);
    };

    const handleDialogClose = (confirm) => {
        setIsDialogOpen(false);
        if (confirm && taskToCancel) {
            cancelTask(taskToCancel);
        }
    };

    const addTask = (taskId, taskName) => {
        const id = String(taskId);
        setTasks(prev => {
            const exists = prev.find(t => t.id === id);
            if (exists) {
                return prev.map(t =>
                    t.id === id ? { ...t, taskName, status: 'starting' } : t
                );
            }
            return [
                ...prev,
                {
                    id,
                    taskName,
                    progress: 0,
                    bytesTransferred: 0,
                    totalBytes: 1,
                    status: 'starting'
                }
            ];
        });
    };

    const formatBytes = (bytes) => {
        if (bytes === 0 || !bytes) return "0 B";

        const units = ["B", "KB", "MB", "GB", "TB"];
        const k = 1024;
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        const size = bytes / Math.pow(k, i);

        return `${size.toFixed(2)} ${units[i]}`;
    };


    const cancelTask = async (taskId) => {
        try {
            const res = await fetch(`http://localhost:8080/api/cancel?taskId=${taskId}`, {
                method: 'DELETE'
            });

            if (res.ok) {
                setTasks(prev =>
                    prev.map(t =>
                        t.id === taskId ? { ...t, status: 'canceled' } : t
                    )
                );
            } else {
                console.error('Lỗi khi hủy task:', res.status);
            }
        } catch (err) {
            console.error('Lỗi khi hủy task:', err);
        }
    };

    // Determine if task is sharing or downloading
    const getTaskType = (task) => {
        // ✅ Ưu tiên taskType từ task object
        if (task.taskType) {
            return task.taskType === 'share' ? 'sharing' : 'downloading';
        }
        
        // Fallback: dựa vào status để xác định (logic cũ)
        if (task.status === 'sharing' || task.taskType === 'sharing') {
            return 'sharing';
        }
        if (task.status === 'downloading' || task.taskType === 'downloading') {
            return 'downloading';
        }
        if (['starting', 'uploading'].includes(task.status)) {
            return 'sharing';
        }
        return 'downloading'; // default
    };

    const getStatusText = (status, taskType) => {
        if (taskType === 'sharing') {
            switch (status) {
                case 'starting': return t('preparing_share');
                case 'sharing':
                case 'downloading': // Backend có thể trả về 'downloading' cho share task
                case 'uploading':
                    return t('sharing');
                case 'completed': return t('share_completed');
                case 'failed': return t('share_failed');
                case 'canceled': return t('share_canceled');
                default: return `${t('sharing')}: ${status}`;
            }
        } else {
            switch (status) {
                case 'starting': return t('preparing_download');
                case 'downloading': return t('downloading');
                case 'completed': return t('download_completed');
                case 'failed': return t('download_failed');
                case 'canceled': return t('download_canceled');
                case 'timeout': return t('download_timeout');
                case 'stalled': return t('download_stalled');
                case 'resumable': return t('resumable');
                default: return `${t('downloading')}: ${status}`;
            }
        }
    };

    return (
        <div className="space-y-6">
            {tasks.length === 0 ? (
                <div className="text-center py-12">
                    <svg className="w-16 h-16 mx-auto text-gray-300 mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"></path>
                    </svg>
                    <p className="text-gray-500 text-lg">{t('no_active_transfers')}</p>
                </div>
            ) : (
                <>
                    {/* Sharing Tasks Section */}
                    {(() => {
                        const sharingTasks = tasks.filter(task => getTaskType(task) === 'sharing');
                        return sharingTasks.length > 0 ? (
                            <div>
                                <h3 className="text-xl font-bold text-blue-900 mb-4 flex items-center">
                                    <svg className="w-6 h-6 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"></path>
                                    </svg>
                                    {t('sharing_files')}
                                </h3>
                                <div className="space-y-4">
                                    {sharingTasks.map(task => {
                                        const taskType = getTaskType(task);
                                        const colors = getStatusColor(task.status);
                                        const eta = calculateETA(task);
                                        const speed = taskSpeeds[task.id];
                                        const statusText = getStatusText(task.status, taskType);

                                        return (
                                            <div key={task.id} className={`bg-white p-6 rounded-xl shadow-lg border-2 ${colors.border} ${colors.bg} transition-all duration-300 hover:shadow-xl`}>
                                                {/* Header with icon, title, and status */}
                                                <div className="flex items-center justify-between mb-4">
                                                    <div className="flex items-center space-x-3">
                                                        <div className={`p-2 rounded-lg ${colors.bg} ${colors.text}`}>
                                                            {getTaskIcon(task.status)}
                                                        </div>
                                                        <div>
                                                            <h3 className="text-lg font-semibold text-gray-800 truncate max-w-xs" title={task.taskName}>
                                                                {task.taskName}
                                                            </h3>
                                                            <div className="flex items-center space-x-2">
                                                                <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${colors.bg} ${colors.text}`}>
                                                                    {statusText}
                                                                </span>
                                                            </div>
                                                        </div>
                                                    </div>

                                                    {/* Action buttons */}
                                                    <div className="flex space-x-2">
                                                        {/* Cancel button for active tasks */}
                                                        {((task.status === 'starting') ||
                                                            (task.status === 'downloading') ||
                                                            (task.status === 'sharing')) && (
                                                            <button
                                                                onClick={() => handleCancelTask(task.id)}
                                                                className="inline-flex items-center px-3 py-1.5 border border-red-300 text-red-700 bg-white rounded-lg hover:bg-red-50 transition-colors duration-200 text-sm font-medium"
                                                            >
                                                                <svg className="w-4 h-4 mr-1.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M6 18L18 6M6 6l12 12"></path>
                                                                </svg>
                                                                {t('cancel')}
                                                            </button>
                                                        )}
                                                    </div>
                                                </div>

                                                {/* Progress bar */}
                                                <div className="mb-4">
                                                    <div className="w-full bg-gray-200 rounded-full h-3 overflow-hidden">
                                                        <div
                                                            className={`h-3 rounded-full transition-all duration-500 ease-out ${
                                                                task.status === 'completed' ? 'bg-gradient-to-r from-green-400 to-green-600' :
                                                                task.status === 'failed' || task.status === 'canceled' ? 'bg-gradient-to-r from-red-400 to-red-600' :
                                                                taskType === 'sharing' ? 'bg-gradient-to-r from-blue-400 to-blue-600' :
                                                                'bg-gradient-to-r from-indigo-400 to-indigo-600'
                                                            }`}
                                                            style={{ width: `${task.progress}%` }}
                                                        ></div>
                                                    </div>
                                                </div>

                                                {/* Stats display for sharing */}
                                                <div className="bg-blue-50 rounded-lg p-4 text-center border border-blue-200">
                                                    <div className="text-blue-600 text-sm font-medium mb-2">
                                                        <svg className="w-4 h-4 inline mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.367 2.684 3 3 0 00-5.367-2.684z"></path>
                                                        </svg>
                                                        {t('sharing_files')}
                                                    </div>
                                                    <div className="text-3xl font-bold text-blue-700">{task.progress}%</div>
                                                    <div className="text-blue-500 text-xs mt-1">{t('completed')}</div>
                                                </div>
                                            </div>
                                        );
                                    })}
                                </div>
                            </div>
                        ) : null;
                    })()}

                    {/* Downloading Tasks Section */}
                    {(() => {
                        const downloadingTasks = tasks.filter(task => getTaskType(task) === 'downloading');
                        return downloadingTasks.length > 0 ? (
                            <div>
                                <h3 className="text-xl font-bold text-blue-900 mb-4 flex items-center">
                                    <svg className="w-6 h-6 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"></path>
                                    </svg>
                                    {t('downloading_files')}
                                </h3>
                                <div className="space-y-4">
                                    {downloadingTasks.map(task => {
                                        const taskType = getTaskType(task);
                                        const colors = getStatusColor(task.status);
                                        const eta = calculateETA(task);
                                        const speed = taskSpeeds[task.id];
                                        const statusText = getStatusText(task.status, taskType);

                                        return (
                                            <div key={task.id} className={`bg-white p-6 rounded-xl shadow-lg border-2 ${colors.border} ${colors.bg} transition-all duration-300 hover:shadow-xl`}>
                                                {/* Header with icon, title, and status */}
                                                <div className="flex items-center justify-between mb-4">
                                                    <div className="flex items-center space-x-3">
                                                        <div className={`p-2 rounded-lg ${colors.bg} ${colors.text}`}>
                                                            {getTaskIcon(task.status)}
                                                        </div>
                                                        <div>
                                                            <h3 className="text-lg font-semibold text-gray-800 truncate max-w-xs" title={task.taskName}>
                                                                {task.taskName}
                                                            </h3>
                                                            <div className="flex items-center space-x-2">
                                                                <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${colors.bg} ${colors.text}`}>
                                                                    {statusText}
                                                                </span>
                                                                {eta && (
                                                                    <span className="text-xs text-gray-500">
                                                                        ETA: {eta}
                                                                    </span>
                                                                )}
                                                            </div>
                                                        </div>
                                                    </div>

                                                    {/* Action buttons */}
                                                    <div className="flex space-x-2">
                                                        {/* Cancel button for active tasks */}
                                                        {((task.status === 'starting') ||
                                                            (task.status === 'downloading') ||
                                                            (task.status === 'sharing')) && (
                                                            <button
                                                                onClick={() => handleCancelTask(task.id)}
                                                                className="inline-flex items-center px-3 py-1.5 border border-red-300 text-red-700 bg-white rounded-lg hover:bg-red-50 transition-colors duration-200 text-sm font-medium"
                                                            >
                                                                <svg className="w-4 h-4 mr-1.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M6 18L18 6M6 6l12 12"></path>
                                                                </svg>
                                                                {t('cancel')}
                                                            </button>
                                                        )}

                                                        {/* Resume button for download tasks */}
                                                        {((task.status === 'resumable') ||
                                                            (task.status === 'timeout')) && onResume && (
                                                            <button
                                                                onClick={() => onResume(task.id)}
                                                                className="inline-flex items-center px-3 py-1.5 border border-green-300 text-green-700 bg-white rounded-lg hover:bg-green-50 transition-colors duration-200 text-sm font-medium"
                                                            >
                                                                <svg className="w-4 h-4 mr-1.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M14.828 14.828a4 4 0 01-5.656 0M9 10h1.586a1 1 0 01.707.293l.707.707A1 1 0 0012.414 11H15m2 0h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                                                                </svg>
                                                                {t('resume')}
                                                            </button>
                                                        )}
                                                    </div>
                                                </div>

                                                {/* Progress bar */}
                                                <div className="mb-4">
                                                    <div className="w-full bg-gray-200 rounded-full h-3 overflow-hidden">
                                                        <div
                                                            className={`h-3 rounded-full transition-all duration-500 ease-out ${
                                                                task.status === 'completed' ? 'bg-gradient-to-r from-green-400 to-green-600' :
                                                                task.status === 'failed' || task.status === 'canceled' ? 'bg-gradient-to-r from-red-400 to-red-600' :
                                                                'bg-gradient-to-r from-indigo-400 to-indigo-600'
                                                            }`}
                                                            style={{ width: `${task.progress}%` }}
                                                        ></div>
                                                    </div>
                                                </div>

                                                {/* Grid layout for download stats */}
                                                <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
                                                    <div className="text-center">
                                                        <div className="text-gray-500 text-xs uppercase tracking-wide">{t('progress')}</div>
                                                        <div className="text-lg font-semibold text-gray-800">{task.progress}%</div>
                                                    </div>
                                                    <div className="text-center">
                                                        <div className="text-gray-500 text-xs uppercase tracking-wide">{t('transferred')}</div>
                                                        <div className="text-lg font-semibold text-gray-800">{formatBytes(task.bytesTransferred)}</div>
                                                    </div>
                                                    <div className="text-center">
                                                        <div className="text-gray-500 text-xs uppercase tracking-wide">{t('total')}</div>
                                                        <div className="text-lg font-semibold text-gray-800">{formatBytes(task.totalBytes)}</div>
                                                    </div>
                                                    <div className="text-center">
                                                        <div className="text-gray-500 text-xs uppercase tracking-wide">{t('speed')}</div>
                                                        <div className="text-lg font-semibold text-gray-800">{formatSpeed(speed)}</div>
                                                    </div>
                                                </div>
                                            </div>
                                        );
                                    })}
                                </div>
                            </div>
                        ) : null;
                    })()}
                </>
            )}
            <ConfirmCancelDialog open={isDialogOpen} onClose={handleDialogClose} />
        </div>
    );
};

export default ProgressBar;
