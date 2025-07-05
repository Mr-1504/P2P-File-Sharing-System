package main.java.model;

import javafx.application.Platform;
import main.java.utils.*;
import main.java.view.P2PView;

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

import static main.java.utils.Infor.SOCKET_TIMEOUT_MS;
import static main.java.utils.Log.*;

public class PeerModel {
    private final int CHUNK_SIZE = Infor.CHUNK_SIZE;
    private final PeerInfor SERVER_HOST = new PeerInfor(Infor.SERVER_IP, Infor.SERVER_PORT);
    private final PeerInfor TRACKER_HOST = new PeerInfor(Infor.TRACKER_IP, Infor.TRACKER_PORT);
    private final Selector selector;
    private final Map<String, FileInfor> mySharedFiles;
    private Set<FileInfor> sharedFileNames;
    private final ExecutorService executor;
    private final CopyOnWriteArrayList<SocketChannel> openChannels = new CopyOnWriteArrayList<>();
    private final List<Future<Boolean>> futures = new ArrayList<>();
    private boolean isRunning;
    private final P2PView view;
    private volatile boolean cancelled = false;

    public PeerModel(P2PView view) throws IOException {
        this.view = view;
        this.view.setCancelAction(() -> {
            cancelled = true;
            for (Future<Boolean> future : futures) {
                future.cancel(true);
            }
            for (SocketChannel channel : openChannels) {
                try {
                    channel.close();
                } catch (IOException e) {
                    logError("Error closing channel: " + channel, e);
                }
            }
            openChannels.clear();
            futures.clear();
        });
        this.view.setCancelButtonEnabled(false);
        mySharedFiles = new HashMap<>();
        sharedFileNames = new HashSet<>();
        executor = Executors.newFixedThreadPool(8);
        selector = Selector.open();
        initializeServerSocket();
        isRunning = true;
        logInfo("Server socket initialized on " + SERVER_HOST.getIp() + ":" + SERVER_HOST.getPort());
    }

    public void initializeServerSocket() throws IOException {
        ServerSocketChannel serverSocket = ServerSocketChannel.open();
        serverSocket.bind(new InetSocketAddress(SERVER_HOST.getIp(), SERVER_HOST.getPort()));
        serverSocket.configureBlocking(false);
        serverSocket.register(selector, SelectionKey.OP_ACCEPT);
        logInfo("Server socket created on " + SERVER_HOST.getIp() + ":" + SERVER_HOST.getPort());
    }

