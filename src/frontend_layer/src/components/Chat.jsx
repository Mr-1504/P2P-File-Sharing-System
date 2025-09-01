import { useState } from 'react';

const Chat = ({ peers, messages, onSendMessage, selectedPeer, setSelectedPeer }) => {
    const [messageInput, setMessageInput] = useState('');

    const handleSend = () => {
        if (messageInput.trim() && selectedPeer) {
            onSendMessage(selectedPeer.id, messageInput);
            setMessageInput('');
        }
    };

    return (
        <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
            <div className="md:col-span-1 bg-white rounded-xl shadow-lg border border-gray-100 p-6">
                <h3 className="text-xl font-bold mb-4 text-gray-800">Danh sách Peer</h3>
                <ul className="space-y-3">
                    {peers.map(peer => (
                        <li
                            key={peer.id}
                            onClick={() => setSelectedPeer(peer)}
                            className={`p-4 rounded-lg cursor-pointer transition-all duration-200 ${selectedPeer?.id === peer.id ? 'bg-blue-100 text-blue-800 shadow-md' : 'hover:bg-gray-100'}`}
                        >
                            <div className="flex justify-between items-center">
                                <span className="font-medium">{peer.name} ({peer.ip})</span>
                                <span className={`text-sm font-semibold ${peer.status === 'Online' ? 'text-green-600' : 'text-red-600'}`}>{peer.status}</span>
                            </div>
                        </li>
                    ))}
                </ul>
            </div>
            <div className="md:col-span-3 bg-white rounded-xl shadow-lg border border-gray-100 p-6 flex flex-col">
                {selectedPeer ? (
                    <>
                        <h3 className="text-xl font-bold mb-4 text-gray-800">Chat với {selectedPeer.name}</h3>
                        <div className="flex-1 overflow-y-auto bg-gray-50 p-4 rounded-lg space-y-4 max-h-[400px]">
                            {(messages[selectedPeer.id] || []).map((msg, index) => (
                                <div key={index} className={`flex ${msg.sender === 'You' ? 'justify-end' : 'justify-start'}`}>
                                    <div className={`max-w-xs p-3 rounded-xl shadow-sm ${msg.sender === 'You' ? 'bg-blue-600 text-white' : 'bg-gray-200 text-gray-800'}`}>
                                        <p className="text-sm">{msg.text}</p>
                                        <p className="text-xs mt-1 opacity-75">{msg.timestamp}</p>
                                    </div>
                                </div>
                            ))}
                        </div>
                        <div className="mt-4 flex">
                            <input
                                type="text"
                                value={messageInput}
                                onChange={(e) => setMessageInput(e.target.value)}
                                onKeyPress={(e) => e.key === 'Enter' && handleSend()}
                                placeholder="Nhập tin nhắn..."
                                className="flex-1 border border-gray-200 rounded-l-lg px-4 py-3 focus:outline-none focus:ring-2 focus:ring-blue-500"
                            />
                            <button
                                onClick={handleSend}
                                className="px-6 py-3 bg-blue-600 text-white rounded-r-lg hover:bg-blue-700 transition-colors duration-200"
                            >
                                Gửi
                            </button>
                        </div>
                    </>
                ) : (
                    <p className="text-center text-gray-500 flex-1 flex items-center justify-center text-lg">Chọn một peer để bắt đầu chat</p>
                )}
            </div>
        </div>
    );
};

export default Chat;