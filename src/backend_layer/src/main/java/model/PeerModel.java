package main.java.model;

import com.google.gson.reflect.TypeToken;
import main.java.domain.adapter.FileInfoAdapter;
import main.java.domain.entities.FileInfo;
import main.java.domain.entities.PeerInfo;
import main.java.domain.entities.ProgressInfo;
import main.java.utils.AppPaths;
import main.java.utils.Infor;
import main.java.utils.Log;
import main.java.utils.LogTag;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.lang.reflect.Type;
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
    private ConcurrentHashMap<FileInfo, Set<PeerInfo>> privateSharedFiles;
    private Set<FileInfo> sharedFileNames;
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
        loadData();
        this.sharedFileNames = new HashSet<>();
        this.executor = Executors.newFixedThreadPool(8);
        this.selector = Selector.open();
        this.isRunning = true;
        Log.logInfo("Server socket initialized on " + this.SERVER_HOST.getIp() + ":" + this.SERVER_HOST.getPort());
        startTimeoutMonitor();
    }

    private void startTimeoutMonitor() {
        this.executor.submit(() -> {
            Log.logInfo("Starting timeout monitor...");
            while (this.isRunning) {
                try {
                    Thread.sleep(10000); // Check every 30 seconds
                    checkForTimeouts();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.logInfo("Timeout monitor interrupted");
                    break;
                }
            }
            Log.logInfo("Timeout monitor stopped");
        });
    }

    private void checkForTimeouts() {
        long currentTime = System.currentTimeMillis();
        Log.logInfo("Checking for timeouts at " + currentTime);
        for (Map.Entry<String, ProgressInfo> entry : this.processes.entrySet()) {
            ProgressInfo progress = entry.getValue();
            String status = progress.getStatus();

            // Only check timeout for active downloads
            if ((status.equals(ProgressInfo.ProgressStatus.DOWNLOADING) ||
                    status.equals(ProgressInfo.ProgressStatus.SHARING)) &&
                    progress.isTimedOut()) {

                Log.logInfo("Download timeout detected for progress ID: " + entry.getKey() +
                        ", last update: " + (currentTime - progress.getLastProgressUpdateTime()) + "ms ago");

                synchronized (progress) {
                    if (status.equals(ProgressInfo.ProgressStatus.DOWNLOADING)) {
                        progress.setStatus(ProgressInfo.ProgressStatus.TIMEOUT);
                        Log.logInfo("Download marked as timed out: " + entry.getKey());
                    } else if (status.equals(ProgressInfo.ProgressStatus.SHARING)) {
                        // For sharing, we might want different handling, but for now just log
                        Log.logInfo("Sharing operation appears stalled: " + entry.getKey());
                    }
                }
            }
        }
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
                PeerInfo clientIdentifier = new PeerInfo(clientIP, SERVER_HOST.getPort());
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
                } else if (completeRequest.startsWith("CHAT_MESSAGE")) {
                    String[] messageParts = completeRequest.split("\\|", 3);
                    if (messageParts.length >= 3) {
                        String senderId = messageParts[1];
                        String message = messageParts[2];
                        Log.logInfo("Processing CHAT_MESSAGE from " + senderId + ": " + message);
                        this.handleChatMessage(clientChannel, senderId, message);
                    } else {
                        Log.logInfo("Invalid CHAT_MESSAGE format: " + completeRequest);
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
            String response;
            if (fileInfo != null) {
                response = "FILE_INFO|" + fileName + "|" + fileInfo.getFileSize() + "|" + fileInfo.getPeerInfo().getIp()
                        + "|" + fileInfo.getPeerInfo().getPort() + "|" + fileInfo.getFileHash() + "\n";
            } else {
                response = "FILE_NOT_FOUND|" + fileName + "\n";
            }
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

    private void acceptConnection(SelectionKey key) throws IOException {
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
        SocketChannel socketChannel = serverSocketChannel.accept();
        Log.logInfo("Accepted connection from: " + socketChannel.getRemoteAddress());
        socketChannel.configureBlocking(false);
        socketChannel.register(this.selector, 1);
    }

    public int registerWithTracker() {
        int count = 0;

        while (count < Infor.MAX_RETRIES) {
            try (Socket socket = this.createSocket(this.TRACKER_HOST)) {
                Gson gson = new GsonBuilder().registerTypeAdapter(FileInfo.class, new FileInfoAdapter())
                        .enableComplexMapKeySerialization().setPrettyPrinting().create();
                Type publicFileType = new TypeToken<Map<String, FileInfo>>() {
                }.getType();
                String publicFileToPeersJson = gson.toJson(publicSharedFiles, publicFileType);
                Type privateFileType = new TypeToken<Map<FileInfo, Set<PeerInfo>>>() {
                }.getType();
                String privateSharedFileJson = gson.toJson(privateSharedFiles, privateFileType);

                StringBuilder registrationMessage = new StringBuilder("REGISTER|");
                registrationMessage.append(this.SERVER_HOST.getIp()).append("|").append(this.SERVER_HOST.getPort())
                        .append("|").append(publicFileToPeersJson)
                        .append("|").append(privateSharedFileJson)
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

    public boolean sharePublicFile(File file, String fileName, String progressId, int isReplace, FileInfo oldFileInfo) {
        try {
            if (isReplace == 1 && oldFileInfo != null) {
                this.unshareFile(oldFileInfo);
                publicSharedFiles.remove(oldFileInfo.getFileName());
                privateSharedFiles.remove(oldFileInfo);
            }

            String fileHash = this.hashFile(file, progressId);
            if (fileHash == null) {
                return false;
            }

            FileInfo newFileInfo = new FileInfo(fileName, file.length(), fileHash, this.SERVER_HOST, true);
            List<FileInfo> fileInfos = new ArrayList<>();
            fileInfos.add(newFileInfo);
            ProgressInfo progress = this.processes.get(progressId);

            boolean result = shareFileList(fileInfos, new HashMap<>());
            if (!result) {
                if (progress != null) {
                    progress.setStatus(ProgressInfo.ProgressStatus.FAILED);
                }
                executor.submit(() -> AppPaths.removeSharedFile(fileName));
                return false;
            }
            this.publicSharedFiles.put(fileName, newFileInfo);
            this.sharedFileNames.add(newFileInfo);


            if (progress != null) {
                synchronized (progress) {
                    progress.setProgressPercentage(100);
                    progress.setStatus(ProgressInfo.ProgressStatus.COMPLETED);
                }
            }
            return true;
        } catch (Exception e) {
            Log.logError("Error in shareFileAsync: " + e.getMessage(), e);
            ProgressInfo progress = this.processes.get(progressId);
            if (progress != null) {
                progress.setStatus(ProgressInfo.ProgressStatus.FAILED);
            }
            return false;
        }
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

                int readedByteCount;
                while ((readedByteCount = fileInputStream.read(bytes)) != -1) {
                    if (progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED)) {
                        return null;
                    }

                    messageDigest.update(bytes, 0, readedByteCount);
                    readbyte += readedByteCount;
                    int percentage = (int) ((double) readbyte * (double) 25.0F / (double) fileSize) + 70;
                    synchronized (progressInfo) {
                        progressInfo.setProgressPercentage(percentage);
                    }
                }

                return this.bytesToHex(messageDigest.digest());
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            this.processes.get(progressId).setStatus(ProgressInfo.ProgressStatus.FAILED);
            return null;
        }
    }

    @Override
    public boolean shareFileList(List<FileInfo> publicFiles, Map<FileInfo, Set<PeerInfo>> privateFiles) {
        try (Socket socket = new Socket(this.TRACKER_HOST.getIp(), this.TRACKER_HOST.getPort())) {
            StringBuilder messageBuilder = new StringBuilder("SHARE|");
            messageBuilder.append(publicFiles.size()).append("|")
                    .append(privateFiles.size()).append("|");

            Gson gson = new GsonBuilder().registerTypeAdapter(FileInfo.class, new FileInfoAdapter())
                    .enableComplexMapKeySerialization().setPrettyPrinting().create();
            Type listType = new TypeToken<List<FileInfo>>() {
            }.getType();
            String publicFileToPeersJson = gson.toJson(publicFiles, listType);
            Type mapType = new TypeToken<Map<FileInfo, Set<PeerInfo>>>() {
            }.getType();
            String privateSharedFileJson = gson.toJson(privateFiles, mapType);

            messageBuilder.append(publicFileToPeersJson).append("|")
                    .append(privateSharedFileJson).append("\n");

            String message = messageBuilder.toString();
            socket.getOutputStream().write(message.getBytes());
            // response
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String response = reader.readLine();
            Log.logInfo("Shared file list with tracker, response: " + response);
            return response.startsWith(LogTag.S_SUCCESS);
        } catch (IOException e) {
            Log.logError("Error sharing file list with tracker: " + e.getMessage(), e);
            return false;
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

    @Override
    public List<FileInfo> getPublicFiles() {
        List<FileInfo> fileInfos = new ArrayList<>(publicSharedFiles.values());
        return fileInfos;
    }

    private int unshareFile(FileInfo fileInfo) {
        try {
            byte res;
            try (Socket socket = new Socket(this.TRACKER_HOST.getIp(), this.TRACKER_HOST.getPort())) {
                String query = "UNSHARED_FILE" + "|" + fileInfo.getFileName() + "|" + fileInfo.getFileSize() + "|" + fileInfo.getFileHash() + "|" + this.SERVER_HOST.getIp() + "|" + this.SERVER_HOST.getPort();
                socket.getOutputStream().write(query.getBytes());
                Log.logInfo("Notified tracker about shared file: " + fileInfo.getFileName() + ", message: " + query);
                res = 1;
            }

            return res;
        } catch (IOException e) {
            Log.logError("Error notifying tracker about shared file: " + fileInfo.getFileName(), e);
            return 0;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();

        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }

        return hex.toString();
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

        final FileInfo fileInfo;
        // Try to find the file info from shared files or reconstruct from progress
        FileInfo tempFileInfo = null;
        for (FileInfo sharedFile : this.publicSharedFiles.values()) {
            if (sharedFile.getFileHash().equals(progressInfo.getFileHash())) {
                tempFileInfo = sharedFile;
                break;
            }
        }

        if (tempFileInfo == null) {
            Log.logInfo("Cannot resume download: file info not found");
            return;
        }
        fileInfo = tempFileInfo;

        File saveFile = new File(progressInfo.getSavePath());
        List<PeerInfo> peerInfos = this.getPeersWithFile(progressInfo.getFileHash());

        if (peerInfos.isEmpty()) {
            Log.logInfo("Cannot resume download: no peers available");
            return;
        }

        this.executor.submit(() -> this.processResumeDownload(fileInfo, saveFile, progressId, peerInfos));
    }

    private Integer processResumeDownload(FileInfo fileInfo, File file, String
            progressId, List<PeerInfo> peerInfos) {
        this.futures.put(progressId, new ArrayList<>());
        this.openChannels.put(progressId, new CopyOnWriteArrayList<>());
        AtomicInteger chunkCount = new AtomicInteger(0);
        ProgressInfo progressInfo = this.processes.get(progressId);
        if (Objects.equals(progressInfo.getStatus(), ProgressInfo.ProgressStatus.CANCELLED)) {
            return LogTag.I_CANCELLED;
        } else {
            progressInfo.setStatus(ProgressInfo.ProgressStatus.DOWNLOADING);
            progressInfo.setTotalBytes(fileInfo.getFileSize());

            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.setLength(fileInfo.getFileSize());
                ConcurrentHashMap<Integer, List<PeerInfo>> peerOfChunk = new ConcurrentHashMap<>();
                int totalChunk = (int) Math.ceil((double) fileInfo.getFileSize() / (double) this.CHUNK_SIZE);
                this.initializeHashMap(peerOfChunk, totalChunk);
                int result = this.downloadAllChunksResume(fileInfo, peerInfos, progressId, chunkCount, raf, peerOfChunk);
                if (result == LogTag.I_CANCELLED) {
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

    private Integer downloadAllChunks(FileInfo file, List<PeerInfo> peerInfos, String progressId, AtomicInteger
                                              chunkCount,
                                      RandomAccessFile raf, ConcurrentHashMap<Integer, List<PeerInfo>> peerOfChunk) throws
            InterruptedException {
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

    public List<PeerInfo> getPeersWithFile(String fileHash) {
        try (Socket socket = new Socket(this.TRACKER_HOST.getIp(), this.TRACKER_HOST.getPort())) {
            String request = "GET_PEERS|" + fileHash + "|" + SERVER_HOST.getIp() + "|" + SERVER_HOST.getPort() + "\n";
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

    public boolean shareFileToPeers(File file, FileInfo oldFileInfo, int isReplace, String
            progressId, List<String> peerList) {
        if (isReplace == 1 && oldFileInfo != null) {
            this.unshareFile(oldFileInfo);
            publicSharedFiles.remove(oldFileInfo.getFileName(), oldFileInfo);
            privateSharedFiles.remove(oldFileInfo);
        }
        String fileHash = this.hashFile(file, progressId);
        String fileName = file.getName();
        long fileSize = file.length();
        FileInfo sharedFile = new FileInfo(fileName, fileSize, fileHash, this.SERVER_HOST, true);

        Set<PeerInfo> peerInfos = new HashSet<>();
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
        boolean result = shareFileList(new ArrayList<>(), Map.of(sharedFile, peerInfos));

        if (!result) {
            Log.logInfo("Failed to share file " + fileName + " to specific peers: " + peerList);
            executor.submit(() -> AppPaths.removeSharedFile(fileName));
            processes.get(progressId).setStatus(ProgressInfo.ProgressStatus.FAILED);
            return false;
        }

        privateSharedFiles.put(sharedFile, new HashSet<>(peerInfos));
        Log.logInfo("Sharing file " + fileName + " (hash: " + fileHash + ") to specific peers: " + peerList);

        processes.get(progressId).setStatus(ProgressInfo.ProgressStatus.COMPLETED);
        processes.get(progressId).setProgressPercentage(100);
        return result;
    }

    public List<PeerInfo> getSelectivePeers(String fileHash) {
        List<PeerInfo> peers = new ArrayList<>();
        for (Map.Entry<FileInfo, Set<PeerInfo>> entry : privateSharedFiles.entrySet()) {
            FileInfo fileInfo = entry.getKey();
            if (fileInfo.getFileHash().equals(fileHash)) {
                peers.addAll(entry.getValue());
                break;
            }
        }
        return peers;
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
                                        progress.updateProgressTime();
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
                                        progress.addDownloadedChunk(chunkIndex);
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
            String filePath = appPath + "/shared_files/" + fileInfo.getFileName();
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

    private boolean hasAccessToFile(PeerInfo clientIdentify, String fileHash) {
        for (FileInfo file : this.publicSharedFiles.values()) {
            if (file.getFileHash().equals(fileHash)) {
                return true;
            }
        }
        List<PeerInfo> selectivePeers = this.getSelectivePeers(fileHash);
        return selectivePeers.contains(clientIdentify);
    }

    private void sendAccessDenied(SocketChannel client) {
        try {
            String message = "ACCESS_DENIED\n";
            ByteBuffer buffer = ByteBuffer.wrap(message.getBytes());

            while (buffer.hasRemaining()) {
                client.write(buffer);
            }

            Log.logInfo("Access denied response sent to client");
        } catch (IOException e) {
            Log.logError("Error sending access denied response: " + e.getMessage(), e);
        }

    }

    private void handleChatMessage(SocketChannel clientChannel, String senderId, String message) {
        try {
            // For now, just acknowledge receipt
            String response = "CHAT_RECEIVED|" + senderId + "\n";
            ByteBuffer buffer = ByteBuffer.wrap(response.getBytes());

            while (buffer.hasRemaining()) {
                clientChannel.write(buffer);
            }

            Log.logInfo("Chat message received from " + senderId + ": " + message);
            // TODO: Store message for frontend retrieval
        } catch (IOException e) {
            Log.logError("Error handling chat message: " + e.getMessage(), e);
        }
    }

    public boolean sendChatMessage(String peerIp, int peerPort, String message) {
        try (Socket socket = this.createSocket(new PeerInfo(peerIp, peerPort))) {
            String senderId = this.SERVER_HOST.getIp() + ":" + this.SERVER_HOST.getPort();
            String request = "CHAT_MESSAGE|" + senderId + "|" + message + "\n";
            socket.getOutputStream().write(request.getBytes());

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String response = reader.readLine();

            if (response != null && response.startsWith("CHAT_RECEIVED")) {
                Log.logInfo("Chat message sent successfully to " + peerIp + ":" + peerPort);
                return true;
            } else {
                Log.logInfo("Failed to send chat message to " + peerIp + ":" + peerPort);
                return false;
            }
        } catch (IOException e) {
            Log.logError("Error sending chat message to " + peerIp + ":" + peerPort + ": " + e.getMessage(), e);
            return false;
        }
    }

//    public void loadSharedFiles() {
//        String sharedFolderPath = AppPaths.getAppDataDirectory() + "/shared_files/";
//        File file = new File(sharedFolderPath);
//        if (file.exists() && file.isDirectory()) {
//            File[] files = file.listFiles();
//            if (files != null && files.length != 0) {
//                Log.logInfo("List of shared files in directory: " + file.getAbsolutePath());
//
//                for (File f : files) {
//                    if (f.isFile()) {
//                        String fileName = f.getName();
//                        long fileSize = f.length();
//                        Log.logInfo(" - " + fileName + " (" + fileSize + " bytes)");
//                        String fileHash = this.computeFileHash(f);
//                        if (fileHash.equals("ERROR")) {
//                            Log.logInfo("Error computing hash for file: " + fileName);
//                        } else {
//                            FileInfo fileInfo = new FileInfo(fileName, fileSize, fileHash, this.SERVER_HOST, true);
//                            this.publicSharedFiles.put(fileName, fileInfo);
//                            this.sharedFileNames.add(fileInfo); // Add to sharedFileNames so it's included in API responses
//                        }
//                    }
//                }
//
//            } else {
//                Log.logInfo("No shared files found in directory: " + file.getAbsolutePath());
//            }
//        } else {
//            Log.logInfo("Shared directory does not exist or is not a directory: " + sharedFolderPath);
//            file.mkdir();
//        }
//    }

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
                                boolean isSharedByMe = this.publicSharedFiles.containsKey(fileName) || this.privateSharedFiles.contains(fileName);
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

    public Map<FileInfo, Set<PeerInfo>> getPrivateSharedFiles() {
        return privateSharedFiles;
    }

    public int stopSharingFile(String fileName) {
        if (!this.publicSharedFiles.containsKey(fileName)) {
            Log.logInfo("File not found in shared files: " + fileName);
            return 2;
        } else {
            FileInfo fileInfo = this.publicSharedFiles.get(fileName);
            this.publicSharedFiles.remove(fileName);
            this.sharedFileNames.removeIf((file) -> file.getFileName().equals(fileName));
            String appPath = AppPaths.getAppDataDirectory();
            String filePath = appPath + "/shared_files/" + fileName;
            File file = new File(filePath);
            if (file.exists() && file.delete()) {
                Log.logInfo("Removed shared file: " + filePath);
            } else {
                Log.logInfo("Failed to remove shared file: " + filePath);
            }

            Log.logInfo("Stopped sharing file: " + fileName);
            return this.unshareFile(fileInfo);
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
                Log.logInfo("Public shared files loaded successfully. " + this.publicSharedFiles.toString() + " files found.");
            } catch (IOException | ClassNotFoundException e) {
                Log.logError("Error loading public shared files: " + e.getMessage(), e);
            }
        }

        // Load private shared files
        File privateFile = new File(dataDirPath + File.separator + "privateSharedFiles.dat");
        if (privateFile.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(privateFile))) {
                this.privateSharedFiles = (ConcurrentHashMap<FileInfo, Set<PeerInfo>>) ois.readObject();
                Log.logInfo("Private shared files loaded successfully. " + this.privateSharedFiles.toString() + " files found.");
            } catch (IOException | ClassNotFoundException e) {
                Log.logError("Error loading private shared files: " + e.getMessage(), e);
            }
        }
    }

    {
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveData));
    }
}
