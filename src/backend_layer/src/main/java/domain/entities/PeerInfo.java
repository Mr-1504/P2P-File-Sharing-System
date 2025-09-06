package domain.entities;

import java.util.Objects;
import java.io.Serializable;

import static main.java.utils.Log.logError;

public class PeerInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    private String ip;
    private int port;
    private int taskForDownloadCount;

    public int getTaskForDownload() {
        return taskForDownloadCount;
    }

    public void addTaskForDownload() {
        if (taskForDownloadCount < 0) {
            taskForDownloadCount = 0;
        }

        if (taskForDownloadCount <= 3) {
            taskForDownloadCount++;
        } else {
            logError("Task limit exceeded for peer: " + this, null);
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
