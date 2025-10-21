import React from 'react';
import ProgressBar from './ProgressBar';
import { useTranslation } from "react-i18next";

function Tasks({ tasks, setTasks, onResume }) {
    const { t } = useTranslation();

    return (
        <div>
            <div className="bg-white rounded-xl shadow-lg p-6 mb-6 border border-gray-100">
                <h2 className="text-2xl font-bold text-blue-900 mb-4">{t('tasks')}</h2>
                <ProgressBar tasks={tasks} setTasks={setTasks} onResume={onResume} />
            </div>
        </div>
    );
}

export default Tasks;
