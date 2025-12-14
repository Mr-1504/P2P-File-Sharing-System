import { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';

const Chat = ({
    peers,
    conversations,
    selectedConversation,
    messages,
    onSendMessage,
    onConversationSelect,
    onLoadOlderMessages,
    canLoadMore
}) => {
    const [messageInput, setMessageInput] = useState('');
    const { t } = useTranslation();
    const messagesEndRef = useRef(null);

    const handleSend = () => {
        if (messageInput.trim() && selectedConversation) {
            onSendMessage(messageInput);
            setMessageInput('');
        }
    };

    useEffect(() => {
        if (messagesEndRef.current) {
            messagesEndRef.current.scrollIntoView({ behavior: 'smooth' });
        }
    }, [messages]);

    const formatTime = (timestamp) => {
        return new Date(timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    };

    return (
        <div
            className="fixed top-[180px] left-0 right-0 bottom-0 bg-white p-4 flex"
        >
            <div className="flex flex-col sm:flex-row gap-4 h-full w-full">
                {/* Left Panel - Conversations */}
                <div
                    className="w-full sm:w-1/4 bg-white rounded-[10px] border border-[#00000040] flex flex-col ml-0 sm:ml-[15px]"
                    style={{ boxShadow: '2px 4px 8px -1px rgba(0, 0, 0, 0.25)' }}
                >
                    <div className="p-4 border-b border-gray-200 sticky top-0 bg-white z-10">
                        <h3 className="text-lg font-bold text-[#196BAD]" style={{ fontFamily: 'Kumbh Sans, -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, sans-serif' }}>
                            {t('conversations_peers')}
                        </h3>
                    </div>
                    <div className="flex-1 overflow-y-auto p-2">
                        {conversations && conversations.length > 0 ? (
                            /* Conversations */
                            conversations.map(conversation => {
                                const conversationName = conversation.name;

                                return (
                                    <div
                                        key={conversation.id}
                                        onClick={() => onConversationSelect(conversation)}
                                        className={`p-4 mx-1 mb-1 cursor-pointer transition-all duration-200 rounded-lg ${
                                            selectedConversation?.id === conversation.id
                                                ? 'bg-blue-50 border border-blue-200 shadow-sm'
                                                : 'hover:bg-gray-50 hover:shadow-sm'
                                        }`}
                                    >
                                        <div className="flex items-center justify-between">
                                            <span className="text-sm font-semibold text-gray-900" style={{ fontFamily: 'Kumbh Sans, -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, sans-serif' }}>
                                                {conversationName}
                                            </span>
                                            {conversation.type === 'group' && (
                                                <span className="text-xs bg-gray-200 px-2 py-1 rounded">
                                                    {conversation.participants.length} members
                                                </span>
                                            )}
                                        </div>
                                        <div className="text-xs text-gray-500 mt-1">
                                            {conversation.lastMessage ? conversation.lastMessage.substring(0, 30) + '...' : 'No messages'}
                                        </div>
                                    </div>
                                );
                            })
                        ) : (
                            /* Peers */
                            peers ? peers
                                .filter(peer => peer.id !== 'me')
                                .map(peer => {
                                    const isOnline = peer.username && typeof peer.username === 'string' && peer.username.trim() !== "" && peer.username !== "null";
                                    return (
                                        <div
                                            key={`peer-${peer.id}`}
                                            onClick={() => {
                                                // Create a new private conversation for this peer
                                                const newConversation = {
                                                    id: `peer-${peer.id}`,
                                                    type: 'private',
                                                    participants: ['me', peer.id],
                                                    name: null, // Will use peer name
                                                    lastMessage: null
                                                };
                                                onConversationSelect(newConversation);
                                            }}
                                            className="p-4 mx-1 mb-1 cursor-pointer transition-all duration-200 rounded-lg hover:bg-gray-50 hover:shadow-sm"
                                        >
                                            <div className="flex items-center justify-between">
                                                <span className="text-sm font-semibold text-gray-700" style={{ fontFamily: 'Kumbh Sans, -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, sans-serif' }}>
                                                    {peer.name}
                                                </span>
                                                <div className={`w-2 h-2 rounded-full ${isOnline ? 'bg-green-500' : 'bg-gray-400'}`}></div>
                                            </div>
                                            <div className="text-xs text-gray-400 mt-1">
                                                {t(isOnline ? 'online_peer' : 'offline_peer')}
                                            </div>
                                        </div>
                                    );
                                }) : (
                                <div className="text-center text-gray-500 py-8">
                                    {t('no_conversations')}
                                </div>
                            )
                        )}
                    </div>
                </div>

                {/* Right Panel - Chat Interface */}
                <div
                    className="flex-1 bg-white rounded-[10px] border border-[#00000040] flex flex-col overflow-hidden"
                    style={{ boxShadow: '2px 4px 8px -1px rgba(0, 0, 0, 0.25)' }}
                >
                    {selectedConversation ? (
                        <>
                            {/* Chat Header */}
                            <div className="p-4 border-b border-gray-200 bg-white sticky top-0 z-10">
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
                                    <div className="flex-1">
                                        <h3 className="text-lg font-bold text-black">
                                            {selectedConversation.name}
                                        </h3>
                                        <div className="flex items-center space-x-1">
                                            <div className="w-2 h-2 bg-green-500 rounded-full"></div>
                                            <span className="text-sm text-black font-normal">
                                                {selectedConversation.type === 'group' ? `${selectedConversation.participants.length} members` : 'Private'}
                                            </span>
                                        </div>
                                    </div>
                                </div>
                            </div>

                            {/* Load More Button */}
                            {canLoadMore && (
                                <div className="p-2 border-b border-gray-200 text-center">
                                    <button
                                        onClick={onLoadOlderMessages}
                                        className="text-sm text-[#196BAD] hover:text-[#1669A6] underline"
                                    >
                                        {t('load_older_messages')}
                                    </button>
                                </div>
                            )}

                            {/* Chat Messages */}
                            <div className="flex-1 overflow-y-auto p-4 space-y-4 bg-white">
                                {messages.map((msg, index) => (
                                    <div
                                        key={msg.id || index}
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
                                        <div className="max-w-xs">
                                            <div
                                                className={`px-4 py-2 rounded-2xl ${
                                                    msg.sender === 'You'
                                                        ? 'bg-[#D1E7FF] text-black'
                                                        : 'bg-[#F0F0F0] text-black'
                                                }`}
                                            >
                                                <p className="text-sm">{msg.text}</p>
                                            </div>
                                            <div className={`text-xs text-gray-500 mt-1 ${msg.sender === 'You' ? 'text-right' : 'text-left'}`}>
                                                {formatTime(msg.timestamp)}
                                            </div>
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
                                <div ref={messagesEndRef} />
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
                            <p className="text-gray-500 text-lg">{t('select_conversation')}</p>
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
};

export default Chat;
