package dto;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "group_members")
@IdClass(GroupMemberId.class)
public class GroupMember {
    @Id
    @Column(name = "group_id")
    private java.util.UUID groupId;

    @Id
    @Column(name = "peer_id")
    private java.util.UUID peerId;

    @Column(length = 20)
    private String role = "member";

    @Column(name = "joined_at")
    private LocalDateTime joinedAt;

    // Relationships
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", insertable = false, updatable = false)
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "peer_id", insertable = false, updatable = false)
    private Peer peer;

    // Constructors
    public GroupMember() {}

    public GroupMember(java.util.UUID groupId, java.util.UUID peerId, String role, LocalDateTime joinedAt) {
        this.groupId = groupId;
        this.peerId = peerId;
        this.role = role;
        this.joinedAt = joinedAt;
    }

    // Getters and setters
    public java.util.UUID getGroupId() {
        return groupId;
    }

    public void setGroupId(java.util.UUID groupId) {
        this.groupId = groupId;
    }

    public java.util.UUID getPeerId() {
        return peerId;
    }

    public void setPeerId(java.util.UUID peerId) {
        this.peerId = peerId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }

    public Group getGroup() {
        return group;
    }

    public void setGroup(Group group) {
        this.group = group;
    }

    public Peer getPeer() {
        return peer;
    }

    public void setPeer(Peer peer) {
        this.peer = peer;
    }
}
