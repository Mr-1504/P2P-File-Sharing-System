package main.java.model;

import java.util.Objects;

public class FileBase {
    private String fileName;
    private long fileSize;
    private PeerInfor peerInfor;

    public FileBase(String fileName, long fileSize, PeerInfor peerInfor) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.peerInfor = peerInfor;
    }

    public String getFileName() {
        return fileName;
    }
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }
    public long getFileSize() {
        return fileSize;
    }
    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }
    public PeerInfor getPeerInfor() {
        return peerInfor;
    }
    public void setPeerInfor(PeerInfor peerInfor) {
        this.peerInfor = peerInfor;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileBase fileBase = (FileBase) o;
        return fileName.equals(fileBase.fileName) &&
                peerInfor.equals(fileBase.peerInfor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileName, peerInfor);
    }

    @Override
    public String toString() {
        return fileName + "'" + fileSize + "'" + peerInfor.getIp() + "'" + peerInfor.getPort();
    }
}
