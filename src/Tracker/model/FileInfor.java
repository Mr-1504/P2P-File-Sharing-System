package model;

import java.util.Objects;
import java.io.Serializable;

public class FileInfor extends FileBase implements Serializable {
    private static final long serialVersionUID = 1L;
    private String fileHash;

    public FileInfor(String fileName, long fileSize, String fileHash, PeerInfor peerInfor) {
        super(fileName, fileSize, peerInfor);
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
        FileInfor fileInfor = (FileInfor) o;
        return Objects.equals(fileHash, fileInfor.fileHash);
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
