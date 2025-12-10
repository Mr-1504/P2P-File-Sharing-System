package domain.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "conversation")
public class Conversation {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "name")
    private String name;

    @Column(name = "avatar_path")
    private String avatarPath;

    @Column(name = "is_group")
    private int isGroup;

    @Column(name = "peer_public_key")
    private String peerPublicKey;

    @Column(name = "last_msg_content")
    private String lastMsgContent;

    @Column(name = "last_msg_time", columnDefinition = "BIGINT")
    private Long lastMsgTime;

    @Column(name = "unread_count")
    private int unreadCount;

    // Getters and setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAvatarPath() {
        return avatarPath;
    }

    public void setAvatarPath(String avatarPath) {
        this.avatarPath = avatarPath;
    }

    public int getIsGroup() {
        return isGroup;
    }

    public void setIsGroup(int isGroup) {
        this.isGroup = isGroup;
    }

    public String getPeerPublicKey() {
        return peerPublicKey;
    }

    public void setPeerPublicKey(String peerPublicKey) {
        this.peerPublicKey = peerPublicKey;
    }

    public String getLastMsgContent() {
        return lastMsgContent;
    }

    public void setLastMsgContent(String lastMsgContent) {
        this.lastMsgContent = lastMsgContent;
    }

    public Long getLastMsgTime() {
        return lastMsgTime;
    }

    public void setLastMsgTime(Long lastMsgTime) {
        this.lastMsgTime = lastMsgTime;
    }

    public int getUnreadCount() {
        return unreadCount;
    }

    public void setUnreadCount(int unreadCount) {
        this.unreadCount = unreadCount;
    }
}
