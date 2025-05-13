package model;

public class FileInfor {
    private String fileName;
    private String fileHash;
    private long fileSize;
    private PeerInfor peerInfor;

    public FileInfor(String fileName, PeerInfor peerInfor) {
        this.fileName = fileName;
        this.peerInfor = peerInfor;
    }

    public String getFileName() {
        return fileName;
    }

    public String getFileHash() {
        return fileHash;
    }

    public long getFileSize() {
        return fileSize;
    }

    public PeerInfor getPeerInfor() {
        return peerInfor;
    }
}
