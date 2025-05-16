package model;

public class PeerInfor {
    private String peerIp;
    private int peerPort;

    public PeerInfor(String peerIp, int peerPort) {
        this.peerIp = peerIp;
        this.peerPort = peerPort;
    }

    public String getPeerIp() {
        return peerIp;
    }

    public void setPeerIp(String peerIp) {
        this.peerIp = peerIp;
    }

    public int getPeerPort() {
        return peerPort;
    }

    public void setPeerPort(int peerPort) {
        this.peerPort = peerPort;
    }

    @Override
    public String toString() {
        return "PeerInfor{" +
                "peerIp='" + peerIp + '\'' +
                ", peerPort=" + peerPort +
                '}';
    }
}
