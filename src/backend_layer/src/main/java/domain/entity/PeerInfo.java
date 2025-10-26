package domain.entity;

import java.util.Objects;
import java.io.Serializable;

/**
 * Represents information about a peer in the file sharing network.
 * Includes IP address, port number, username, and download task management.
 */
public class PeerInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    private String ip;
    private int port;
    private int taskForDownloadCount;
    private String username;

    /**
     * Get the number of tasks currently assigned for download from this peer.
     *
     * @return The count of download tasks.
     */
    public int getTaskForDownload() {
        return taskForDownloadCount;
    }

    /**
     * Increment the count of tasks assigned for download from this peer,
     * ensuring it does not exceed the maximum limit of 3.
     */
    public void addTaskForDownload() {
        if (taskForDownloadCount < 0) {
            taskForDownloadCount = 0;
        }

        if (taskForDownloadCount <= 3) {
            taskForDownloadCount++;
        }
    }


    /**
     * Decrement the count of tasks assigned for download from this peer,
     * ensuring it does not go below zero.
     */
    public void removeTaskForDownload() {
        if (taskForDownloadCount > 0) {
            taskForDownloadCount--;
        }
    }

    /**
     * Check if the peer is available for more download tasks.
     *
     * @return true if the peer can accept more download tasks, false otherwise.
     */
    public boolean isAvailableForDownload() {
        return taskForDownloadCount < 3;
    }

    /**
     * Constructor to initialize PeerInfo with IP and port.
     *
     * @param ip   IP address of the peer.
     * @param port Port number of the peer.
     */
    public PeerInfo(String ip, int port) {
        this.taskForDownloadCount = 1;
        this.ip = ip;
        this.port = port;
    }

    /**
     * Constructor to initialize PeerInfo with IP, port, and username.
     *
     * @param ip       IP address of the peer.
     * @param port     Port number of the peer.
     * @param username Username of the peer.
     */
    public PeerInfo(String ip, int port, String username) {
        this.taskForDownloadCount = 1;
        this.ip = ip;
        this.port = port;
        this.username = username;
    }

    /**
     * Get the IP address of the peer.
     *
     * @return The IP address as a String.
     */
    public String getIp() {
        return ip;
    }

    /**
     * Set the IP address of the peer.
     *
     * @param ip The IP address to set.
     */
    public void setIp(String ip) {
        this.ip = ip;
    }

    /**
     * Get the port number of the peer.
     *
     * @return The port number as an integer.
     */
    public int getPort() {
        return port;
    }

    /**
     * Get the username of the peer.
     *
     * @return The username as a String.
     */
    public String getUsername() {
        return username;
    }

    /**
     * Set the username of the peer.
     *
     * @param username The username to set.
     */
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
