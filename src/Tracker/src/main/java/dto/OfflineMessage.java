package dto;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "offline_messages")
public class OfflineMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sender_id", nullable = false)
    private java.util.UUID senderId;

    @Column(name = "receiver_id", nullable = false)
    private java.util.UUID receiverId;

    @Column(name = "group_id")
    private java.util.UUID groupId;

    @Column(name = "encrypted_payload", nullable = false, columnDefinition = "bytea")
    private byte[] encryptedPayload;

    @Column(length = 20)
    private String type = "text";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Relationships
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", insertable = false, updatable = false)
    private Peer sender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id", insertable = false, updatable = false)
    private Peer receiver;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", insertable = false, updatable = false)
    private Group group;

    // Constructors
    public OfflineMessage() {}

    public OfflineMessage(java.util.UUID senderId, java.util.UUID receiverId, java.util.UUID groupId, byte[] encryptedPayload, String type, LocalDateTime createdAt) {
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.groupId = groupId;
        this.encryptedPayload = encryptedPayload;
        this.type = type;
        this.createdAt = createdAt;
    }

    // Getters and setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public java.util.UUID getSenderId() {
        return senderId;
    }

    public void setSenderId(java.util.UUID senderId) {
        this.senderId = senderId;
    }

    public java.util.UUID getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(java.util.UUID receiverId) {
        this.receiverId = receiverId;
    }

    public java.util.UUID getGroupId() {
        return groupId;
    }

    public void setGroupId(java.util.UUID groupId) {
        this.groupId = groupId;
    }

    public byte[] getEncryptedPayload() {
        return encryptedPayload;
    }

    public void setEncryptedPayload(byte[] encryptedPayload) {
        this.encryptedPayload = encryptedPayload;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Peer getSender() {
        return sender;
    }

    public void setSender(Peer sender) {
        this.sender = sender;
    }

    public Peer getReceiver() {
        return receiver;
    }

    public void setReceiver(Peer receiver) {
        this.receiver = receiver;
    }

    public Group getGroup() {
        return group;
    }

    public void setGroup(Group group) {
        this.group = group;
    }
}
