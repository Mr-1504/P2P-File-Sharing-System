package src.model;

import java.util.Objects;
import java.io.Serializable;

public class PeerInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    private String ip;
    private int port;
    private String username;

    public PeerInfo(String ip, int port) {
        this.ip = ip;
        this.port = port;
    }

    public PeerInfo(String ip, int port, String username) {
        this.ip = ip;
        this.port = port;
        this.username = username;
    }
    public String getUsername() {
        return username;
    }
    public void setUsername(String username) {
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
        return ip + "|" + port + (username != null ? "|" + username : "");
    }
}
