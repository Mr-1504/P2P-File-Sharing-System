package domain.repository;

import domain.entity.Conversation;
import domain.entity.Message;
import domain.entity.GroupMember;

import java.util.List;

/**
 * Repository interface for chat functionality.
 */
public interface IChatRepository {

    // Conversation operations
    Conversation saveConversation(Conversation conversation);
    Conversation findConversationById(String conversationId);
    List<Conversation> findAllConversations();
    void updateConversation(Conversation conversation);

    // Message operations
    Message saveMessage(Message message);
    List<Message> findMessagesByConversationId(String conversationId, int limit, int offset);
    List<Message> findOfflineMessages(String receiverId);
    void deleteMessages(List<String> messageIds);

    // Group operations
    GroupMember saveGroupMember(GroupMember member);
    List<GroupMember> findGroupMembersByGroupId(String groupId);
    void updateGroupMember(GroupMember member);
}
