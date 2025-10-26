import { useTranslation } from 'react-i18next';

const FileTable = ({ files, onDownload, onStopSharing, onShareToPeers, isLoading }) => {
    const { t } = useTranslation();

    const formatFileSize = (sizeInBytes) => {
        const sizeMB = sizeInBytes / (1024 * 1024);
        if (sizeMB < 1) {
            return `${(sizeInBytes / 1024).toFixed(2)} KB`;
        }
        return `${sizeMB.toFixed(2)} MB`;
    };

    return (
        <div className="overflow-x-auto bg-white rounded-xl shadow-lg border border-gray-100">
            <table className="min-w-full divide-y divide-gray-200">
                <thead className="bg-sky-700 text-white">
                    <tr>
                        <th className="px-6 py-4 text-left text-sm font-semibold tracking-wide">{t('fileName')}</th>
                        <th className="px-6 py-4 text-left text-sm font-semibold tracking-wide">{t('fileSize')}</th>
                        <th className="px-6 py-4 text-left text-sm font-semibold tracking-wide">{t('peer')}</th>
                        <th className="px-6 py-4 text-left text-sm font-semibold tracking-wide">{t('action')}</th>
                    </tr>
                </thead>
                <tbody className="bg-white divide-y divide-gray-200">
                    {files.length === 0 ? (
                        <tr>
                            <td colSpan="4" className="px-6 py-6 text-center text-gray-500 text-lg">{t('no_files')}</td>
                        </tr>
                    ) : (
                        files.map((file, index) => (
                            <tr key={index} className="hover:bg-blue-50 transition-all duration-200">
                                <td className="px-6 py-4 whitespace-nowrap text-gray-800 font-medium">{file.fileName}</td>
                                <td className="px-6 py-4 whitespace-nowrap text-gray-600">{formatFileSize(file.fileSize)}</td>
                                <td className="px-6 py-4 whitespace-nowrap text-gray-600">{file.peerInfo.username || 'Peer'}</td>
                                <td className="px-6 py-4 whitespace-nowrap flex space-x-2">
                                    {!file.isSharedByMe && (
                                        <button
                                            onClick={() => onDownload(file)}
                                            className="text-blue-600 hover:text-blue-800 flex items-center transition-colors duration-200 disabled:opacity-50"
                                            disabled={isLoading}
                                        >
                                            <svg className="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"></path>
                                            </svg>
                                            {t('download')}
                                        </button>
                                    )}
                                    {file.isSharedByMe && (
                                        <>
                                            <button
                                                onClick={() => onShareToPeers(file)}
                                                className="text-green-600 hover:text-green-800 flex items-center transition-colors duration-200 disabled:opacity-50"
                                                disabled={isLoading}
                                            >
                                                <svg className="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.367 2.684 3 3 0 00-5.367-2.684z"></path>
                                                </svg>
                                                {t('permission')}
                                            </button>
                                            <button
                                                onClick={() => {
                                                    if (window.confirm(`Bạn có chắc muốn hủy chia sẻ "${file.fileName}"?`)) {
                                                        onStopSharing(file);
                                                    }
                                                }}
                                                className="text-red-600 hover:text-red-800 flex items-center transition-colors duration-200 disabled:opacity-50"
                                                disabled={isLoading}
                                            >
                                                <svg className="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M6 18L18 6M6 6l12 12"></path>
                                                </svg>
                                                {t('cancel_sharing')}
                                            </button>
                                        </>
                                    )}
                                </td>
                            </tr>
                        ))
                    )}
                </tbody>
            </table>
        </div>
    );
};

export default FileTable;
