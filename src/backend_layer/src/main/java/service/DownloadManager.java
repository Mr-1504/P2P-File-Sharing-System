package main.java.service;

import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.domain.entity.ProgressInfo;
import main.java.utils.Infor;
import main.java.utils.Log;
import main.java.utils.LogTag;
import main.java.utils.SSLUtils;

import javax.net.ssl.SSLSocket;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class DownloadManager {
    private final int CHUNK_SIZE;
    private final ExecutorService executor;
    private final FileManager fileManager;
    private final TrackerConnector trackerConnector;

    // Shared data structures - passed from coordinator
    private Map<String, ProgressInfo> processes;
    private Map<String, CopyOnWriteArrayList<SocketChannel>> openChannels;
    private Map<String, List<Future<Boolean>>> futures;
    private final ReentrantLock fileLock = new ReentrantLock();

    public DownloadManager(int chunkSize, FileManager fileManager, TrackerConnector trackerConnector) {
        this.CHUNK_SIZE = chunkSize;
        this.executor = Executors.newFixedThreadPool(8);
        this.fileManager = fileManager;
        this.trackerConnector = trackerConnector;
    }

    public void setSharedDataStructures(Map<String, ProgressInfo> processes,
                                       Map<String, CopyOnWriteArrayList<SocketChannel>> openChannels,
                                       Map<String, List<Future<Boolean>>> futures) {
        this.processes = processes;
        this.openChannels = openChannels;
        this.futures = futures;
    }

    public void downloadFile(FileInfo fileInfo, File file, List<PeerInfo> peerInfos, String progressId) {
        this.executor.submit(() -> this.processDownload(fileInfo, file, progressId, peerInfos));
    }

    public void resumeDownload(String progressId) {
        ProgressInfo progressInfo = this.processes.get(progressId);
        if (progressInfo == null || !progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.PAUSED)) {
            Log.logInfo("Cannot resume download: progress not found or not paused");
            return;
        }

        final FileInfo fileInfo = findFileInfo(progressInfo);
        if (fileInfo == null) {
            Log.logInfo("Cannot resume download: file info not found");
            return;
        }

        File saveFile = new File(progressInfo.getSavePath());
        List<PeerInfo> peerInfos = this.trackerConnector.getPeersWithFile(progressInfo.getFileHash());

        if (peerInfos.isEmpty()) {
            Log.logInfo("Cannot resume download: no peers available");
            return;
        }

        this.executor.submit(() -> this.processResumeDownload(fileInfo, saveFile, progressId, peerInfos));
    }

    private FileInfo findFileInfo(ProgressInfo progressInfo) {
        // Try to find from shared files or reconstruct
        for (var entry : this.fileManager.getPublicSharedFiles().entrySet()) {
            if (entry.getValue().getFileHash().equals(progressInfo.getFileHash())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Integer processResumeDownload(FileInfo fileInfo, File file, String progressId, List<PeerInfo> peerInfos) {
        this.futures.put(progressId, new ArrayList<>());
        this.openChannels.put(progressId, new CopyOnWriteArrayList<>());
        AtomicInteger chunkCount = new AtomicInteger(0);
        ProgressInfo progressInfo = this.processes.get(progressId);
        if (Objects.equals(progressInfo.getStatus(), ProgressInfo.ProgressStatus.CANCELLED)) {
            return LogTag.I_CANCELLED;
        } else {
            progressInfo.setStatus(ProgressInfo.ProgressStatus.DOWNLOADING);

            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.setLength(fileInfo.getFileSize());
                ConcurrentHashMap<Integer, List<PeerInfo>> peerOfChunk = new ConcurrentHashMap<>();
                int totalChunk = (int) Math.ceil((double) fileInfo.getFileSize() / (double) this.CHUNK_SIZE);
                this.initializeHashMap(peerOfChunk, totalChunk);
                int result = this.downloadAllChunksResume(fileInfo, peerInfos, progressId, chunkCount, raf, peerOfChunk);
                if (result == LogTag.I_CANCELLED) {
                    return LogTag.I_CANCELLED;
                } else {
                    String fileHash = this.fileManager.computeFileHash(file);
                    if (fileHash.equals(LogTag.S_ERROR)) {
                        return LogTag.I_ERROR;
                    } else {
                        String expectedFileHash = fileInfo.getFileHash();
                        if (!fileHash.equalsIgnoreCase(expectedFileHash)) {
                            return LogTag.I_HASH_MISMATCH;
                        } else {
                            ProgressInfo finalProgress = processes.get(progressId);
                            if (finalProgress != null) {
                                synchronized (finalProgress) {
                                    finalProgress.setStatus(ProgressInfo.ProgressStatus.COMPLETED);
                                    finalProgress.setProgressPercentage(100);
                                    finalProgress.setBytesTransferred(fileInfo.getFileSize());
                                    finalProgress.setTotalBytes(fileInfo.getFileSize());
                                }
                                Log.logInfo("Download resumed and completed successfully for " + progressId + ", final bytes: " + fileInfo.getFileSize());
                            } else {
                                Log.logError("Progress object not found for completed download: " + progressId, null);
                            }
                            return LogTag.I_SUCCESS;
                        }
                    }
                }
            } catch (Exception e) {
                Log.logError("Error during file download resume: " + e.getMessage(), e);
                return LogTag.I_ERROR;
            }
        }
    }

    private Integer processDownload(FileInfo fileInfo, File file, String progressId, List<PeerInfo> peerInfos) {
        this.futures.put(progressId, new ArrayList<>());
        this.openChannels.put(progressId, new CopyOnWriteArrayList<>());
        AtomicInteger chunkCount = new AtomicInteger(0);
        ProgressInfo progressInfo = this.processes.get(progressId);
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
                int totalChunk = (int) Math.ceil((double) fileInfo.getFileSize() / (double) this.CHUNK_SIZE);
                this.initializeHashMap(peerOfChunk, totalChunk);
                int result = this.downloadAllChunks(fileInfo, peerInfos, progressId, chunkCount, raf, peerOfChunk);
                if (result == LogTag.I_CANCELLED) {
                    this.cancelDownload(file.getPath());
                    return LogTag.I_CANCELLED;
                } else {
                    String fileHash = this.fileManager.computeFileHash(file);
                    if (fileHash.equals(LogTag.S_ERROR)) {
                        return LogTag.I_ERROR;
                    } else {
                        String expectedFileHash = fileInfo.getFileHash();
                        if (!fileHash.equalsIgnoreCase(expectedFileHash)) {
                            return LogTag.I_HASH_MISMATCH;
                        } else {
                            ProgressInfo finalProgress = processes.get(progressId);
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

    private Integer downloadAllChunks(FileInfo file, List<PeerInfo> peerInfos, String progressId, AtomicInteger chunkCount,
                                      RandomAccessFile raf, ConcurrentHashMap<Integer, List<PeerInfo>> peerOfChunk) throws InterruptedException {
        int totalChunk = (int) Math.ceil((double) file.getFileSize() / (double) this.CHUNK_SIZE);
        ArrayList<Integer> failedChunks = new ArrayList<>();
        ProgressInfo progressInfo = this.processes.get(progressId);

        for (int i = 0; i < totalChunk; ++i) {
            if (progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED) || Thread.currentThread().isInterrupted()) {
                return LogTag.I_CANCELLED;
            }

            while (((ThreadPoolExecutor) this.executor).getActiveCount() >= 6) {
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

    private Integer downloadAllChunksResume(FileInfo file, List<PeerInfo> peerInfos, String
            progressId, AtomicInteger chunkCount,
                                            RandomAccessFile raf, ConcurrentHashMap<Integer, List<PeerInfo>> peerOfChunk) throws
            InterruptedException {
        int totalChunk = (int) Math.ceil((double) file.getFileSize() / (double) this.CHUNK_SIZE);
        ArrayList<Integer> failedChunks = new ArrayList<>();
        ProgressInfo progressInfo = this.processes.get(progressId);

        // Only download chunks that haven't been downloaded yet
        for (int i = 0; i < totalChunk; ++i) {
            if (progressInfo.isChunkDownloaded(i)) {
                Log.logInfo("Chunk " + i + " already downloaded, skipping");
                chunkCount.incrementAndGet();
                continue;
            }

            if (progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED) || Thread.currentThread().isInterrupted()) {
                return LogTag.I_CANCELLED;
            }

            while (((ThreadPoolExecutor) this.executor).getActiveCount() >= 6) {
                Thread.sleep(50L);
                if (progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED)) {
                    return LogTag.I_CANCELLED;
                }
            }

            if (!this.downloadChunkWithRetryResume(i, peerInfos, raf, file, progressId, chunkCount, peerOfChunk)) {
                Log.logInfo("Chunk " + i + " failed to download, adding to retry list");
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

                if (!this.downloadChunkWithRetryResume(j, peerInfos, raf, file, progressId, chunkCount, peerOfChunk)) {
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
                Future<Boolean> future = this.executor.submit(() -> {
                    this.fileLock.lock();

                    boolean result;
                    try {
                        result = this.downloadChunk(peerInfo, chunkIndex, raf, file, progressId, chunkCount);
                    } finally {
                        this.fileLock.unlock();
                    }

                    return result;
                });
                this.futures.get(progressId).add(future);
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

    private boolean downloadChunkWithRetryResume(int chunkIndex, List<PeerInfo> peerInfos, RandomAccessFile
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
                Future<Boolean> future = this.executor.submit(() -> {
                    this.fileLock.lock();

                    boolean result;
                    try {
                        result = this.downloadChunkResume(peerInfo, chunkIndex, raf, file, progressId, chunkCount);
                    } finally {
                        this.fileLock.unlock();
                    }

                    return result;
                });
                this.futures.get(progressId).add(future);
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
            if ((this.processes.get(progressId)).getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED)) {
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

    private boolean downloadChunk(PeerInfo peerInfo, int chunkIndex, RandomAccessFile raf, FileInfo file, String
            progressId, AtomicInteger chunkCount) {
        int retryCount = 3;
        ProgressInfo progressInfo = this.processes.get(progressId);
        if (progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED)) {
            return false;
        } else {
            for (int i = 1; i <= retryCount; ++i) {
                if (progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED) || Thread.currentThread().isInterrupted()) {
                    Log.logInfo("Process cancelled by user while downloading chunk " + chunkIndex + " from peer " + peerInfo.toString());
                    return false;
                }

                try (SSLSocket sslSocket = createSecureSocket(peerInfo)) {
                    String fileHash = file.getFileHash();
                    String request = "GET_CHUNK|" + fileHash + "|" + chunkIndex + "\n";
                    sslSocket.getOutputStream().write(request.getBytes());

                    DataInputStream dis = new DataInputStream(sslSocket.getInputStream());

                    // Read chunk index (4 bytes)
                    int receivedIndex = dis.readInt();

                    if (receivedIndex == chunkIndex) {
                        // Read chunk data
                        ByteArrayOutputStream chunkData = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        long startTime = System.currentTimeMillis();

                        while (System.currentTimeMillis() - startTime < 5000L) {
                            if (progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED) || Thread.currentThread().isInterrupted()) {
                                Log.logInfo("Process cancelled by user while reading chunk data from peer " + peerInfo);
                                return false;
                            }

                            if (dis.available() > 0) {
                                bytesRead = dis.read(buffer);
                                if (bytesRead == -1) {
                                    break;
                                }
                                chunkData.write(buffer, 0, bytesRead);
                            } else {
                                Thread.sleep(100L);
                            }
                        }

                        byte[] chunkDataByteArray = chunkData.toByteArray();
                        if (chunkDataByteArray.length != 0) {
                            synchronized (raf) {
                                raf.seek((long) chunkIndex * (long) this.CHUNK_SIZE);
                                raf.write(chunkDataByteArray);
                            }

                            long totalChunks = (file.getFileSize() + (long) this.CHUNK_SIZE - 1L) / (long) this.CHUNK_SIZE;
                            long downloadedChunks = chunkCount.incrementAndGet();
                            int percent = (int) ((double) downloadedChunks * (double) 100.0F / (double) totalChunks);
                            ProgressInfo progress = processes.get(progressId);
                            if (progress != null) {
                                synchronized (progress) {
                                    progress.addBytesTransferred(chunkDataByteArray.length);
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
            return false;
        }
    }

    private boolean downloadChunkResume(PeerInfo peerInfo, int chunkIndex, RandomAccessFile raf, FileInfo
            file, String progressId, AtomicInteger chunkCount) {
        int retryCount = 3;
        ProgressInfo progressInfo = this.processes.get(progressId);
        if (progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED)) {
            return false;
        } else {
            for (int i = 1; i <= retryCount; ++i) {
                if (progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED) || Thread.currentThread().isInterrupted()) {
                    Log.logInfo("Process cancelled by user while downloading chunk " + chunkIndex + " from peer " + peerInfo.toString());
                    return false;
                }

                try (SSLSocket sslSocket = createSecureSocket(peerInfo)) {
                    String fileHash = file.getFileHash();
                    String request = "GET_CHUNK|" + fileHash + "|" + chunkIndex + "\n";
                    sslSocket.getOutputStream().write(request.getBytes());

                    DataInputStream dis = new DataInputStream(sslSocket.getInputStream());

                    // Read chunk index (4 bytes)
                    int receivedIndex = dis.readInt();

                    if (receivedIndex == chunkIndex) {
                        // Read chunk data
                        ByteArrayOutputStream chunkData = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        long startTime = System.currentTimeMillis();

                        while (System.currentTimeMillis() - startTime < 5000L) {
                            if (progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED) || Thread.currentThread().isInterrupted()) {
                                Log.logInfo("Process cancelled by user while reading chunk data from peer " + peerInfo);
                                return false;
                            }

                            if (dis.available() > 0) {
                                bytesRead = dis.read(buffer);
                                if (bytesRead == -1) {
                                    break;
                                }
                                chunkData.write(buffer, 0, bytesRead);
                            } else {
                                Thread.sleep(100L);
                            }
                        }

                        byte[] chunkDataByteArray = chunkData.toByteArray();
                        if (chunkDataByteArray.length != 0) {
                            synchronized (raf) {
                                raf.seek((long) chunkIndex * (long) this.CHUNK_SIZE);
                                raf.write(chunkDataByteArray);
                            }

                            long totalChunks = (file.getFileSize() + (long) this.CHUNK_SIZE - 1L) / (long) this.CHUNK_SIZE;
                            long downloadedChunks = chunkCount.incrementAndGet();
                            int percent = (int) ((double) downloadedChunks * (double) 100.0F / (double) totalChunks);
                            ProgressInfo progress = processes.get(progressId);
                            if (progress != null) {
                                synchronized (progress) {
                                    progress.addBytesTransferred(chunkDataByteArray.length);
                                    progress.setProgressPercentage(percent);
                                    progress.addDownloadedChunk(chunkIndex);
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
            return false;
        }
    }

    private void cancelDownload(String savePath) {
        Log.logInfo("Download process cancelled for file: " + savePath);
        File file = new File(savePath);
        java.nio.file.Path path = file.toPath();
        if (java.nio.file.Files.exists(path) && file.delete()) {
            Log.logInfo("Deleted file: " + file.getAbsolutePath());
        } else {
            Log.logInfo("Cannot delete file: " + file.getAbsolutePath() + ". It may not exist or is not writable.");
        }
    }

    private SSLSocket createSecureSocket(PeerInfo peerInfo) throws Exception {
        SSLSocket sslSocket = SSLUtils.createSSLSocketFactory().createSocket(peerInfo.getIp(), peerInfo.getPort());
        sslSocket.setUseClientMode(true);
        sslSocket.setNeedClientAuth(true);
        sslSocket.setSoTimeout(Infor.SOCKET_TIMEOUT_MS);
        sslSocket.startHandshake();
        return sslSocket;
    }
}
