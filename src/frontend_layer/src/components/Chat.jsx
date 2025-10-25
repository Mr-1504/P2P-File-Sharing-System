import { useState } from 'react';
import { useTranslation } from 'react-i18next';

const Chat = ({ peers, messages, onSendMessage, selectedPeer, setSelectedPeer }) => {
    const [messageInput, setMessageInput] = useState('');
    const { t } = useTranslation();

    const handleSend = () => {
        if (messageInput.trim() && selectedPeer) {
            onSendMessage(selectedPeer.id, messageInput);
            setMessageInput('');
        }
    };

    return (
        <div
            className="h-[600px] bg-[#C9DEEF54] rounded-[10px] p-4 mx-auto
                       w-full sm:w-[95%] md:w-[85%] lg:w-[75%]"
            style={{ boxShadow: '2px 4px 8px -1px rgba(0, 0, 0, 0.25)' }}
        >
            {/* Bọc 2 panel bằng flex */}
            <div className="flex flex-col sm:flex-row gap-4 h-[552px]">
                {/* Left Panel - Peer List */}
                <div
                    className="w-full sm:w-1/4 bg-white rounded-[10px] border border-[#00000040] flex flex-col ml-0 sm:ml-[15px]"
                    style={{ boxShadow: '2px 4px 8px -1px rgba(0, 0, 0, 0.25)' }}
                >
                    <div className="p-4 border-b border-gray-200">
                        <h3 className="text-lg font-bold text-[#196BAD]" style={{ fontFamily: 'Kumbh Sans, -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, sans-serif' }}>{t('peerList')}</h3>
                    </div>
                    <div className="flex-1 overflow-y-auto">
                        {peers.map(peer => (
                            <div
                                key={peer.id}
                                onClick={() => setSelectedPeer(peer)}
                                className={`p-3 cursor-pointer transition-all duration-200 border-b border-gray-100 ${
                                    selectedPeer?.id === peer.id
                                        ? 'bg-blue-50'
                                        : 'hover:bg-gray-50'
                                }`}
                            >
                                <div className="flex items-center justify-between">
                                    <span className="text-sm font-medium text-[#4F4F4F]" style={{ fontFamily: 'Kumbh Sans, -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, sans-serif' }}>{peer.ip}</span>
                                    {peer.status === 'Online' && (
                                        <div className="flex items-center space-x-1">
                                            <div className="w-2 h-2 bg-green-500 rounded-full"></div>
                                            <span className="text-xs text-[#4F4F4F]" style={{ fontFamily: 'Kumbh Sans, -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, sans-serif' }}>Online</span>
                                        </div>
                                    )}
                                </div>
                            </div>
                        ))}
                    </div>
                </div>

                {/* Right Panel - Chat Interface */}
                <div
                    className="flex-1 bg-white rounded-[10px] border border-[#00000040] flex flex-col overflow-hidden"
                    style={{ boxShadow: '2px 4px 8px -1px rgba(0, 0, 0, 0.25)' }}
                >
                    {selectedPeer ? (
                        <>
                            {/* Chat Header */}
                            <div className="p-4 border-b border-gray-200 bg-white">
                                <div className="flex items-center space-x-3">
                                    <div className="w-10 h-10 bg-gray-200 rounded-lg flex items-center justify-center">
                                        <svg
                                            className="w-6 h-6 text-gray-600"
                                            fill="currentColor"
                                            viewBox="0 0 20 20"
                                        >
                                            <path
                                                fillRule="evenodd"
                                                d="M10 9a3 3 0 100-6 3 3 0 000 6zm-7 9a7 7 0 1114 0H3z"
                                                clipRule="evenodd"
                                            />
                                        </svg>
                                    </div>
                                    <div>
                                        <h3 className="text-lg font-bold text-black">{selectedPeer.name}</h3>
                                        <div className="flex items-center space-x-1">
                                            <div className="w-2 h-2 bg-green-500 rounded-full"></div>
                                            <span className="text-sm text-black font-normal">Online</span>
                                        </div>
                                    </div>
                                </div>
                            </div>

                            {/* Chat Messages */}
                            <div className="flex-1 overflow-y-auto p-4 space-y-4 bg-white">
                                {(messages[selectedPeer.id] || []).map((msg, index) => (
                                    <div
                                        key={index}
                                        className={`flex items-end ${
                                            msg.sender === 'You' ? 'justify-end' : 'justify-start'
                                        }`}
                                    >
                                        {msg.sender !== 'You' && (
                                            <div className="w-8 h-8 bg-gray-200 rounded-lg flex items-center justify-center mr-2 flex-shrink-0">
                                                <svg
                                                    className="w-5 h-5 text-gray-600"
                                                    fill="currentColor"
                                                    viewBox="0 0 20 20"
                                                >
                                                    <path
                                                        fillRule="evenodd"
                                                        d="M10 9a3 3 0 100-6 3 3 0 000 6zm-7 9a7 7 0 1114 0H3z"
                                                        clipRule="evenodd"
                                                    />
                                                </svg>
                                            </div>
                                        )}
                                        <div
                                            className={`max-w-xs px-4 py-2 rounded-2xl ${
                                                msg.sender === 'You'
                                                    ? 'bg-[#D1E7FF] text-black'
                                                    : 'bg-[#F0F0F0] text-black'
                                            }`}
                                        >
                                            <p className="text-sm">{msg.text}</p>
                                        </div>
                                        {msg.sender === 'You' && (
                                            <div className="w-8 h-8 bg-gray-200 rounded-lg flex items-center justify-center ml-2 flex-shrink-0">
                                                <svg
                                                    className="w-5 h-5 text-gray-600"
                                                    fill="currentColor"
                                                    viewBox="0 0 20 20"
                                                >
                                                    <path
                                                        fillRule="evenodd"
                                                        d="M10 9a3 3 0 100-6 3 3 0 000 6zm-7 9a7 7 0 1114 0H3z"
                                                        clipRule="evenodd"
                                                    />
                                                </svg>
                                            </div>
                                        )}
                                    </div>
                                ))}
                            </div>

                            {/* Message Input */}
                            <div className="p-4 border-t border-gray-200 bg-white">
                                <div className="flex items-center bg-white border border-gray-300 rounded-lg px-4 py-2">
                                    <input
                                        type="text"
                                        value={messageInput}
                                        onChange={(e) => setMessageInput(e.target.value)}
                                        onKeyPress={(e) => e.key === 'Enter' && handleSend()}
                                        placeholder={t('enter_message')}
                                        className="flex-1 outline-none text-black placeholder-gray-400"
                                    />
                                    <button
                                        onClick={handleSend}
                                        className="ml-2 p-1 text-[#196BAD] hover:text-[#1669A6] transition-colors duration-200"
                                    >
                                        <svg
                                            className="w-5 h-5 rotate-45"
                                            fill="currentColor"
                                            viewBox="0 0 20 20"
                                        >
                                            <path d="M10.894 2.553a1 1 0 00-1.788 0l-7 14a1 1 0 001.169 1.409l5-1.429A1 1 0 009 15.571V11a1 1 0 112 0v4.571a1 1 0 00.725.962l5 1.428a1 1 0 001.17-1.408l-7-14z" />
                                        </svg>
                                    </button>
                                </div>
                            </div>
                        </>
                    ) : (
                        <div className="flex-1 flex items-center justify-center">
                            <p className="text-gray-500 text-lg">Chọn một peer để bắt đầu chat</p>
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
};

export default Chat;