    public void startServer() {
        executor.submit(() -> {
            logInfo("Starting TCP server loop...");
            try {
                while (isRunning) {
                    selector.select();
                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();
                        if (!key.isValid()) continue;
                        try {
                            if (key.isAcceptable()) {
                                acceptConnection(key);
                            } else if (key.isReadable()) {
                                handleRead(key);
                            }
                        } catch (IOException ex) {
                            logError("Error processing key: " + key, ex);
                            key.cancel();
                            closeChannel(key.channel());
                        }
                    }
                }
            } catch (IOException e) {
                logError("TCP server error: " + e.getMessage(), e);
            } finally {
                shutdown();
            }
        });
    }

    private void shutdown() {
        logInfo("Shutting down TCP server...");
        isRunning = false;
        try {
            selector.close();
            logInfo("Selector closed.");
        } catch (IOException e) {
            logError("Error closing selector: " + e.getMessage(), e);
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                logInfo("Executor service forcefully shut down.");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            logError("Executor service interrupted during shutdown: " + e.getMessage(), e);
        }
        logInfo("TCP server shutdown complete.");
    }

    private void closeChannel(SelectableChannel channel) {
        if (channel != null && channel.isOpen()) {
            try {
                channel.close();
                logInfo("Closed channel: " + channel);
            } catch (IOException e) {
                logError("Error closing channel: " + channel, e);
            }
        }
    }

    public void startUDPServer() {
        executor.submit(() -> {
            try (DatagramSocket socket = new DatagramSocket(SERVER_HOST.getPort())) {
                byte[] buffer = new byte[1024];
                logInfo("UDP server started on " + SERVER_HOST.getIp() + ":" + SERVER_HOST.getPort());

                while (isRunning) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    String request = new String(packet.getData(), 0, packet.getLength());
                    logInfo("Received UDP request: " + request);
                    if (request.equals(RequestInfor.PING)) {
                        sendPongResponse(socket, packet);
                    }
                }
            } catch (IOException e) {
                logError("UDP server error: " + e.getMessage(), e);
            }
        });
    }

    private void sendPongResponse(DatagramSocket socket, DatagramPacket packet) throws IOException {
        byte[] pong = RequestInfor.PONG.getBytes();
        DatagramPacket resonsePacket = new DatagramPacket(pong, pong.length, packet.getAddress(), packet.getPort());
        socket.send(resonsePacket);
        logInfo("Pong response sent to " + packet.getAddress() + ":" + packet.getPort());
    }

    private void handleRead(SelectionKey key) {
        SocketChannel client = (SocketChannel) key.channel();
        try {
            StringBuilder requestBuilder = new StringBuilder();
            int initialBufferSize = 8192;
            ByteBuffer buffer = ByteBuffer.allocate(initialBufferSize);
            logInfo("Handling read operation for key: " + key + ", client: " + client.getRemoteAddress());
            int totalBytesRead = 0;

            while (true) {
                buffer.clear();
                int bytesRead = client.read(buffer);
                logInfo("Read " + bytesRead + " bytes from " + client.getRemoteAddress());
                if (bytesRead == -1) {
                    logInfo("Client closed connection");
                    break;
                }
                if (bytesRead == 0 && totalBytesRead > 0) {
                    logInfo("No more data to read, processing request");
                    break;
                }
                if (bytesRead == 0) {
                    Thread.sleep(100);
                    continue;
                }
                totalBytesRead += bytesRead;
                buffer.flip();
                String chunk = new String(buffer.array(), 0, buffer.limit());
                requestBuilder.append(chunk);
                logInfo("Current request: [" + requestBuilder + "]");
                if (requestBuilder.toString().contains("\n")) {
                    logInfo("Request complete, breaking loop");
                    break;
                }
                if (bytesRead == buffer.capacity()) {
                    initialBufferSize *= 2;
                    logInfo("Increasing buffer size to " + initialBufferSize + " bytes");
                    buffer = ByteBuffer.allocate(initialBufferSize);
                }
            }

            String request = requestBuilder.toString().trim();
            logInfo("Final request: [" + request + "]");
            if (request.isEmpty()) {
                logInfo("Empty request received");
                return;
            }
            if (request.startsWith(RequestInfor.SEARCH)) {
                String fileName = request.split("\\|")[1];
                logInfo("Processing SEARCH for file: " + fileName);
                sendFileInfor(client, fileName);
            } else if (request.startsWith(RequestInfor.GET_CHUNK)) {
                String[] parts = request.split("\\|");
                String fileName = parts[1];
                int chunkIndex = Integer.parseInt(parts[2]);
                logInfo("Processing GET_CHUNK for file: " + fileName + ", chunk: " + chunkIndex);
                sendChunk(client, fileName, chunkIndex);
            } else {
                logInfo("Unknown request: [" + request + "]");
            }
        } catch (IOException | InterruptedException e) {
            logError("Error handling read: " + e.getMessage(), e);
        } finally {
            try {
                client.close();
                key.cancel();
                logInfo("Closed client connection: " + client.getLocalAddress() + " -> " + client.getRemoteAddress());
            } catch (IOException e) {
                logError("Error closing client: " + e.getMessage(), e);
            }
        }
    }

    private void sendFileInfor(SocketChannel client, String fileName) {
        try {
            FileInfor fileInfor = mySharedFiles.get(fileName);
            String response = getResponse(fileName, fileInfor);
            ByteBuffer buffer = ByteBuffer.wrap(response.getBytes());
            int totalBytesWritten = 0;
            while (buffer.hasRemaining()) {
                int bytesWritten = client.write(buffer);
                totalBytesWritten += bytesWritten;
                logInfo("Wrote " + bytesWritten + " bytes, total: " + totalBytesWritten);
            }
            logInfo("Sent file information for: " + fileName + ", response: [" + response.trim() + "]");
        } catch (IOException e) {
            logError("Error sending file info: " + e.getMessage(), e);
        }
    }

    private static String getResponse(String fileName, FileInfor fileInfor) {
        String response;
        if (fileInfor != null) {
            response = RequestInfor.FILE_INFO + Infor.FIELD_SEPARATOR + fileInfor.getFileName() + Infor.FIELD_SEPARATOR + fileInfor.getFileSize()
                    + Infor.FIELD_SEPARATOR + fileInfor.getPeerInfor().getIp() + Infor.FIELD_SEPARATOR + fileInfor.getPeerInfor().getPort()
                    + Infor.FIELD_SEPARATOR + fileInfor.getFileHash() + "\n";
        } else {
            response = RequestInfor.FILE_NOT_FOUND + Infor.FIELD_SEPARATOR + fileName + "\n";
        }
        return response;
    }

    private void acceptConnection(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        logInfo("Accepted connection from: " + client.getRemoteAddress());
        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ);
    }

    public int registerWithTracker() {
        for (int i = 0; i < Infor.MAX_RETRIES; i++) {
            try (Socket socket = createSocket(TRACKER_HOST)) {
                String message = RequestInfor.REGISTER + Infor.FIELD_SEPARATOR + SERVER_HOST.getIp() + Infor.FIELD_SEPARATOR + SERVER_HOST.getPort() + "\n";
                socket.getOutputStream().write(message.getBytes());
                logInfo("Registered with tracker: " + message);

                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String response = in.readLine();
                logInfo("check: " + response);
                if (response == null) {
                    logInfo("Tracker did not respond.");
                    return LogTag.I_ERROR;
                }
                if (response.startsWith(RequestInfor.SHARED_LIST)) {
                    logInfo("Tracker registration successful: " + response);

                    String[] parts = response.split("\\|");
                    if (parts.length < 2) {
                        logInfo("Invalid response format from tracker: " + response);
                        return LogTag.I_INVALID;
                    }
                    if (parts.length == 3) {
                        String[] sharedFilesList = parts[2].split(",");
                        logInfo("Files shared by tracker: " + Arrays.toString(sharedFilesList));
                        for (String fileInfo : sharedFilesList) {
                            String[] fileParts = fileInfo.split("'");
                            if (fileParts.length != 5) {
                                logInfo("Invalid file info format: " + fileInfo);
                                continue;
                            }
                            String fileName = fileParts[0];
                            long fileSize = Long.parseLong(fileParts[1]);
                            String fileHash = fileParts[2];
                            PeerInfor peerInfor = new PeerInfor(fileParts[3], Integer.parseInt(fileParts[4]));
                            sharedFileNames.add(new FileInfor(fileName, fileSize, fileHash, peerInfor));
                        }
                        logInfo("Total files shared by tracker: " + sharedFileNames.size());
                        return LogTag.I_SUCCESS;
                    } else {
                        logInfo("Invalid response format from tracker: " + response);
                        return LogTag.I_INVALID;
                    }
                } else if (response.startsWith(RequestInfor.REGISTERED)) {
                    return LogTag.I_NOT_FOUND;
                } else {
                    logInfo("Tracker registration failed or no response: " + response);
                    return LogTag.I_ERROR;
                }
            } catch (ConnectException e) {
                logError("Tracker connection failed: " + e.getMessage(), e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } catch (IOException e) {
                logError("Error connecting to tracker: " + e.getMessage(), e);
                return LogTag.I_ERROR;
            } catch (Exception e) {
                logError("Unexpected error during tracker registration: " + e.getMessage(), e);
                return LogTag.I_ERROR;
            }
        }
        logError("Cannot connect to tracker after multiple attempts.", null);
        return LogTag.I_ERROR;
    }

    public Future<Boolean> shareFileAsync(File file, String fileName) {
        return executor.submit(() -> {
            logInfo("File path: " + file.getAbsolutePath());

            try (InputStream is = new FileInputStream(file)) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[CHUNK_SIZE];
                int bytesRead;
                long totalBytes = file.length();
                long readBytes = 0;
                logInfo("Sharing file: " + file.getName() + ", size: " + totalBytes + " bytes");

                while ((bytesRead = is.read(buffer)) != -1) {
                    if (cancelled) {
                        logInfo("File sharing cancelled by user: " + file.getName());
                        Platform.runLater(() ->
                                view.updateProgress("Chia sẻ file " + file.getName() + " đã bị hủy", 0, 0, 0));

                        File sharedFile = new File(GetDir.getShareDir(file.getName()));
                        if (sharedFile.exists() && sharedFile.delete()) {
                            logInfo("Removed shared file: " + sharedFile.getAbsolutePath());
                        } else {
                            logInfo("Failed to remove shared file: " + sharedFile.getAbsolutePath());
                        }

                        cancelled = false;
                        return Boolean.FALSE;
                    }

                    md.update(buffer, 0, bytesRead);
                    readBytes += bytesRead;

                    int progress = (int) ((readBytes * 100.0) / totalBytes);
                    long finalReadBytes = readBytes;
                    Platform.runLater(() ->
                            view.updateProgress("Đang chia sẻ file " + fileName, progress, finalReadBytes, totalBytes));
                }

                String fullFileHash = bytesToHex(md.digest());
                logInfo("File " + file.getName() + " shared successfully with hash: " + fullFileHash);

                FileInfor fileInfor = new FileInfor(fileName, file.length(), fullFileHash, SERVER_HOST);
                mySharedFiles.put(file.getName(), fileInfor);

                notifyTracker(fileInfor, true);
                return Boolean.TRUE;

            } catch (IOException | NoSuchAlgorithmException e) {
                return Boolean.FALSE;
            }
        });
    }


    public void shareFileList() {
        Set<FileInfor> files = convertSharedFilesToFileBase();

        try (Socket socket = new Socket(TRACKER_HOST.getIp(), TRACKER_HOST.getPort())) {
            StringBuilder messageBuilder = new StringBuilder(RequestInfor.SHARED_LIST + Infor.FIELD_SEPARATOR);
            messageBuilder.append(files.size()).append(Infor.FIELD_SEPARATOR);
            for (FileInfor file : files) {
                messageBuilder.append(file.getFileName()).append("'")
                        .append(file.getFileSize()).append("'")
                        .append(file.getFileHash()).append(",");
            }
            if (messageBuilder.charAt(messageBuilder.length() - 1) == ',') {
                messageBuilder.deleteCharAt(messageBuilder.length() - 1);
            }
            messageBuilder.append(Infor.FIELD_SEPARATOR).append(SERVER_HOST.getIp())
                    .append(Infor.FIELD_SEPARATOR).append(SERVER_HOST.getPort()).append("\n");
            String message = messageBuilder.toString();

            socket.getOutputStream().write(message.getBytes());

            logInfo("Shared file list with tracker: " + message);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Set<FileInfor> convertSharedFilesToFileBase() {
        Set<FileInfor> fileList = new HashSet<>();

        for (Map.Entry<String, FileInfor> entry : mySharedFiles.entrySet()) {
            FileInfor fileInfor = entry.getValue();
            fileList.add(fileInfor);
        }

        return fileList;
    }

    private int notifyTracker(FileInfor fileInfor, boolean isShared) {
        try (Socket socket = new Socket(TRACKER_HOST.getIp(), TRACKER_HOST.getPort())) {
            String message = (isShared ? RequestInfor.SHARE : RequestInfor.UNSHARED_FILE)
                    + Infor.FIELD_SEPARATOR + fileInfor.getFileName() + Infor.FIELD_SEPARATOR
                    + fileInfor.getFileSize() + Infor.FIELD_SEPARATOR
                    + fileInfor.getFileHash() + Infor.FIELD_SEPARATOR
                    + SERVER_HOST.getIp() + Infor.FIELD_SEPARATOR + SERVER_HOST.getPort();

            socket.getOutputStream().write(message.getBytes());
            logInfo("Notified tracker about shared file: " + fileInfor.getFileName() + ", message: " + message);
            return LogTag.I_SUCCESS;
        } catch (IOException e) {
            logError("Error notifying tracker about shared file: " + fileInfor.getFileName(), e);
            return LogTag.I_ERROR;
        }
    }

    private String bytesToHex(byte[] digest) {
        StringBuilder sb = new StringBuilder();
        for (byte b : digest)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }


    public Future<Integer> downloadFile(FileInfor fileInfor, String savePath, List<PeerInfor> peers) {
        return executor.submit(() -> {
            try {
//                PriorityQueue<PeerInfor> peer
                File saveFile = new File(savePath);
                if (!saveFile.getParentFile().exists()) {
                    String errorMessage = "Lỗi: Thư mục lưu không tồn tại: " + saveFile.getParent();
                    logInfo(errorMessage);
                    return LogTag.I_NOT_EXIST;
                }
                if (!saveFile.getParentFile().canWrite()) {
                    String errorMessage = "Lỗi: Không có quyền ghi vào thư mục: " + saveFile.getParent();
                    logInfo(errorMessage);
                    return LogTag.I_NOT_PERMISSION;
                }

                try (RandomAccessFile raf = new RandomAccessFile(saveFile, "rw")) {
                    raf.setLength(fileInfor.getFileSize());
                    AtomicInteger progressCounter = new AtomicInteger(0);
                    int totalChunks = (int) (fileInfor.getFileSize() / CHUNK_SIZE) + (fileInfor.getFileSize() % CHUNK_SIZE == 0 ? 0 : 1);
                    for (int i = 0; i < totalChunks; i++) {
                        final int chunkIndex = i;
                        if (cancelled) {
                            return LogTag.I_CANCELLED;
                        }
                        while (((ThreadPoolExecutor) executor).getActiveCount() >= 6) {
                            Thread.sleep(100);
                            if (cancelled) {
                                return LogTag.I_CANCELLED;
                            }
                        }
                        int retryCount = 0;
                        final int MAX_RETRY = 100; // thử 100 lần (tức 5 giây)

                        PeerInfor peer = null;
                        do {
                            if (retryCount++ >= MAX_RETRY) {
                                logInfo("Timeout while waiting for available peer for chunk " + chunkIndex);
                                return LogTag.I_ERROR;
                            }
                            logInfo("Waiting for available peer...");
                            Thread.sleep(50);
                            if (cancelled) {
                                return LogTag.I_CANCELLED;
                            }
                            peer = Collections.min(peers, Comparator.comparingInt(PeerInfor::getTaskForDownload));
                        } while (!peer.isAvailableForDownload());

                        logInfo("Selected peer for download: " + peer.getIp() + ":" + peer.getPort() + ", taskcount: " + peer.getTaskForDownload());
                        peer.addTaskForDownload();
                        PeerInfor finalPeer = peer;
                        futures.add(executor.submit(() -> downloadChunk(finalPeer, fileInfor.getFileHash(), chunkIndex, raf, fileInfor, progressCounter)));
                    }
                    boolean allChunksDownloaded = true;
                    for (Future<Boolean> future : futures) {
                        try {
                            if (!future.get()) {
                                allChunksDownloaded = false;
                            }
                        } catch (Exception e) {
                            String errorMessage = "Lỗi khi tải chunk: " + e.getMessage();
                            logInfo(errorMessage);
                            allChunksDownloaded = false;
                        }
                        if (cancelled) {
                            return LogTag.I_CANCELLED;
                        }
                    }
                    if (allChunksDownloaded) {
                        String downloadedFileHash = computeFileHash(saveFile);
                        if (downloadedFileHash.equals(LogTag.S_ERROR)) {
                            logInfo("Error computing hash for downloaded file: " + saveFile.getAbsolutePath());
                            return LogTag.I_ERROR;
                        }
                        String originalHash = fileInfor.getFileHash();

                        if (!downloadedFileHash.equalsIgnoreCase(originalHash)) {
                            logInfo("Hash mismatch: downloaded file hash " + downloadedFileHash + " does not match original hash " + originalHash);
                            return LogTag.I_HASH_MISMATCH;
                        }

                        logInfo("Successfully downloaded file: " + saveFile.getAbsolutePath() + " with hash: " + downloadedFileHash);
                        return LogTag.I_SUCCESS;
                    } else {
                        logInfo("Failed to download all chunks for file: " + fileInfor.getFileName());
                        return LogTag.I_FAILURE;
                    }
                }
            } catch (IOException e) {
                logError("Error downloading file: " + fileInfor.getFileName() + " to " + savePath, e);
                return LogTag.I_ERROR;
            } finally {
                cancelDownload(savePath);
                cancelled = false;
                for (Future<Boolean> future : futures) {
                    future.cancel(true);
                }
                for (SocketChannel channel : openChannels) {
                    try {
                        channel.close();
                    } catch (IOException e) {
                        logError("Error closing channel: " + channel, e);
                    }
                }
                openChannels.clear();
            }
        });
    }

    public List<PeerInfor> getPeersWithFile(String fileHash) {
        try (Socket socket = new Socket(TRACKER_HOST.getIp(), TRACKER_HOST.getPort())) {
            String message = RequestInfor.GET_PEERS + Infor.FIELD_SEPARATOR + fileHash + "\n";

            socket.getOutputStream().write(message.getBytes());
            logInfo("Requesting peers with file hash: " + fileHash + ", message: " + message);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String response = in.readLine();
            if (response == null || response.isEmpty()) {
                logInfo("No response from tracker for file hash: " + fileHash);
                return Collections.emptyList();
            }

            String[] parts = response.split(Infor.FIELD_SEPARATOR_REGEX);
            if (parts.length != 3 || !parts[0].equals(RequestInfor.GET_PEERS)) {
                logInfo("Invalid response format from tracker: " + response);
                return Collections.emptyList();
            }

            int peerCount = Integer.parseInt(parts[1]);
            if (peerCount == 0) {
                logInfo("No peers found for file hash: " + fileHash);
                return Collections.emptyList();
            }
            String[] peerInfos = parts[2].split(",");
            List<PeerInfor> peers = new ArrayList<>();
            for (String peerInfo : peerInfos) {
                String[] peerParts = peerInfo.split("'");
                if (peerParts.length != 2) {
                    logInfo("Invalid peer info format: " + peerInfo);
                    continue;
                }
                String ip = peerParts[0];
                int port = Integer.parseInt(peerParts[1]);
                peers.add(new PeerInfor(ip, port));
            }

            if (peers.isEmpty()) {
                logInfo("No valid peers found for file hash: " + fileHash);
                return Collections.emptyList();
            }
            if (peerCount != peers.size()) {
                logInfo("Peer count mismatch: expected " + peerCount + ", found " + peers.size());
                return Collections.emptyList();
            }

            logInfo("Received response from tracker: " + response);
            return peers;
        } catch (IOException e) {
            logError("Error getting peers with file hash: " + fileHash, e);
            return Collections.emptyList();
        }
    }

    private String computeFileHash(File file) {
        try (InputStream is = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }
            return bytesToHex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            logError("Error computing hash for file: " + file.getAbsolutePath(), e);
            return LogTag.S_ERROR;
        }
    }

    private void cancelDownload(String savePath) {
        logInfo("Download process cancelled for file: " + savePath);
        Platform.runLater(() ->
                view.updateProgress("Quá trình tải file " + savePath + " đã bị hủy", 0, 0, 0));

        File sharedFile = new File(savePath);
        Path path = sharedFile.toPath();
        if (Files.exists(path) && sharedFile.delete()) {
            logInfo("Deleted file: " + sharedFile.getAbsolutePath());
        } else {
            logInfo("Cannot delete file: " + sharedFile.getAbsolutePath() + ". It may not exist or is not writable.");
        }
    }

    private boolean downloadChunk(PeerInfor peer, String fileName, int chunkIndex, RandomAccessFile raf, FileInfor fileInfor, AtomicInteger progressCounter) {
        int maxRetries = 3;
        if (cancelled) {
            return false;
        }
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                logInfo("Process cancelled by user while downloading chunk " + chunkIndex + " from peer " + peer);
                return false;
            }
            SocketChannel channel = null;
            try {
                channel = SocketChannel.open(new InetSocketAddress(peer.getIp(), peer.getPort()));
                channel.socket().setSoTimeout(SOCKET_TIMEOUT_MS);
                openChannels.add(channel);
                String request = RequestInfor.GET_CHUNK + Infor.FIELD_SEPARATOR + fileName + Infor.FIELD_SEPARATOR + chunkIndex + "\n";
                ByteBuffer requestBuffer = ByteBuffer.wrap(request.getBytes());
                while (requestBuffer.hasRemaining()) {
                    channel.write(requestBuffer);
                }

                ByteBuffer indexBuffer = ByteBuffer.allocate(4);
                int totalIndexBytes = 0;
                long startTime = System.currentTimeMillis();
                while (totalIndexBytes < 4 && (System.currentTimeMillis() - startTime) < 10000) {
                    if (cancelled) {
                        logInfo("Process cancelled by user while reading index chunk from peer " + peer);
                        return false;
                    }
                    int bytesRead = channel.read(indexBuffer);
                    if (bytesRead == -1) break;
                    totalIndexBytes += bytesRead;
                }

                if (totalIndexBytes < 4) {
                    logInfo("Don't receive enough bytes for index chunk from peer " + peer + " (attempt: " + attempt + ")");
                    continue;
                }

                indexBuffer.flip();
                int receivedChunkIndex = indexBuffer.getInt();
                if (receivedChunkIndex != chunkIndex) {
                    logInfo("Received chunk index " + receivedChunkIndex + " does not match requested index " + chunkIndex + " from peer " + peer + " (attempt " + attempt + ")");
                    continue;
                }

                ByteBuffer chunkBuffer = ByteBuffer.allocate(CHUNK_SIZE);
                ByteArrayOutputStream chunkData = new ByteArrayOutputStream();
                startTime = System.currentTimeMillis();

                while ((System.currentTimeMillis() - startTime) < 10000) {
                    if (cancelled) {
                        logInfo("Process cancelled by user while reading chunk data from peer " + peer);
                        return false;
                    }
                    int bytesRead = channel.read(chunkBuffer);
                    if (bytesRead == -1) break;
                    if (bytesRead == 0) {
                        Thread.sleep(100); // chờ tiếp
                        continue;
                    }

                    chunkBuffer.flip();
                    chunkData.write(chunkBuffer.array(), 0, bytesRead);
                    chunkBuffer.clear();
                }

                byte[] chunkBytes = chunkData.toByteArray();
                if (chunkBytes.length == 0) {
                    logInfo("Received empty chunk data from peer " + peer + " for chunk index " + chunkIndex + " (attempt " + attempt + ")");
                    continue;
                }

                synchronized (raf) {
                    raf.seek((long) chunkIndex * CHUNK_SIZE);
                    raf.write(chunkBytes);
                }

                long totalChunks = fileInfor.getFileSize() / CHUNK_SIZE + (fileInfor.getFileSize() % CHUNK_SIZE == 0 ? 0 : 1);
                long downloadedChunks = progressCounter.incrementAndGet();
                int percent = (int) ((downloadedChunks * 100.0) / totalChunks);

                Platform.runLater(() ->
                        view.updateProgress("Đang tải file " + fileName, percent, downloadedChunks * CHUNK_SIZE, fileInfor.getFileSize()));

                logInfo("Successfully downloaded chunk " + chunkIndex + " from peer " + peer + " (attempt " + attempt + ")");
                peer.removeTaskForDownload();
                return true;

            } catch (IOException | InterruptedException e) {
                logError("Error downloading chunk " + chunkIndex + " from peer " + peer + " (attempt " + attempt + "): " + e.getMessage(), e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } finally {
                if (channel != null) {
                    openChannels.remove(channel);
                    try {
                        channel.close();
                    } catch (IOException e) {
                        logError("Error closing channel: " + channel, e);
                    }
                }
            }
        }

        logInfo("Failed to download chunk " + chunkIndex + " from peer " + peer + " after " + maxRetries + " attempts.");
        return false;
    }


    private void sendChunk(SocketChannel client, String fileHash, int chunkIndex) {
        FileInfor orderedFile = null;
        for (FileInfor fileInfor : mySharedFiles.values()) {
            if (fileInfor.getFileHash().equals(fileHash)) {
                orderedFile = fileInfor;
                break;
            }
        }
        if (orderedFile == null) {
            logInfo("File not found in shared files: " + fileHash);
            return;
        }
        String filePath = GetDir.getDir() + "\\shared_files\\" + orderedFile.getFileName();
        File file = new File(filePath);
        if (!file.exists() || !file.canRead()) {
            logInfo("File does not exist or cannot be read: " + filePath);
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] chunk = new byte[CHUNK_SIZE];
            raf.seek((long) chunkIndex * CHUNK_SIZE);
            int bytesRead = raf.read(chunk);
            if (bytesRead > 0) {
                ByteBuffer buffer = ByteBuffer.allocate(bytesRead + 4);
                buffer.putInt(chunkIndex);
                buffer.put(chunk, 0, bytesRead);
                buffer.flip();
                int totalBytesWritten = 0;
                while (buffer.hasRemaining()) {
                    int written = client.write(buffer);
                    totalBytesWritten += written;
                }
                logInfo("Chunk " + chunkIndex + " of file " + orderedFile.getFileName() + " for " + fileHash + " sent successfully.");
                logInfo("Chunk index sent: " + chunkIndex);
                logInfo("Total bytes written: " + totalBytesWritten);
                if (totalBytesWritten < bytesRead + 4) {
                    logInfo("Warning: Not all bytes were sent for chunk " + chunkIndex + " of file " + orderedFile.getFileName() + ". Expected: " + (bytesRead + 4) + ", Sent: " + totalBytesWritten);
                } else {
                    logInfo("All bytes sent successfully for chunk " + chunkIndex + " of file " + orderedFile.getFileName());
                }
            } else {
                logInfo("No bytes read for chunk " + chunkIndex + " of file " + orderedFile.getFileName() + ". File may be empty or chunk index is out of bounds.");
            }
        } catch (IOException e) {
            logError("Error sending chunk " + chunkIndex + " of file " + orderedFile.getFileName() + ": " + e.getMessage(), e);
        }
    }

    public Future<FileInfor> getFileInforFromPeers(PeerInfor peer, String fileName) {
        return executor.submit(() -> {
            logInfo("Searching for file " + fileName + " on peer: " + peer.getIp() + ":" + peer.getPort());
            try (SocketChannel channel = SocketChannel.open(
                    new InetSocketAddress(peer.getIp(), peer.getPort()))) {
                channel.socket().setSoTimeout(5000);
                logInfo("Connected to peer: " + channel.getRemoteAddress());

                String request = RequestInfor.SEARCH + Infor.FIELD_SEPARATOR + new File(fileName).getName() + "\n";
                ByteBuffer requestBuffer = ByteBuffer.wrap(request.getBytes());
                while (requestBuffer.hasRemaining()) {
                    channel.write(requestBuffer);
                }
                logInfo("Sent SEARCH request: " + request.trim());


                StringBuilder responseBuilder = new StringBuilder();
                int initialBufferSize = 8192;
                ByteBuffer buffer = ByteBuffer.allocate(initialBufferSize);
                long startTime = System.currentTimeMillis();
                long timeoutMillis = 5000;
                int totalBytesRead = 0;

                while (System.currentTimeMillis() - startTime < timeoutMillis) {
                    buffer.clear();
                    int bytesRead = channel.read(buffer);
                    logInfo("Read " + bytesRead + " bytes");
                    if (bytesRead == -1) {
                        logInfo("Peer closed connection");
                        break;
                    }
                    if (bytesRead == 0) {
                        if (totalBytesRead > 0 && responseBuilder.toString().contains("\n")) {
                            logInfo("Received complete response");
                            break;
                        }
                        Thread.sleep(100);
                        continue;
                    }
                    totalBytesRead += bytesRead;
                    buffer.flip();
                    String chunk = new String(buffer.array(), 0, buffer.limit());
                    responseBuilder.append(chunk);
                    logInfo("Current response: [" + responseBuilder + "]");
                    if (responseBuilder.toString().contains("\n")) {
                        logInfo("Response complete, breaking loop");
                        break;
                    }
                    if (bytesRead == buffer.capacity()) {
                        initialBufferSize *= 2;
                        logInfo("Increasing buffer size to " + initialBufferSize + " bytes");
                        buffer = ByteBuffer.allocate(initialBufferSize);
                    }
                }

                String response = responseBuilder.toString().trim();
                logInfo("Final response: [" + response + "]");
                if (response.isEmpty()) {
                    logInfo("No response received from peer: " + peer);
                    return null;
                }
                if (response.startsWith(RequestInfor.FILE_INFO)) {
                    String[] parts = response.split("\\|", -1);
                    if (parts.length < 6) {
                        logInfo("Invalid FILE_INFO response: [" + response + "]");
                        return null;
                    }
                    String name = parts[1];
                    long size = Long.parseLong(parts[2]);
                    PeerInfor peerInfo = new PeerInfor(parts[3], Integer.parseInt(parts[4]));
                    String fileHash = parts[5];
                    return new FileInfor(name, size, fileHash, peerInfo);
                } else {
                    logInfo("File not found on peer: " + peer);
                    return null;
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("Error connecting to peer " + peer + ": " + e.getMessage());
                e.printStackTrace();
                return null;
            }
        });
    }

    public List<FileInfor> queryTracker(String keyword) {
        List<FileInfor> peers = new ArrayList<>();
        try (Socket socket = new Socket(TRACKER_HOST.getIp(), TRACKER_HOST.getPort())) {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out.println(RequestInfor.QUERY + Infor.FIELD_SEPARATOR + keyword);
            String response = in.readLine();
            if (response != null && response.startsWith(RequestInfor.QUERY)) {
                String[] res = response.split(Infor.FIELD_SEPARATOR_REGEX);
                if (res.length < 2) {
                    logInfo("Invalid response format from tracker: " + response);
                    return peers;
                }
                logInfo("Tracker response: " + response);

                int fileCount = Integer.parseInt(res[1]);
                if (fileCount == 0) {
                    logInfo("No peers found for query: " + keyword);
                    return peers;
                }
                String[] fileList = res[2].split(",");
                logInfo("Found " + fileCount + " peers for query: " + keyword);

                for (String fileInfo : fileList) {
                    String[] parts = fileInfo.split("'");
                    if (parts.length != 5) {
                        logInfo("Invalid file info format: " + fileInfo);
                        continue;
                    }
                    String fileName = parts[0];
                    long fileSize = Long.parseLong(parts[1]);
                    String fileHash = parts[2];
                    String peerIp = parts[3];
                    int peerPort = Integer.parseInt(parts[4]);
                    peers.add(new FileInfor(fileName, fileSize, fileHash, new PeerInfor(peerIp, peerPort)));
                }
            }
        } catch (Exception e) {
            logError("Error querying tracker for keyword: " + keyword, e);
            return null;
        }
        return peers;
    }

    public void loadSharedFiles() {
        // lấy danh sách file đang chia sẻ trong folder shared_files
        String path = GetDir.getDir() + "\\shared_files\\";
        File sharedDir = new File(path);
        if (!sharedDir.exists() || !sharedDir.isDirectory()) {
            logInfo("Shared directory does not exist or is not a directory: " + path);
            return;
        }

        File[] files = sharedDir.listFiles();
        if (files == null || files.length == 0) {
            logInfo("No shared files found in directory: " + sharedDir.getAbsolutePath());
            return;
        }

        logInfo("List of shared files in directory: " + sharedDir.getAbsolutePath());
        for (File file : files) {
            if (file.isFile()) {
                String fileName = file.getName();
                long fileSize = file.length();
                logInfo(" - " + fileName + " (" + fileSize + " bytes)");

                String fileHash = computeFileHash(file);
                if (fileHash.equals(LogTag.S_ERROR)) {
                    logInfo("Error computing hash for file: " + fileName);
                    continue;
                }
                mySharedFiles.put(fileName, new FileInfor(fileName, fileSize, fileHash, SERVER_HOST));
            }
        }
    }

    public int refreshSharedFileNames() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(TRACKER_HOST.getIp(), TRACKER_HOST.getPort()));

            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            String msg = RequestInfor.REFRESH + Infor.FIELD_SEPARATOR + SERVER_HOST.getIp() + Infor.FIELD_SEPARATOR + SERVER_HOST.getPort() + "\n";
            out.println(msg);

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String response = in.readLine();

            if (response != null && response.startsWith(RequestInfor.REFRESHED)) {
                String[] parts = response.split("\\|");
                if (parts.length != 3) {
                    logInfo("Invalid response format from tracker: " + response);
                    return LogTag.I_INVALID;
                }

                int fileCount = Integer.parseInt(parts[1]);
                String[] fileList = parts[2].split(",");

                if (fileList.length != fileCount) {
                    logInfo("File count mismatch: expected " + fileCount + ", got " + fileList.length);
                    return LogTag.I_INVALID;
                }

                sharedFileNames.clear();

                for (String fileInfo : fileList) {
                    String[] fileParts = fileInfo.split("'");
                    if (fileParts.length != 5) {
                        logInfo("Invalid file info format: " + fileInfo);
                        continue;
                    }
                    String fileName = fileParts[0];
                    long fileSize = Long.parseLong(fileParts[1]);
                    String fileHash = fileParts[2];
                    PeerInfor peerInfor = new PeerInfor(fileParts[3], Integer.parseInt(fileParts[4]));
                    sharedFileNames.add(new FileInfor(fileName, fileSize, fileHash, peerInfor));
                }
                logInfo("Refreshed shared file names from tracker: " + sharedFileNames.size() + " files found.");
                return LogTag.I_SUCCESS;
            }
            logInfo("Invalid response from tracker: " + response);
            return LogTag.I_INVALID;
        } catch (Exception e) {
            e.printStackTrace();
            return LogTag.I_ERROR;
        }
    }

    public Set<FileInfor> getSharedFileNames() {
        return sharedFileNames;
    }

    public void setSharedFileNames(Set<FileInfor> sharedFileNames) {
        this.sharedFileNames = sharedFileNames;
    }

    public Map<String, FileInfor> getMySharedFiles() {
        return mySharedFiles;
    }

    public boolean isMe(String ip, int port) {
        return SERVER_HOST.getIp().equals(ip) && SERVER_HOST.getPort() == port;
    }

    public int stopSharingFile(String fileName) {
        if (mySharedFiles.containsKey(fileName)) {
            FileInfor fileInfor = mySharedFiles.get(fileName);
            mySharedFiles.remove(fileName);
            sharedFileNames.removeIf(file -> file.getFileName().equals(fileName));
            String filePath = GetDir.getDir() + "\\shared_files\\" + fileName;
            File file = new File(filePath);
            if (file.exists() && file.delete()) {
                logInfo("Removed shared file: " + filePath);
            } else {
                logInfo("Failed to remove shared file: " + filePath);
            }

            logInfo("Stopped sharing file: " + fileName);
            return notifyTracker(fileInfor, false);
        } else {
            logInfo("File not found in shared files: " + fileName);
            return LogTag.I_NOT_FOUND;
        }
    }

    private Socket createSocket(PeerInfor peer) throws IOException {
        Socket socket = new Socket();
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        socket.connect(new InetSocketAddress(peer.getIp(), peer.getPort()), SOCKET_TIMEOUT_MS);
        return socket;
    }
}