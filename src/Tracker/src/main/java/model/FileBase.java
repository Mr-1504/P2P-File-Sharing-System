package model;

import java.util.Objects;
import java.io.Serializable;

public class FileBase implements Serializable {
    private static final long serialVersionUID = 1L;
    private String fileName;
    private long fileSize;
    private PeerInfo peerInfo;

    public FileBase(String fileName, long fileSize, PeerInfo peerInfo) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.peerInfo = peerInfo;
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
    public PeerInfo getPeerInfo() {
        return peerInfo;
    }
    public void setPeerInfor(PeerInfo peerInfo) {
        this.peerInfo = peerInfo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileBase fileBase = (FileBase) o;
        return fileName.equals(fileBase.fileName) &&
                peerInfo.equals(fileBase.peerInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileName, peerInfo);
    }

    @Override
    public String toString() {
        return fileName + "'" + fileSize + "'" + peerInfo.getIp() + "'" + peerInfo.getPort();
    }
}
