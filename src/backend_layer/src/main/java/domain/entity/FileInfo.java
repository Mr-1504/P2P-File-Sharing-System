package main.java.domain.entity;

import java.util.Objects;
import java.io.Serializable;

public class FileInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    private String fileName;
    private long fileSize;
    private String fileHash;
    private PeerInfo peerInfo;
    private boolean isSharedByMe;

    public FileInfo(String fileName, long fileSize, String fileHash, PeerInfo peerInfo) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.fileHash = fileHash;
        this.peerInfo = peerInfo;
    }

    public FileInfo(String fileName, long fileSize, String fileHash, PeerInfo peerInfo, boolean isSharedByMe) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.fileHash = fileHash;
        this.peerInfo = peerInfo;
        this.isSharedByMe = isSharedByMe;
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

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public PeerInfo getPeerInfo() {
        return peerInfo;
    }

    public void setPeerInfo(PeerInfo peerInfo) {
        this.peerInfo = peerInfo;
    }

    public boolean isSharedByMe() {
        return isSharedByMe;
    }

    public void setSharedByMe(boolean sharedByMe) {
        isSharedByMe = sharedByMe;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileInfo fileInfo = (FileInfo) o;
        return fileName.equals(fileInfo.fileName) &&
                peerInfo.equals(fileInfo.peerInfo) &&
                Objects.equals(fileHash, fileInfo.fileHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileName, peerInfo, fileHash);
    }

    @Override
    public String toString() {
        return fileName + "'" + fileSize + "'" + fileHash + "'" + peerInfo.getIp() + "'" + peerInfo.getPort();
    }
}
