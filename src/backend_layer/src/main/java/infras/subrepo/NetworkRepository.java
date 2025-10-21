package main.java.infras.subrepo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import main.java.domain.adapter.FileInfoAdapter;
import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.domain.repository.INetworkRepository;
import main.java.domain.repository.IPeerRepository;
import main.java.infras.utils.FileUtils;
import main.java.utils.AppPaths;
import main.java.utils.Config;
import main.java.utils.Log;
import main.java.utils.LogTag;
import main.java.infras.utils.SSLUtils;

import javax.net.ssl.*;
import java.io.*;
import java.lang.reflect.Type;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class NetworkRepository implements INetworkRepository {

    private final IPeerRepository peerModel;
    private final ExecutorService executorService;
    private boolean isRunning;

    public NetworkRepository(IPeerRepository peerModel) {
        this.isRunning = true;
        this.peerModel = peerModel;
        this.executorService = Executors.newFixedThreadPool(8);
    }

    @Override
    public void initializeServerSocket(String username) throws Exception {
        FileUtils.loadData(username, peerModel.getPublicSharedFiles(), peerModel.getPrivateSharedFiles());
        peerModel.setSelector(Selector.open());
        peerModel.setSslContext(SSLUtils.createSSLContext());
        peerModel.setSslEngineMap(new ConcurrentHashMap<>());
        peerModel.setPendingData(new ConcurrentHashMap<>());
        peerModel.setChannelAttachments(new ConcurrentHashMap<>());

        try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
            serverChannel.configureBlocking(false);
            serverChannel.socket().bind(new InetSocketAddress(Config.SERVER_IP, Config.PEER_PORT));
            serverChannel.register(peerModel.getSelector(), SelectionKey.OP_ACCEPT);

            Log.logInfo("Non-blocking SSL server socket initialized on " + Config.SERVER_IP + ":" + Config.PEER_PORT);
        }
    }

    @Override
    public void startServer() {
        this.executorService.submit(() -> {
            Log.logInfo("Starting non-blocking SSL server loop...");

            try {
                while (isRunning) {
                    int readyChannels = peerModel.getSelector().select(1000L);

                    if (readyChannels > 0 || peerModel.getPendingData().values().stream().anyMatch(buff -> buff != null && buff.hasRemaining())) {
                        for (SelectionKey key : peerModel.getSelector().selectedKeys()) {
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
                        peerModel.getSelector().selectedKeys().clear();

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

    @Override
    public void startUDPServer() {
        this.executorService.submit(() -> {
            try (DatagramSocket udpSocket = new DatagramSocket(Config.PEER_PORT)) {
                byte[] buffer = new byte[1024];
                Log.logInfo("UDP server started on " + Config.SERVER_IP + ":" + Config.PEER_PORT);

                while (isRunning) {
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

    @Override
    public int registerWithTracker() {
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        int count = 0;

        while (count < Config.MAX_RETRIES) {
            try (SSLSocket sslSocket = SSLUtils.createSecureSocket(new PeerInfo(Config.TRACKER_IP, Config.TRACKER_PORT))) {
                Log.logInfo("Established SSL connection to tracker");

                Gson gson = new GsonBuilder().registerTypeAdapter(FileInfo.class, new FileInfoAdapter())
                        .enableComplexMapKeySerialization().create();
                Type publicFileType = new TypeToken<Map<String, FileInfo>>() {
                }.getType();
                String publicFileToPeersJson = gson.toJson(peerModel.getPublicSharedFiles(), publicFileType);
                Type privateFileType = new TypeToken<Map<FileInfo, Set<PeerInfo>>>() {
                }.getType();
                String privateSharedFileJson = gson.toJson(peerModel.getPrivateSharedFiles(), privateFileType);

                String message = "REGISTER|" + Config.SERVER_IP + "|" + Config.PEER_PORT +
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
            } catch (Exception e) {
                Log.logError("SSL Error connecting to tracker: " + e.getMessage(), e);
                return 0;
            }
        }

        Log.logError("Cannot establish SSL connection to tracker after multiple attempts.", null);
        return 0;
    }

    private void shutdown() {
        Log.logInfo("Shutting down SSL server...");
        isRunning = false;

        peerModel.getExecutor().shutdown();

        try {
            if (!peerModel.getExecutor().awaitTermination(5L, TimeUnit.SECONDS)) {
                peerModel.getExecutor().shutdownNow();
                Log.logInfo("Executor service forcefully shut down.");
            }
        } catch (InterruptedException e) {
            peerModel.getExecutor().shutdownNow();
            Thread.currentThread().interrupt();
            Log.logError("Executor service interrupted during shutdown: " + e.getMessage(), e);
        }

        Log.logInfo("SSL server shutdown complete.");
    }

    private void closeChannel(java.nio.channels.SelectableChannel channel) {
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
        try (ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel()) {
            SocketChannel socketChannel = serverChannel.accept();

            if (socketChannel != null) {
                socketChannel.configureBlocking(false);
                Log.logInfo("Accepted SSL connection from: " + socketChannel.getRemoteAddress());

                SSLEngine sslEngine = peerModel.getSslContext().createSSLEngine();
                sslEngine.setUseClientMode(false);
                sslEngine.setNeedClientAuth(false);

                SSLSession session = sslEngine.getSession();
                int netBufferSize = session.getPacketBufferSize();
                int appBufferSize = session.getApplicationBufferSize();

                ByteBuffer netInBuffer = ByteBuffer.allocate(netBufferSize);
                ByteBuffer appInBuffer = ByteBuffer.allocate(appBufferSize);

                Map<String, Object> connectionData = new HashMap<>();
                connectionData.put("netInBuffer", netInBuffer);
                connectionData.put("appInBuffer", appInBuffer);

                peerModel.getChannelAttachments().put(socketChannel, connectionData);
                peerModel.getSslEngineMap().put(socketChannel, sslEngine);

                socketChannel.register(peerModel.getSelector(), SelectionKey.OP_READ, netInBuffer);

                sslEngine.beginHandshake();

                Log.logInfo("SSL connection setup complete for: " + socketChannel.getRemoteAddress());
            }
        }
    }

    private void handleSSLRead(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        SSLEngine sslEngine = peerModel.getSslEngineMap().get(socketChannel);

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
        ByteBuffer pendingData = this.peerModel.getPendingData().get(socketChannel);

        if (pendingData != null && pendingData.hasRemaining()) {
            socketChannel.write(pendingData);
            if (!pendingData.hasRemaining()) {
                this.peerModel.getPendingData().remove(socketChannel);
                key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            }
        } else {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
        }
    }

    private void processSSLData(SocketChannel socketChannel, SSLEngine sslEngine, ByteBuffer netInBuffer) {
        Map<String, Object> connectionData = peerModel.getChannelAttachments().get(socketChannel);
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
                    appInBuffer.flip();
                    processApplicationData(socketChannel, appInBuffer);
                    appInBuffer.compact();
                    break;
                case BUFFER_OVERFLOW:
                    Log.logInfo("SSL buffer overflow, resizing application buffer");
                    resizeApplicationBuffer(sslEngine, connectionData);
                    break;
                case BUFFER_UNDERFLOW:
                    break;
                case CLOSED:
                    Log.logInfo("SSL engine closed for: " + socketChannel.getRemoteAddress());
                    closeChannel(socketChannel);
                    break;
            }

            if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                runHandshakeTasks(sslEngine);
            }

        } catch (Exception e) {
            Log.logError("SSL error processing data: " + e.getMessage(), e);
            closeChannel(socketChannel);
        }
    }

    private void processApplicationData(SocketChannel socketChannel, ByteBuffer appBuffer) {
        StringBuilder requestBuilder = new StringBuilder();
        while (appBuffer.hasRemaining()) {
            requestBuilder.append((char) appBuffer.get());
        }
        String request = requestBuilder.toString();
        if (!request.isEmpty()) {
            try {
                Log.logInfo("Received SSL application data: [" + request.trim() + "] from " + socketChannel.getRemoteAddress());
                String response = this.processSSLRequest(socketChannel, request.trim());
                sendSSLResponse(socketChannel, response);
            } catch (Exception e) {
                Log.logError("Error processing SSL request: " + e.getMessage(), e);
            }
        }
    }

    private void sendSSLResponse(SocketChannel socketChannel, String response) {
        SSLEngine sslEngine = peerModel.getSslEngineMap().get(socketChannel);
        if (sslEngine == null) {
            Log.logError("SSL engine not found for response", null);
            return;
        }

        try {
            ByteBuffer responseBuffer = ByteBuffer.wrap(response.getBytes());
            ByteBuffer netBuffer = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
            SSLEngineResult result = sslEngine.wrap(responseBuffer, netBuffer);

            if (result.getStatus() == SSLEngineResult.Status.OK) {
                netBuffer.flip();
                peerModel.getPendingData().put(socketChannel, netBuffer);
                SelectionKey key = socketChannel.keyFor(peerModel.getSelector());
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
            peerModel.getExecutor().submit(task);
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
        for (Map.Entry<SocketChannel, SSLEngine> entry : peerModel.getSslEngineMap().entrySet()) {
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

    private void sendPongResponse(DatagramSocket udpSocket, DatagramPacket receivedPacket) throws IOException {
        String response = "PONG|" + AppPaths.loadUsername();
        byte[] pongData = response.getBytes();
        DatagramPacket pongPacket = new DatagramPacket(pongData, pongData.length, receivedPacket.getAddress(), receivedPacket.getPort());
        udpSocket.send(pongPacket);
        Log.logInfo("Pong response: " + response + " sent to " + receivedPacket.getAddress() + ":" + receivedPacket.getPort());
    }

    @Override
    public String processSSLRequest(SocketChannel socketChannel, String request) {
        try {
            String clientIP = socketChannel.getRemoteAddress().toString().split(":")[0].replace("/", "");
            PeerInfo clientIdentifier = new PeerInfo(clientIP, Config.PEER_PORT);

            if (request.startsWith("SEARCH")) {
                String fileName = request.split("\\|")[1];
                Map<String, FileInfo> publicSharedFiles = peerModel.getPublicSharedFiles();
                FileInfo fileInfo = publicSharedFiles.get(fileName);
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
        } catch (IOException e) {
            Log.logError("Error processing request: " + e.getMessage(), e);
        }
        return "UNKNOWN_REQUEST\n";
    }

    private String getChunkData(String fileHash, int chunkIndex) {
        FileInfo fileInfo = findFileByHash(fileHash);
        if (fileInfo == null) {
            return "FILE_NOT_FOUND\n";
        }

        String appPath = main.java.utils.AppPaths.getAppDataDirectory();
        String filePath = appPath + "/shared_files/" + fileInfo.getFileName();
        File file = new File(filePath);

        if (file.exists() && file.canRead()) {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                byte[] chunkData = new byte[Config.CHUNK_SIZE];
                raf.seek((long) chunkIndex * (long) Config.CHUNK_SIZE);
                int byteRead = raf.read(chunkData);

                if (byteRead > 0) {
                    byte[] actualData = byteRead == Config.CHUNK_SIZE ? chunkData : Arrays.copyOf(chunkData, byteRead);
                    return "CHUNK_DATA|" + chunkIndex + "|" + Base64.getEncoder().encodeToString(actualData) + "\n";
                }
            } catch (IOException e) {
                Log.logError("Error reading chunk data: " + e.getMessage(), e);
            }
        }
        return "CHUNK_ERROR\n";
    }

    private FileInfo findFileByHash(String fileHash) {
        Map<String, FileInfo> publicSharedFiles = peerModel.getPublicSharedFiles();
        for (FileInfo file : publicSharedFiles.values()) {
            if (file.getFileHash().equals(fileHash)) {
                return file;
            }
        }
        Map<FileInfo, Set<PeerInfo>> privateSharedFiles = peerModel.getPrivateSharedFiles();
        for (FileInfo file : privateSharedFiles.keySet()) {
            if (file.getFileHash().equals(fileHash)) {
                return file;
            }
        }
        return null;
    }

    private boolean hasAccessToFile(PeerInfo clientIdentify, String fileHash) {
        Map<String, FileInfo> publicSharedFiles = peerModel.getPublicSharedFiles();
        for (FileInfo file : publicSharedFiles.values()) {
            if (file.getFileHash().equals(fileHash)) {
                return true;
            }
        }
        List<PeerInfo> selectivePeers = peerModel.getSelectivePeers(fileHash);
        return selectivePeers.stream().anyMatch(p -> p.getIp().equals(clientIdentify.getIp()));
    }
}
