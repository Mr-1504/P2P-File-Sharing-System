import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import Chat from '../components/Chat';
import * as chatApi from '../utils/chatApi';

const ChatPage = ({ addNotification }) => {
    const { t } = useTranslation();
    const [peers, setPeers] = useState([]);
    const [conversations, setConversations] = useState([]);
    const [selectedConversation, setSelectedConversation] = useState(null);
    const [messages, setMessages] = useState([]);
    const [loading, setLoading] = useState(true);
    const [messageLimit] = useState(50);
    const [messageOffset, setMessageOffset] = useState(0);

    // Load peers and conversations on mount
    useEffect(() => {
        loadChatData();
    }, []);

    // Poll for new messages every 0.1 seconds
    useEffect(() => {
        if (selectedConversation) {
            const interval = setInterval(async () => {
                try {
                    const newMessages = await chatApi.getMessages(selectedConversation.id, 10, 0);
                    // Only update if there are new messages
                    if (newMessages.length > 0 && (!messages.length || !newMessages[0]?.id || !messages[0]?.id || newMessages[0].id !== messages[0].id)) {
                        setMessages(newMessages);
                        // Acknowledge unread messages
                        const unreadMessageIds = newMessages.filter(msg => !msg.read && msg.sender !== 'You').map(msg => msg.id);
                        if (unreadMessageIds.length > 0) {
                            await chatApi.acknowledgeMessages(unreadMessageIds);
                        }
                    }
                } catch (error) {
                    console.error('Error polling messages:', error);
                }
            }, 100); // 0.1 seconds

            return () => clearInterval(interval);
        }
    }, [selectedConversation, messages]);

    const loadChatData = async () => {
        try {
            setLoading(true);
            const [peersData, conversationsData, offlineMessages] = await Promise.all([
                chatApi.getPeers(),
                chatApi.getConversations(),
                chatApi.getOfflineMessages()
            ]);
            setPeers(peersData || []);
            setConversations(conversationsData || []);

            // Handle offline messages - could show a notification or integrate into conversations
            if (offlineMessages && offlineMessages.length > 0) {
                addNotification(`${offlineMessages.length} offline messages received`, false);
            }

            // Select first conversation if available (safe null check)
            if (conversationsData && conversationsData.length > 0) {
                setSelectedConversation(conversationsData[0]);
            }
        } catch (error) {
            console.error('Error loading chat data:', error);
            addNotification(t('error_loading_chat'), true);
        } finally {
            setLoading(false);
        }
    };

    // Load messages when conversation changes
    useEffect(() => {
        if (selectedConversation) {
            loadMessages(selectedConversation.id);
        }
    }, [selectedConversation, messageOffset]);

    const loadMessages = async (conversationId, append = false) => {
        try {
            const messagesData = await chatApi.getMessages(conversationId, messageLimit, messageOffset);
            setMessages(prev => append ? [...messagesData, ...prev] : messagesData);

            // Acknowledge messages as read
            const unreadMessageIds = messagesData.filter(msg => !msg.read).map(msg => msg.id);
            if (unreadMessageIds.length > 0) {
                await chatApi.acknowledgeMessages(unreadMessageIds);
            }
        } catch (error) {
            console.error('Error loading messages:', error);
            addNotification(t('error_loading_messages'), true);
        }
    };

    const handleSendMessage = async (text) => {
        if (!selectedConversation || !text.trim()) return;

        try {
            const messageData = {
                text,
                conversationId: selectedConversation.id,
                timestamp: new Date().toISOString()
            };

            // Send message via API
            if (selectedConversation.type === 'group') {
                await chatApi.sendGroupMessage(selectedConversation.id, text);
            } else {
                // For private, find recipient
                const recipient = peers.find(p => p.id !== 'me' && selectedConversation.participants.includes(p.id));
                if (recipient) {
                    await chatApi.sendPrivateMessage(recipient.id, text);
                }
            }

            // Optimistically update UI
            const newMessage = {
                id: Date.now(), // Temporary ID
                text,
                sender: 'You',
                timestamp: new Date().toISOString(),
                read: true
            };
            setMessages(prev => [...prev, newMessage]);

            addNotification(t('message_sent'), false);
        } catch (error) {
            console.error('Error sending message:', error);
            addNotification(t('error_sending_message'), true);
        }
    };

    const handleConversationSelect = (conversation) => {
        setSelectedConversation(conversation);
        setMessageOffset(0); // Reset pagination
    };

    const loadOlderMessages = () => {
        if (selectedConversation && messages.length >= messageLimit) {
            setMessageOffset(prev => prev + messageLimit);
        }
    };

    if (loading) {
        return (
            <div className="flex items-center justify-center h-full">
                <div className="text-lg">{t('loading')}</div>
            </div>
        );
    }

    return (
        <Chat
            peers={peers}
            conversations={conversations}
            selectedConversation={selectedConversation}
            messages={messages}
            onSendMessage={handleSendMessage}
            onConversationSelect={handleConversationSelect}
            onLoadOlderMessages={loadOlderMessages}
            canLoadMore={messages.length >= messageLimit}
        />
    );
};

export default ChatPage;
