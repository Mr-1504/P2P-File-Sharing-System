package domain.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "peers")
public class Peer {
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

    public void setId(UUID id) {
        this.id = id;
    }
    public UUID getId() {
        return id;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public boolean isOnline() {
        return isOnline;
    }

    public void setOnline(boolean online) {
        isOnline = online;
    }

    public LocalDateTime getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(LocalDateTime lastSeen) {
        this.lastSeen = lastSeen;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;
    @Column(length = 45)
    private String ip;
    private int port;
    @Column(name = "public_key", nullable = false, length = 1024)
    private String publicKey;
    @Column(name = "is_online")
    private boolean isOnline;
    @Column(name = "last_seen")
    private LocalDateTime lastSeen;
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Peer(String ip, int port, String publicKey, boolean isOnline, LocalDateTime lastSeen, LocalDateTime createdAt) {
        this.ip = ip;
        this.port = port;
        this.publicKey = publicKey;
        this.isOnline = isOnline;
        this.lastSeen = lastSeen;
        this.createdAt = createdAt;
    }

    public Peer() {
    }
}
