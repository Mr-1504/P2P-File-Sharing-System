package domain.entity;

import utils.Config;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents metadata for a resumable file download.
 * This data can be serialized and saved to disk for resumption.
 */
public class DownloadMetadata {
    private String fileName;
    private String fileHash;
    private long fileSize;
    private int chunkSize;
    private String savePath;
    private List<ChunkInfo> chunks;
    private Instant createdAt;
    private Instant lastModified;

    /**
     * Constructor for new download metadata.
     *
     * @param fileName  Name of the file
     * @param fileHash  SHA-256 hash of the complete file
     * @param fileSize  Total file size in bytes
     * @param savePath  Save path (without .part extension)
     */
    public DownloadMetadata(String fileName, String fileHash, long fileSize, String savePath) {
        this.fileName = fileName;
        this.fileHash = fileHash;
        this.fileSize = fileSize;
        this.chunkSize = Config.CHUNK_SIZE;
        this.savePath = savePath;
        this.chunks = new ArrayList<>();
        this.createdAt = Instant.now();
        this.lastModified = Instant.now();

        initializeChunks();
    }

    /**
     * Initialize chunks based on file size and chunk size.
     */
    private void initializeChunks() {
        int totalChunks = (int) Math.ceil((double) fileSize / (double) chunkSize);
        chunks.clear();

        for (int i = 0; i < totalChunks; i++) {
            long start = (long) i * chunkSize;
            long end = Math.min(start + chunkSize - 1, fileSize - 1);
            chunks.add(new ChunkInfo(i, start, end));
        }
    }

    // Getters and setters
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

    public String getSavePath() { return savePath; }
    public void setSavePath(String savePath) { this.savePath = savePath; }

    public List<ChunkInfo> getChunks() { return chunks; }
    public void setChunks(List<ChunkInfo> chunks) { this.chunks = chunks; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastModified() { return lastModified; }
    public void setLastModified(Instant lastModified) { this.lastModified = lastModified; }

    /**
     * Get the .part file path (incomplete file).
     */
    public String getPartFilePath() {
        return savePath + ".part";
    }

    /**
     * Get the .meta file path (metadata file).
     */
    public String getMetaFilePath() {
        return savePath + ".part.meta";
    }

    /**
     * Get chunks that need to be downloaded (pending or failed).
     */
    public List<ChunkInfo> getChunksToDownload() {
        return chunks.stream()
                .filter(chunk -> !chunk.isCompleted())
                .toList();
    }

    /**
     * Get completed chunks count.
     */
    public int getCompletedChunksCount() {
        return (int) chunks.stream().filter(ChunkInfo::isCompleted).count();
    }

    /**
     * Get total bytes downloaded.
     */
    public long getDownloadedBytes() {
        return chunks.stream()
                .filter(ChunkInfo::isCompleted)
                .mapToLong(ChunkInfo::getDownloadedBytes)
                .sum();
    }

    /**
     * Calculate download progress percentage.
     */
    public int getProgressPercentage() {
        if (fileSize == 0) return 0;
        return (int) ((getDownloadedBytes() * 100) / fileSize);
    }

    /**
     * Check if all chunks are completed.
     */
    public boolean isComplete() {
        return chunks.stream().allMatch(ChunkInfo::isCompleted);
    }

    /**
     * Update the last modified time.
     */
    public void updateLastModified() {
        this.lastModified = Instant.now();
    }

    /**
     * Get chunk by index.
     */
    public ChunkInfo getChunk(int index) {
        if (index >= 0 && index < chunks.size()) {
            return chunks.get(index);
        }
        return null;
    }

    /**
     * Check if .part file exists.
     */
    public boolean partFileExists() {
        File partFile = new File(getPartFilePath());
        return partFile.exists();
    }

    /**
     * Check if .meta file exists.
     */
    public boolean metaFileExists() {
        File metaFile = new File(getMetaFilePath());
        return metaFile.exists();
    }

    @Override
    public String toString() {
        return String.format("DownloadMetadata{fileName='%s', fileSize=%d, chunks=%d/%d, progress=%d%%}",
                fileName, fileSize, getCompletedChunksCount(), chunks.size(), getProgressPercentage());
    }
}
