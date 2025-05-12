package model;

public class PeerInfor {
    private final String host;
    private final int port;

    public PeerInfor(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PeerInfor peerInfo = (PeerInfor) o;
        return port == peerInfo.port && host.equals(peerInfo.host);
    }

    @Override
    public int hashCode() {
        return host.hashCode() + port;
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}