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
    const [currentUsername, setCurrentUsername] = useState('');

    // Load username and peers/conversations on mount
    useEffect(() => {
        // Load username from localStorage
        const storedUsername = localStorage.getItem('p2p_username');
        if (storedUsername) {
            setCurrentUsername(storedUsername);
        }
        loadChatData();
    }, []);

    // Poll for new messages every 10 seconds
    useEffect(() => {
        if (selectedConversation && currentUsername) {
            const interval = setInterval(async () => {
                try {
                    const newMessages = await chatApi.getMessages(selectedConversation.id, 10, 0);
                    // Only update if there are new messages
                    if (newMessages.length > 0 && (!messages.length || !newMessages[0]?.id || !messages[0]?.id || newMessages[0].id !== messages[0].id)) {
                        // Map API response to expected format for new messages
                        const mappedNewMessages = newMessages.map(msg => ({
                            id: msg.id,
                            text: msg.content || msg.text || '',
                            sender: msg.senderId === currentUsername ? 'You' : (msg.senderId || 'Unknown'),
                            timestamp: new Date(msg.createdAt).toISOString(),
                            read: msg.read || false, // Respect API read status
                            status: msg.status,
                            msgType: msg.msgType
                        }));

                        setMessages(mappedNewMessages);

                        // Update last message in conversations list for better UI responsiveness
                        const latestMessage = newMessages[0]; // Most recent message (API response, not mapped)
                        setConversations(prevConversations =>
                            prevConversations.map(conv =>
                                conv.id === selectedConversation.id
                                    ? {
                                        ...conv,
                                        lastMessage: latestMessage.content || latestMessage.text || '',
                                        lastMessageTime: latestMessage.createdAt || latestMessage.timestamp
                                    }
                                    : conv
                            )
                        );

                        // Acknowledge unread messages - only acknowledge messages NOT sent by current user
                        const unreadMessageIds = newMessages
                            .filter(msg => !msg.read && msg.senderId !== currentUsername)
                            .map(msg => msg.id);
                        if (unreadMessageIds.length > 0) {
                            await chatApi.acknowledgeMessages(unreadMessageIds);
                        }
                    }
                } catch (error) {
                    console.error('Error polling messages:', error);
                }
            }, 1000); // Poll every 10 seconds

            return () => clearInterval(interval);
        }
    }, [selectedConversation, currentUsername, messages]);

    const loadChatData = async () => {
        try {
            setLoading(true);
            const [conversationsData, offlineMessages, peersData] = await Promise.all([
                chatApi.getConversations(),
                chatApi.getOfflineMessages(),
                chatApi.getPeers() // Always fetch peers to show all peers feature
            ]);
            // Map conversations to expected format: {id, type, name, participants, lastMessage, unreadCount}
            // Align with API docs: {id, name, isGroup, peerPublicKey, lastMsgContent, lastMsgTime, unreadCount}
            const mappedConversations = (conversationsData || []).map(conv => ({
                id: conv.id,
                type: conv.isGroup ? 'group' : 'private',
                name: conv.name,
                participants: conv.isGroup ? (conv.participants || []) : ['me', conv.name], // For groups use participants array, for private use 'me' and peer name
                lastMessage: conv.lastMsgContent || '', // Changed from lastMessage to lastMsgContent
                lastMessageTime: conv.lastMsgTime, // Add timestamp
                unreadCount: conv.unreadCount || 0,
                peerPublicKey: conv.peerPublicKey // Store public key for encryption
            }));
            const mappedPeers = (peersData || []).map(peer => ({
                id: `${peer.ip}:${peer.port}`,
                name: peer.username || `${peer.ip}:${peer.port}`,
                username: peer.username,
                ip: peer.ip,
                port: peer.port,
                taskForDownloadCount: peer.taskForDownloadCount
            }));
            setPeers(mappedPeers);
            setConversations(mappedConversations);

            // Handle offline messages - could show a notification or integrate into conversations
            if (offlineMessages && offlineMessages.length > 0) {
                addNotification(`${offlineMessages.length} offline messages received`, false);
            }

            // Select first conversation if available (safe null check)
            if (mappedConversations && mappedConversations.length > 0) {
                setSelectedConversation(mappedConversations[0]);
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

            // Map API response to expected format
            const mappedMessages = (messagesData || []).map(msg => ({
                id: msg.id,
                text: msg.content || msg.text || '', // Use content field from API
                sender: msg.senderId === currentUsername ? 'You' : (msg.senderId || 'Unknown'), // Determine if it's current user's message
                timestamp: new Date(msg.createdAt).toISOString(), // Convert unix timestamp to ISO string
                read: true, // Assume loaded messages are read
                status: msg.status,
                msgType: msg.msgType
            }));

            // Sort messages by timestamp ascending (oldest first) so newest appear at bottom
            const sortedMessages = mappedMessages.sort((a, b) =>
                new Date(a.timestamp) - new Date(b.timestamp)
            );

            setMessages(prev => append ? [...sortedMessages, ...prev] : sortedMessages);

            // Acknowledge unread messages - use original API data for this
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

            // Send message via API - using "content" parameter name per API docs
            if (selectedConversation.type === 'group') {
                await chatApi.sendGroupMessage(selectedConversation.id, text);
            } else {
                // For private, use conversation ID as receiverId per user feedback
                await chatApi.sendPrivateMessage(selectedConversation.id, text);
            }

            // Optimistically update UI
            const newMessage = {
                id: Date.now(), // Temporary ID
                text,
                sender: 'You',
                timestamp: new Date().toISOString(),
                read: true,
                content: text // Add content field for consistency
            };
            setMessages(prev => [...prev, newMessage]);

            // Update last message in conversations list immediately
            setConversations(prevConversations =>
                prevConversations.map(conv =>
                    conv.id === selectedConversation.id
                        ? {
                            ...conv,
                            lastMessage: text,
                            lastMessageTime: new Date().toISOString()
                        }
                        : conv
                )
            );

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

    // Separate function for loading older messages with append=true
    const loadOlderMessagesWithAppend = async () => {
        if (selectedConversation && messages.length >= messageLimit) {
            const newOffset = messageOffset + messageLimit;
            try {
                const messagesData = await chatApi.getMessages(selectedConversation.id, messageLimit, newOffset);

                if (messagesData && messagesData.length > 0) {
                    // Map and sort older messages
                    const mappedMessages = (messagesData || []).map(msg => ({
                        id: msg.id,
                        text: msg.content || msg.text || '',
                        sender: msg.senderId === currentUsername ? 'You' : (msg.senderId || 'Unknown'),
                        timestamp: new Date(msg.createdAt).toISOString(),
                        read: true,
                        status: msg.status,
                        msgType: msg.msgType
                    }));

                    // Sort by timestamp ascending and prepend to existing messages
                    const sortedMessages = mappedMessages.sort((a, b) =>
                        new Date(a.timestamp) - new Date(b.timestamp)
                    );

                    setMessages(prev => [...sortedMessages, ...prev]);
                    setMessageOffset(newOffset);

                    // Acknowledge unread messages from older messages
                    const unreadMessageIds = messagesData.filter(msg => !msg.read).map(msg => msg.id);
                    if (unreadMessageIds.length > 0) {
                        await chatApi.acknowledgeMessages(unreadMessageIds);
                    }
                }
            } catch (error) {
                console.error('Error loading older messages:', error);
                addNotification(t('error_loading_messages'), true);
            }
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
            onLoadOlderMessages={loadOlderMessagesWithAppend}
            canLoadMore={messages.length >= messageLimit}
        />
    );
};

export default ChatPage;
