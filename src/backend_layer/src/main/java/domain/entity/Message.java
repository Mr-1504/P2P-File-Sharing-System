package domain.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "message")
public class Message {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "conversation_id")
    private String conversationId;

    @Column(name = "sender_id")
    private String senderId;

    @Column(name = "msg_type")
    private String msgType;

    @Column(name = "content")
    private String content;

    @Column(name = "status")
    private String status;

    @Column(name = "created_at")
    private Long createdAt;

    // Relationship to Conversation (optional, not mapped for simplicity)
    // @ManyToOne(fetch = FetchType.LAZY)
    // @JoinColumn(name = "conversation_id", insertable = false, updatable = false)
    // private Conversation conversation;

    // Getters and setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public String getMsgType() {
        return msgType;
    }

    public void setMsgType(String msgType) {
        this.msgType = msgType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
