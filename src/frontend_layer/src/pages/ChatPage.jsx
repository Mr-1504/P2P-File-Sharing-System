import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import Chat from '../components/Chat';

const ChatPage = ({ addNotification }) => {
    const { t } = useTranslation();
    const [peers, setPeers] = useState([
        { id: 1, name: 'Peer 1', ip: '172.16.201.135.5000', status: 'Online' },
        { id: 2, name: 'Peer 2', ip: '192.168.1.100', status: 'Online' },
        { id: 3, name: 'Peer 3', ip: '10.0.0.50', status: 'Offline' }
    ]);
    const [messages, setMessages] = useState({
        1: [
            { sender: 'Peer 1', text: 'Hi', timestamp: '10:00 AM' },
            { sender: 'You', text: 'Hi', timestamp: '10:01 AM' },
            { sender: 'Peer 1', text: 'Bye', timestamp: '10:02 AM' },
            { sender: 'Peer 1', text: 'See you next time', timestamp: '10:03 AM' }
        ],
        2: [
            { sender: 'You', text: 'Hello there!', timestamp: '09:00 AM' },
            { sender: 'Peer 2', text: 'Hi! How are you?', timestamp: '09:01 AM' }
        ],
        3: []
    });
    const [selectedPeer, setSelectedPeer] = useState(peers[0]); // Select first peer by default

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