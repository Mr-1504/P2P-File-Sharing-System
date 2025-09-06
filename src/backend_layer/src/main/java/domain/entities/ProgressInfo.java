package main.java.domain.entities;

public class ProgressInfo {
    private String id;
    private String status;
    private String fileName;
    private long bytesTransferred;
    private long totalBytes;
    private int progressPercentage = 0;

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
    }
}
