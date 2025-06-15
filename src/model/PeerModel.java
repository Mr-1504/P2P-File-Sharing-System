package model;

import utils.*;

import javax.net.ssl.*;
import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.lang.Thread.sleep;
import static utils.Infor.*;
import static utils.Log.*;

public class PeerModel extends NioSslPeer {
    private final String KEYSTORE_PATH = EnvConf.getEnv("PEER_STORE_PATH", "peer-keystore.jks");
    private final String TRUSTSTORE_PATH = EnvConf.getEnv("PEER_TRUSTSTORE_PATH", "peer-truststore.jks");
    private final String STORE_PASSWORD = EnvConf.getEnv("PEER_STORE_PASSWORD", "password");
    private final int CHUNK_SIZE = Infor.CHUNK_SIZE;
    private final PeerInfor SERVER_HOST = new PeerInfor(Infor.SERVER_IP, Infor.SERVER_PORT);
    private final PeerInfor TRACKER_HOST = new PeerInfor(Infor.TRACKER_IP, Infor.TRACKER_PORT);
    private ServerSocketChannel serverSocket;
    private final Map<String, FileInfor> mySharedFiles;
    private Set<FileBase> sharedFileNames;
    private final ExecutorService executor;
    private boolean isRunning;
    private final SSLContext sslContext;

    public PeerModel() throws IOException {
        mySharedFiles = new HashMap<>();
        sharedFileNames = new HashSet<>();
        executor = Executors.newFixedThreadPool(10);
        sslContext = initSSLContext();
        serverSocket = ServerSocketChannel.open();
        serverSocket.bind(new InetSocketAddress(SERVER_HOST.getIp(), SERVER_HOST.getPort()));
        serverSocket.configureBlocking(false);
        isRunning = true;
        logInfo("Server socket initialized on " + SERVER_HOST.getIp() + ":" + SERVER_HOST.getPort());
        loadSharedFiles();
    }

