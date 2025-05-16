package model;

public class FileInfor {
    private String fileName;
    private PeerInfor peerInfor;

    public FileInfor(String fileName, PeerInfor peerInfor) {
        this.fileName = fileName;
        this.peerInfor = peerInfor;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public PeerInfor getPeerInfor() {
        return peerInfor;
    }

    public void setPeerInfor(PeerInfor peerInfor) {
        this.peerInfor = peerInfor;
    }
}
