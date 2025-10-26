package domain.entity;

import java.util.Set;
import java.util.HashSet;

/**
 * Class representing the progress information of a file transfer task.
 */
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
    private String taskType;
    private long lastProgressUpdateTime = System.currentTimeMillis();
    private long timeoutThresholdMs = 120000; // 2 minutes default

    // Resumable download fields
    private boolean resumable = false;
    private String partFilePath;
    private String metaFilePath;
    private int totalChunks = 0;
    private int downloadedChunksCount = 0;
    private int failedChunksCount = 0;

    /**
     * Constructor to initialize ProgressInfo with essential details.
     *
     * @param id       Unique identifier for the progress task.
     * @param status   Current status of the task.
     * @param fileName Name of the file being transferred.
     * @param taskType Type of the task (download/share).
     */
    public ProgressInfo(String id, String status, String fileName, String taskType) {
        this.id = id;
        this.status = status;
        this.fileName = fileName;
        this.taskType = taskType;
    }

    /**
     * Get the unique identifier of the progress task.
     *
     * @return The progress task ID.
     */
    public String getId() {
        return id;
    }

    /**
     * Get the current status of the task.
     *
     * @return The task status.
     */
    public String getStatus() {
        return status;
    }

    /**
     * Set the current status of the task.
     *
     * @param status The new task status.
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * Set progress percentage.
     *
     * @param progressPercentage The progress percentage to set.
     */
    public void setProgressPercentage(int progressPercentage) {
        this.progressPercentage = progressPercentage;
    }

    /**
     * Get Bytes transferred so far.
     *
     * @return The number of bytes transferred.
     */
    public long getBytesTransferred() {
        return bytesTransferred;
    }

    /**
     * Set Bytes transferred so far.
     *
     * @param bytesTransferred The number of bytes transferred.
     */
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

    public long getLastProgressUpdateTime() {
        return lastProgressUpdateTime;
    }

    public void updateProgressTime() {
        this.lastProgressUpdateTime = System.currentTimeMillis();
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

    public interface TaskType {
        String DOWNLOAD = "download";
        String SHARE = "share";
    }

    // Getters and setters for existing fields
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

    public Set<Integer> getDownloadedChunks() {
        return downloadedChunks;
    }

    public void setDownloadedChunks(Set<Integer> downloadedChunks) {
        this.downloadedChunks = downloadedChunks;
    }

    public void addDownloadedChunk(int chunkIndex) {
        this.downloadedChunks.add(chunkIndex);
    }

    public boolean isResumable() {
        return resumable;
    }

    public void setResumable(boolean resumable) {
        this.resumable = resumable;
    }

    public String getPartFilePath() {
        return partFilePath;
    }

    public void setPartFilePath(String partFilePath) {
        this.partFilePath = partFilePath;
    }

    public String getMetaFilePath() {
        return metaFilePath;
    }

    public void setMetaFilePath(String metaFilePath) {
        this.metaFilePath = metaFilePath;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(int totalChunks) {
        this.totalChunks = totalChunks;
    }

    public int getDownloadedChunksCount() {
        return downloadedChunksCount;
    }

    public void setDownloadedChunksCount(int downloadedChunksCount) {
        this.downloadedChunksCount = downloadedChunksCount;
    }

    public void incrementDownloadedChunksCount() {
        this.downloadedChunksCount++;
    }

    public int getFailedChunksCount() {
        return failedChunksCount;
    }

    public void setFailedChunksCount(int failedChunksCount) {
        this.failedChunksCount = failedChunksCount;
    }

    public void incrementFailedChunksCount() {
        this.failedChunksCount++;
    }

    /**
     * Reset failed chunks count (useful when resuming).
     */
    public void resetFailedChunksCount() {
        this.failedChunksCount = 0;
    }

    /**
     * Check if download can be resumed.
     */
    public boolean canResume() {
        return resumable && !ProgressStatus.COMPLETED.equals(status) && !ProgressStatus.CANCELLED.equals(status);
    }
}
