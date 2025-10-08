package main.java.domain.entity;

import java.util.Set;
import java.util.HashSet;

public class ProgressInfo {
    private String id;
    private String status;
    private String fileName;
    private long bytesTransferred;
    private long totalBytes;
    private int progressPercentage = 0;
    private Set<Integer> downloadedChunks = new HashSet<>();
    private String fileHash;
    private String savePath;
    private long lastProgressUpdateTime = System.currentTimeMillis();
    private long timeoutThresholdMs = 120000; // 2 minutes default

    public ProgressInfo(String id, String status, String fileName) {
        this.id = id;
        this.status = status;
        this.fileName = fileName;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getProgressPercentage() {
        return progressPercentage;
    }

    public void setProgressPercentage(int progressPercentage) {
        this.progressPercentage = progressPercentage;
    }

    public long getBytesTransferred() {
        return bytesTransferred;
    }

    public void setBytesTransferred(long bytesTransferred) {
        this.bytesTransferred = bytesTransferred;
    }

    public void addBytesTransferred(long bytes) {
        this.bytesTransferred += bytes;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Set<Integer> getDownloadedChunks() {
        return downloadedChunks;
    }

    public void setDownloadedChunks(Set<Integer> downloadedChunks) {
        this.downloadedChunks = downloadedChunks;
    }

    public void addDownloadedChunk(int chunkIndex) {
        this.downloadedChunks.add(chunkIndex);
    }

    public boolean isChunkDownloaded(int chunkIndex) {
        return this.downloadedChunks.contains(chunkIndex);
    }

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public String getSavePath() {
        return savePath;
    }

    public void setSavePath(String savePath) {
        this.savePath = savePath;
    }

    public long getLastProgressUpdateTime() {
        return lastProgressUpdateTime;
    }

    public void setLastProgressUpdateTime(long lastProgressUpdateTime) {
        this.lastProgressUpdateTime = lastProgressUpdateTime;
    }

    public void updateProgressTime() {
        this.lastProgressUpdateTime = System.currentTimeMillis();
    }

    public long getTimeoutThresholdMs() {
        return timeoutThresholdMs;
    }

    public void setTimeoutThresholdMs(long timeoutThresholdMs) {
        this.timeoutThresholdMs = timeoutThresholdMs;
    }

    public boolean isTimedOut() {
        return !status.equals(ProgressStatus.COMPLETED) &&
                (System.currentTimeMillis() - lastProgressUpdateTime) > timeoutThresholdMs;
    }

    public static String generateProgressId() {
        return String.valueOf(System.currentTimeMillis());
    }

    public interface ProgressStatus {
        String STARTING = "starting";
        String DOWNLOADING = "downloading";
        String SHARING = "sharing";
        String COMPLETED = "completed";
        String FAILED = "failed";
        String CANCELLED = "canceled";
        String PAUSED = "paused";
        String STALLED = "stalled";
        String TIMEOUT = "timeout";
        String RESUMABLE = "resumable";
    }
}
