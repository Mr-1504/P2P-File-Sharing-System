package main.domain.entity;

import java.util.Objects;
import java.io.Serializable;

public class PeerInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    private String ip;
    private int port;
    private int taskForDownloadCount;
    private String username;

    public int getTaskForDownload() {
        return taskForDownloadCount;
    }

    public void addTaskForDownload() {
        if (taskForDownloadCount < 0) {
            taskForDownloadCount = 0;
        }

        if (taskForDownloadCount <= 3) {
            taskForDownloadCount++;
        }
    }

    public void removeTaskForDownload() {
        if (taskForDownloadCount > 0) {
            taskForDownloadCount--;
        }
    }

    public boolean isAvailableForDownload() {
        return taskForDownloadCount < 3;
    }

    public PeerInfo(String ip, int port) {
        this.taskForDownloadCount = 1;
        this.ip = ip;
        this.port = port;
    }

    public PeerInfo(String ip, int port, String username) {
        this.taskForDownloadCount = 1;
        this.ip = ip;
        this.port = port;
        this.username = username;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    @Override
    public int hashCode() {
        return Objects.hash(ip, port);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        PeerInfo other = (PeerInfo) obj;
        return port == other.port && Objects.equals(ip, other.ip);
    }

    @Override
    public String toString() {
        return ip + "|" + port;
    }
}
