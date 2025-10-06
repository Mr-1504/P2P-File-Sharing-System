package model;

import java.util.Objects;
import java.io.Serializable;

public class FileInfo extends FileBase implements Serializable {
    private static final long serialVersionUID = 1L;
    private String fileHash;

    public FileInfo(String fileName, long fileSize, String fileHash, PeerInfo peerInfo) {
        super(fileName, fileSize, peerInfo);
        this.fileHash = fileHash;
    }

    public String getFileHash() {
        return fileHash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false; // Kiểm tra equals của FileBase
        FileInfo fileInfo = (FileInfo) o;
        return Objects.equals(fileHash, fileInfo.fileHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), fileHash);
    }

    @Override
    public String toString() {
        return super.toString() + ", chunkHashes=" + fileHash;
    }
}
