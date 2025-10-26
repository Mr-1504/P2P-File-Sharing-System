package domain.entity;

import java.time.Instant;

/**
 * Represents a single chunk of a file download with its status and metadata.
 */
public class ChunkInfo {
    private int index;
    private long start;
    private long end;
    private String status; // completed, downloading, pending, failed
    private long downloadedBytes;
    private String checksum; // SHA-256 hash of the chunk
    private int retryCount;
    private Instant lastAttempt;

    /**
     * Constructor for ChunkInfo.
     *
     * @param index Chunk index
     * @param start Byte offset start
     * @param end Byte offset end
     */
    public ChunkInfo(int index, long start, long end) {
        this.index = index;
        this.start = start;
        this.end = end;
        this.status = ChunkStatus.PENDING;
        this.downloadedBytes = 0;
        this.retryCount = 0;
        this.lastAttempt = null;
    }

    // Getters and setters
    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }

    public long getStart() { return start; }
    public void setStart(long start) { this.start = start; }

    public long getEnd() { return end; }
    public void setEnd(long end) { this.end = end; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getDownloadedBytes() { return downloadedBytes; }
    public void setDownloadedBytes(long downloadedBytes) { this.downloadedBytes = downloadedBytes; }

    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public Instant getLastAttempt() { return lastAttempt; }
    public void setLastAttempt(Instant lastAttempt) { this.lastAttempt = lastAttempt; }

    /**
     * Increment retry count and update last attempt time.
     */
    public void incrementRetry() {
        this.retryCount++;
        this.lastAttempt = Instant.now();
    }

    /**
     * Check if chunk download is completed.
     */
    public boolean isCompleted() {
        return ChunkStatus.COMPLETED.equals(status);
    }

    /**
     * Check if chunk download is pending.
     */
    public boolean isPending() {
        return ChunkStatus.PENDING.equals(status);
    }

    /**
     * Check if chunk download is failed.
     */
    public boolean isFailed() {
        return ChunkStatus.FAILED.equals(status);
    }

    /**
     * Check if chunk download is in progress.
     */
    public boolean isDownloading() {
        return ChunkStatus.DOWNLOADING.equals(status);
    }

    /**
     * Mark chunk as completed and set downloaded bytes.
     */
    public void markCompleted(long downloadedBytes) {
        this.status = ChunkStatus.COMPLETED;
        this.downloadedBytes = downloadedBytes;
    }

    /**
     * Mark chunk as failed.
     */
    public void markFailed() {
        this.status = ChunkStatus.FAILED;
    }

    /**
     * Mark chunk as downloading.
     */
    public void markDownloading() {
        this.status = ChunkStatus.DOWNLOADING;
    }

    @Override
    public String toString() {
        return String.format("ChunkInfo{index=%d, start=%d, end=%d, status='%s', downloadedBytes=%d}",
                index, start, end, status, downloadedBytes);
    }

    public interface ChunkStatus {
        String PENDING = "pending";
        String DOWNLOADING = "downloading";
        String COMPLETED = "completed";
        String FAILED = "failed";
    }
}
