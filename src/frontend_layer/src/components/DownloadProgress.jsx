import { useEffect, useState } from 'react';
import { ProgressBar as BootstrapProgressBar } from 'react-bootstrap';

function DownloadProgress() {
    const [downloads, setDownloads] = useState({});

    useEffect(() => {
        const ws = new WebSocket('ws://localhost:8080');
        ws.onmessage = (event) => {
            const data = JSON.parse(event.data);
            if (data.progress === -1) {
                setDownloads((prev) => {
                    const newDownloads = { ...prev };
                    delete newDownloads[data.fileName];
                    return newDownloads;
                });
            } else {
                setDownloads((prev) => ({
                    ...prev,
                    [data.fileName]: { progress: data.progress, speed: data.speed, eta: data.eta }
                }));
            }
        };
        ws.onerror = (error) => console.error('WebSocket error:', error);
        ws.onclose = () => console.log('WebSocket disconnected');
        return () => ws.close();
    }, []);

    return (
        <div className="mt-6 space-y-4">
            {Object.entries(downloads).map(([fileName, info]) => (
                <div key={fileName} className="bg-white p-4 rounded-xl shadow-lg border border-gray-100">
                    <h4 className="text-sm font-semibold text-gray-700">Downloading: {fileName}</h4>
                    <BootstrapProgressBar
                        now={info.progress}
                        label={`${info.progress}%`}
                        className="mt-2"
                    />
                    <p className="text-sm text-gray-600">Speed: {info.speed.toFixed(2)} KB/s</p>
                    <p className="text-sm text-gray-600">ETA: {info.eta >= 0 ? `${info.eta} seconds` : 'Calculating...'}</p>
                </div>
            ))}
        </div>
    );
}

export default DownloadProgress;