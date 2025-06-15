package model;

import utils.GetDir;
import utils.Infor;
import utils.LogTag;
import utils.RequestInfor;
import view.P2PView;

import javax.swing.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static utils.Infor.SOCKET_TIMEOUT_MS;
import static utils.Log.*;

public class PeerModel {
    private final int CHUNK_SIZE = Infor.CHUNK_SIZE;
    private final PeerInfor SERVER_HOST = new PeerInfor(Infor.SERVER_IP, Infor.SERVER_PORT);
    private final PeerInfor TRACKER_HOST = new PeerInfor(Infor.TRACKER_IP, Infor.TRACKER_PORT);
    private final Selector selector;
    private ServerSocketChannel serverSocket;
    private final Map<String, FileInfor> mySharedFiles;
    private Set<FileBase> sharedFileNames;
    private final ExecutorService executor;
    private final CopyOnWriteArrayList<SocketChannel> openChannels = new CopyOnWriteArrayList<>();
    private final List<Future<Boolean>> futures = new ArrayList<>();
    private final boolean isRunning;
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
        executor = Executors.newFixedThreadPool(10);
        selector = Selector.open();
        serverSocket = ServerSocketChannel.open();
        serverSocket.bind(new InetSocketAddress(SERVER_HOST.getIp(), SERVER_HOST.getPort()));
        serverSocket.configureBlocking(false);
        serverSocket.register(selector, SelectionKey.OP_ACCEPT);
        isRunning = true;
        logInfor("Server socket initialized on " + SERVER_HOST.getIp() + ":" + SERVER_HOST.getPort());
    }

    private void logInfor(String s) {
    }

    public void startServer() {
        executor.submit(() -> {
            logInfor("Starting TCP server loop...");
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
    }

    private void closeChannel(SelectableChannel channel) {
    }

    public void startUDPServer() {
        executor.submit(() -> {
            try (DatagramSocket socket = new DatagramSocket(SERVER_HOST.getPort())) {
                byte[] buffer = new byte[1024];
                logInfor("UDP server started on " + SERVER_HOST.getIp() + ":" + SERVER_HOST.getPort());

                while (isRunning) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    String request = new String(packet.getData(), 0, packet.getLength());
                    logInfor("Received UDP request: " + request);
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
        logInfor("Pong response sent to " + packet.getAddress() + ":" + packet.getPort());
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
                logInfo("Current request: [" + requestBuilder.toString() + "]");
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
            System.err.println("Error handling read: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                client.close();
                key.cancel();
                logInfo("Closed client connection: " + client.getLocalAddress() + " -> " + client.getRemoteAddress());
            } catch (IOException e) {
                System.err.println("Error closing client: " + e.getMessage());
            }
        }
    }

    private void sendFileInfor(SocketChannel client, String fileName) {
        try {
            FileInfor fileInfor = mySharedFiles.get(fileName);
            String response;
            if (fileInfor != null) {
                response = RequestInfor.FILE_INFO + "|" + fileInfor.getFileName() + "|" + fileInfor.getFileSize()
                        + "|" + fileInfor.getPeerInfor().getIp() + "|" + fileInfor.getPeerInfor().getPort()
                        + "|" + String.join(",", fileInfor.getChunkHashes()) + "\n";
            } else {
                response = RequestInfor.FILE_NOT_FOUND + "|" + fileName + "\n";
            }
            ByteBuffer buffer = ByteBuffer.wrap(response.getBytes());
            int totalBytesWritten = 0;
            while (buffer.hasRemaining()) {
                int bytesWritten = client.write(buffer);
                totalBytesWritten += bytesWritten;
                logInfo("Wrote " + bytesWritten + " bytes, total: " + totalBytesWritten);
            }
            logInfo("Sent file information for: " + fileName + ", response: [" + response.trim() + "]");
        } catch (IOException e) {
            System.err.println("Error sending file info: " + e.getMessage());
            e.printStackTrace();
        }
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
                String message = RequestInfor.REGISTER + "|" + SERVER_HOST.getIp() + "|" + SERVER_HOST.getPort() + "\n";
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
                            if (fileParts.length != 4) {
                                logInfo("Invalid file info format: " + fileInfo);
                                continue;
                            }
                            String fileName = fileParts[0];
                            long fileSize = Long.parseLong(fileParts[1]);
                            PeerInfor peerInfor = new PeerInfor(fileParts[2], Integer.parseInt(fileParts[3]));
                            sharedFileNames.add(new FileBase(fileName, fileSize, peerInfor));
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
                System.err.println("Lỗi kết nối tracker, thử lại... (" + (i + 1) + ")");
                try {
                    Thread.sleep(1000); // Đợi 1 giây trước khi thử lại
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } catch (IOException e) {
                System.err.println("Lỗi khi đăng ký với tracker: " + e.getMessage());
            }
        }
        System.err.println("Không thể kết nối với tracker sau 3 lần thử.");
        return LogTag.I_ERROR;
    }

    public Future<Boolean> shareFileAsync(File file) {
        return executor.submit(() -> {
            List<String> chunkHashes = new ArrayList<>();
            logInfo("File path: " + file.getAbsolutePath());
            // show progress
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                byte[] chunk = new byte[CHUNK_SIZE];
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                int bytesRead;
                logInfo("Sharing file: " + file.getName() + ", size: " + file.length() + " bytes");
                while ((bytesRead = raf.read(chunk)) != -1) {
                    if (cancelled) {
                        logInfo("File sharing cancelled by user: " + file.getName());
                        SwingUtilities.invokeLater(() -> {
                            view.updateProgress("Chia sẻ file " + file.getName() + " đã bị hủy", 0, 0, 0);
                        });

                        File sharedFile = new File(GetDir.getShareDir(file.getName()));
                        Path path = sharedFile.toPath();

                        if (Files.exists(path) && sharedFile.delete()) {
                            logInfo("Đã xóa file chia sẻ: " + sharedFile.getAbsolutePath());
                        } else {
                            logInfo("Không thể xóa file chia sẻ: " + sharedFile.getAbsolutePath());
                        }
                        cancelled = false;
                        return false;
                    }
                    md.update(chunk, 0, bytesRead);
                    String chunkHash = bytesToHex(md.digest());
                    chunkHashes.add(chunkHash);
                    long sharedBytes = raf.getFilePointer();
                    long totalBytes = file.length();
                    int progress = (int) ((sharedBytes * 100.0) / totalBytes);

                    logInfo(String.format("Progress: %d%% (%.2f/%.2f MB)",
                            progress, sharedBytes / (1024.0 * 1024), totalBytes / (1024.0 * 1024)));
                    SwingUtilities.invokeLater(() -> {
                        view.updateProgress("Đang chia sẻ file " + file.getName(), progress, sharedBytes, totalBytes);
                    });
                }
                logInfo("File " + file.getName() + " shared successfully with " + chunkHashes.size() + " chunks.");
            } catch (IOException | NoSuchAlgorithmException e) {
                e.printStackTrace();
                return false;
            }

            mySharedFiles.put(file.getName(), new FileInfor(file.getName(), file.length(), chunkHashes, SERVER_HOST));
            notifyTracker(file.getName(), true);
            return true;
        });
    }

    public void shareFileList() {
        Set<FileBase> files = convertSharedFilesToFileBase();

        try (Socket socket = new Socket(TRACKER_HOST.getIp(), TRACKER_HOST.getPort())) {
            StringBuilder messageBuilder = new StringBuilder(RequestInfor.SHARED_LIST + "|");
            messageBuilder.append(files.size()).append("|");
            for (FileBase file : files) {
                messageBuilder.append(file.getFileName()).append("'")
                        .append(file.getFileSize()).append(",");
            }
            if (messageBuilder.charAt(messageBuilder.length() - 1) == ',') {
                messageBuilder.deleteCharAt(messageBuilder.length() - 1);
            }
            messageBuilder.append("|").append(SERVER_HOST.getIp()).append("|").append(SERVER_HOST.getPort());
            String message = messageBuilder.toString();

            socket.getOutputStream().write(message.getBytes());

            logInfo("Shared file list with tracker: " + message);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Set<FileBase> convertSharedFilesToFileBase() {
        Set<FileBase> fileList = new HashSet<>();

        for (Map.Entry<String, FileInfor> entry : mySharedFiles.entrySet()) {
            FileInfor fileInfor = entry.getValue();
            fileList.add(new FileBase(fileInfor.getFileName(), fileInfor.getFileSize(), fileInfor.getPeerInfor()));
        }

        return fileList;
    }

    private void notifyTracker(String fileName, boolean isShared) {
        try (Socket socket = new Socket(TRACKER_HOST.getIp(), TRACKER_HOST.getPort())) {

            String message = (isShared ? RequestInfor.SHARE : RequestInfor.UNSHARED_FILE)
                    + "|" + fileName + "|" + SERVER_HOST.getIp() + "|" + SERVER_HOST.getPort();

            socket.getOutputStream().write(message.getBytes());
            logInfo("Notified tracker about shared file: " + fileName + ", message: " + message);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String bytesToHex(byte[] digest) {
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public Future<Integer> downloadFile(String fileName, String savePath, PeerInfor peerInfor) {
        return executor.submit(() -> {
            try {
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

                Future<FileInfor> result = getFileInforFromPeers(peerInfor, fileName);
                FileInfor fileInfor = result.get();
                if (fileInfor == null) {
                    return LogTag.I_NOT_FOUND;
                }
                try (RandomAccessFile raf = new RandomAccessFile(saveFile, "rw")) {
                    raf.setLength(fileInfor.getFileSize());
                    AtomicInteger pregressCounter = new AtomicInteger(0);
                    for (int i = 0; i < fileInfor.getChunkHashes().size(); i++) {
                        final int chunkIndex = i;
                        if (cancelled) {
                            return LogTag.I_CANCELLED;
                        }
                        futures.add(executor.submit(() -> downloadChunk(peerInfor, fileName, chunkIndex, raf, fileInfor, pregressCounter)));
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
                        logInfo("Tải file hoàn tất: " + fileName + " vào " + savePath);
                        return LogTag.I_SUCCESS;
                    } else {
                        logInfo("Tải file thất bại: Một số chunk không tải được.");
                        return LogTag.I_FAILURE;
                    }
                }
            } catch (IOException e) {
                logError("Lỗi khi tải file: " + e.getMessage(), e);
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

    private void cancelDownload(String savePath) {
        logInfo("Tải xuống bị hủy bởi người dùng.");
        SwingUtilities.invokeLater(() -> {
            view.updateProgress("Quá trình tải file " + savePath + " đã bị hủy", 0, 0, 0);
        });

        File sharedFile = new File(savePath);
        Path path = sharedFile.toPath();
        if (Files.exists(path) && sharedFile.delete()) {
            logInfo("Đã xóa file tải xuống: " + sharedFile.getAbsolutePath());
        } else {
            logInfo("Không thể xóa file chia sẻ: " + sharedFile.getAbsolutePath());
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
                channel.socket().setSoTimeout(10000); // timeout 10s
                openChannels.add(channel);
                // Gửi yêu cầu lấy chunk
                String request = RequestInfor.GET_CHUNK + "|" + fileName + "|" + chunkIndex + "\n";
                ByteBuffer requestBuffer = ByteBuffer.wrap(request.getBytes());
                while (requestBuffer.hasRemaining()) {
                    channel.write(requestBuffer);
                }

                // ==== 1. Đọc chunkIndex (4 byte đầu) ====
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

                // ==== 2. Đọc dữ liệu chunk ====
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

                // ==== 3. Kiểm tra hash ====
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update(chunkBytes);
                String chunkHash = bytesToHex(md.digest());
                String expectedHash = fileInfor.getChunkHashes().get(chunkIndex);

                if (!chunkHash.equals(expectedHash)) {
                    logInfo("Hash mismatch for chunk " + chunkIndex + " from peer " + peer + " (attempt " + attempt + "). Expected: " + expectedHash + ", Received: " + chunkHash);
                    continue;
                }

                // ==== 4. Ghi chunk vào file ====
                synchronized (raf) {
                    raf.seek((long) chunkIndex * CHUNK_SIZE);
                    raf.write(chunkBytes);
                }

                long totalChunks = fileInfor.getChunkHashes().size();
                long downloadedChunks = progressCounter.incrementAndGet();
                int percent = (int) ((downloadedChunks * 100.0) / totalChunks);

                SwingUtilities.invokeLater(() -> {
                    view.updateProgress("Đang tải file " + fileName, percent, downloadedChunks * CHUNK_SIZE, fileInfor.getFileSize());
                });

                logInfo("Successfully downloaded chunk " + chunkIndex + " from peer " + peer + " (attempt " + attempt + ")");
                return true;

            } catch (IOException | NoSuchAlgorithmException | InterruptedException e) {
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


    private void sendChunk(SocketChannel client, String fileName, int chunkIndex) {
        FileInfor fileInfor = mySharedFiles.get(fileName);
        if (fileInfor == null) {
            logInfo("File " + fileName + " không có trong danh sách chia sẻ.");
            return;
        }
        String filePath = GetDir.getDir() + "\\shared_files\\" + fileName;
        File file = new File(filePath);
        if (!file.exists() || !file.canRead()) {
            logInfo("File không tồn tại hoặc không đọc được tại: " + filePath);
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
                logInfo("Chunk index: " + chunkIndex);
                logInfo("Dữ liệu thực đọc từ file: " + bytesRead + " bytes");
                logInfo("Tổng số bytes gửi đi qua mạng (gồm cả index): " + totalBytesWritten + " bytes");

                logInfo("Gửi chunk " + chunkIndex + " của file " + fileName + " (" + totalBytesWritten + " bytes)");
            } else {
                logInfo("Không có dữ liệu để gửi cho chunk " + chunkIndex);
            }
        } catch (IOException e) {
            System.err.println("Lỗi gửi chunk " + chunkIndex + " của file " + fileName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public Future<FileInfor> getFileInforFromPeers(PeerInfor peer, String fileName) {
        return executor.submit(() -> {
            logInfo("Searching for file " + fileName + " on peer: " + peer.getIp() + ":" + peer.getPort());
            try (SocketChannel channel = SocketChannel.open(
                    new InetSocketAddress(peer.getIp(), peer.getPort()))) {
                channel.socket().setSoTimeout(5000);
                logInfo("Connected to peer: " + channel.getRemoteAddress());

                // Gửi yêu cầu
                String request = RequestInfor.SEARCH + "|" + new File(fileName).getName() + "\n";
                ByteBuffer requestBuffer = ByteBuffer.wrap(request.getBytes());
                while (requestBuffer.hasRemaining()) {
                    channel.write(requestBuffer);
                }
                logInfo("Sent SEARCH request: " + request.trim());

                // Đọc phản hồi
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
                    logInfo("Current response: [" + responseBuilder.toString() + "]");
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
                    List<String> chunkHashes = Arrays.asList(parts[5].split(","));
                    return new FileInfor(name, size, chunkHashes, peerInfo);
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

    public List<String> queryTracker(String fileName) throws IOException {
        List<String> peers = new ArrayList<>();
        try (Socket socket = new Socket(TRACKER_HOST.getIp(), TRACKER_HOST.getPort())) {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out.println(RequestInfor.QUERY + "|" + new File(fileName).getName());
            String response = in.readLine();
            if (response != null && !response.isEmpty()) {
                peers.addAll(Arrays.asList(response.split(",")));
            }
        }
        return peers;
    }

    public void loadSharedFiles() {
        // lấy danh sách file đang chia sẻ trong folder shared_files
        String path = GetDir.getDir() + "\\shared_files\\";
        File sharedDir = new File(path);
        if (!sharedDir.exists() || !sharedDir.isDirectory()) {
            logInfo("Thư mục chia sẻ không tồn tại hoặc không phải là thư mục: " + sharedDir.getAbsolutePath());
            return;
        }

        File[] files = sharedDir.listFiles();
        if (files == null || files.length == 0) {
            logInfo("Không có file nào được chia sẻ trong thư mục: " + sharedDir.getAbsolutePath());
            return;
        }

        logInfo("Danh sách file đang chia sẻ:");
        for (File file : files) {
            if (file.isFile()) {
                String fileName = file.getName();
                long fileSize = file.length();
                logInfo(" - " + fileName + " (" + fileSize + " bytes)");

                List<String> chunkHashes = new ArrayList<>();
                try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                    byte[] chunk = new byte[CHUNK_SIZE];
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    int bytesRead;
                    while ((bytesRead = raf.read(chunk)) != -1) {
                        md.update(chunk, 0, bytesRead);
                        String chunkHash = bytesToHex(md.digest());
                        chunkHashes.add(chunkHash);
                    }
                } catch (IOException | NoSuchAlgorithmException e) {
                    e.printStackTrace();
                }
                mySharedFiles.put(fileName, new FileInfor(fileName, fileSize, chunkHashes, SERVER_HOST));
            }
        }
    }

    public int refreshSharedFileNames() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(TRACKER_HOST.getIp(), TRACKER_HOST.getPort()));

            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            String msg = RequestInfor.REFRESH + "|" + SERVER_HOST.getIp() + "|" + SERVER_HOST.getPort() + "\n";
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
                    if (fileParts.length != 4) {
                        logInfo("Invalid file info format: " + fileInfo);
                        continue;
                    }
                    String fileName = fileParts[0];
                    long fileSize = Long.parseLong(fileParts[1]);
                    PeerInfor peerInfor = new PeerInfor(fileParts[2], Integer.parseInt(fileParts[3]));
                    sharedFileNames.add(new FileBase(fileName, fileSize, peerInfor));
                }
                logInfo("Đã làm mới danh sách file chia sẻ từ tracker. Tổng số file: " + sharedFileNames.size());
                return LogTag.I_SUCCESS;
            }
            logInfo("Tracker không trả về phản hồi hợp lệ: " + response);
            return LogTag.I_INVALID;
        } catch (Exception e) {
            e.printStackTrace();
            return LogTag.I_ERROR;
        }
    }

    public Set<FileBase> getSharedFileNames() {
        return sharedFileNames;
    }

    public void setSharedFileNames(Set<FileBase> sharedFileNames) {
        ;
        this.sharedFileNames = sharedFileNames;
    }

    public Map<String, FileInfor> getMySharedFiles() {
        return mySharedFiles;
    }

    public boolean isMe(String ip, int port) {
        return SERVER_HOST.getIp().equals(ip) && SERVER_HOST.getPort() == port;
    }

    public void stopSharingFile(String fileName) {
        if (mySharedFiles.containsKey(fileName)) {
            mySharedFiles.remove(fileName);
            sharedFileNames.removeIf(file -> file.getFileName().equals(fileName));
            // xóa file khỏi thư mục chia sẻ
            String filePath = GetDir.getDir() + "\\shared_files\\" + fileName;
            File file = new File(filePath);
            if (file.exists() && file.delete()) {
                logInfo("Đã xóa file khỏi thư mục chia sẻ: " + filePath);
            } else {
                logInfo("Không thể xóa file khỏi thư mục chia sẻ: " + filePath);
            }

            logInfo("Đã dừng chia sẻ file: " + fileName);
            notifyTracker(fileName, false);
        } else {
            logInfo("Không tìm thấy file để dừng chia sẻ: " + fileName);
        }
    }

    private Socket createSocket(PeerInfor peer) throws IOException {
        Socket socket = new Socket();
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        socket.connect(new InetSocketAddress(peer.getIp(), peer.getPort()), SOCKET_TIMEOUT_MS);
        return socket;
    }
}