package model;

import java.util.List;

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
}
