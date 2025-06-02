package model;

import java.util.List;

public class FileInfor {
    private String fileName;
    private long fileSize;
    private List<String> chunkHashes;
    private PeerInfor peerInfor;

    public FileInfor(String fileName, long fileSize, List<String> chunkHashes, PeerInfor peerInfor) {
        this.fileName = fileName;
        this.peerInfor = peerInfor;
        this.fileSize = fileSize;
        this.chunkHashes = chunkHashes;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public List<String> getChunkHashes() {
        return chunkHashes;
    }

    public void setChunkHashes(List<String> chunkHashes) {
        this.chunkHashes = chunkHashes;
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
