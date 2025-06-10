package model;

import java.util.List;
import java.util.Objects;

public class FileInfor extends FileBase {
    private List<String> chunkHashes;

    public FileInfor(String fileName, long fileSize, List<String> chunkHashes, PeerInfor peerInfor) {
        super(fileName, fileSize, peerInfor);
        this.chunkHashes = chunkHashes;
    }

    public List<String> getChunkHashes() {
        return chunkHashes;
    }

    public void setChunkHashes(List<String> chunkHashes) {
        this.chunkHashes = chunkHashes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false; // Kiểm tra equals của FileBase
        FileInfor fileInfor = (FileInfor) o;
        return Objects.equals(chunkHashes, fileInfor.chunkHashes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), chunkHashes);
    }

    @Override
    public String toString() {
        return super.toString() + ", chunkHashes=" + chunkHashes;
    }
}