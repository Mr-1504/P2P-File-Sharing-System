package main.java.infras.subrepo;

import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.domain.entity.ProgressInfo;
import main.java.domain.repository.IFileDownloadRepository;
import main.java.domain.repository.IPeerRepository;
import main.java.infras.utils.FileUtils;
import main.java.infras.utils.SSLUtils;
import main.java.utils.Config;
import main.java.utils.Log;
import main.java.utils.LogTag;

import javax.net.ssl.SSLSocket;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class FileDownloadRepository implements IFileDownloadRepository {

    private final IPeerRepository peerModel;
    private final ExecutorService executorService;

    public FileDownloadRepository(IPeerRepository peerModel) {
        this.peerModel = peerModel;
        this.executorService = Executors.newFixedThreadPool(10);
    }

    @Override
    public void downloadFile(FileInfo fileInfo, File file, List<PeerInfo> peerInfos, String progressId) {
        this.executorService.submit(() -> this.processDownload(fileInfo, file, progressId, peerInfos));
    }

    @Override
    public Map<String, ProgressInfo> getProgress() {
        return this.peerModel.getProcesses();
    }

    @Override
    public void setProgress(ProgressInfo progress) {
        this.peerModel.getProcesses().put(progress.getId(), progress);
    }

    @Override
    public void cleanupProgress(List<String> progressIds) {
        for (String progressId : progressIds) {
            this.peerModel.getProcesses().remove(progressId);
            List<Future<Boolean>> processingFutures = this.peerModel.getFutures().remove(progressId);
            if (processingFutures != null) {
                for (Future<Boolean> processingFuture : processingFutures) {
                    processingFuture.cancel(true);
                }
            }

            CopyOnWriteArrayList<SSLSocket> sslSockets = this.peerModel.getOpenChannels().remove(progressId);
            if (sslSockets != null) {
                for (SSLSocket sslSocket : sslSockets) {
                    try {
                        sslSocket.close();
                    } catch (IOException e) {
                        Log.logError("Error closing channel: " + sslSocket, e);
                    }
                }
            }
        }
    }

    private Integer processDownload(FileInfo fileInfo, File file, String progressId, List<PeerInfo> peerInfos) {
        this.peerModel.getFutures().put(progressId, new ArrayList<>());
        this.peerModel.getOpenChannels().put(progressId, new CopyOnWriteArrayList<>());
        AtomicInteger chunkCount = new AtomicInteger(0);
        ProgressInfo progressInfo = this.peerModel.getProcesses().get(progressId);
        if (Objects.equals(progressInfo.getStatus(), ProgressInfo.ProgressStatus.CANCELLED)) {
            return LogTag.I_CANCELLED;
        } else {
            progressInfo.setStatus(ProgressInfo.ProgressStatus.DOWNLOADING);
            progressInfo.setTotalBytes(fileInfo.getFileSize());
            progressInfo.setBytesTransferred(0L);
            progressInfo.updateProgressTime(); // Reset timeout when download starts

            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.setLength(fileInfo.getFileSize());
                ConcurrentHashMap<Integer, List<PeerInfo>> peerOfChunk = new ConcurrentHashMap<>();
                int totalChunk = (int) Math.ceil((double) fileInfo.getFileSize() / (double) Config.CHUNK_SIZE);
                this.initializeHashMap(peerOfChunk, totalChunk);
                int result = this.downloadAllChunks(fileInfo, peerInfos, progressId, chunkCount, raf, peerOfChunk);
                if (result == LogTag.I_CANCELLED) {
                    this.cancelDownload(file.getPath());
                    return LogTag.I_CANCELLED;
                } else {
                    String fileHash = FileUtils.computeFileHash(file);
                    if (fileHash.equals(LogTag.S_ERROR)) {
                        return LogTag.I_ERROR;
                    } else {
                        String expectedFileHash = fileInfo.getFileHash();
                        if (!fileHash.equalsIgnoreCase(expectedFileHash)) {
                            return LogTag.I_HASH_MISMATCH;
                        } else {
                            ProgressInfo finalProgress = peerModel.getProcesses().get(progressId);
                            if (finalProgress != null) {
                                synchronized (finalProgress) {
                                    finalProgress.setStatus(ProgressInfo.ProgressStatus.COMPLETED);
                                    finalProgress.setProgressPercentage(100);
                                    finalProgress.setBytesTransferred(fileInfo.getFileSize());
                                    finalProgress.setTotalBytes(fileInfo.getFileSize());
                                }
                                Log.logInfo("Download completed successfully for " + progressId + ", final bytes: " + fileInfo.getFileSize());
                            } else {
                                Log.logError("Progress object not found for completed download: " + progressId, null);
                            }
                            return LogTag.I_SUCCESS;
                        }
                    }
                }
            } catch (Exception e) {
                Log.logError("Error during file download: " + e.getMessage(), e);
                this.cancelDownload(file.getPath());
                return LogTag.I_ERROR;
            }
        }
    }

    private Integer downloadAllChunks(FileInfo file, List<PeerInfo> peerInfos, String progressId, AtomicInteger
                                              chunkCount,
                                      RandomAccessFile raf, ConcurrentHashMap<Integer, List<PeerInfo>> peerOfChunk) throws
            InterruptedException {
        int totalChunk = (int) Math.ceil((double) file.getFileSize() / (double) Config.CHUNK_SIZE);
        ArrayList<Integer> failedChunks = new ArrayList<>();
        ProgressInfo progressInfo = this.peerModel.getProcesses().get(progressId);

        for (int i = 0; i < totalChunk; ++i) {
            if (progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED) || Thread.currentThread().isInterrupted()) {
                return LogTag.I_CANCELLED;
            }

            while (((ThreadPoolExecutor) this.peerModel.getExecutor()).getActiveCount() >= 6) {
                Thread.sleep(50L);
                if (progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED)) {
                    return LogTag.I_CANCELLED;
                }
            }

            if (!this.downloadChunkWithRetry(i, peerInfos, raf, file, progressId, chunkCount, peerOfChunk)) {
                Log.logInfo("Chunk " + i + "Failed to download, adding to retry list");
                failedChunks.add(i);
            }
        }

        int maxRetryCount = 2;

        for (int i = 1; i <= maxRetryCount && !failedChunks.isEmpty(); ++i) {
            Log.logInfo("Retrying failed chunks, round " + i + " with " + failedChunks.size() + " chunks");
            ArrayList<Integer> stillFailed = new ArrayList<>();

            for (int j : failedChunks) {
                if (progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED)) {
                    return LogTag.I_CANCELLED;
                }

                if (!this.downloadChunkWithRetry(j, peerInfos, raf, file, progressId, chunkCount, peerOfChunk)) {
                    stillFailed.add(j);
                }
            }

            failedChunks = stillFailed;
        }

        if (!failedChunks.isEmpty()) {
            return LogTag.I_FAILURE;
        } else {
            return LogTag.I_SUCCESS;
        }
    }

    private boolean downloadChunkWithRetry(int chunkIndex, List<PeerInfo> peerInfos, RandomAccessFile
            raf, FileInfo file, String progressId, AtomicInteger
                                                   chunkCount, ConcurrentHashMap<Integer, List<PeerInfo>> peerOfChunk) throws InterruptedException {
        int maxRetries = 3;

        for (int i = 0; i < maxRetries; ++i) {
            PeerInfo peerInfo = this.selectAvailablePeer(progressId, peerInfos, chunkIndex, peerOfChunk.getOrDefault(chunkIndex, new ArrayList<>()));
            if (peerInfo == null) {
                Log.logInfo("No available peers for chunk " + chunkIndex);
                return false;
            }

            try {
                Log.logInfo("Retrying to download chunk " + chunkIndex + " from peer " + peerInfo.getIp() + ":" + peerInfo.getPort() + " (attempt " + (i + 1) + ")");
                peerInfo.addTaskForDownload();
                peerOfChunk.computeIfAbsent(chunkIndex, (v) -> new ArrayList<>()).add(peerInfo);
                Future<Boolean> future = this.executorService.submit(() -> {
                    this.peerModel.getFileLock().lock();

                    boolean result;
                    try {
                        result = this.downloadChunk(peerInfo, chunkIndex, raf, file, progressId, chunkCount);
                    } finally {
                        this.peerModel.getFileLock().unlock();
                    }

                    return result;
                });
                this.peerModel.getFutures().get(progressId).add(future);
                if (future.get()) {
                    Log.logInfo("Chunk " + chunkIndex + " downloaded successfully from peer " + peerInfo.getIp() + ":" + peerInfo.getPort());
                    return true;
                }

                Log.logInfo("Chunk " + chunkIndex + " download failed from peer " + peerInfo.getIp() + ":" + peerInfo.getPort() + " (attempt " + (i + 1) + ")");
                Thread.sleep(50L * (long) (i + 1));
            } catch (Exception e) {
                Log.logError("Error downloading chunk " + chunkIndex + " from peer " + peerInfo.getIp() + ":" + peerInfo.getPort() + ": " + e.getMessage(), e);
                Thread.sleep(50L * (long) (i + 1));
            } finally {
                peerInfo.removeTaskForDownload();
            }
        }

        Log.logInfo("Failed to download chunk " + chunkIndex + " after " + maxRetries + " attempts");
        return false;
    }

    private PeerInfo selectAvailablePeer(String progressId, List<PeerInfo> peerInfos, int chunkIndex, List<
            PeerInfo> usedPeers) throws InterruptedException {
        int retryCount = 0;
        final int MAX_RETRY = 20;

        while (retryCount++ < MAX_RETRY) {
            if ((this.peerModel.getProcesses().get(progressId)).getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED)) {
                return null;
            }

            PeerInfo peerInfo = peerInfos.stream().filter(
                            (peer) -> !usedPeers.contains(peer) && peer.isAvailableForDownload())
                    .min(Comparator.comparingInt(PeerInfo::getTaskForDownload))
                    .orElse(null);
            if (peerInfo != null) {
                return peerInfo;
            }

            Log.logInfo("Waiting for available peer for chunk " + chunkIndex + " (retry " + retryCount + ")");
            Thread.sleep(50L);
        }

        return null;
    }

    private void initializeHashMap(ConcurrentHashMap<Integer, List<PeerInfo>> peerOfChunk, int totalChunks) {
        for (int i = 0; i < totalChunks; ++i) {
            peerOfChunk.putIfAbsent(i, new ArrayList<>());
        }
    }

    private void cancelDownload(String savePath) {
        Log.logInfo("Download process cancelled for file: " + savePath);
        File file = new File(savePath);
        Path path = file.toPath();
        if (Files.exists(path) && file.delete()) {
            Log.logInfo("Deleted file: " + file.getAbsolutePath());
        } else {
            Log.logInfo("Cannot delete file: " + file.getAbsolutePath() + ". It may not exist or is not writable.");
        }
    }

    private boolean downloadChunk(PeerInfo peerInfo, int chunkIndex, RandomAccessFile raf, FileInfo file, String
            progressId, AtomicInteger chunkCount) {
        int retryCount = 3;
        ProgressInfo progressInfo = this.peerModel.getProcesses().get(progressId);
        if (!progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED)) {
            for (int i = 1; i <= retryCount; ++i) {
                if (progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED) || Thread.currentThread().isInterrupted()) {
                    Log.logInfo("Process cancelled by user while downloading chunk " + chunkIndex + " from peer " + peerInfo.toString());
                    return false;
                }

                try (SSLSocket sslSocket = SSLUtils.createSecureSocket(peerInfo)) {
                    String fileHash = file.getFileHash();
                    String request = "GET_CHUNK|" + fileHash + "|" + chunkIndex + "\n";
                    sslSocket.getOutputStream().write(request.getBytes());

                    DataInputStream dis = new DataInputStream(sslSocket.getInputStream());

                    // Read chunk index (4 bytes)
                    int receivedIndex = dis.readInt();

                    if (receivedIndex == chunkIndex) {
                        int chunkLength = dis.readInt();

                        // Read chunk data
                        byte[] chunkDataByteArray = new byte[chunkLength];
                        dis.readFully(chunkDataByteArray);

                        if (chunkLength > 0) {
                            synchronized (raf) {
                                raf.seek((long) chunkIndex * (long) Config.CHUNK_SIZE);
                                raf.write(chunkDataByteArray);
                            }

                            long totalChunks = (file.getFileSize() + (long) Config.CHUNK_SIZE - 1L) / (long) Config.CHUNK_SIZE;
                            long downloadedChunks = chunkCount.incrementAndGet();
                            int percent = (int) ((double) downloadedChunks * (double) 100.0F / (double) totalChunks);
                            ProgressInfo progress = peerModel.getProcesses().get(progressId);
                            if (progress != null) {
                                synchronized (progress) {
                                    progress.addBytesTransferred(chunkLength);
                                    progress.setProgressPercentage(percent);
                                    progress.updateProgressTime();
                                }
                            }
                            Log.logInfo("Successfully downloaded chunk " + chunkIndex + " from peer " + peerInfo + " (attempt " + i + ")");
                            return true;
                        }
                    }

                    Log.logInfo("Failed to receive valid chunk " + chunkIndex + " from peer " + peerInfo + " (attempt " + i + ")");
                } catch (InterruptedException | IOException e) {
                    Log.logError("SSL Error downloading chunk " + chunkIndex + " from peer " + peerInfo + " (attempt " + i + "): " + e.getMessage(), e);

                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        Log.logError("Process interrupted during sleep after error while downloading chunk " + chunkIndex + " from peer " + peerInfo, ex);
                        return false;
                    }
                } catch (Exception e) {
                    Log.logError("SSL Unexpected error downloading chunk " + chunkIndex + " from peer " + peerInfo + " (attempt " + i + "): " + e.getMessage(), e);
                    return false;
                }
            }

            Log.logInfo("Failed to download chunk " + chunkIndex + " from peer " + peerInfo + " after " + retryCount + " attempts.");
        }
        return false;
    }
}
