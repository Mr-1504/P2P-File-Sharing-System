import { useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { buildApiUrl } from '../utils/config';

export const useTasks = (addNotification) => {
    const { t } = useTranslation();
    const [tasks, setTasks] = useState([]);
    const [taskTimeouts, setTaskTimeouts] = useState({});
    const [taskMap] = useState(new Map());

    const queryProgress = async () => {
        if (taskMap.size === 0) return;

        let allCompleted = true;
        taskMap.forEach((info, id) => {
            if (!['completed', 'failed', 'canceled', 'timeout'].includes(info.status)) {
                allCompleted = false;
            }
        });

        if (allCompleted) return;

        try {
            const response = await fetch(buildApiUrl('/api/progress'), {
                method: 'GET',
                headers: { 'Content-Type': 'application/json' }
            });
            if (response.ok) {
                const data = await response.json();

                if (data) {
                    let completedTasks = [];
                    const now = Date.now();
                    const timeoutThreshold = 2 * 60 * 1000; // 2 minutes

                    setTasks(prev => {
                        let updated = [...prev];

                        Object.entries(data).forEach(([id, info]) => {
                            const taskId = String(id);
                            const taskIndex = updated.findIndex(t => t.id === taskId);

                                const taskMapInfo = taskMap.get(taskId);
                                const taskType = info.taskType || 'sharing';

                            if (taskIndex === -1) {
                                updated.push({
                                    id: taskId,
                                    taskName: info.fileName || "Unknown Task",
                                    progress: info.progressPercentage || 0,
                                    bytesTransferred: info.bytesTransferred || 0,
                                    totalBytes: info.totalBytes || 1,
                                    status: info.status,
                                    taskType: info.taskType
                                });
                            } else {
                                const currentTask = updated[taskIndex];
                                let newStatus = (currentTask.status === 'canceled')
                                    ? currentTask.status
                                    : info.status;

                                // Check for timeout/stalled downloads (chỉ áp dụng cho download)
                                if (taskType === 'download') {
                                    const lastUpdate = taskTimeouts[taskId] || now;
                                    const timeSinceLastUpdate = now - lastUpdate;

                                    if (newStatus === 'downloading' || newStatus === 'starting') {
                                        if (timeSinceLastUpdate >= timeoutThreshold) {
                                            newStatus = 'timeout';
                                            addNotification(t('download_timeout', { taskName: currentTask.taskName }), true);
                                        } else if (timeSinceLastUpdate >= 30 * 1000) {
                                            newStatus = 'stalled';
                                        }
                                    }

                                    // Update timeout tracking
                                    if (currentTask.progress !== info.progressPercentage ||
                                        currentTask.bytesTransferred !== info.bytesTransferred) {
                                        setTaskTimeouts(prev => ({
                                            ...prev,
                                            [taskId]: now
                                        }));
                                    } else {
                                        setTaskTimeouts(prev => ({
                                            ...prev,
                                            [taskId]: lastUpdate
                                        }));
                                    }
                                }

                                // Cập nhật task trong mảng
                                updated[taskIndex] = {
                                    ...currentTask,
                                    taskName: info.fileName || "Unknown Task",
                                    progress: info.progressPercentage,
                                    bytesTransferred: info.bytesTransferred,
                                    totalBytes: info.totalBytes,
                                    status: newStatus,
                                    taskType: taskType
                                };

                                taskMap.set(taskId, { ...taskMapInfo, status: newStatus, taskType: info.taskType });

                                if (['completed', 'failed', 'canceled', 'timeout'].includes(newStatus)) {
                                    completedTasks.push(taskId);
                                }
                            }
                        });

                        return updated;
                    });

                    // Cleanup completed tasks
                    // if (completedTasks.length > 0) {
                    //     try {
                    //         const response = await fetch(buildApiUrl('/api/progress/cleanup'), {
                    //             method: "POST",
                    //             headers: { "Content-Type": "application/json" },
                    //             body: JSON.stringify({ taskIds: completedTasks })
                    //         });
                    //         if (response.ok) {
                    //             setTasks(prev => prev.filter(t => !completedTasks.includes(t.id)));
                    //             // completedTasks.forEach(taskId => {
                    //             //     taskMap.delete(taskId);
                    //             // });
                    //             console.log("Cleanup successful for tasks:", completedTasks);
                    //         } else {
                    //             console.error("Cleanup failed with status:", response.status, 'Response:', await response.text());
                    //         }
                    //     } catch (err) {
                    //         console.error("Lỗi khi cleanup backend:", err);
                    //     }
                    // }
                }
            }
        } catch (error) {
            console.error("Lỗi khi truy vấn tiến trình:", error);
        }
    };

    const handleResume = async (taskId) => {
        try {
            const response = await fetch(buildApiUrl(`/api/resume?taskId=${taskId}`), {
                method: 'POST'
            });

            if (response.ok) {
                setTasks(prev =>
                    prev.map(t =>
                        t.id === taskId ? { ...t, status: 'downloading' } : t
                    )
                );
                addNotification(t('resume_download_success'), false);
            } else {
                throw new Error('Lỗi khi tiếp tục tải xuống');
            }
        } catch (err) {
            console.error('Lỗi khi resume task:', err);
            addNotification(t('resume_download_error'), true);
        }
    };

    useEffect(() => {
        const interval = setInterval(queryProgress, 2000);
        return () => clearInterval(interval);
    }, []);

    return {
        tasks,
        setTasks,
        taskMap,
        handleResume
    };
};
