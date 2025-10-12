package main.java.model;

import com.google.gson.reflect.TypeToken;
import main.java.domain.adapter.FileInfoAdapter;
import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.domain.entity.ProgressInfo;
import main.java.utils.AppPaths;
import main.java.utils.Config;
import main.java.utils.Log;
import main.java.utils.LogTag;
import main.java.utils.SSLUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.net.ssl.*;
import java.io.PrintWriter;

import java.io.*;
import java.lang.reflect.Type;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
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
    private Selector selector;
    private SSLContext sslContext;
    private ConcurrentHashMap<SocketChannel, SSLEngine> sslEngineMap;
    private ConcurrentHashMap<SocketChannel, ByteBuffer> pendingData;
    private ConcurrentHashMap<SocketChannel, Map<String, Object>> channelAttachments;
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
        this.CHUNK_SIZE = Config.CHUNK_SIZE;
        this.SERVER_HOST = new PeerInfo(Config.SERVER_IP, Config.PEER_PORT);
        this.TRACKER_HOST = new PeerInfo(Config.TRACKER_IP, Config.TRACKER_PORT);
        this.openChannels = new ConcurrentHashMap<>();
        this.futures = new ConcurrentHashMap<>();
        this.processes = new ConcurrentHashMap<>();
        this.fileLock = new ReentrantLock();
        this.publicSharedFiles = new ConcurrentHashMap<>();
        this.privateSharedFiles = new ConcurrentHashMap<>();
        loadData();
        this.sharedFileNames = new HashSet<>();
        this.executor = Executors.newFixedThreadPool(8);
        this.isRunning = true;

        // Initialize SSL certificates - generate key pair and request signing if not exists
        if (!SSLUtils.initializeSSLCertificates()) {
            Log.logError("Failed to initialize SSL certificates! SSL is mandatory for secure communication.", null);
            throw new RuntimeException("SSL certificate initialization failed");
        }

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

    public void initializeServerSocket() throws Exception {
        // Initialize non-blocking SSL server with NIO
        this.selector = Selector.open();
        this.sslContext = SSLUtils.createSSLContext();
        this.sslEngineMap = new ConcurrentHashMap<>();
        this.pendingData = new ConcurrentHashMap<>();
        this.channelAttachments = new ConcurrentHashMap<>();

        // Create server socket channel for non-blocking operation
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.socket().bind(new InetSocketAddress(this.SERVER_HOST.getIp(), this.SERVER_HOST.getPort()));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        Log.logInfo("Non-blocking SSL server socket initialized on " + this.SERVER_HOST.getIp() + ":" + this.SERVER_HOST.getPort());
    }

    public void startServer() {
        this.executor.submit(() -> {
            Log.logInfo("Starting non-blocking SSL server loop...");

            try {
                while (this.isRunning) {
                    int readyChannels = selector.select(1000L); // Wait for events with 1 second timeout

                    if (readyChannels > 0 || pendingData.values().stream().anyMatch(buff -> buff != null && buff.hasRemaining())) {
                        for (SelectionKey key : selector.selectedKeys()) {
                            try {
                                if (key.isAcceptable()) {
                                    acceptSSLConnection(key);
                                } else if (key.isReadable()) {
                                    handleSSLRead(key);
                                } else if (key.isWritable()) {
                                    handleSSLWrite(key);
                                }
                            } catch (Exception e) {
                                Log.logError("Error handling key: " + e.getMessage(), e);
                                closeChannel(key.channel());
                                key.cancel();
                            }
                        }
                        selector.selectedKeys().clear();

                        // Process any pending handshake operations
                        processPendingHandshakes();
                    }
                }
            } catch (IOException serverException) {
                Log.logError("SSL server error: " + serverException.getMessage(), serverException);
            } finally {
                this.shutdown();
            }
        });
    }

    private void shutdown() {
        Log.logInfo("Shutting down SSL server...");
        this.isRunning = false;

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

        Log.logInfo("SSL server shutdown complete.");
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

    private void acceptSSLConnection(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel socketChannel = serverChannel.accept();

        if (socketChannel != null) {
            socketChannel.configureBlocking(false);
            Log.logInfo("Accepted SSL connection from: " + socketChannel.getRemoteAddress());

            // Create SSLEngine for this connection
            SSLEngine sslEngine = sslContext.createSSLEngine();
            sslEngine.setUseClientMode(false);
            sslEngine.setNeedClientAuth(false);

            // Set buffer sizes
            SSLSession session = sslEngine.getSession();
            int netBufferSize = session.getPacketBufferSize();
            int appBufferSize = session.getApplicationBufferSize();

            // Create buffers
            ByteBuffer netInBuffer = ByteBuffer.allocate(netBufferSize);
            ByteBuffer netOutBuffer = ByteBuffer.allocate(netBufferSize);
            ByteBuffer appInBuffer = ByteBuffer.allocate(appBufferSize);
            ByteBuffer appOutBuffer = ByteBuffer.allocate(appBufferSize);

            // Create a map for per-connection data
            Map<String, Object> connectionData = new HashMap<>();
            connectionData.put("netInBuffer", netInBuffer);
            connectionData.put("appInBuffer", appInBuffer);
            connectionData.put("appOutBuffer", appOutBuffer);

            channelAttachments.put(socketChannel, connectionData);
            sslEngineMap.put(socketChannel, sslEngine);

            // Register for read operations
            socketChannel.register(selector, SelectionKey.OP_READ, netInBuffer);

            // Begin SSL handshake
            sslEngine.beginHandshake();

            Log.logInfo("SSL connection setup complete for: " + socketChannel.getRemoteAddress());
        }
    }

    private void handleSSLRead(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        SSLEngine sslEngine = sslEngineMap.get(socketChannel);

        if (sslEngine == null) {
            Log.logError("SSL engine not found for channel: " + socketChannel, null);
            closeChannel(socketChannel);
            return;
        }

        ByteBuffer netInBuffer = (ByteBuffer) key.attachment();

        try {
            int bytesRead = socketChannel.read(netInBuffer);
            if (bytesRead == -1) {
                Log.logInfo("Client closed connection: " + socketChannel.getRemoteAddress());
                closeChannel(socketChannel);
                return;
            }

            if (bytesRead > 0) {
                netInBuffer.flip();
                processSSLData(socketChannel, sslEngine, netInBuffer);
                netInBuffer.compact();
            }
        } catch (IOException e) {
            Log.logError("Error reading from SSL channel: " + e.getMessage(), e);
            closeChannel(socketChannel);
        }
    }

    private void handleSSLWrite(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        ByteBuffer pendingData = this.pendingData.get(socketChannel);

        if (pendingData != null && pendingData.hasRemaining()) {
            int bytesWritten = socketChannel.write(pendingData);
            if (bytesWritten == 0) {
                Log.logInfo("Cannot write more data to channel: " + socketChannel);
            }
            if (!pendingData.hasRemaining()) {
                this.pendingData.remove(socketChannel);
                key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            }
        } else {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
        }
    }

    private void processSSLData(SocketChannel socketChannel, SSLEngine sslEngine, ByteBuffer netInBuffer) {
        Map<String, Object> connectionData = channelAttachments.get(socketChannel);
        if (connectionData == null) {
            Log.logError("No connection data found for channel", null);
            closeChannel(socketChannel);
            return;
        }

        ByteBuffer appInBuffer = (ByteBuffer) connectionData.get("appInBuffer");

        try {
            SSLEngineResult result = sslEngine.unwrap(netInBuffer, appInBuffer);

            switch (result.getStatus()) {
                case OK:
                    SSLEngineResult.HandshakeStatus handshakeStatus = result.getHandshakeStatus();
                    switch (handshakeStatus) {
                        case NOT_HANDSHAKING:
                            // Handshake complete, process application data
                            if (appInBuffer.hasRemaining()) {
                                processApplicationData(socketChannel, appInBuffer);
                            }
                            break;
                        case NEED_TASK:
                            runHandshakeTasks(sslEngine);
                            break;
                        case NEED_WRAP:
                        case NEED_UNWRAP:
                            // Continue handshake
                            break;
                        case FINISHED:
                            Log.logInfo("SSL handshake finished for: " + socketChannel.getRemoteAddress());
                            break;
                    }
                    break;
                case BUFFER_OVERFLOW:
                    Log.logInfo("SSL buffer overflow, resizing application buffer");
                    resizeApplicationBuffer(sslEngine, connectionData);
                    break;
                case BUFFER_UNDERFLOW:
                    Log.logInfo("SSL buffer underflow, need more data");
                    break;
                case CLOSED:
                    Log.logInfo("SSL engine closed for: " + socketChannel.getRemoteAddress());
                    closeChannel(socketChannel);
                    break;
            }
        } catch (Exception e) {
            Log.logError("SSL error processing data: " + e.getMessage(), e);
            closeChannel(socketChannel);
        }
    }

    private void processApplicationData(SocketChannel socketChannel, ByteBuffer appBuffer) {
        // Extract the request
        StringBuilder requestBuilder = new StringBuilder();
        while (appBuffer.hasRemaining()) {
            char c = (char) appBuffer.get();
            requestBuilder.append(c);
            if (requestBuilder.toString().endsWith("\n")) {
                break;
            }
        }

        String completeRequest = requestBuilder.toString().trim();
        if (!completeRequest.isEmpty()) {
            try {
                Log.logInfo("Received SSL application data: [" + completeRequest + "] from " + socketChannel.getRemoteAddress());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            try {
                String clientIP = socketChannel.getRemoteAddress().toString().split(":")[0].replace("/", "");
                PeerInfo clientIdentifier = new PeerInfo(clientIP, SERVER_HOST.getPort());

                String response = processSSLRequest(clientIdentifier, completeRequest);
                sendSSLResponse(socketChannel, response);
            } catch (Exception e) {
                Log.logError("Error processing SSL request: " + e.getMessage(), e);
            }
        }
    }

    private String processSSLRequest(PeerInfo clientIdentifier, String request) {
        if (request.startsWith("SEARCH")) {
            String fileName = request.split("\\|")[1];
            FileInfo fileInfo = this.publicSharedFiles.get(fileName);
            if (fileInfo != null) {
                return "FILE_INFO|" + fileName + "|" + fileInfo.getFileSize() + "|" +
                        fileInfo.getPeerInfo().getIp() + "|" + fileInfo.getPeerInfo().getPort() + "|" +
                        fileInfo.getFileHash() + "\n";
            } else {
                return "FILE_NOT_FOUND|" + fileName + "\n";
            }
        } else if (request.startsWith("GET_CHUNK")) {
            String[] requestParts = request.split("\\|");
            String fileHash = requestParts[1];
            int chunkIndex = Integer.parseInt(requestParts[2]);
            if (this.hasAccessToFile(clientIdentifier, fileHash)) {
                return getChunkData(fileHash, chunkIndex);
            } else {
                return "ACCESS_DENIED\n";
            }
        } else if (request.startsWith("CHAT_MESSAGE")) {
            String[] messageParts = request.split("\\|", 3);
            if (messageParts.length >= 3) {
                Log.logInfo("Processing SSL chat message from " + messageParts[1] + ": " + messageParts[2]);
                return "CHAT_RECEIVED|" + messageParts[1] + "\n";
            } else {
                return "ERROR|Invalid chat format\n";
            }
        }

        return "UNKNOWN_REQUEST\n";
    }

    private String getChunkData(String fileHash, int chunkIndex) {
        FileInfo fileInfo = null;
        for (FileInfo file : this.publicSharedFiles.values()) {
            if (file.getFileHash().equals(fileHash)) {
                fileInfo = file;
                break;
            }
        }

        if (fileInfo == null) {
            return "FILE_NOT_FOUND\n";
        }

        String appPath = AppPaths.getAppDataDirectory();
        String filePath = appPath + "/shared_files/" + fileInfo.getFileName();
        File file = new File(filePath);

        if (file.exists() && file.canRead()) {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                byte[] chunkData = new byte[this.CHUNK_SIZE];
                raf.seek((long) chunkIndex * (long) this.CHUNK_SIZE);
                int byteRead = raf.read(chunkData);

                if (byteRead > 0) {
                    // Convert to string format for transmission
                    return "CHUNK_DATA|" + chunkIndex + "|" + Base64.getEncoder().encodeToString(chunkData) + "\n";
                }
            } catch (IOException e) {
                Log.logError("Error reading chunk data: " + e.getMessage(), e);
            }
        }

        return "CHUNK_ERROR\n";
    }

    private void sendSSLResponse(SocketChannel socketChannel, String response) {
        SSLEngine sslEngine = sslEngineMap.get(socketChannel);
        if (sslEngine == null) {
            Log.logError("SSL engine not found for response", null);
            return;
        }

        try {
            ByteBuffer responseBuffer = ByteBuffer.wrap(response.getBytes());
            // Use the existing wrap method or implement SSL wrapping here
            // This is a simplified version - in a full implementation, you'd wrap the data
            ByteBuffer netBuffer = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
            SSLEngineResult result = sslEngine.wrap(responseBuffer, netBuffer);

            if (result.getStatus() == SSLEngineResult.Status.OK) {
                netBuffer.flip();
                pendingData.put(socketChannel, netBuffer);

                // Register for write operations
                SelectionKey key = socketChannel.keyFor(selector);
                if (key != null) {
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                }
            }
        } catch (SSLException e) {
            Log.logError("Error sending SSL response: " + e.getMessage(), e);
        }
    }

    private void runHandshakeTasks(SSLEngine sslEngine) {
        Runnable task;
        while ((task = sslEngine.getDelegatedTask()) != null) {
            executor.submit(task);
        }
    }

    private void resizeApplicationBuffer(SSLEngine sslEngine, Map<String, Object> connectionData) {
        SSLSession session = sslEngine.getSession();
        int appBufferSize = session.getApplicationBufferSize();
        ByteBuffer oldBuffer = (ByteBuffer) connectionData.get("appInBuffer");
        ByteBuffer newBuffer = ByteBuffer.allocate(appBufferSize);
        oldBuffer.flip();
        newBuffer.put(oldBuffer);
        connectionData.put("appInBuffer", newBuffer);
    }

    private void processPendingHandshakes() {
        // Process any pending SSL handshake operations
        // Process any pending SSL handshake operations
        for (Map.Entry<SocketChannel, SSLEngine> entry : sslEngineMap.entrySet()) {
            SocketChannel channel = entry.getKey();
            SSLEngine engine = entry.getValue();

            if (engine.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                try {
                    runHandshakeTasks(engine);
                } catch (Exception e) {
                    Log.logError("Error processing handshake tasks: " + e.getMessage(), e);
                    closeChannel(channel);
                }
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

    private void sendFileInforSSL(SSLSocket sslSocket, String fileName) {
        try {
            FileInfo fileInfo = this.publicSharedFiles.get(fileName);
            String response;
            if (fileInfo != null) {
                response = "FILE_INFO|" + fileName + "|" + fileInfo.getFileSize() + "|" + fileInfo.getPeerInfo().getIp()
                        + "|" + fileInfo.getPeerInfo().getPort() + "|" + fileInfo.getFileHash() + "\n";
            } else {
                response = "FILE_NOT_FOUND|" + fileName + "\n";
            }
            sslSocket.getOutputStream().write(response.getBytes());
            Log.logInfo("Sent SSL file information for: " + fileName + ", response: [" + response.trim() + "]");
        } catch (IOException sendException) {
            Log.logError("Error sending file info via SSL: " + sendException.getMessage(), sendException);
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
        // SSL is now mandatory - throw error if certificates not available
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        int count = 0;

        while (count < Config.MAX_RETRIES) {
            SSLSocket sslSocket = null;
            try {
                // Create SSL connection to tracker (now mandatory)
                PeerInfo sslTrackerHost = new PeerInfo(this.TRACKER_HOST.getIp(), Config.TRACKER_PORT);
                sslSocket = createSecureSocket(sslTrackerHost);
                Log.logInfo("Established SSL connection to tracker");

                // Send registration message over SSL
                Gson gson = new GsonBuilder().registerTypeAdapter(FileInfo.class, new FileInfoAdapter())
                        .enableComplexMapKeySerialization().create();
                Type publicFileType = new TypeToken<Map<String, FileInfo>>() {
                }.getType();
                String publicFileToPeersJson = gson.toJson(publicSharedFiles, publicFileType);
                Type privateFileType = new TypeToken<Map<FileInfo, Set<PeerInfo>>>() {
                }.getType();
                String privateSharedFileJson = gson.toJson(privateSharedFiles, privateFileType);

                String message = "REGISTER|" + this.SERVER_HOST.getIp() + "|" + this.SERVER_HOST.getPort() +
                        "|" + publicFileToPeersJson +
                        "|" + privateSharedFileJson +
                        "\n";
                sslSocket.getOutputStream().write(message.getBytes());
                Log.logInfo("Registered with tracker via SSL with data structures: " + message);
                BufferedReader reader = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));
                String response = reader.readLine();
                Log.logInfo("SSL Tracker response: " + response);
                if (response != null) {
                    if (response.startsWith("REGISTERED")) {
                        return LogTag.I_NOT_FOUND;
                    } else if (response.startsWith("SHARED_LIST")) {
                        Log.logInfo("SSL Tracker registration successful: " + response);
                        return 1;
                    } else {
                        Log.logInfo("SSL Tracker registration failed: " + response);
                        return 0;
                    }
                } else {
                    Log.logInfo("SSL Tracker did not respond.");
                    return 0;
                }
            } catch (ConnectException e) {
                Log.logError("SSL Tracker connection failed: " + e.getMessage(), e);
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                ++count;
            } catch (IOException e) {
                Log.logError("SSL Error connecting to tracker: " + e.getMessage(), e);
                return 0;
            } catch (Exception e) {
                Log.logError("SSL Unexpected error during tracker registration: " + e.getMessage(), e);
                return 0;
            } finally {
                if (sslSocket != null) {
                    try {
                        sslSocket.close();
                        Log.logInfo("SSL socket closed after registration attempt");
                    } catch (IOException e) {
                        Log.logError("Error closing SSL socket: " + e.getMessage(), e);
                    }
                }
            }
        }

        Log.logError("Cannot establish SSL connection to tracker after multiple attempts.", null);
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
        // SSL is now mandatory for all communications
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        try {
            // Create SSL connection to tracker (now mandatory)
            PeerInfo sslTrackerHost = new PeerInfo(this.TRACKER_HOST.getIp(), Config.TRACKER_PORT);
            SSLSocket sslSocket = createSecureSocket(sslTrackerHost);
            Socket socket = sslSocket; // Cast for compatibility
            Log.logInfo("Established SSL connection to tracker for sharing files");
            StringBuilder messageBuilder = new StringBuilder("SHARE|");
            messageBuilder.append(publicFiles.size()).append("|")
                    .append(privateFiles.size()).append("|");

            Gson gson = new GsonBuilder().registerTypeAdapter(FileInfo.class, new FileInfoAdapter())
                    .enableComplexMapKeySerialization().create();
            Type listType = new TypeToken<List<FileInfo>>() {
            }.getType();
            String publicFileToPeersJson = gson.toJson(publicFiles, listType);
            Type mapType = new TypeToken<Map<FileInfo, Set<PeerInfo>>>() {
            }.getType();
            String privateSharedFileJson = gson.toJson(privateFiles, mapType);

            messageBuilder.append(publicFileToPeersJson).append("|")
                    .append(privateSharedFileJson).append("\n");

            String message = messageBuilder.toString();
            Log.logInfo("Sent request: " + message);
            socket.getOutputStream().write(message.getBytes());
            // response
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String response = reader.readLine();
            Log.logInfo("Shared file list with tracker, response: " + response);
            return response.startsWith(LogTag.S_SUCCESS);
        } catch (Exception e) {
            Log.logError("Error sharing file list with tracker: " + e.getMessage(), e);
            return false;
        }
    }

    // SSL is now mandatory for all communications
    public List<String> getKnownPeers() {
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        try {
            // Create SSL connection to tracker (now mandatory)
            PeerInfo sslTrackerHost = new PeerInfo(this.TRACKER_HOST.getIp(), Config.TRACKER_PORT);
            SSLSocket sslSocket = createSecureSocket(sslTrackerHost);
            Log.logInfo("Established SSL connection to tracker for getting known peers");

            String request = "GET_KNOWN_PEERS\n";
            sslSocket.getOutputStream().write(request.getBytes());
            Log.logInfo("SSL Requesting known peers from tracker, message: " + request);
            BufferedReader buff = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));
            String response = buff.readLine();
            if (response == null || response.isEmpty()) {
                Log.logInfo("No SSL response from tracker for known peers");
                return Collections.emptyList();
            } else {
                String[] parts = response.split("\\|");
                if (parts.length != 3 || !parts[0].equals("GET_KNOWN_PEERS")) {
                    Log.logInfo("Invalid SSL response format from tracker: " + response);
                    return Collections.emptyList();
                } else {
                    int peerCount = Integer.parseInt(parts[1]);
                    if (peerCount == 0) {
                        Log.logInfo("No known peers found via SSL");
                        return Collections.emptyList();
                    } else {
                        String[] peerParts = parts[2].split(",");
                        ArrayList<String> peerInfos = new ArrayList<>();
                        Collections.addAll(peerInfos, peerParts);
                        if (peerInfos.isEmpty()) {
                            Log.logInfo("No valid known peers found via SSL");
                            return Collections.emptyList();
                        } else if (peerCount != peerInfos.size()) {
                            Log.logInfo("Known peer count mismatch via SSL: expected " + peerCount + ", found " + peerInfos.size());
                            return Collections.emptyList();
                        } else {
                            Log.logInfo("Received known peers from tracker via SSL: " + response);
                            return peerInfos;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.logError("SSL Error getting known peers from tracker", e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<FileInfo> getPublicFiles() {
        List<FileInfo> fileInfos = new ArrayList<>(publicSharedFiles.values());
        return fileInfos;
    }

    private int unshareFile(FileInfo fileInfo) {
        // SSL is now mandatory for all communications
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        try {
            // Create SSL connection to tracker (now mandatory)
            PeerInfo sslTrackerHost = new PeerInfo(this.TRACKER_HOST.getIp(), Config.TRACKER_PORT);
            SSLSocket sslSocket = createSecureSocket(sslTrackerHost);
            Log.logInfo("Established SSL connection to tracker for unsharing file");

            String query = "UNSHARED_FILE" + "|" + fileInfo.getFileName() + "|" + fileInfo.getFileSize() + "|" + fileInfo.getFileHash() + "|" + this.SERVER_HOST.getIp() + "|" + this.SERVER_HOST.getPort();
            sslSocket.getOutputStream().write(query.getBytes());
            Log.logInfo("Notified tracker about shared file: " + fileInfo.getFileName() + " via SSL, message: " + query);

            sslSocket.close();
            return 1;

        } catch (Exception e) {
            Log.logError("SSL Error notifying tracker about shared file: " + fileInfo.getFileName(), e);
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
        // SSL is now mandatory for all communications
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        try {
            // Create SSL connection to tracker (now mandatory)
            PeerInfo sslTrackerHost = new PeerInfo(this.TRACKER_HOST.getIp(), Config.TRACKER_PORT);
            SSLSocket sslSocket = createSecureSocket(sslTrackerHost);
            Log.logInfo("Established SSL connection to tracker for getting peers with file hash");

            String request = "GET_PEERS|" + fileHash + "|" + SERVER_HOST.getIp() + "|" + SERVER_HOST.getPort() + "\n";
            sslSocket.getOutputStream().write(request.getBytes());
            Log.logInfo("SSL Requesting peers with file hash: " + fileHash + ", message: " + request);
            BufferedReader buff = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));
            String response = buff.readLine();
            if (response == null || response.isEmpty()) {
                Log.logInfo("No SSL response from tracker for file hash: " + fileHash);
                return Collections.emptyList();
            } else {
                String[] parts = response.split("\\|");
                if (parts.length != 3 || !parts[0].equals("GET_PEERS")) {
                    Log.logInfo("Invalid SSL response format from tracker: " + response);
                    return Collections.emptyList();
                } else {
                    int peerCount = Integer.parseInt(parts[1]);
                    if (peerCount == 0) {
                        Log.logInfo("No peers found via SSL for file hash: " + fileHash);
                        return Collections.emptyList();
                    } else {
                        String[] peerInfos = parts[2].split(",");
                        ArrayList<PeerInfo> peers = new ArrayList<>();

                        for (String peerInfo : peerInfos) {
                            String[] peerParts = peerInfo.split("'");
                            if (peerParts.length != 2) {
                                Log.logInfo("Invalid peer info format via SSL: " + peerInfo);
                            } else {
                                String ip = peerParts[0];
                                int port = Integer.parseInt(peerParts[1]);
                                peers.add(new PeerInfo(ip, port));
                            }
                        }

                        if (peers.isEmpty()) {
                            Log.logInfo("No valid peers found via SSL for file hash: " + fileHash);
                            return Collections.emptyList();
                        } else if (peerCount != peers.size()) {
                            Log.logInfo("Peer count mismatch via SSL: expected " + peerCount + ", found " + peers.size());
                            return Collections.emptyList();
                        } else {
                            Log.logInfo("Received SSL response from tracker: " + response);
                            return peers;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.logError("SSL Error getting peers with file hash: " + fileHash, e);
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

                SSLSocket sslSocket = null;

                try {
                    // Create SSL connection to peer now mandatory
                    sslSocket = createSecureSocket(peerInfo);
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
                } finally {
                    if (sslSocket != null) {
                        try {
                            sslSocket.close();
                        } catch (IOException e) {
                            Log.logError("Error closing SSL socket: " + sslSocket, e);
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

                SSLSocket sslSocket = null;

                try {
                    // Create SSL connection to peer now mandatory
                    sslSocket = createSecureSocket(peerInfo);
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
                } finally {
                    if (sslSocket != null) {
                        try {
                            sslSocket.close();
                        } catch (IOException e) {
                            Log.logError("Error closing SSL socket: " + sslSocket, e);
                        }
                    }
                }
            }

            Log.logInfo("Failed to download chunk " + chunkIndex + " from peer " + peerInfo + " after " + retryCount + " attempts.");
            return false;
        }
    }

    private void sendChunkSSL(SSLSocket sslSocket, String fileHash, int chunkIndex) {
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
                try {
                    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                        byte[] chunkbyte = new byte[this.CHUNK_SIZE];
                        raf.seek((long) chunkIndex * (long) this.CHUNK_SIZE);
                        int byteReads = raf.read(chunkbyte);
                        if (byteReads <= 0) {
                            Log.logInfo("No bytes read for chunk " + chunkIndex + " of file " + fileInfo.getFileName() + ". File may be empty or chunk index is out of bounds.");
                        } else {
                            DataOutputStream dos = new DataOutputStream(sslSocket.getOutputStream());
                            dos.writeInt(chunkIndex);  // Send chunk index as 4 bytes
                            dos.write(chunkbyte, 0, byteReads);  // Send chunk data
                            dos.flush();

                            Log.logInfo("SSL Chunk " + chunkIndex + " of file " + fileInfo.getFileName() + " for " + fileHash + " sent successfully. Bytes sent: " + (byteReads + 4));
                        }
                    }
                } catch (IOException e) {
                    Log.logError("Error sending chunk " + chunkIndex + " of file " + fileInfo.getFileName() + " via SSL: " + e.getMessage(), e);
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

    private void sendAccessDeniedSSL(SSLSocket sslSocket) {
        try {
            String message = "ACCESS_DENIED\n";
            sslSocket.getOutputStream().write(message.getBytes());
            Log.logInfo("SSL Access denied response sent to client");
        } catch (IOException e) {
            Log.logError("Error sending access denied response via SSL: " + e.getMessage(), e);
        }
    }

    private void handleChatMessageSSL(SSLSocket sslSocket, String senderId, String message) {
        try {
            // For now, just acknowledge receipt
            String response = "CHAT_RECEIVED|" + senderId + "\n";
            sslSocket.getOutputStream().write(response.getBytes());
            Log.logInfo("SSL Chat message received from " + senderId + ": " + message);
            // TODO: Store message for frontend retrieval
        } catch (IOException e) {
            Log.logError("Error handling chat message via SSL: " + e.getMessage(), e);
        }
    }

    public boolean sendChatMessage(String peerIp, int peerPort, String message) {
        // SSL is now mandatory for all communications
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        try (SSLSocket sslSocket = this.createSecureSocket(new PeerInfo(peerIp, peerPort))) {
            String senderId = this.SERVER_HOST.getIp() + ":" + this.SERVER_HOST.getPort();
            String request = "CHAT_MESSAGE|" + senderId + "|" + message + "\n";
            sslSocket.getOutputStream().write(request.getBytes());

            BufferedReader reader = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));
            String response = reader.readLine();

            if (response != null && response.startsWith("CHAT_RECEIVED")) {
                Log.logInfo("SSL Chat message sent successfully to " + peerIp + ":" + peerPort);
                return true;
            } else {
                Log.logInfo("SSL Failed to send chat message to " + peerIp + ":" + peerPort);
                return false;
            }
        } catch (Exception e) {
            Log.logError("SSL Error sending chat message to " + peerIp + ":" + peerPort + ": " + e.getMessage(), e);
            return false;
        }
    }

    public int refreshSharedFileNames() {
        // SSL is now mandatory for all communications
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        try {
            // Create SSL connection to tracker (now mandatory)
            PeerInfo sslTrackerHost = new PeerInfo(this.TRACKER_HOST.getIp(), Config.TRACKER_PORT);
            SSLSocket sslSocket = createSecureSocket(sslTrackerHost);
            Log.logInfo("Established SSL connection to tracker for refreshing shared files");

            PrintWriter printWriter = new PrintWriter(sslSocket.getOutputStream(), true);
            String ip = this.SERVER_HOST.getIp();
            String request = "REFRESH|" + ip + "|" + this.SERVER_HOST.getPort() + "\n";
            printWriter.println(request);
            BufferedReader buffer = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));
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

    private SSLSocket createSecureSocket(PeerInfo peerInfo) throws Exception {
        SSLSocketFactory sslSocketFactory = SSLUtils.createSSLSocketFactory();
        SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(peerInfo.getIp(), peerInfo.getPort());
        sslSocket.setUseClientMode(true);
        sslSocket.setNeedClientAuth(true);
        sslSocket.setSoTimeout(Config.SOCKET_TIMEOUT_MS);
        sslSocket.startHandshake();
        return sslSocket;
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