    private SSLContext initSSLContext() {
        try {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            KeyStore trustStore = KeyStore.getInstance("JKS");

            try (InputStream keyStoreStream = new FileInputStream(KEYSTORE_PATH);
                 InputStream trustStoreStream = new FileInputStream(TRUSTSTORE_PATH)) {
                keyStore.load(keyStoreStream, STORE_PASSWORD.toCharArray());
                trustStore.load(trustStoreStream, STORE_PASSWORD.toCharArray());
            }

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, STORE_PASSWORD.toCharArray());

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            return sslContext;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SSL context", e);
        }
    }

    public void startServer() {
        executor.submit(() -> {
            logInfo("Starting SSL/TCP server loop...");
            Selector selector = null;
            try {
                selector = Selector.open();
                serverSocket.register(selector, SelectionKey.OP_ACCEPT);
                while (isRunning) {
                    selector.select();
                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();
                        if (!key.isValid()) continue;
                        try {
                            if (key.isAcceptable()) {
                                acceptConnection(selector, key);
                            } else if (key.isReadable()) {
                                handleRead(key);
                            } else if (key.isWritable()) {
                                handleWrite(key);
                            }
                        } catch (Exception e) {
                            logError("Error processing key: " + key, e);
                            key.cancel();
                            closeChannel(key.channel());
                        }
                    }
                }
            } catch (IOException e) {
                logError("SSL/TCP server error: " + e.getMessage(), e);
            } finally {
                shutdown(selector);
            }
        });
    }

    private void shutdown(Selector selector) {
        isRunning = false;
        try {
            if (serverSocket != null) serverSocket.close();
            if (selector != null) selector.close();
        } catch (IOException e) {
            logError("Error shutting down server: " + e.getMessage(), e);
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logInfo("Server shutdown completed.");
    }

    private void closeChannel(SelectableChannel channel) {
        if (channel != null && channel.isOpen()) {
            try {
                channel.close();
                logInfo("Closed channel: " + channel);
            } catch (IOException e) {
                logError("Error closing channel: " + e.getMessage(), e);
            }
        }
    }

    private void acceptConnection(Selector selector, SelectionKey key) throws Exception {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        client.configureBlocking(false);
        SSLEngine engine = sslContext.createSSLEngine();
        engine.setUseClientMode(false);
        engine.setNeedClientAuth(true);
        engine.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
        if (doHandshake(client, engine)) {
            client.register(selector, SelectionKey.OP_READ, engine);
            logInfo("Accepted SSL connection from: " + client.getRemoteAddress());
        } else {
            client.close();
            logError("Handshake failed with client: " + client.getRemoteAddress(), null);
        }
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        SSLEngine engine = (SSLEngine) key.attachment();
        try {
            peerNetData.clear();
            int bytesRead = client.read(peerNetData);
            if (bytesRead > 0) {
                peerNetData.flip();
                while (peerNetData.hasRemaining()) {
                    peerAppData.clear();
                    SSLEngineResult result = engine.unwrap(peerNetData, peerAppData);
                    if (result.getStatus() == SSLEngineResult.Status.OK) {
                        peerAppData.flip();
                        String request = new String(peerAppData.array(), 0, peerAppData.limit()).trim();
                        logInfo("Received from peer: " + request);
                        if (request.startsWith(RequestInfor.SEARCH)) {
                            String fileName = request.split(Infor.FIELD_SEPARATOR_REGEX)[1];
                            sendFileInfor(client, engine, fileName);
                            key.interestOps(SelectionKey.OP_WRITE);
                        } else if (request.startsWith(RequestInfor.GET_CHUNK)) {
                            String[] parts = request.split(Infor.FIELD_SEPARATOR_REGEX);
                            String fileName = parts[1];
                            int chunkIndex = Integer.parseInt(parts[2]);
                            sendChunk(client, engine, fileName, chunkIndex);
                            key.interestOps(SelectionKey.OP_WRITE);
                        }
                    } else if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        peerAppData = enlargeApplicationBuffer(engine, peerAppData);
                    }
                }
            } else if (bytesRead < 0) {
                handleEndOfStream(client, engine);
            }
        } catch (IOException e) {
            logError("Error reading from client: " + e.getMessage(), e);
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        SSLEngine engine = (SSLEngine) key.attachment();
        try {
            myAppData.clear();
            myNetData.clear();
            SSLEngineResult result = engine.wrap(myAppData, myNetData);
            if (result.getStatus() == SSLEngineResult.Status.OK) {
                myNetData.flip();
                while (myNetData.hasRemaining()) {
                    client.write(myNetData);
                }
                key.interestOps(SelectionKey.OP_READ);
                logInfo("Response sent to client");
            } else if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                myNetData = enlargePacketBuffer(engine, myNetData);
            }
        } catch (IOException e) {
            logError("Error writing to client: " + e.getMessage(), e);
            key.cancel();
        }
    }

    private void sendFileInfor(SocketChannel client, SSLEngine engine, String fileName) throws IOException {
        FileInfor fileInfor = mySharedFiles.get(fileName);
        String response = fileInfor != null ?
                RequestInfor.FILE_INFO + FIELD_SEPARATOR + fileInfor.getFileName() + FIELD_SEPARATOR + fileInfor.getFileSize()
                        + FIELD_SEPARATOR + fileInfor.getPeerInfor().getIp() + FIELD_SEPARATOR + fileInfor.getPeerInfor().getPort()
                        + FIELD_SEPARATOR + String.join(LIST_SEPARATOR, fileInfor.getChunkHashes()) + "\n"
                : RequestInfor.FILE_NOT_FOUND + FIELD_SEPARATOR + fileName + "\n";
        write(client, engine, response);
        logInfo("Sent file info for: " + fileName);
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

    public List<String> queryTracker(String fileName) throws IOException {
        List<String> peers = new ArrayList<>();
        try (SocketChannel channel = SocketChannel.open(new InetSocketAddress(TRACKER_HOST.getIp(), TRACKER_HOST.getPort()))) {
            channel.configureBlocking(false);
            SSLEngine sslEngine = sslContext.createSSLEngine(TRACKER_HOST.getIp(), TRACKER_HOST.getPort());
            sslEngine.setUseClientMode(true);
            sslEngine.setNeedClientAuth(true);
            sslEngine.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
            sslEngine.beginHandshake();

            Selector selector = Selector.open();
            SSLChannelWrapper wrapper = new SSLChannelWrapper(channel, sslEngine, selector);
            String request = RequestInfor.QUERY + FIELD_SEPARATOR + new File(fileName).getName();
            wrapper.write(request + "\n");

            long startTime = System.currentTimeMillis();
            String response = null;
            while (System.currentTimeMillis() - startTime < SOCKET_TIMEOUT_MS) {
                response = wrapper.read();
                if (response != null) break;
                sleep(100);
            }
            wrapper.close();
            if (response != null && !response.isEmpty()) {
                peers.addAll(Arrays.asList(response.split(LIST_SEPARATOR)));
            }
        } catch (Exception e) {
            logError("Error querying tracker for file " + fileName + ": " + e.getMessage(), e);
            throw new IOException("Error querying tracker for file " + fileName, e);
        }
        return peers;
    }

    public Future<FileInfor> getFileInforFromPeers(PeerInfor peer, String fileName) {
        return executor.submit(() -> {
            logInfo("Searching for file " + fileName + " on peer: " + peer.getIp() + ":" + peer.getPort());
            try (SocketChannel channel = SocketChannel.open(new InetSocketAddress(peer.getIp(), peer.getPort()))) {
                channel.configureBlocking(false);
                SSLEngine sslEngine = sslContext.createSSLEngine(peer.getIp(), peer.getPort());
                sslEngine.setUseClientMode(true);
                sslEngine.setNeedClientAuth(true);
                sslEngine.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
                sslEngine.beginHandshake();

                Selector selector = Selector.open();
                SSLChannelWrapper wrapper = new SSLChannelWrapper(channel, sslEngine, selector);
                String request = RequestInfor.SEARCH + FIELD_SEPARATOR + new File(fileName).getName() + "\n";
                wrapper.write(request);
                wrapper.flush();
                long startTime = System.currentTimeMillis();
                String response = null;
                while (System.currentTimeMillis() - startTime < SOCKET_TIMEOUT_MS) {
                    response = wrapper.read();
                    if (response != null) break;
                    Thread.sleep(100);
                }
                wrapper.close();

                if (response == null || response.isEmpty()) {
                    logInfo("No response from peer " + peer + " for file " + fileName);
                    return null;
                }
                logInfo("Received response from peer " + peer + ": " + response);
                return parseFileInforResponse(response);
            } catch (IOException | InterruptedException e) {
                logError("Error connecting to peer " + peer + ": " + e.getMessage(), e);
                return null;
            }
        });
    }

    public Future<Integer> downloadFile(String fileName, String savePath, PeerInfor peerInfor) {
        return executor.submit(() -> {
            try {
                File saveFile = new File(savePath);
                int validationResult = validateSavePath(saveFile);
                if (validationResult != LogTag.I_SUCCESS) {
                    return validationResult;
                }

                FileInfor fileInfor = getFileInforFromPeers(peerInfor, fileName).get();
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
                        if (!future.get())
                            allChunksDownloaded = false;
                    }
                    if (allChunksDownloaded) {
                        logInfo("Completed downloading file: " + fileName + " to " + savePath);
                        return LogTag.I_SUCCESS;
                    } else {
                        logError("Failed to download all chunks for file: " + fileName, null);
                        return LogTag.I_FAILURE;
                    }
                }
            } catch (IOException e) {
                logError("Error downloading file " + fileName + ": " + e.getMessage(), e);
                return LogTag.I_ERROR;
            }
        });
    }

    private int validateSavePath(File saveFile) {
        File parentDir = saveFile.getParentFile();
        if (!parentDir.exists()) {
            logError("Save directory does not exist: " + parentDir, null);
            return LogTag.I_NOT_EXIST;
        }
        if (!parentDir.canWrite()) {
            logError("No write permission for directory: " + parentDir, null);
            return LogTag.I_NOT_PERMISSION;
        }
        return LogTag.I_SUCCESS;
    }

    private boolean downloadChunk(PeerInfor peer, String fileName, int chunkIndex, RandomAccessFile raf, String expectedHash) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (SocketChannel channel = SocketChannel.open(new InetSocketAddress(peer.getIp(), peer.getPort()))) {
                channel.configureBlocking(false);
                SSLEngine sslEngine = sslContext.createSSLEngine(peer.getIp(), peer.getPort());
                sslEngine.setUseClientMode(true);
                sslEngine.setNeedClientAuth(true);
                sslEngine.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
                sslEngine.beginHandshake();

                Selector selector = Selector.open();
                SSLChannelWrapper wrapper = new SSLChannelWrapper(channel, sslEngine, selector);
                String request = RequestInfor.GET_CHUNK + FIELD_SEPARATOR + fileName + FIELD_SEPARATOR + chunkIndex + "\n";
                wrapper.write(request);

                long startTime = System.currentTimeMillis();
                byte[] chunkData = null;
                while (System.currentTimeMillis() - startTime < SOCKET_TIMEOUT_MS) {
                    chunkData = wrapper.readChunk();
                    if (chunkData != null) break;
                    Thread.sleep(100);
                }
                wrapper.close();

                if (chunkData == null || chunkData.length < 4) {
                    logError("Don't receive chunk data for chunk " + chunkIndex + " from peer " + peer + " (attempt " + attempt + ")", null);
                    continue;
                }

                ByteBuffer buffer = ByteBuffer.wrap(chunkData);
                int receivedChunkIndex = buffer.getInt();
                if (receivedChunkIndex != chunkIndex) {
                    logError("Received chunk index " + receivedChunkIndex + " but expected " + chunkIndex + " from peer " + peer + " (attempt " + attempt + ")", null);
                    continue;
                }

                byte[] chunkBytes = new byte[chunkData.length - 4];
                buffer.get(chunkBytes);
                if (chunkBytes.length == 0) {
                    logError("Received empty chunk for chunk " + chunkIndex + " from peer " + peer + " (attempt " + attempt + ")", null);
                    continue;
                }

                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update(chunkBytes);
                String chunkHash = bytesToHex(md.digest());
                if (!chunkHash.equals(expectedHash)) {
                    logError("Hash mismatch for chunk " + chunkIndex + " from peer " + peer + " (attempt " + attempt + "). Expected: " + expectedHash + ", Received: " + chunkHash, null);
                    continue;
                }

                synchronized (raf) {
                    raf.seek((long) chunkIndex * CHUNK_SIZE);
                    raf.write(chunkBytes);
                }
                logInfo("Successfully downloaded chunk " + chunkIndex + " of file " + fileName + " from peer " + peer + " (attempt " + attempt + ")");
                return true;
            } catch (IOException | NoSuchAlgorithmException | InterruptedException e) {
                logError("Error downloading chunk " + chunkIndex + " from peer " + peer + " (attempt " + attempt + "): " + e.getMessage(), e);
            }
        }

        logError("Failed to download chunk " + chunkIndex + " of file " + fileName + " from peer " + peer + " after " + MAX_RETRIES + " attempts", null);
        return false;
    }

    private FileInfor parseFileInforResponse(String response) {
        if (response.isEmpty()) {
            logError("Empty response", null);
            return null;
        }
        if (!response.startsWith(RequestInfor.FILE_INFO)) {
            logInfo("File not found in response: " + response);
            return null;
        }
        String[] parts = response.split(FIELD_SEPARATOR_REGEX, -1);
        if (parts.length < 6) {
            logError("Invalid FILE_INFO response: " + response, null);
            return null;
        }
        try {
            String name = parts[1];
            long size = Long.parseLong(parts[2]);
            PeerInfor peerInfo = new PeerInfor(parts[3], Integer.parseInt(parts[4]));
            List<String> chunkHashes = Arrays.asList(parts[5].split(LIST_SEPARATOR));
            return new FileInfor(name, size, chunkHashes, peerInfo);
        } catch (NumberFormatException e) {
            logError("Invalid file info format: " + response, e);
            return null;
        }
    }

    @Override
    protected void write(SocketChannel socketChannel, SSLEngine engine, String message) throws IOException {
        logInfo("Writing to peer: " + message);
        myAppData.clear();
        myAppData.put(message.getBytes());
        myAppData.flip();
        while (myAppData.hasRemaining()) {
            myNetData.clear();
            SSLEngineResult result = engine.wrap(myAppData, myNetData);
            switch (result.getStatus()) {
                case OK:
                    myNetData.flip();
                    while (myNetData.hasRemaining()) {
                        socketChannel.write(myNetData);
                    }
                    break;
                case BUFFER_OVERFLOW:
                    myNetData = enlargePacketBuffer(engine, myNetData);
                    break;
                case BUFFER_UNDERFLOW:
                    throw new SSLException("Buffer underflow occurred after a wrap.");
                case CLOSED:
                    closeConnection(socketChannel, engine);
                    return;
                default:
                    throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
            }
        }
    }

    @Override
    protected void read(SocketChannel socketChannel, SSLEngine engine) throws Exception {
        logInfo("Reading from peer...");
        peerNetData.clear();
        int waitToReadMillis = 50;
        boolean exitReadLoop = false;
        while (!exitReadLoop) {
            int bytesRead = socketChannel.read(peerNetData);
            if (bytesRead > 0) {
                peerNetData.flip();
                while (peerNetData.hasRemaining()) {
                    peerAppData.clear();
                    SSLEngineResult result = engine.unwrap(peerNetData, peerAppData);
                    switch (result.getStatus()) {
                        case OK:
                            peerAppData.flip();
                            logInfo("Received: " + new String(peerAppData.array(), 0, peerAppData.limit()).trim());
                            exitReadLoop = true;
                            break;
                        case BUFFER_OVERFLOW:
                            peerAppData = enlargeApplicationBuffer(engine, peerAppData);
                            break;
                        case BUFFER_UNDERFLOW:
                            peerNetData = handleBufferUnderflow(engine, peerNetData);
                            break;
                        case CLOSED:
                            closeConnection(socketChannel, engine);
                            return;
                        default:
                            throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
                    }
                }
            } else if (bytesRead < 0) {
                handleEndOfStream(socketChannel, engine);
                return;
            }
            Thread.sleep(waitToReadMillis);
        }
    }

    public int registerWithTracker() throws InterruptedException {
        for (int i = 0; i < MAX_RETRIES; i++) {
            try (SocketChannel channel = SocketChannel.open()) {
                channel.configureBlocking(false);
                channel.connect(new InetSocketAddress(TRACKER_HOST.getIp(), TRACKER_HOST.getPort()));
                SSLEngine engine = sslContext.createSSLEngine(TRACKER_HOST.getIp(), TRACKER_HOST.getPort());
                engine.setUseClientMode(true);
                engine.setNeedClientAuth(true);
                engine.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
                engine.beginHandshake();

                if (doHandshake(channel, engine)) {
                    String message = RequestInfor.REGISTER + FIELD_SEPARATOR + SERVER_HOST.getIp() + FIELD_SEPARATOR + SERVER_HOST.getPort() + "\n";
                    write(channel, engine, message);
                    String response = readResponse(channel, engine);
                    return parseTrackerResponse(response);
                }
            } catch (Exception e) {
                logError("Cannot connect to tracker on attempt " + (i + 1) + ": " + e.getMessage(), e);
                sleep(1000);
            }
        }
        logError("Failed to register with tracker after " + MAX_RETRIES + " attempts", null);
        return LogTag.I_ERROR;
    }

    private String readResponse(SocketChannel channel, SSLEngine engine) throws Exception {
        read(channel, engine); // Sử dụng phương thức override
        peerAppData.flip();
        byte[] data = new byte[peerAppData.remaining()];
        peerAppData.get(data);
        return new String(data).trim();
    }

    private int parseTrackerResponse(String response) {
        if (response == null || response.isEmpty()) {
            logInfo("Tracker did not respond");
            return LogTag.I_ERROR;
        }
        if (response.startsWith(RequestInfor.SHARED_LIST)) {
            String[] parts = response.split(Infor.FIELD_SEPARATOR_REGEX, -1);
            if (parts.length >= 3) {
                String[] fileList = parts[2].split(Infor.LIST_SEPARATOR);
                for (String fileInfo : fileList) {
                    FileBase fileBase = parseFileBase(fileInfo);
                    if (fileBase != null) sharedFileNames.add(fileBase);
                }
                logInfo("Total files shared by tracker: " + sharedFileNames.size());
                return LogTag.I_SUCCESS;
            }
            logError("Invalid response format from tracker: " + response, null);
            return LogTag.I_INVALID;
        } else if (response.startsWith(RequestInfor.REGISTERED)) {
            return LogTag.I_NOT_FOUND;
        }
        logError("Tracker registration failed: " + response, null);
        return LogTag.I_ERROR;
    }

    private FileBase parseFileBase(String fileInfo) {
        if (fileInfo == null || fileInfo.isEmpty()) return null;
        String[] parts = fileInfo.split(Infor.FILE_SEPARATOR, -1);
        if (parts.length != 4) return null;
        try {
            String fileName = parts[0];
            long fileSize = Long.parseLong(parts[1]);
            PeerInfor peerInfo = new PeerInfor(parts[2], Integer.parseInt(parts[3]));
            return new FileBase(fileName, fileSize, peerInfo);
        } catch (NumberFormatException e) {
            logError("Invalid file info format: " + fileInfo, e);
            return null;
        }
    }

    public boolean shareFile(String filePath) {
        File file = new File(filePath);
        if (!file.exists() || !file.canRead()) {
            logError("File không tồn tại hoặc không đọc được: " + filePath, null);
            return false;
        }
        List<String> chunkHashes = computeChunkHashes(file);
        mySharedFiles.put(file.getName(), new FileInfor(file.getName(), file.length(), chunkHashes, SERVER_HOST));
        logInfo("Shared file: " + file.getName());
        notifyTracker(file.getName(), true);
        return true;
    }

    private List<String> computeChunkHashes(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] chunk = new byte[CHUNK_SIZE];
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            List<String> chunkHashes = new ArrayList<>();
            int bytesRead;
            while ((bytesRead = raf.read(chunk)) != -1) {
                md.update(chunk, 0, bytesRead);
                chunkHashes.add(bytesToHex(md.digest()));
            }
            return chunkHashes;
        } catch (IOException | NoSuchAlgorithmException e) {
            logError("Error computing chunk hashes: " + e.getMessage(), e);
            return null;
        }
    }

    private String bytesToHex(byte[] digest) {
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void notifyTracker(String fileName, boolean isShared) {
        try (SocketChannel channel = SocketChannel.open()) {
            channel.configureBlocking(false);
            channel.connect(new InetSocketAddress(TRACKER_HOST.getIp(), TRACKER_HOST.getPort()));
            SSLEngine engine = sslContext.createSSLEngine(TRACKER_HOST.getIp(), TRACKER_HOST.getPort());
            engine.setUseClientMode(true);
            engine.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
            if (doHandshake(channel, engine)) {
                String message = (isShared ? RequestInfor.SHARE : RequestInfor.UNSHARED_FILE) + FIELD_SEPARATOR + fileName
                        + FIELD_SEPARATOR + SERVER_HOST.getIp() + FIELD_SEPARATOR + SERVER_HOST.getPort() + "\n";
                write(channel, engine, message);
                logInfo("Notified tracker about " + (isShared ? "sharing" : "unsharing") + " file: " + fileName);
            }
        } catch (IOException e) {
            logError("Error notifying tracker: " + e.getMessage(), e);
        }
    }

    private void sendChunk(SocketChannel client, SSLEngine engine, String fileName, int chunkIndex) throws IOException {
        FileInfor fileInfor = mySharedFiles.get(fileName);
        if (fileInfor == null) {
            logError("File not found: " + fileName, null);
            return;
        }
        String filePath = GetDir.getSharedFilePath(fileName);
        File file = new File(filePath);
        if (!file.exists() || !file.canRead()) {
            logError("File not readable: " + filePath, null);
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] chunk = new byte[CHUNK_SIZE];
            raf.seek((long) chunkIndex * CHUNK_SIZE);
            int bytesRead = raf.read(chunk);
            if (bytesRead > 0) {
                String response = chunkIndex + FIELD_SEPARATOR
                        + Base64.getEncoder().encodeToString(Arrays.copyOf(chunk, bytesRead)) + "\n";
                write(client, engine, response);
                logInfo("Sent chunk " + chunkIndex + " of " + fileName);
            }
        }
    }

    public void loadSharedFiles() {
        String path = GetDir.getSharedDir();
        File sharedDir = new File(path);
        if (!sharedDir.exists() || !sharedDir.isDirectory()) {
            logError("Shared folder does not exist or is not a directory: " + path, null);
            return;
        }

        File[] files = sharedDir.listFiles();
        if (files == null || files.length == 0) {
            logInfo("No shared files found in directory: " + path);
            return;
        }

        for (File file : files) {
            if (file.isFile()) {
                List<String> chunkHashes = computeChunkHashes(file);
                if (chunkHashes != null) {
                    synchronized (mySharedFiles) {
                        mySharedFiles.put(file.getName(), new FileInfor(file.getName(), file.length(), chunkHashes, SERVER_HOST));
                    }
                    logInfo("Loaded shared file: " + file.getName() + ", size: " + file.length() + " bytes, chunks: " + chunkHashes.size());
                }
            }
        }
    }

    public void shareFileList() {
        Set<FileBase> files = convertSharedFilesToFileBase();

        try (SocketChannel socket = SocketChannel.open(new InetSocketAddress(TRACKER_HOST.getIp(), TRACKER_HOST.getPort()))) {
            socket.configureBlocking(false);
            SSLEngine sslEngine = sslContext.createSSLEngine(TRACKER_HOST.getIp(), TRACKER_HOST.getPort());
            sslEngine.setUseClientMode(true);
            sslEngine.setNeedClientAuth(true);
            sslEngine.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
            sslEngine.beginHandshake();
            Selector selector = Selector.open();
            SSLChannelWrapper wrapper = new SSLChannelWrapper(socket, sslEngine, selector);

            StringBuilder message = new StringBuilder(RequestInfor.SHARED_LIST + FIELD_SEPARATOR + files.size() + FIELD_SEPARATOR);
            for (FileBase file : files) {
                message.append(file.getFileName()).append(Infor.FILE_SEPARATOR)
                        .append(file.getFileSize()).append(Infor.FILE_SEPARATOR);
            }
            if (message.charAt(message.length() - 1) == LIST_SEPARATOR.charAt(0)) {
                message.deleteCharAt(message.length() - 1);
            }
            message.append(FIELD_SEPARATOR)
                    .append(SERVER_HOST.getIp()).append(FIELD_SEPARATOR)
                    .append(SERVER_HOST.getPort()).append("\n");

            wrapper.write(message.toString());
            wrapper.flush();
            wrapper.close();
            logInfo("Sent shared file list to tracker: " + message.toString());
        } catch (IOException e) {
            logError("Error sending shared file list to tracker: " + e.getMessage(), e);
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


    public int refreshSharedFileNames() {
        try (SocketChannel socket = SocketChannel.open(new InetSocketAddress(TRACKER_HOST.getIp(), TRACKER_HOST.getPort()))) {
            socket.configureBlocking(false);
            SSLEngine sslEngine = sslContext.createSSLEngine(TRACKER_HOST.getIp(), TRACKER_HOST.getPort());
            sslEngine.setUseClientMode(true);
            sslEngine.setNeedClientAuth(true);
            sslEngine.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
            sslEngine.beginHandshake();
            Selector selector = Selector.open();
            SSLChannelWrapper wrapper = new SSLChannelWrapper(socket, sslEngine, selector);

            String msg = RequestInfor.REFRESH + FIELD_SEPARATOR + SERVER_HOST.getIp() + FIELD_SEPARATOR + SERVER_HOST.getPort() + "\n";
            wrapper.write(msg);

            long startTime = System.currentTimeMillis();
            String response = null;
            while (System.currentTimeMillis() - startTime < SOCKET_TIMEOUT_MS) {
                response = wrapper.read();
                if (response != null) break;
                Thread.sleep(100);
            }
            wrapper.close();

            if (response.startsWith(RequestInfor.REFRESHED)) {
                String[] parts = response.split(FIELD_SEPARATOR_REGEX, -1);
                if (parts.length != 3) {
                    logError("Invalid response format from tracker: " + response, null);
                    return LogTag.I_INVALID;
                }

                int fileCount = Integer.parseInt(parts[1]);
                if (fileCount < 0) {
                    logError("Số lượng file chia sẻ không hợp lệ: " + fileCount, null);
                    return LogTag.I_INVALID;
                }
                String[] fileList = parts[2].split(LIST_SEPARATOR);

                if (fileList.length != fileCount) {
                    logError("File count mismatch: expected " + fileCount + ", got " + fileList.length, null);
                    return LogTag.I_INVALID;
                }

                sharedFileNames.clear();

                for (String fileInfo : fileList) {
                    FileBase fileBase = parseFileBase(fileInfo);
                    if (fileBase != null)
                        sharedFileNames.add(fileBase);
                }
                logInfo("Refreshed shared file names from tracker, total files: " + sharedFileNames.size());
                return LogTag.I_SUCCESS;
            }
            logError("Tracker did not respond with REFRESHED message: " + response, null);
            return LogTag.I_INVALID;
        } catch (Exception e) {
            logError("Error refreshing shared file names: " + e.getMessage(), e);
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
            String filePath = GetDir.getDir() + "\\shared_files\\" + fileName;
            File file = new File(filePath);
            if (file.exists() && file.delete()) {
                logInfo("Removed file from shared directory: " + filePath);
            } else {
                logError("Failed to delete file from shared directory: " + filePath, null);
            }
            logInfo("Stopped sharing file: " + fileName);
            notifyTracker(fileName, false);
        } else {
            logError("File " + fileName + " is not being shared, cannot stop sharing.", null);
        }
    }
}