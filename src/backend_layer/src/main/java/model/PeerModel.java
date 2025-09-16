package main.java.model;

import com.google.gson.Gson;
import main.java.domain.entities.FileInfo;
import main.java.domain.entities.PeerInfo;
import main.java.domain.entities.ProgressInfo;
import main.java.utils.AppPaths;
import main.java.utils.Infor;
import main.java.utils.Log;
import main.java.utils.LogTag;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class PeerModel implements IPeerModel {
    private final int CHUNK_SIZE;
    private final PeerInfo SERVER_HOST;
    private final PeerInfo TRACKER_HOST;
    private final Selector selector;
    private ConcurrentHashMap<String, FileInfo> publicSharedFiles;
    private ConcurrentHashMap<FileInfo, List<PeerInfo>> privateSharedFiles;
    private Set<FileInfo> sharedFileNames;
    private final ConcurrentHashMap<String, Set<String>> selectiveSharedFiles;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SocketChannel>> openChannels;
    private final ConcurrentHashMap<String, List<Future<Boolean>>> futures;
    private final ConcurrentHashMap<String, ProgressInfo> processes;
    private boolean isRunning;
    private final ReentrantLock fileLock;

    public PeerModel() throws IOException {
        this.CHUNK_SIZE = Infor.CHUNK_SIZE;
        this.SERVER_HOST = new PeerInfo(Infor.SERVER_IP, Infor.SERVER_PORT);
        this.TRACKER_HOST = new PeerInfo(Infor.TRACKER_IP, Infor.TRACKER_PORT);
        this.openChannels = new ConcurrentHashMap<>();
        this.futures = new ConcurrentHashMap<>();
        this.processes = new ConcurrentHashMap<>();
        this.fileLock = new ReentrantLock();
        this.publicSharedFiles = new ConcurrentHashMap<>();
        this.privateSharedFiles = new ConcurrentHashMap<>();
        this.selectiveSharedFiles = new ConcurrentHashMap<>();
        loadData();
        this.sharedFileNames = new HashSet<>();
        this.executor = Executors.newFixedThreadPool(8);
        this.selector = Selector.open();
        this.isRunning = true;
        Log.logInfo("Server socket initialized on " + this.SERVER_HOST.getIp() + ":" + this.SERVER_HOST.getPort());
    }

    public void initializeServerSocket() throws IOException {
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(new InetSocketAddress(this.SERVER_HOST.getIp(), this.SERVER_HOST.getPort()));
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.register(this.selector, SelectionKey.OP_ACCEPT);
        Log.logInfo("Server socket created on " + this.SERVER_HOST.getIp() + ":" + this.SERVER_HOST.getPort());
    }

    public void startServer() {
        this.executor.submit(() -> {
            Log.logInfo("Starting TCP server loop...");

            try {
                while (this.isRunning) {
                    this.selector.select();
                    Iterator<SelectionKey> selectedKeysIterator = this.selector.selectedKeys().iterator();

                    while (selectedKeysIterator.hasNext()) {
                        SelectionKey currentKey = selectedKeysIterator.next();
                        selectedKeysIterator.remove();
                        if (currentKey.isValid()) {
                            try {
                                if (currentKey.isAcceptable()) {
                                    this.acceptConnection(currentKey);
                                } else if (currentKey.isReadable()) {
                                    this.handleRead(currentKey);
                                }
                            } catch (IOException processingException) {
                                Log.logError("Error processing key: " + currentKey, processingException);
                                currentKey.cancel();
                                this.closeChannel(currentKey.channel());
                            }
                        }
                    }
                }
            } catch (IOException serverException) {
                Log.logError("TCP server error: " + serverException.getMessage(), serverException);
            } finally {
                this.shutdown();
            }

        });
    }

    private void shutdown() {
        Log.logInfo("Shutting down TCP server...");
        this.isRunning = false;

        try {
            this.selector.close();
            Log.logInfo("Selector closed.");
        } catch (IOException e) {
            Log.logError("Error closing selector: " + e.getMessage(), e);
        }

        this.executor.shutdown();

        try {
            if (!this.executor.awaitTermination(5L, TimeUnit.SECONDS)) {
                this.executor.shutdownNow();
                Log.logInfo("Executor service forcefully shut down.");
            }
        } catch (InterruptedException e) {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
            Log.logError("Executor service interrupted during shutdown: " + e.getMessage(), e);
        }

        Log.logInfo("TCP server shutdown complete.");
    }

    private void closeChannel(SelectableChannel channel) {
        if (channel != null && channel.isOpen()) {
            try {
                channel.close();
                Log.logInfo("Closed channel: " + channel);
            } catch (IOException closeException) {
                Log.logError("Error closing channel: " + channel, closeException);
            }
        }
    }

    public void startUDPServer() {
        this.executor.submit(() -> {
            try (DatagramSocket udpSocket = new DatagramSocket(this.SERVER_HOST.getPort())) {
                byte[] buffer = new byte[1024];
                Log.logInfo("UDP server started on " + this.SERVER_HOST.getIp() + ":" + this.SERVER_HOST.getPort());

                while (this.isRunning) {
                    DatagramPacket receivedPacket = new DatagramPacket(buffer, buffer.length);
                    udpSocket.receive(receivedPacket);
                    String receivedMessage = new String(receivedPacket.getData(), 0, receivedPacket.getLength());
                    Log.logInfo("Received UDP request: " + receivedMessage);
                    if (receivedMessage.equals("PING")) {
                        this.sendPongResponse(udpSocket, receivedPacket);
                    }
                }
            } catch (IOException udpException) {
                Log.logError("UDP server error: " + udpException.getMessage(), udpException);
            }

        });
    }

    private void sendPongResponse(DatagramSocket udpSocket, DatagramPacket receivedPacket) throws IOException {
        byte[] pongData = "PONG".getBytes();
        DatagramPacket pongPacket = new DatagramPacket(pongData, pongData.length, receivedPacket.getAddress(), receivedPacket.getPort());
        udpSocket.send(pongPacket);
        Log.logInfo("Pong response sent to " + receivedPacket.getAddress() + ":" + receivedPacket.getPort());
    }

    private void handleRead(SelectionKey clientKey) {
        SocketChannel clientChannel = (SocketChannel) clientKey.channel();

        try {
            StringBuilder requestBuilder = new StringBuilder();
            int bufferSize = 8192;
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            Log.logInfo("Handling read operation for key: " + clientKey + ", client: " + clientChannel.getRemoteAddress());
            int totalBytesRead = 0;

            while (true) {
                buffer.clear();
                int bytesRead = clientChannel.read(buffer);
                Log.logInfo("Read " + bytesRead + " bytes from " + clientChannel.getRemoteAddress());
                if (bytesRead == -1) {
                    Log.logInfo("Client closed connection");
                    break;
                }

                if (bytesRead == 0 && totalBytesRead > 0) {
                    Log.logInfo("No more data to read, processing request");
                    break;
                }

                if (bytesRead == 0) {
                    Thread.sleep(100L);
                } else {
                    totalBytesRead += bytesRead;
                    buffer.flip();
                    String chunk = new String(buffer.array(), 0, buffer.limit());
                    requestBuilder.append(chunk);
                    Log.logInfo("Current request: [" + requestBuilder + "]");
                    if (requestBuilder.toString().contains("\n")) {
                        Log.logInfo("Request complete, breaking loop");
                        break;
                    }

                    if (bytesRead == buffer.capacity()) {
                        bufferSize *= 2;
                        Log.logInfo("Increasing buffer size to " + bufferSize + " bytes");
                        buffer = ByteBuffer.allocate(bufferSize);
                    }
                }
            }

            String completeRequest = requestBuilder.toString().trim();
            Log.logInfo("Final request: [" + completeRequest + "]");
            if (!completeRequest.isEmpty()) {
                String clientIP = clientChannel.getRemoteAddress().toString().split(":")[0].replace("/", "");
                String clientIdentifier = clientIP + "|" + this.SERVER_HOST.getPort();
                if (completeRequest.startsWith("SEARCH")) {
                    String fileName = completeRequest.split("\\|")[1];
                    Log.logInfo("Processing SEARCH for file: " + fileName);
                    this.sendFileInfor(clientChannel, fileName);
                    return;
                } else if (completeRequest.startsWith("GET_CHUNK")) {
                    String[] requestParts = completeRequest.split("\\|");
                    String fileHash = requestParts[1];
                    int chunkIndex = Integer.parseInt(requestParts[2]);
                    Log.logInfo("Processing GET_CHUNK for file hash: " + fileHash + ", chunk: " + chunkIndex);
                    if (this.hasAccessToFile(clientIdentifier, fileHash)) {
                        this.sendChunk(clientChannel, fileHash, chunkIndex);
                    } else {
                        Log.logInfo("Access denied for peer " + clientIdentifier + " to file " + fileHash);
                        this.sendAccessDenied(clientChannel);
                    }

                    return;
                } else {
                    Log.logInfo("Unknown request: [" + completeRequest + "]");
                    return;
                }
            }

            Log.logInfo("Empty request received");
        } catch (InterruptedException | IOException processingException) {
            Log.logError("Error handling read: " + processingException.getMessage(), processingException);
        } finally {
            try {
                clientChannel.close();
                clientKey.cancel();
                Log.logInfo("Closed client connection: " + clientChannel.getLocalAddress() + " -> " + clientChannel.getRemoteAddress());
            } catch (IOException closeException) {
                Log.logError("Error closing client: " + closeException.getMessage(), closeException);
            }

        }

    }

    private void sendFileInfor(SocketChannel clientChannel, String fileName) {
        try {
            FileInfo fileInfo = this.publicSharedFiles.get(fileName);
            String response = getResponse(fileName, fileInfo);
            ByteBuffer responseBuffer = ByteBuffer.wrap(response.getBytes());
            int totalBytesWritten = 0;

            while (responseBuffer.hasRemaining()) {
                int bytesWritten = clientChannel.write(responseBuffer);
                totalBytesWritten += bytesWritten;
                Log.logInfo("Wrote " + bytesWritten + " bytes, total: " + totalBytesWritten);
            }

            Log.logInfo("Sent file information for: " + fileName + ", response: [" + response.trim() + "]");
        } catch (IOException sendException) {
            Log.logError("Error sending file info: " + sendException.getMessage(), sendException);
        }
    }

    private static String getResponse(String fileName, FileInfo file) {
        String response;
        if (file != null) {
            String var10000 = file.getFileName();
            response = "FILE_INFO|" + var10000 + "|" + file.getFileSize() + "|" + file.getPeerInfo().getIp() + "|" + file.getPeerInfo().getPort() + "|" + file.getFileHash() + "\n";
        } else {
            response = "FILE_NOT_FOUND|" + fileName + "\n";
        }

        return response;
    }

    private void acceptConnection(SelectionKey var1) throws IOException {
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) var1.channel();
        SocketChannel socketChannel = serverSocketChannel.accept();
        Log.logInfo("Accepted connection from: " + socketChannel.getRemoteAddress());
        socketChannel.configureBlocking(false);
        socketChannel.register(this.selector, 1);
    }

    public int registerWithTracker() {
        int count = 0;

        while (count < Infor.MAX_RETRIES) {
            try (Socket socket = this.createSocket(this.TRACKER_HOST)) {
                // Serialize the data structures
                Gson gson = new Gson();
                String publicFileToPeersJson = gson.toJson(this.publicSharedFiles);
                String privateSharedFileJson = gson.toJson(this.privateSharedFiles);
                String selectiveSharedFilesJson = gson.toJson(this.selectiveSharedFiles);

                StringBuilder registrationMessage = new StringBuilder("REGISTER|");
                registrationMessage.append(this.SERVER_HOST.getIp()).append("|").append(this.SERVER_HOST.getPort())
                        .append("|").append(publicFileToPeersJson)
                        .append("|").append(privateSharedFileJson)
                        .append("|").append(selectiveSharedFilesJson)
                        .append("\n");

                String message = registrationMessage.toString();
                socket.getOutputStream().write(message.getBytes());
                Log.logInfo("Registered with tracker with data structures: " + message);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String response = reader.readLine();
                Log.logInfo("Tracker response: " + response);
                if (response != null) {
                    if (response.startsWith("REGISTERED")) {
                        return LogTag.I_NOT_FOUND;
                    } else if (response.startsWith("SHARED_LIST")) {
                        Log.logInfo("Tracker registration successful: " + response);
                        // Parse the shared list if needed
                        return 1;
                    } else {
                        Log.logInfo("Tracker registration failed: " + response);
                        return 0;
                    }
                } else {
                    Log.logInfo("Tracker did not respond.");
                    return 0;
                }
            } catch (ConnectException e) {
                Log.logError("Tracker connection failed: " + e.getMessage(), e);
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                ++count;
            } catch (IOException e) {
                Log.logError("Error connecting to tracker: " + e.getMessage(), e);
                return 0;
            } catch (Exception e) {
                Log.logError("Unexpected error during tracker registration: " + e.getMessage(), e);
                return 0;
            }
        }

        Log.logError("Cannot connect to tracker after multiple attempts.", null);
        return 0;
    }

    public void shareFileAsync(File file, String var2, String var3) {
        this.executor.submit(() -> {
            String var4 = this.hashFile(file, var3);
            FileInfo var5 = new FileInfo(var2, file.length(), var4, this.SERVER_HOST, true);
            this.publicSharedFiles.put(file.getName(), var5);
            this.sharedFileNames.add(var5); // Add to sharedFileNames so it's included in API responses
            this.notifyTracker(var5, true);
            ProgressInfo var6 = this.processes.get(var3);
            synchronized (var6) {
                var6.setProgressPercentage(100);
                var6.setStatus("completed");
            }
            return Boolean.TRUE;
        });
    }

    private String hashFile(File file, String progressId) {
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[this.CHUNK_SIZE];
            long fileSize = file.length();
            long readbyte = 0L;
            ProgressInfo progressInfo = this.processes.get(progressId);
            if (Objects.equals(progressInfo.getStatus(), ProgressInfo.ProgressStatus.CANCELLED)) {
                return null;
            } else {
                progressInfo.setStatus(ProgressInfo.ProgressStatus.SHARING);
                progressInfo.setTotalBytes(fileSize);
                progressInfo.setBytesTransferred(readbyte);

                int readedByteCount;
                while ((readedByteCount = fileInputStream.read(bytes)) != -1) {
                    if (progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED)) {
                        return null;
                    }

                    messageDigest.update(bytes, 0, readedByteCount);
                    readbyte += readedByteCount;
                    int percentage = (int) ((double) readbyte * (double) 100.0F / (double) fileSize);
                    synchronized (progressInfo) {
                        progressInfo.setProgressPercentage(percentage);
                        progressInfo.setBytesTransferred(readbyte);
                    }
                }

                String fullFileHash = this.bytesToHex(messageDigest.digest());
                return fullFileHash;
            }
        } catch (NoSuchAlgorithmException | IOException var16) {
            this.processes.get(progressId).setStatus(ProgressInfo.ProgressStatus.FAILED);
            return null;
        }
    }

    public void shareFileList() {
        Set<FileInfo> publicFileSet = this.getPublicFileSet();

        try {
            try (Socket socket = new Socket(this.TRACKER_HOST.getIp(), this.TRACKER_HOST.getPort())) {
                StringBuilder messageBuilder = new StringBuilder("SHARED_LIST|");
                messageBuilder.append(publicFileSet.size()).append("|");

                for (FileInfo fileInfo : publicFileSet) {
                    messageBuilder.append(fileInfo.getFileName()).append("'").append(fileInfo.getFileSize()).append("'").append(fileInfo.getFileHash()).append(",");
                }

                if (messageBuilder.charAt(messageBuilder.length() - 1) == ',') {
                    messageBuilder.deleteCharAt(messageBuilder.length() - 1);
                }

                messageBuilder.append("|").append(this.SERVER_HOST.getIp()).append("|").append(this.SERVER_HOST.getPort()).append("\n");
                String message = messageBuilder.toString();
                socket.getOutputStream().write(message.getBytes());
                Log.logInfo("Shared file list with tracker: " + message);
            }

        } catch (IOException e) {
            Log.logError("Error sharing file list with tracker: " + e.getMessage(), e);
        }
    }

    private Set<FileInfo> getPublicFileSet() {
        HashSet<FileInfo> publicSet = new HashSet<>();

        for (Map.Entry<String, FileInfo> s : this.publicSharedFiles.entrySet()) {
            FileInfo var4 = s.getValue();
            publicSet.add(var4);
        }

        return publicSet;
    }

    private int notifyTracker(FileInfo fileInfo, boolean isShared) {
        try {
            byte var5;
            try (Socket var3 = new Socket(this.TRACKER_HOST.getIp(), this.TRACKER_HOST.getPort())) {
                String var4 = (isShared ? "SHARE" : "UNSHARED_FILE") + "|" + fileInfo.getFileName() + "|" + fileInfo.getFileSize() + "|" + fileInfo.getFileHash() + "|" + this.SERVER_HOST.getIp() + "|" + this.SERVER_HOST.getPort();
                var3.getOutputStream().write(var4.getBytes());
                String var10000 = fileInfo.getFileName();
                Log.logInfo("Notified tracker about shared file: " + var10000 + ", message: " + var4);
                var5 = 1;
            }

            return var5;
        } catch (IOException e) {
            Log.logError("Error notifying tracker about shared file: " + fileInfo.getFileName(), e);
            return 0;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();

        for (byte var6 : bytes) {
            hex.append(String.format("%02x", var6));
        }

        return hex.toString();
    }

    public void downloadFile(FileInfo fileInfo, File file, List<PeerInfo> peerInfos, String progressId) {
        this.executor.submit(() -> this.processDownload(fileInfo, file, progressId, peerInfos));
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
                    String fileHash = this.computeFileHash(file);
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
        ArrayList<Integer> failedChunks  = new ArrayList<>();
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
                failedChunks .add(i);
            }
        }

        int maxRetryCount = 2;

        for (int i = 1; i <= maxRetryCount && !failedChunks .isEmpty(); ++i) {
            Log.logInfo("Retrying failed chunks, round " + i + " with " + failedChunks .size() + " chunks");
            ArrayList<Integer> stillFailed = new ArrayList<>();

            for (int j : failedChunks ) {
                if (progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED)) {
                    return LogTag.I_CANCELLED;
                }

                if (!this.downloadChunkWithRetry(j, peerInfos, raf, file, progressId, chunkCount, peerOfChunk)) {
                    stillFailed.add(j);
                }
            }

            failedChunks  = stillFailed;
        }

        if (!failedChunks .isEmpty()) {
            return LogTag.I_FAILURE;
        } else {
            return LogTag.I_SUCCESS;
        }
    }

    private boolean downloadChunkWithRetry(int chunkIndex, List<PeerInfo> peerInfos, RandomAccessFile raf, FileInfo file, String progressId, AtomicInteger chunkCount, ConcurrentHashMap<Integer, List<PeerInfo>> peerOfChunk) throws InterruptedException {
        int maxRetries  = 3;

        for (int i = 0; i < maxRetries ; ++i) {
            PeerInfo peerInfo = this.selectAvailablePeer(progressId, peerInfos, chunkIndex, peerOfChunk.getOrDefault(chunkIndex, new ArrayList<>()));
            if (peerInfo == null) {
                Log.logInfo("No available peers for chunk " + chunkIndex);
                return false;
            }

            try {
                Log.logInfo("Retrying to download chunk " + chunkIndex + " from peer " + peerInfo.getIp() + ":" + peerInfo.getPort() + " (attempt " + (i + 1) + ")");
                peerInfo.addTaskForDownload();
                peerOfChunk.computeIfAbsent(chunkIndex, (var0) -> new ArrayList<>()).add(peerInfo);
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

        Log.logInfo("Failed to download chunk " + chunkIndex + " after " + maxRetries  + " attempts");
        return false;
    }

    private PeerInfo selectAvailablePeer(String progressId, List<PeerInfo> peerInfos, int chunkIndex, List<PeerInfo> usedPeers) throws InterruptedException {
        int retryCount = 0;
        final int MAX_RETRY = 20;

        while (retryCount++ < MAX_RETRY) {
            if ((this.processes.get(progressId)).getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED)) {
                return null;
            }

            PeerInfo peerInfo = peerInfos.stream().filter(
                            (var1x) -> !usedPeers.contains(var1x) && var1x.isAvailableForDownload())
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

    public List<PeerInfo> getPeersWithFile(String fileHash) {
        try (Socket socket = new Socket(this.TRACKER_HOST.getIp(), this.TRACKER_HOST.getPort())) {
            String request = "GET_PEERS|" + fileHash + "\n";
            socket.getOutputStream().write(request.getBytes());
            Log.logInfo("Requesting peers with file hash: " + fileHash + ", message: " + request);
            BufferedReader buff = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String response = buff.readLine();
            if (response == null || response.isEmpty()) {
                Log.logInfo("No response from tracker for file hash: " + fileHash);
                return Collections.emptyList();
            } else {
                String[] parts = response.split("\\|");
                if (parts.length != 3 || !parts[0].equals("GET_PEERS")) {
                    Log.logInfo("Invalid response format from tracker: " + response);
                    return Collections.emptyList();
                } else {
                    int peerCount = Integer.parseInt(parts[1]);
                    if (peerCount == 0) {
                        Log.logInfo("No peers found for file hash: " + fileHash);
                        return Collections.emptyList();
                    } else {
                        String[] peerInfos = parts[2].split(",");
                        ArrayList<PeerInfo> peers = new ArrayList<>();

                        for (String peerInfo : peerInfos) {
                            String[] peerParts = peerInfo.split("'");
                            if (peerParts.length != 2) {
                                Log.logInfo("Invalid peer info format: " + peerInfo);
                            } else {
                                String ip = peerParts[0];
                                int port = Integer.parseInt(peerParts[1]);
                                peers.add(new PeerInfo(ip, port));
                            }
                        }

                        if (peers.isEmpty()) {
                            Log.logInfo("No valid peers found for file hash: " + fileHash);
                            return Collections.emptyList();
                        } else if (peerCount != peers.size()) {
                            Log.logInfo("Peer count mismatch: expected " + peerCount + ", found " + peers.size());
                            return Collections.emptyList();
                        } else {
                            Log.logInfo("Received response from tracker: " + response);
                            return peers;
                        }
                    }
                }
            }
        } catch (IOException e) {
            Log.logError("Error getting peers with file hash: " + fileHash, e);
            return Collections.emptyList();
        }
    }

    public boolean shareFileToPeers(File file, String progressId, List<String> peerList) {
        String fileHash = this.hashFile(file, progressId);
        List<PeerInfo> peerInfos = new ArrayList<>();
        for (String peer : peerList) {
            String[] parts = peer.split(":");
            if (parts.length == 2) {
                String ip = parts[0];
                int port = Integer.parseInt(parts[1]);
                peerInfos.add(new PeerInfo(ip, port));
            } else {
                Log.logInfo("Invalid peer format: " + peer);
                return false;
            }
        }

        try (Socket socket = new Socket(this.TRACKER_HOST.getIp(), this.TRACKER_HOST.getPort())) {
            StringBuilder messageBuilder = new StringBuilder("SHARE_TO_PEERS|");
            messageBuilder.append(fileHash).append("|");
            messageBuilder.append(peerList.size()).append("|");

            for (String peer : peerList) {
                messageBuilder.append(peer).append("|");
            }

            if (messageBuilder.charAt(messageBuilder.length() - 1) == '|') {
                messageBuilder.deleteCharAt(messageBuilder.length() - 1);
            }

            messageBuilder.append("\n");
            String message = messageBuilder.toString();
            socket.getOutputStream().write(message.getBytes());
            String fileName = file.getName();
            privateSharedFiles.put(new FileInfo(fileName, file.length(), fileHash, this.SERVER_HOST, true), peerInfos);
            this.selectiveSharedFiles.put(fileHash, new HashSet<>(peerList));
            Log.logInfo("Sharing file " + fileName + " (hash: " + fileHash + ") to specific peers: " + peerList + ", message: " + message);
            BufferedReader buff = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String response = buff.readLine();
            Log.logInfo("Response from tracker: " + response);
            return response != null && response.contains("thành công");
        } catch (IOException e) {
            Log.logError("Error sharing file to peers: " + file.getName(), e);
            return false;
        }
    }

    public List<String> getSelectivePeers(String fileHash) {
        try (Socket socket = new Socket(this.TRACKER_HOST.getIp(), this.TRACKER_HOST.getPort())) {
            String request = "GET_SHARED_PEERS|" + fileHash + "\n";
            socket.getOutputStream().write(request.getBytes());
            Log.logInfo("Requesting selective peers for file hash: " + fileHash + ", message: " + request);
            BufferedReader buff = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String response = buff.readLine();
            if (response == null || response.isEmpty()) {
                Log.logInfo("No response from tracker for selective peers of file hash: " + fileHash);
                return Collections.emptyList();
            } else {
                String[] parts = response.split("\\|");
                if (parts.length != 3 || !parts[0].equals("GET_SHARED_PEERS")) {
                    Log.logInfo("Invalid response format from tracker: " + response);
                    return Collections.emptyList();
                } else {
                    int peerCount = Integer.parseInt(parts[1]);
                    if (peerCount == 0) {
                        Log.logInfo("No selective peers found for file hash: " + fileHash);
                        return Collections.emptyList();
                    } else {
                        String[] peerParts = parts[2].split(",");
                        ArrayList<String> peerInfos = new ArrayList<>();

                        Collections.addAll(peerInfos, peerParts);

                        if (peerInfos.isEmpty()) {
                            Log.logInfo("No valid selective peers found for file hash: " + fileHash);
                            return Collections.emptyList();
                        } else if (peerCount != peerInfos.size()) {
                            Log.logInfo("Selective peer count mismatch: expected " + peerCount + ", found " + peerInfos.size());
                            return Collections.emptyList();
                        } else {
                            Log.logInfo("Received selective peers from tracker: " + response);
                            return peerInfos;
                        }
                    }
                }
            }
        } catch (IOException e) {
            Log.logError("Error getting selective peers for file hash: " + fileHash, e);
            return Collections.emptyList();
        }
    }

    public List<String> getKnownPeers() {
        try (Socket socket = new Socket(this.TRACKER_HOST.getIp(), this.TRACKER_HOST.getPort())) {
            String request = "GET_KNOWN_PEERS\n";
            socket.getOutputStream().write(request.getBytes());
            Log.logInfo("Requesting known peers from tracker, message: " + request);
            BufferedReader buff = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String response = buff.readLine();
            if (response == null || response.isEmpty()) {
                Log.logInfo("No response from tracker for known peers");
                return Collections.emptyList();
            } else {
                String[] parts = response.split("\\|");
                if (parts.length != 3 || !parts[0].equals("GET_KNOWN_PEERS")) {
                    Log.logInfo("Invalid response format from tracker: " + response);
                    return Collections.emptyList();
                } else {
                    int peerCount = Integer.parseInt(parts[1]);
                    if (peerCount == 0) {
                        Log.logInfo("No known peers found");
                        return Collections.emptyList();
                    } else {
                        String[] peerParts = parts[2].split(",");
                        ArrayList<String> peerInfos = new ArrayList<>();

                        Collections.addAll(peerInfos, peerParts);

                        if (peerInfos.isEmpty()) {
                            Log.logInfo("No valid known peers found");
                            return Collections.emptyList();
                        } else if (peerCount != peerInfos.size()) {
                            Log.logInfo("Known peer count mismatch: expected " + peerCount + ", found " + peerInfos.size());
                            return Collections.emptyList();
                        } else {
                            Log.logInfo("Received known peers from tracker: " + response);
                            return peerInfos;
                        }
                    }
                }
            }
        } catch (IOException e) {
            Log.logError("Error getting known peers from tracker", e);
            return Collections.emptyList();
        }
    }

    private String computeFileHash(File file) {
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] buff = new byte[this.CHUNK_SIZE];

            int byteRead;
            while ((byteRead = fileInputStream.read(buff)) != -1) {
                messageDigest.update(buff, 0, byteRead);
            }

            return this.bytesToHex(messageDigest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            Log.logError("Error computing hash for file: " + file.getAbsolutePath(), e);
            return "ERROR";
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

    private boolean downloadChunk(PeerInfo peerInfo, int chunkIndex, RandomAccessFile raf, FileInfo file, String progressId, AtomicInteger chunkCount) {
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

                SocketChannel socketChannel = null;

                try {
                    socketChannel = SocketChannel.open(new InetSocketAddress(peerInfo.getIp(), peerInfo.getPort()));
                    socketChannel.socket().setSoTimeout(Infor.SOCKET_TIMEOUT_MS);
                    (this.openChannels.get(progressId)).add(socketChannel);
                    String fileHash = file.getFileHash();
                    String request = "GET_CHUNK|" + fileHash + "|" + chunkIndex + "\n";
                    ByteBuffer buff = ByteBuffer.wrap(request.getBytes());

                    while (buff.hasRemaining()) {
                        socketChannel.write(buff);
                    }

                    ByteBuffer indexBuffer = ByteBuffer.allocate(4);
                    int totalIndexBytes = 0;

                    int byteRead = 0;
                    for (long startTime = System.currentTimeMillis(); totalIndexBytes < 4 && System.currentTimeMillis() - startTime < 5000L; totalIndexBytes += byteRead) {
                        if (progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED)) {
                            Log.logInfo("Process cancelled by user while reading index chunk from peer " + peerInfo);
                            return false;
                        }

                        byteRead = socketChannel.read(indexBuffer);
                        if (byteRead == -1) {
                            break;
                        }
                    }

                    if (totalIndexBytes < 4) {
                        Log.logInfo("Don't receive enough bytes for index chunk from peer " + peerInfo + " (attempt: " + i + ")");
                    } else {
                        indexBuffer.flip();
                        byteRead = indexBuffer.getInt();
                        if (byteRead != chunkIndex) {
                            Log.logInfo("Received chunk index " + byteRead + " does not match requested index " + chunkIndex + " from peer " + peerInfo + " (attempt " + i + ")");
                        } else {
                            ByteBuffer chunkBuffer = ByteBuffer.allocate(this.CHUNK_SIZE);
                            ByteArrayOutputStream chunkData = new ByteArrayOutputStream();
                            long startTime = System.currentTimeMillis();

                            while (System.currentTimeMillis() - startTime < 5000L) {
                                if (progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED) || Thread.currentThread().isInterrupted()) {
                                    Log.logInfo("Process cancelled by user while reading chunk data from peer " + peerInfo);
                                    return false;
                                }

                                int byteReads = socketChannel.read(chunkBuffer);
                                if (byteReads == -1) {
                                    break;
                                }

                                if (byteReads == 0) {
                                    Thread.sleep(100L);
                                } else {
                                    chunkBuffer.flip();
                                    chunkData.write(chunkBuffer.array(), 0, byteReads);
                                    chunkBuffer.clear();
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
                                        progress.addBytesTransferred(CHUNK_SIZE);
                                        progress.setProgressPercentage(percent);
                                    }
                                }
                                Log.logInfo("Successfully downloaded chunk " + chunkIndex + " from peer " + peerInfo + " (attempt " + i + ")");
                                return true;
                            }

                            Log.logInfo("Received empty chunk data from peer " + peerInfo + " for chunk index " + chunkIndex + " (attempt " + i + ")");
                        }
                    }
                } catch (InterruptedException | IOException e) {
                    Log.logError("Error downloading chunk " + chunkIndex + " from peer " + peerInfo + " (attempt " + i + "): " + e.getMessage(), e);

                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        Log.logError("Process interrupted during sleep after error while downloading chunk " + chunkIndex + " from peer " + peerInfo, ex);
                        return false;
                    }
                } finally {
                    if (socketChannel != null) {
                        this.openChannels.get(progressId).remove(socketChannel);

                        try {
                            socketChannel.close();
                        } catch (IOException e) {
                            Log.logError("Error closing channel: " + socketChannel, e);
                        }
                    }

                }
            }

            Log.logInfo("Failed to download chunk " + chunkIndex + " from peer " + peerInfo + " after " + retryCount + " attempts.");
            return false;
        }
    }

    private void sendChunk(SocketChannel socketChannel, String fileHash, int chunkIndex) {
        FileInfo fileInfo = null;

        for (FileInfo file : this.publicSharedFiles.values()) {
            if (file.getFileHash().equals(fileHash)) {
                fileInfo = file;
                break;
            }
        }

        if (fileInfo == null) {
            Log.logInfo("File not found in shared files: " + fileHash);
        } else {
            String appPath = AppPaths.getAppDataDirectory();
            String filePath = appPath + "\\shared_files\\" + fileInfo.getFileName();
            File file = new File(filePath);
            if (file.exists() && file.canRead()) {
                try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                    byte[] chunkbyte = new byte[this.CHUNK_SIZE];
                    raf.seek((long) chunkIndex * (long) this.CHUNK_SIZE);
                    int byteReads = raf.read(chunkbyte);
                    if (byteReads <= 0) {
                        Log.logInfo("No bytes read for chunk " + chunkIndex + " of file " + fileInfo.getFileName() + ". File may be empty or chunk index is out of bounds.");
                    } else {
                        ByteBuffer buffer = ByteBuffer.allocate(byteReads + 4);
                        buffer.putInt(chunkIndex);
                        buffer.put(chunkbyte, 0, byteReads);
                        buffer.flip();

                        int totalBytesWritten;
                        int bytesWritten;
                        for (totalBytesWritten = 0; buffer.hasRemaining(); totalBytesWritten += bytesWritten) {
                            bytesWritten = socketChannel.write(buffer);
                        }

                        Log.logInfo("Chunk " + chunkIndex + " of file " + fileInfo.getFileName() + " for " + fileHash + " sent successfully.");
                        Log.logInfo("Chunk index sent: " + chunkIndex);
                        Log.logInfo("Total bytes written: " + totalBytesWritten);
                        if (totalBytesWritten < byteReads + 4) {
                            Log.logInfo("Warning: Not all bytes were sent for chunk " + chunkIndex + " of file " + fileInfo.getFileName() + ". Expected: " + (byteReads + 4) + ", Sent: " + totalBytesWritten);
                        } else {
                            Log.logInfo("All bytes sent successfully for chunk " + chunkIndex + " of file " + fileInfo.getFileName());
                        }
                    }
                } catch (IOException e) {
                    Log.logError("Error sending chunk " + chunkIndex + " of file " + fileInfo.getFileName() + ": " + e.getMessage(), e);
                }

            } else {
                Log.logInfo("File does not exist or cannot be read: " + filePath);
            }
        }
    }

    private boolean hasAccessToFile(String clientIdentify, String fileHash) {
        for (FileInfo file : this.publicSharedFiles.values()) {
            if (file.getFileHash().equals(fileHash)) {
                // First check if file is publicly shared (no selective peers)
                List<String> selectivePeers = this.getSelectivePeers(fileHash);
                if (selectivePeers.isEmpty()) {
                    return true; // Public file, anyone can access
                }

                // Check if requesting peer is in the allowed list
                return selectivePeers.contains(clientIdentify);
            }
        }

        return false; // File not found in our shared files
    }

    private void sendAccessDenied(SocketChannel client) {
        try {
            String message = "ACCESS_DENIED\n";
            ByteBuffer buffer = ByteBuffer.wrap(message.getBytes());

            while (buffer.hasRemaining()) {
                client.write(buffer);
            }

            Log.logInfo("Access denied response sent to client");
        } catch (IOException var4) {
            Log.logError("Error sending access denied response: " + var4.getMessage(), var4);
        }

    }

    public void loadSharedFiles() {
        String sharedFolderPath = AppPaths.getAppDataDirectory() + "\\shared_files\\";
        File file = new File(sharedFolderPath);
        if (file.exists() && file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null && files.length != 0) {
                Log.logInfo("List of shared files in directory: " + file.getAbsolutePath());

                for (File f : files) {
                    if (f.isFile()) {
                        String fileName = f.getName();
                        long fileSize = f.length();
                        Log.logInfo(" - " + fileName + " (" + fileSize + " bytes)");
                        String fileHash = this.computeFileHash(f);
                        if (fileHash.equals("ERROR")) {
                            Log.logInfo("Error computing hash for file: " + fileName);
                        } else {
                            FileInfo fileInfo = new FileInfo(fileName, fileSize, fileHash, this.SERVER_HOST, true);
                            this.publicSharedFiles.put(fileName, fileInfo);
                            this.sharedFileNames.add(fileInfo); // Add to sharedFileNames so it's included in API responses
                        }
                    }
                }

            } else {
                Log.logInfo("No shared files found in directory: " + file.getAbsolutePath());
            }
        } else {
            Log.logInfo("Shared directory does not exist or is not a directory: " + sharedFolderPath);
        }
    }

    public int refreshSharedFileNames() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(this.TRACKER_HOST.getIp(), this.TRACKER_HOST.getPort()));
            PrintWriter printWriter = new PrintWriter(socket.getOutputStream(), true);
            String ip = this.SERVER_HOST.getIp();
            String request = "REFRESH|" + ip + "|" + this.SERVER_HOST.getPort() + "\n";
            printWriter.println(request);
            BufferedReader buffer = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String response = buffer.readLine();
            if (response != null && response.startsWith("REFRESHED")) {
                String[] parts = response.split("\\|");
                if (parts.length != 3) {
                    Log.logInfo("Invalid response format from tracker: " + response);
                    return -1;
                } else {
                    int filesCount = Integer.parseInt(parts[1]);
                    String[] fileParts = parts[2].split(",");
                    if (fileParts.length != filesCount) {
                        Log.logInfo("File count mismatch: expected " + filesCount + ", got " + fileParts.length);
                        return -1;
                    } else {
                        this.sharedFileNames.clear();

                        for (String file : fileParts) {
                            String[] f = file.split("'");
                            if (f.length != 5) {
                                Log.logInfo("Invalid file info format: " + file);
                            } else {
                                String fileName = f[0];
                                long fileSize = Long.parseLong(f[1]);
                                String fileHash = f[2];
                                PeerInfo peerInfo = new PeerInfo(f[3], Integer.parseInt(f[4]));
                                // Check if this file is shared by us
                                boolean isSharedByMe = this.publicSharedFiles.containsKey(fileName);
                                this.sharedFileNames.add(new FileInfo(fileName, fileSize, fileHash, peerInfo, isSharedByMe));
                            }
                        }

                        Log.logInfo("Refreshed shared file names from tracker: " + this.sharedFileNames.size() + " files found.");
                        return 1;
                    }
                }
            } else {
                Log.logInfo("Invalid response from tracker: " + response);
                return -1;
            }
        } catch (Exception e) {
            Log.logError("Error refreshing shared file names from tracker: " + e.getMessage(), e);
            return 0;
        }
    }

    public Set<FileInfo> getSharedFileNames() {
        return this.sharedFileNames;
    }

    public void setSharedFileNames(Set<FileInfo> filesSet) {
        this.sharedFileNames = filesSet;
    }

    public Map<String, FileInfo> getPublicSharedFiles() {
        return this.publicSharedFiles;
    }

    public int stopSharingFile(String fileName) {
        if (!this.publicSharedFiles.containsKey(fileName)) {
            Log.logInfo("File not found in shared files: " + fileName);
            return 2;
        } else {
            FileInfo fileInfo = this.publicSharedFiles.get(fileName);
            this.publicSharedFiles.remove(fileName);
            this.sharedFileNames.removeIf((var1x) -> var1x.getFileName().equals(fileName));
            String appPath = AppPaths.getAppDataDirectory();
            String filePath = appPath + "\\shared_files\\" + fileName;
            File file = new File(filePath);
            if (file.exists() && file.delete()) {
                Log.logInfo("Removed shared file: " + filePath);
            } else {
                Log.logInfo("Failed to remove shared file: " + filePath);
            }

            Log.logInfo("Stopped sharing file: " + fileName);
            return this.notifyTracker(fileInfo, false);
        }
    }

    public void cleanupProgress(List<String> progressIds) {
        for (String progressId : progressIds) {
            this.processes.remove(progressId);
            List<Future<Boolean>> processingFutures = this.futures.remove(progressId);
            if (processingFutures != null) {
                for (Future<Boolean> processingFuture : processingFutures) {
                    processingFuture.cancel(true);
                }
            }

            List<SocketChannel> socketChannels = this.openChannels.remove(progressId);
            if (socketChannels != null) {
                for (SocketChannel socketChannel : socketChannels) {
                    try {
                        socketChannel.close();
                    } catch (IOException e) {
                        Log.logError("Error closing channel: " + socketChannel, e);
                    }
                }
            }
        }

    }

    public Map<String, ProgressInfo> getProgress() {
        return this.processes;
    }

    public void setProgress(ProgressInfo progress) {
        this.processes.put(progress.getId(), progress);
    }

    private Socket createSocket(PeerInfo peerInfo) throws IOException {
        Socket socket = new Socket();
        socket.setSoTimeout(Infor.SOCKET_TIMEOUT_MS);
        socket.connect(new InetSocketAddress(peerInfo.getIp(), peerInfo.getPort()), Infor.SOCKET_TIMEOUT_MS);
        return socket;
    }

    // save persistent data
    private void saveData() {
        String dataDirPath = AppPaths.getAppDataDirectory() + File.separator + "data";
        File dataDir = new File(dataDirPath);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }

        // Save public shared files
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(dataDirPath + File.separator + "publicSharedFiles.dat"))) {
            oos.writeObject(this.publicSharedFiles);
            Log.logInfo("Public shared files saved successfully.");
        } catch (IOException e) {
            Log.logError("Error saving public shared files: " + e.getMessage(), e);
        }

        //save private shared files
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(dataDirPath + File.separator + "privateSharedFiles.dat"))) {
            oos.writeObject(this.privateSharedFiles);
            Log.logInfo("Private shared files saved successfully.");
        } catch (IOException e) {
            Log.logError("Error saving private shared files: " + e.getMessage(), e);
        }
    }

    private void loadData() {
        String dataDirPath = AppPaths.getAppDataDirectory() + File.separator + "data";
        File dataDir = new File(dataDirPath);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
            return;
        }

        // Load public shared files
        File publicFile = new File(dataDirPath + File.separator + "publicSharedFiles.dat");
        if (publicFile.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(publicFile))) {
                this.publicSharedFiles = (ConcurrentHashMap<String, FileInfo>) ois.readObject();
                Log.logInfo("Public shared files loaded successfully.");
            } catch (IOException | ClassNotFoundException e) {
                Log.logError("Error loading public shared files: " + e.getMessage(), e);
            }
        }

        // Load private shared files
        File privateFile = new File(dataDirPath + File.separator + "privateSharedFiles.dat");
        if (privateFile.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(privateFile))) {
                this.privateSharedFiles = (ConcurrentHashMap<FileInfo, List<PeerInfo>>) ois.readObject();
                Log.logInfo("Private shared files loaded successfully.");
            } catch (IOException | ClassNotFoundException e) {
                Log.logError("Error loading private shared files: " + e.getMessage(), e);
            }
        }
    }

    {
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveData));
    }
}
