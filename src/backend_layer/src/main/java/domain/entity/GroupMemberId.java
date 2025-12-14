package domain.entity;

import java.io.Serializable;
import java.util.Objects;

public class GroupMemberId implements Serializable {

    private String groupId;
    private String peerId;

    public GroupMemberId() {}

    public GroupMemberId(String groupId, String peerId) {
        this.groupId = groupId;
        this.peerId = peerId;
    }

    // Getters and setters

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getPeerId() {
        return peerId;
    }

    public void setPeerId(String peerId) {
        this.peerId = peerId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GroupMemberId that = (GroupMemberId) o;
        return Objects.equals(groupId, that.groupId) && Objects.equals(peerId, that.peerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, peerId);
    }
}
