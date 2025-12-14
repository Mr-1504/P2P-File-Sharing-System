package service;

import domain.entity.Conversation;
import domain.entity.Message;
import domain.entity.Peer;
import domain.entity.PeerInfo;

import java.util.List;

/**
 * Chat service interface.
 */
public interface IChatService {

    // Message sending
    boolean sendPrivateMessage(String conversationId, String content);
    boolean sendGroupMessage(String groupId, String content);

    // Conversation management
    Conversation createPrivateConversation(String conversationName, String receiverPublicKey);
    Conversation createPrivateConversation(PeerInfo receiver, String receiverPublicKey);
    Conversation createGroupConversation(String groupName, List<String> memberIds);
    Conversation getConversationById(String conversationId);
    List<Conversation> getAllConversations();
    boolean isPrivateConversationExists(String username);
    void createPrivateConversationIfNotExists(String username, String publicKey);

    // Message retrieval
    List<Message> getMessages(String conversationId, int limit, int offset);
    List<Message> getOfflineMessages(String receiverId);

    // Message acknowledgment
    void acknowledgeMessages(List<String> messageIds);

    // Tracker communication for keys
    String getPublicKeyFromTracker(String peerId);

    // Group management
    boolean addGroupMember(String groupId, String memberId, String publicKey);
    List<String> getGroupMembers(String groupId);
}
