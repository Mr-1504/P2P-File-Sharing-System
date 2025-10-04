import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import Chat from '../components/Chat';

const ChatPage = ({ addNotification }) => {
    const { t } = useTranslation();
    const [peers, setPeers] = useState([
        { id: 1, name: 'Peer 1', ip: '192.168.1.1', status: 'Online' },
        { id: 2, name: 'Peer 2', ip: '192.168.1.2', status: 'Offline' }
    ]);
    const [messages, setMessages] = useState({
        1: [
            { sender: 'You', text: 'Xin chào!', timestamp: '10:00 AM' },
            { sender: 'Peer 1', text: 'Chào bạn!', timestamp: '10:01 AM' }
        ],
        2: [{ sender: 'You', text: 'Bạn có tệp nào không?', timestamp: '09:00 AM' }]
    });
    const [selectedPeer, setSelectedPeer] = useState(null);

    const handleSendMessage = (peerId, text) => {
        const newMessage = { sender: 'You', text, timestamp: new Date().toLocaleTimeString() };
        setMessages(prev => ({
            ...prev,
            [peerId]: [...(prev[peerId] || []), newMessage]
        }));
        addNotification(t('message_sent'), false);
        
        // Simulate response
        setTimeout(() => {
            const responseMessage = { 
                sender: `Peer ${peerId}`, 
                text: 'Tin nhắn nhận được!', 
                timestamp: new Date().toLocaleTimeString() 
            };
            setMessages(prev => ({
                ...prev,
                [peerId]: [...prev[peerId], responseMessage]
            }));
        }, 1000);
    };

    return (
        <Chat
            peers={peers}
            messages={messages}
            onSendMessage={handleSendMessage}
            selectedPeer={selectedPeer}
            setSelectedPeer={setSelectedPeer}
        />
    );
};

export default ChatPage;