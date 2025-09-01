import React, { useEffect } from 'react';

const ProgressBar = ({ tasks, setTasks }) => {
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

    const getStatusColor = (status) => {
        switch (status) {
            case 'canceled':
            case 'failed':
                return 'text-red-600';
            case 'starting':
            case 'downloading...':
            case 'sharing...':
                return 'text-blue-600';  // hoặc xanh lá cũng được
            case 'completed':
                return 'text-green-600';
            default:
                return 'text-gray-600'; // fallback
        }
    };



    return (
        <div className="space-y-4">
            {tasks.map(task => (
                <div key={task.id} className="bg-white p-4 rounded-lg shadow-md border border-gray-100">
                    <div className="flex justify-between items-center mb-2">
                        <span className="text-sm font-semibold text-gray-700">{task.taskName}</span>
                        <span className={`text-sm ${getStatusColor(task.status)}`}>
                            {task.status.charAt(0).toUpperCase() + task.status.slice(1)}
                        </span>

                    </div>
                    <div className="w-full bg-gray-200 rounded-full h-2.5">
                        <div
                            className="bg-blue-600 h-2.5 rounded-full transition-all duration-300"
                            style={{ width: `${task.progress}%` }}
                        ></div>
                    </div>
                    <div className="flex justify-between text-xs text-gray-500 mt-1">
                        <span>{(formatBytes(task.bytesTransferred))} / {(formatBytes(task.totalBytes))}</span>
                        <span>{task.progress}%</span>
                    </div>
                    {((task.status === 'starting') ||
                        (task.status === 'downloading...') ||
                        (task.status === 'sharing...')) && (
                            <button
                                onClick={() => cancelTask(task.id)}
                                className="mt-2 px-4 py-1 bg-red-600 text-white rounded-lg hover:bg-red-700 transition-colors duration-200"
                            >
                                Hủy
                            </button>
                        )}

                </div>
            ))}
        </div>
    );
};

export default ProgressBar;