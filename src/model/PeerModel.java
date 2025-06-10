package model;

import utils.GetDir;
import utils.Infor;
import utils.LogTag;
import utils.RequestInfor;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static utils.Infor.SOCKET_TIMEOUT_MS;

public class PeerModel {
    private final int CHUNK_SIZE = Infor.CHUNK_SIZE;
    private final PeerInfor SERVER_HOST = new PeerInfor(Infor.SERVER_IP, Infor.SERVER_PORT);
    private final PeerInfor TRACKER_HOST = new PeerInfor(Infor.TRACKER_IP, Infor.TRACKER_PORT);
    private final Selector selector;
    private ServerSocketChannel serverSocket;
    private final Map<String, FileInfor> mySharedFiles;
    private Set<FileBase> sharedFileNames;
    private final ExecutorService executor;
    private final boolean isRunning;

    public PeerModel() throws IOException {
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
        loadSharedFiles();
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
            System.out.println("Handling read operation for key: " + key + ", client: " + client.getRemoteAddress());
            int totalBytesRead = 0;

            while (true) {
                buffer.clear();
                int bytesRead = client.read(buffer);
                System.out.println("Read " + bytesRead + " bytes from " + client.getRemoteAddress());
                if (bytesRead == -1) {
                    System.out.println("Client closed connection");
                    break;
                }
                if (bytesRead == 0 && totalBytesRead > 0) {
                    System.out.println("No more data to read, processing request");
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
                System.out.println("Current request: [" + requestBuilder.toString() + "]");
                if (requestBuilder.toString().contains("\n")) {
                    System.out.println("Request complete, breaking loop");
                    break;
                }
                if (bytesRead == buffer.capacity()) {
                    initialBufferSize *= 2;
                    System.out.println("Increasing buffer size to " + initialBufferSize + " bytes");
                    buffer = ByteBuffer.allocate(initialBufferSize);
                }
            }

            String request = requestBuilder.toString().trim();
            System.out.println("Final request: [" + request + "]");
            if (request.isEmpty()) {
                System.out.println("Empty request received");
                return;
            }
            if (request.startsWith(RequestInfor.SEARCH)) {
                String fileName = request.split("\\|")[1];
                System.out.println("Processing SEARCH for file: " + fileName);
                sendFileInfor(client, fileName);
            } else if (request.startsWith(RequestInfor.GET_CHUNK)) {
                String[] parts = request.split("\\|");
                String fileName = parts[1];
                int chunkIndex = Integer.parseInt(parts[2]);
                System.out.println("Processing GET_CHUNK for file: " + fileName + ", chunk: " + chunkIndex);
                sendChunk(client, fileName, chunkIndex);
            } else {
                System.out.println("Unknown request: [" + request + "]");
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Error handling read: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                client.close();
                key.cancel();
                System.out.println("Closed client connection: " + client.getLocalAddress() + " -> " + client.getRemoteAddress());
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
                System.out.println("Wrote " + bytesWritten + " bytes, total: " + totalBytesWritten);
            }
            System.out.println("Sent file information for: " + fileName + ", response: [" + response.trim() + "]");
        } catch (IOException e) {
            System.err.println("Error sending file info: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void acceptConnection(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        System.out.println("Accepted connection from: " + client.getRemoteAddress());
        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ);
    }

    public int registerWithTracker() {
        for (int i = 0; i < Infor.MAX_RETRIES; i++) {
            try (Socket socket = createSocket(TRACKER_HOST)) {
                String message = RequestInfor.REGISTER + "|" + SERVER_HOST.getIp() + "|" + SERVER_HOST.getPort() + "\n";
                socket.getOutputStream().write(message.getBytes());
                System.out.println("Registered with tracker: " + message);

                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String response = in.readLine();
                System.out.println("check: " + response);
                if (response == null) {
                    System.out.println("Tracker did not respond.");
                    return LogTag.I_ERROR;
                }
                if (response.startsWith(RequestInfor.SHARED_LIST)) {
                    System.out.println("Tracker registration successful: " + response);

                    String[] parts = response.split("\\|");
                    if (parts.length < 2) {
                        System.out.println("Invalid response format from tracker: " + response);
                        return LogTag.I_INVALID;
                    }
                    if (parts.length == 3) {
                        String[] sharedFilesList = parts[2].split(",");
                        System.out.println("Files shared by tracker: " + Arrays.toString(sharedFilesList));
                        for (String fileInfo : sharedFilesList) {
                            String[] fileParts = fileInfo.split("'");
                            if (fileParts.length != 4) {
                                System.out.println("Invalid file info format: " + fileInfo);
                                continue;
                            }
                            String fileName = fileParts[0];
                            long fileSize = Long.parseLong(fileParts[1]);
                            PeerInfor peerInfor = new PeerInfor(fileParts[2], Integer.parseInt(fileParts[3]));
                            sharedFileNames.add(new FileBase(fileName, fileSize, peerInfor));
                        }
                        System.out.println("Total files shared by tracker: " + sharedFileNames.size());
                        return LogTag.I_SUCCESS;
                    } else {
                        System.out.println("Invalid response format from tracker: " + response);
                        return LogTag.I_INVALID;
                    }
                } else if (response.startsWith(RequestInfor.REGISTERED)) {
                    return LogTag.I_NOT_FOUND;
                } else {
                    System.out.println("Tracker registration failed or no response: " + response);
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

    public boolean shareFile(String filePath) {
        File file = new File(filePath);
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
            return false;
        }
        mySharedFiles.put(file.getName(), new FileInfor(file.getName(), file.length(), chunkHashes, SERVER_HOST));
        System.out.println("Shared file: " + file.getName() + ", details: " + mySharedFiles.get(file.getName()));
        notifyTracker(file.getName(), true);
        return true;
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

            System.out.println("Shared file list with tracker: " + message);
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

            System.out.println("Notified tracker about shared file: " + fileName + ", message: " + message);
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
                    System.out.println(errorMessage);
                    return LogTag.I_NOT_EXIST;
                }
                if (!saveFile.getParentFile().canWrite()) {
                    String errorMessage = "Lỗi: Không có quyền ghi vào thư mục: " + saveFile.getParent();
                    System.out.println(errorMessage);
                    return LogTag.I_NOT_PERMISSION;
                }

                Future<FileInfor> result = getFileInforFromPeers(peerInfor, fileName);
                FileInfor fileInfor = result.get();
                if (fileInfor == null) {
                    return LogTag.I_NOT_FOUND;
                }
                try (RandomAccessFile raf = new RandomAccessFile(saveFile, "rw")) {
                    raf.setLength(fileInfor.getFileSize());
                    List<Future<Boolean>> futures = new ArrayList<>();
                    for (int i = 0; i < fileInfor.getChunkHashes().size(); i++) {
                        final int chunkIndex = i;
                        futures.add(executor.submit(() -> downloadChunk(peerInfor, fileName, chunkIndex, raf, fileInfor.getChunkHashes().get(chunkIndex))));
                    }
                    boolean allChunksDownloaded = true;
                    for (Future<Boolean> future : futures) {
                        try {
                            if (!future.get()) {
                                allChunksDownloaded = false;
                            }
                        } catch (Exception e) {
                            String errorMessage = "Lỗi khi tải chunk: " + e.getMessage();
                            System.out.println(errorMessage);
                            allChunksDownloaded = false;
                        }
                    }
                    if (allChunksDownloaded) {
                        System.out.println("Tải file hoàn tất: " + fileName + " vào " + savePath);
                        return LogTag.I_SUCCESS;
                    } else {
                        System.out.println("Tải file thất bại: Một số chunk không tải được.");
                        return LogTag.I_FAILURE;
                    }
                }
            } catch (IOException e) {
                System.err.println("Lỗi khi tải file: " + e.getMessage());
                e.printStackTrace();
                return LogTag.I_ERROR;
            }
        });
    }

