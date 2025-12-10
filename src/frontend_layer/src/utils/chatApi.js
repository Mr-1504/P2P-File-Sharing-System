import { buildApiUrl } from './config';

// Chat API utility functions

export const sendPrivateMessage = async (recipientId, message) => {
    const response = await fetch(buildApiUrl('/api/chat/send-private'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ recipientId, message })
    });
    if (!response.ok) throw new Error('Failed to send private message');
    return await response.json();
};

export const sendGroupMessage = async (groupId, message) => {
    const response = await fetch(buildApiUrl('/api/chat/send-group'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ groupId, message })
    });
    if (!response.ok) throw new Error('Failed to send group message');
    return await response.json();
};

export const getConversations = async () => {
    const response = await fetch(buildApiUrl('/api/chat/conversations'));
    if (!response.ok) throw new Error('Failed to fetch conversations');
    return await response.json();
};

export const createConversation = async (participantIds, type = 'private') => {
    const response = await fetch(buildApiUrl('/api/chat/conversations'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ participantIds, type })
    });
    if (!response.ok) throw new Error('Failed to create conversation');
    return await response.json();
};

export const getConversation = async (conversationId) => {
    const response = await fetch(buildApiUrl(`/api/chat/conversations/${conversationId}`));
    if (!response.ok) throw new Error('Failed to fetch conversation');
    return await response.json();
};

export const getMessages = async (conversationId, limit = 50, offset = 0) => {
    const response = await fetch(buildApiUrl(`/api/chat/messages?conversationId=${conversationId}&limit=${limit}&offset=${offset}`));
    if (!response.ok) throw new Error('Failed to fetch messages');
    return await response.json();
};

export const getOfflineMessages = async () => {
    const response = await fetch(buildApiUrl('/api/chat/offline-messages'));
    if (!response.ok) throw new Error('Failed to fetch offline messages');
    return await response.json();
};

export const acknowledgeMessages = async (messageIds) => {
    const response = await fetch(buildApiUrl('/api/chat/acknowledge'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ messageIds })
    });
    if (!response.ok) throw new Error('Failed to acknowledge messages');
    return await response.json();
};

export const addGroupMember = async (groupId, memberId) => {
    const response = await fetch(buildApiUrl(`/api/chat/groups/${groupId}/members`), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ memberId })
    });
    if (!response.ok) throw new Error('Failed to add group member');
    return await response.json();
};

export const getGroupMembers = async (groupId) => {
    const response = await fetch(buildApiUrl(`/api/chat/groups/${groupId}/members`));
    if (!response.ok) throw new Error('Failed to fetch group members');
    return await response.json();
};

// Additional helper functions for peers (assuming API exists)
export const getPeers = async () => {
    const response = await fetch(buildApiUrl('/api/peers'));
    if (!response.ok) throw new Error('Failed to fetch peers');
    return await response.json();
};