    private boolean downloadChunk(PeerInfor peer, String fileName, int chunkIndex, RandomAccessFile raf, String expectedHash) {
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                try (SocketChannel channel = SocketChannel.open(new InetSocketAddress(peer.getIp(), peer.getPort()))) {
                    channel.socket().setSoTimeout(10000); // timeout 10s

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
                        int bytesRead = channel.read(indexBuffer);
                        if (bytesRead == -1) break;
                        totalIndexBytes += bytesRead;
                    }

                    if (totalIndexBytes < 4) {
                        System.out.println("Không nhận được index chunk từ peer " + peer + " (lần thử " + attempt + ")");
                        continue;
                    }

                    indexBuffer.flip();
                    int receivedChunkIndex = indexBuffer.getInt();
                    if (receivedChunkIndex != chunkIndex) {
                        System.out.println("Nhận chunk index " + receivedChunkIndex + ", kỳ vọng " + chunkIndex);
                        continue;
                    }

                    // ==== 2. Đọc dữ liệu chunk ====
                    ByteBuffer chunkBuffer = ByteBuffer.allocate(CHUNK_SIZE);
                    ByteArrayOutputStream chunkData = new ByteArrayOutputStream();
                    startTime = System.currentTimeMillis();

                    while ((System.currentTimeMillis() - startTime) < 10000) {
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
                        System.out.println("Không nhận được dữ liệu cho chunk " + chunkIndex + " từ peer " + peer + " (lần thử " + attempt + ")");
                        continue;
                    }

                    // ==== 3. Kiểm tra hash ====
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    md.update(chunkBytes);
                    String chunkHash = bytesToHex(md.digest());

                    if (!chunkHash.equals(expectedHash)) {
                        System.out.println("Hash không khớp cho chunk " + chunkIndex + " từ peer " + peer + " (lần thử " + attempt + ")");
                        continue;
                    }

                    // ==== 4. Ghi chunk vào file ====
                    synchronized (raf) {
                        raf.seek((long) chunkIndex * CHUNK_SIZE);
                        raf.write(chunkBytes);
                    }

                    System.out.println("Tải xuống chunk " + chunkIndex + " từ peer " + peer + " (lần thử " + attempt + "), kích thước: " + chunkBytes.length + " bytes");
                    return true;
                }
            } catch (IOException | NoSuchAlgorithmException | InterruptedException e) {
                System.err.println("Lỗi tải chunk " + chunkIndex + " từ peer " + peer + " (lần thử " + attempt + "): " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("Không thể tải chunk " + chunkIndex + " từ bất kỳ peer nào sau " + maxRetries + " lần thử.");
        return false;
    }


    private void sendChunk(SocketChannel client, String fileName, int chunkIndex) {
        FileInfor fileInfor = mySharedFiles.get(fileName);
        if (fileInfor == null) {
            System.out.println("File " + fileName + " không có trong danh sách chia sẻ.");
            return;
        }
        String filePath = GetDir.getDir() + "\\shared_files\\" + fileName;
        File file = new File(filePath);
        if (!file.exists() || !file.canRead()) {
            System.out.println("File không tồn tại hoặc không đọc được tại: " + filePath);
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
                System.out.println("Chunk index: " + chunkIndex);
                System.out.println("Dữ liệu thực đọc từ file: " + bytesRead + " bytes");
                System.out.println("Tổng số bytes gửi đi qua mạng (gồm cả index): " + totalBytesWritten + " bytes");

                System.out.println("Gửi chunk " + chunkIndex + " của file " + fileName + " (" + totalBytesWritten + " bytes)");
            } else {
                System.out.println("Không có dữ liệu để gửi cho chunk " + chunkIndex);
            }
        } catch (IOException e) {
            System.err.println("Lỗi gửi chunk " + chunkIndex + " của file " + fileName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public Future<FileInfor> getFileInforFromPeers(PeerInfor peer, String fileName) {
        return executor.submit(() -> {
            System.out.println("Searching for file " + fileName + " on peer: " + peer.getIp() + ":" + peer.getPort());
            try (SocketChannel channel = SocketChannel.open(
                    new InetSocketAddress(peer.getIp(), peer.getPort()))) {
                channel.socket().setSoTimeout(5000);
                System.out.println("Connected to peer: " + channel.getRemoteAddress());

                // Gửi yêu cầu
                String request = RequestInfor.SEARCH + "|" + new File(fileName).getName() + "\n";
                ByteBuffer requestBuffer = ByteBuffer.wrap(request.getBytes());
                while (requestBuffer.hasRemaining()) {
                    channel.write(requestBuffer);
                }
                System.out.println("Sent SEARCH request: " + request.trim());

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
                    System.out.println("Read " + bytesRead + " bytes");
                    if (bytesRead == -1) {
                        System.out.println("Peer closed connection");
                        break;
                    }
                    if (bytesRead == 0) {
                        if (totalBytesRead > 0 && responseBuilder.toString().contains("\n")) {
                            System.out.println("Received complete response");
                            break;
                        }
                        Thread.sleep(100);
                        continue;
                    }
                    totalBytesRead += bytesRead;
                    buffer.flip();
                    String chunk = new String(buffer.array(), 0, buffer.limit());
                    responseBuilder.append(chunk);
                    System.out.println("Current response: [" + responseBuilder.toString() + "]");
                    if (responseBuilder.toString().contains("\n")) {
                        System.out.println("Response complete, breaking loop");
                        break;
                    }
                    if (bytesRead == buffer.capacity()) {
                        initialBufferSize *= 2;
                        System.out.println("Increasing buffer size to " + initialBufferSize + " bytes");
                        buffer = ByteBuffer.allocate(initialBufferSize);
                    }
                }

                String response = responseBuilder.toString().trim();
                System.out.println("Final response: [" + response + "]");
                if (response.isEmpty()) {
                    System.out.println("No response received from peer: " + peer);
                    return null;
                }
                if (response.startsWith(RequestInfor.FILE_INFO)) {
                    String[] parts = response.split("\\|", -1);
                    if (parts.length < 6) {
                        System.out.println("Invalid FILE_INFO response: [" + response + "]");
                        return null;
                    }
                    String name = parts[1];
                    long size = Long.parseLong(parts[2]);
                    PeerInfor peerInfo = new PeerInfor(parts[3], Integer.parseInt(parts[4]));
                    List<String> chunkHashes = Arrays.asList(parts[5].split(","));
                    return new FileInfor(name, size, chunkHashes, peerInfo);
                } else {
                    System.out.println("File not found on peer: " + peer);
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
            System.out.println("Thư mục chia sẻ không tồn tại hoặc không phải là thư mục: " + sharedDir.getAbsolutePath());
            return;
        }

        File[] files = sharedDir.listFiles();
        if (files == null || files.length == 0) {
            System.out.println("Không có file nào được chia sẻ trong thư mục: " + sharedDir.getAbsolutePath());
            return;
        }

        System.out.println("Danh sách file đang chia sẻ:");
        for (File file : files) {
            if (file.isFile()) {
                String fileName = file.getName();
                long fileSize = file.length();
                System.out.println(" - " + fileName + " (" + fileSize + " bytes)");

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
                    System.out.println("Invalid response format from tracker: " + response);
                    return LogTag.I_INVALID;
                }

                int fileCount = Integer.parseInt(parts[1]);
                String[] fileList = parts[2].split(",");

                if (fileList.length != fileCount) {
                    System.out.println("File count mismatch: expected " + fileCount + ", got " + fileList.length);
                    return LogTag.I_INVALID;
                }

                sharedFileNames.clear();

                for (String fileInfo : fileList) {
                    String[] fileParts = fileInfo.split("'");
                    if (fileParts.length != 4) {
                        System.out.println("Invalid file info format: " + fileInfo);
                        continue;
                    }
                    String fileName = fileParts[0];
                    long fileSize = Long.parseLong(fileParts[1]);
                    PeerInfor peerInfor = new PeerInfor(fileParts[2], Integer.parseInt(fileParts[3]));
                    sharedFileNames.add(new FileBase(fileName, fileSize, peerInfor));
                }
                System.out.println("Đã làm mới danh sách file chia sẻ từ tracker. Tổng số file: " + sharedFileNames.size());
                return LogTag.I_SUCCESS;
            }
            System.out.println("Tracker không trả về phản hồi hợp lệ: " + response);
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
                System.out.println("Đã xóa file khỏi thư mục chia sẻ: " + filePath);
            } else {
                System.out.println("Không thể xóa file khỏi thư mục chia sẻ: " + filePath);
            }

            System.out.println("Đã dừng chia sẻ file: " + fileName);
            notifyTracker(fileName, false);
        } else {
            System.out.println("Không tìm thấy file để dừng chia sẻ: " + fileName);
        }
    }

    private Socket createSocket(PeerInfor peer) throws IOException {
        Socket socket = new Socket();
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        socket.connect(new InetSocketAddress(peer.getIp(), peer.getPort()), SOCKET_TIMEOUT_MS);
        return socket;
    }

    private void logInfo(String message) {
        System.out.println("[INFO] " + message);
    }

    private void logError(String message, Exception e) {
        System.err.println("[ERROR] " + message);
        if (e != null) {
            e.printStackTrace();
        }
    }
}