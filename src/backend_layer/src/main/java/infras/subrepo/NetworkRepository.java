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
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;

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
        this.executorService = Executors.newFixedThreadPool(20);
    }

    @Override
    public void initializeServerSocket(String username) throws Exception {
        FileUtils.loadData(username, peerModel.getPublicSharedFiles(), peerModel.getPrivateSharedFiles());
        peerModel.setSelector(Selector.open());
        peerModel.setSslContext(SSLUtils.createSSLContext());
        peerModel.setSslEngineMap(new ConcurrentHashMap<>());
        peerModel.setPendingData(new ConcurrentHashMap<>());
        peerModel.setChannelAttachments(new ConcurrentHashMap<>());

        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.socket().bind(new InetSocketAddress(Config.SERVER_IP, Config.PEER_PORT));
        serverChannel.register(peerModel.getSelector(), SelectionKey.OP_ACCEPT);

        Log.logInfo("Non-blocking SSL server socket initialized on " + Config.SERVER_IP + ":" + Config.PEER_PORT);
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

        executorService.shutdown();

        try {
            if (!executorService.awaitTermination(5L, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
                Log.logInfo("Executor service forcefully shut down.");
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
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
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel socketChannel = serverChannel.accept();

        if (socketChannel != null) {
            socketChannel.configureBlocking(false);
            Log.logInfo("Accepted SSL connection from: " + socketChannel.getRemoteAddress());

            SSLEngine sslEngine = peerModel.getSslContext().createSSLEngine();
            sslEngine.setUseClientMode(false);
            sslEngine.setNeedClientAuth(true);

            sslEngine.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
            sslEngine.setEnabledCipherSuites(sslEngine.getSupportedCipherSuites()); // enable all supported

            SSLSession session = sslEngine.getSession();
            int netBufferSize = Math.max(session.getPacketBufferSize(), 16 * 1024);
            int appBufferSize = Math.max(session.getApplicationBufferSize(), 16 * 1024);
            int maxAccumulatorSize = 65536;

            ByteBuffer netInBuffer = ByteBuffer.allocate(netBufferSize);
            ByteBuffer appInBuffer = ByteBuffer.allocate(appBufferSize);
            ByteBuffer appDataAccumulator = ByteBuffer.allocate(maxAccumulatorSize); // SỬA: Dùng ByteBuffer

            Log.logInfo("SSLEngine protocols: " + Arrays.toString(sslEngine.getEnabledProtocols()));
            Log.logInfo("SSLEngine ciphers: " + Arrays.toString(sslEngine.getEnabledCipherSuites()));

            Map<String, Object> connectionData = new HashMap<>();
            connectionData.put("netInBuffer", netInBuffer);
            connectionData.put("appInBuffer", appInBuffer);
            connectionData.put("appData", appDataAccumulator);
            connectionData.put("pendingQueue", new ArrayDeque<ByteBuffer>());
            peerModel.getChannelAttachments().put(socketChannel, connectionData);
            peerModel.getSslEngineMap().put(socketChannel, sslEngine);

            socketChannel.register(peerModel.getSelector(), SelectionKey.OP_READ, netInBuffer);

            sslEngine.beginHandshake();

            Log.logInfo("SSL connection setup complete for: " + socketChannel.getRemoteAddress());
        }
    }

    private void handleSSLRead(SelectionKey key) throws IOException {
        System.out.println("Handling SSL read");
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
        Map<String, Object> connectionData = this.peerModel.getChannelAttachments().get(socketChannel);
        ByteBuffer pendingData = this.peerModel.getPendingData().get(socketChannel);

        if (pendingData == null) {
            ByteBuffer nextBuffer = pollPendingBuffer(connectionData);
            if (nextBuffer != null) {
                this.peerModel.getPendingData().put(socketChannel, nextBuffer);
                pendingData = nextBuffer;
            }
        }

        if (pendingData != null) {
            socketChannel.write(pendingData);
            if (!pendingData.hasRemaining()) {
                this.peerModel.getPendingData().remove(socketChannel, pendingData);
                ByteBuffer nextBuffer = pollPendingBuffer(connectionData);
                if (nextBuffer != null) {
                    this.peerModel.getPendingData().put(socketChannel, nextBuffer);
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                } else {
                    key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                }
            } else {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
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

        while (netInBuffer.hasRemaining()) {
            SSLEngineResult result;
            try {
                result = sslEngine.unwrap(netInBuffer, appInBuffer);
            } catch (SSLException e) {
                Log.logError("SSL error processing data: " + e.getMessage(), e);
                closeChannel(socketChannel);
                return;
            } catch (Exception e) {
                Log.logError("Unexpected error processing data: " + e.getMessage(), e);
                closeChannel(socketChannel);
                return;
            }

            switch (result.getStatus()) {
                case OK -> {
                    if (result.bytesProduced() > 0 && isHandshakeComplete(sslEngine)) {
                        appInBuffer.flip();
                        processApplicationData(socketChannel, appInBuffer);
                        appInBuffer.compact();
                    }
                }
                case BUFFER_OVERFLOW -> {
                    Log.logInfo("SSL buffer overflow, resizing application buffer");
                    resizeApplicationBuffer(sslEngine, connectionData);
                    appInBuffer = (ByteBuffer) connectionData.get("appInBuffer");
                    continue;
                }
                case BUFFER_UNDERFLOW -> {
                    handleHandshakeStatus(socketChannel, sslEngine, result.getHandshakeStatus());
                    return;
                }
                case CLOSED -> {
                    Log.logInfo("SSL engine closed for: " + socketChannel);
                    closeChannel(socketChannel);
                    return;
                }
            }

            handleHandshakeStatus(socketChannel, sslEngine, result.getHandshakeStatus());
        }

        handleHandshakeStatus(socketChannel, sslEngine, sslEngine.getHandshakeStatus());
    }

    private void processApplicationData(SocketChannel socketChannel, ByteBuffer appBuffer) {
        Map<String, Object> connectionData = peerModel.getChannelAttachments().get(socketChannel);
        if (connectionData == null) {
            Log.logError("Application buffer missing connection data", null);
            closeChannel(socketChannel);
            return;
        }

        ByteBuffer accumulator = (ByteBuffer) connectionData.get("appData");

        if (!appBuffer.hasRemaining()) {
            return;
        }

        if (accumulator.remaining() < appBuffer.remaining()) {
            Log.logError("Accumulator capacity exceeded. Message dropped or connection closed.", null);
            closeChannel(socketChannel);
            return;
        }

        accumulator.put(appBuffer);

        accumulator.flip();

        int processedPosition = accumulator.position();

        while (processedPosition < accumulator.limit()) {
            int newlineIndex = -1;

            for (int i = processedPosition; i < accumulator.limit(); i++) {
                if (accumulator.get(i) == '\n') {
                    newlineIndex = i;
                    break;
                }
            }

            if (newlineIndex >= 0) {
                int messageLength = newlineIndex - processedPosition;
                byte[] messageBytes = new byte[messageLength];

                accumulator.get(processedPosition, messageBytes, 0, messageLength);

                String message = new String(messageBytes, StandardCharsets.UTF_8).trim();

                processedPosition = newlineIndex + 1;

                if (!message.isEmpty()) {
                    try {
                        Log.logInfo("Received SSL application data: [" + message + "] from " + socketChannel.getRemoteAddress());
                        this.processSSLRequest(socketChannel, message);
                    } catch (Exception e) {
                        Log.logError("Error processing SSL request: " + e.getMessage(), e);
                    }
                }
            } else {
                break;
            }
        }

        if (processedPosition > accumulator.position()) {
            accumulator.position(processedPosition);
        }

        accumulator.compact();
    }

    private void queuePendingData(SocketChannel socketChannel, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return;
        }

        Map<String, Object> connectionData = peerModel.getChannelAttachments().get(socketChannel);
        if (connectionData == null) {
            Log.logError("Missing connection data for pending queue", null);
            closeChannel(socketChannel);
            return;
        }

        ByteBuffer data = ByteBuffer.allocate(buffer.remaining());
        data.put(buffer);
        data.flip();

        synchronized (connectionData) {
            ByteBuffer current = peerModel.getPendingData().get(socketChannel);
            if (current == null) {
                peerModel.getPendingData().put(socketChannel, data);
            } else {
                @SuppressWarnings("unchecked")
                ArrayDeque<ByteBuffer> queue = (ArrayDeque<ByteBuffer>) connectionData.computeIfAbsent("pendingQueue", key -> new ArrayDeque<>());
                queue.add(data);
            }
        }

        SelectionKey key = socketChannel.keyFor(peerModel.getSelector());
        if (key != null) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        }

        Selector selector = peerModel.getSelector();
        if (selector != null) {
            selector.wakeup();
        }
    }

    private ByteBuffer pollPendingBuffer(Map<String, Object> connectionData) {
        if (connectionData == null) {
            return null;
        }

        synchronized (connectionData) {
            @SuppressWarnings("unchecked")
            ArrayDeque<ByteBuffer> queue = (ArrayDeque<ByteBuffer>) connectionData.get("pendingQueue");
            if (queue == null) {
                return null;
            }

            ByteBuffer next = queue.poll();
            if (next != null) {
                return next;
            }
        }

        return null;
    }

    private void wrapAndQueue(SocketChannel socketChannel, SSLEngine sslEngine, ByteBuffer source) {
        int netBufferSize = sslEngine.getSession().getPacketBufferSize();

        while (source.hasRemaining()) {
            ByteBuffer netBuffer = ByteBuffer.allocate(netBufferSize);
            SSLEngineResult result;

            try {
                result = sslEngine.wrap(source, netBuffer);
            } catch (SSLException e) {
                Log.logError("Error sending SSL data: " + e.getMessage(), e);
                closeChannel(socketChannel);
                return;
            }

            switch (result.getStatus()) {
                case OK -> {
                    netBuffer.flip();
                    queuePendingData(socketChannel, netBuffer);
                }
                case BUFFER_OVERFLOW -> {
                    netBufferSize = Math.max(netBufferSize * 2, sslEngine.getSession().getPacketBufferSize());
                    continue;
                }
                case CLOSED -> {
                    closeChannel(socketChannel);
                    return;
                }
                default -> {
                    Log.logError("Unexpected SSL wrap status: " + result.getStatus(), null);
                    return;
                }
            }

            handleHandshakeStatus(socketChannel, sslEngine, result.getHandshakeStatus());
        }

        handleHandshakeStatus(socketChannel, sslEngine, sslEngine.getHandshakeStatus());
    }

    private void handleHandshakeStatus(SocketChannel channel, SSLEngine engine, SSLEngineResult.HandshakeStatus status) {
        SSLEngineResult.HandshakeStatus current = status;

        while (current != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING &&
                current != SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
            switch (current) {
                case NEED_TASK -> {
                    boolean ranTask = runHandshakeTasks(engine);
                    SSLEngineResult.HandshakeStatus nextStatus = engine.getHandshakeStatus();
                    if (!ranTask && nextStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                        return;
                    }
                    current = nextStatus;
                }
                case NEED_WRAP -> {
                    ByteBuffer emptySrc = ByteBuffer.allocate(0);
                    int bufferSize = engine.getSession().getPacketBufferSize();

                    while (true) {
                        ByteBuffer netBuffer = ByteBuffer.allocate(bufferSize);

                        try {
                            SSLEngineResult wrapResult = engine.wrap(emptySrc, netBuffer);

                            switch (wrapResult.getStatus()) {
                                case OK -> {
                                    if (netBuffer.position() > 0) {
                                        netBuffer.flip();
                                        queuePendingData(channel, netBuffer);
                                    }
                                    current = wrapResult.getHandshakeStatus();
                                }
                                case BUFFER_OVERFLOW -> {
                                    bufferSize = Math.max(bufferSize * 2, engine.getSession().getPacketBufferSize());
                                    continue;
                                }
                                case CLOSED -> {
                                    closeChannel(channel);
                                    return;
                                }
                                default -> {
                                    Log.logError("Unexpected handshake wrap status: " + wrapResult.getStatus(), null);
                                    return;
                                }
                            }
                        } catch (SSLException e) {
                            Log.logError("Error during handshake wrap: " + e.getMessage(), e);
                            closeChannel(channel);
                            return;
                        }

                        break;
                    }
                }
                case FINISHED -> current = engine.getHandshakeStatus();
                default -> {
                    return;
                }
            }
        }
    }

    private boolean isHandshakeComplete(SSLEngine engine) {
        SSLEngineResult.HandshakeStatus status = engine.getHandshakeStatus();
        return status == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING ||
                status == SSLEngineResult.HandshakeStatus.FINISHED;
    }

    private void sendSSLResponse(SocketChannel socketChannel, String response) {
        SSLEngine sslEngine = peerModel.getSslEngineMap().get(socketChannel);
        if (sslEngine == null) {
            Log.logError("SSL engine not found for response", null);
            return;
        }

        ByteBuffer responseBuffer = StandardCharsets.UTF_8.encode(response);
        wrapAndQueue(socketChannel, sslEngine, responseBuffer);
    }

    private void sendSSLBinary(SocketChannel socketChannel, byte[] data) {
        SSLEngine sslEngine = peerModel.getSslEngineMap().get(socketChannel);
        if (sslEngine == null) {
            Log.logError("SSL engine not found for binary response", null);
            return;
        }

        ByteBuffer responseBuffer = ByteBuffer.wrap(data);
        wrapAndQueue(socketChannel, sslEngine, responseBuffer);
    }

    private boolean runHandshakeTasks(SSLEngine sslEngine) {
        boolean ranTask = false;
        Runnable task;
        while ((task = sslEngine.getDelegatedTask()) != null) {
            ranTask = true;
            executorService.submit(task);
        }
        return ranTask;
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

            try {
                SSLEngineResult.HandshakeStatus status = engine.getHandshakeStatus();
                if (status == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                    boolean ranTask = runHandshakeTasks(engine);
                    status = engine.getHandshakeStatus();
                    if (!ranTask && status == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                        continue;
                    }
                }
                handleHandshakeStatus(channel, engine, status);
            } catch (Exception e) {
                Log.logError("Error processing handshake tasks: " + e.getMessage(), e);
                closeChannel(channel);
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
    public void processSSLRequest(SocketChannel socketChannel, String request) {
        try {
            String clientIP = socketChannel.getRemoteAddress().toString().split(":")[0].replace("/", "");
            PeerInfo clientIdentifier = new PeerInfo(clientIP, Config.PEER_PORT);

            Log.logInfo("Received request: " + request);
            if (request.startsWith("SEARCH")) {
                String fileName = request.split("\\|")[1];
                Map<String, FileInfo> publicSharedFiles = peerModel.getPublicSharedFiles();
                FileInfo fileInfo = publicSharedFiles.get(fileName);
                String response;
                if (fileInfo != null) {
                    response = "FILE_INFO|" + fileName + "|" + fileInfo.getFileSize() + "|" +
                            fileInfo.getPeerInfo().getIp() + "|" + fileInfo.getPeerInfo().getPort() + "|" +
                            fileInfo.getFileHash() + "\n";
                } else {
                    response = "FILE_NOT_FOUND|" + fileName + "\n";
                }
                sendSSLResponse(socketChannel, response);
            } else if (request.startsWith("GET_CHUNK")) {
                String[] requestParts = request.split("\\|");
                String fileHash = requestParts[1];
                int chunkIndex = Integer.parseInt(requestParts[2]);
                byte[] data;
                if (this.hasAccessToFile(clientIdentifier, fileHash)) {
                    data = getChunkData(fileHash, chunkIndex);
                } else {
                    byte[] errorData = "ACCESS_DENIED".getBytes(StandardCharsets.UTF_8);

                    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                         DataOutputStream dos = new DataOutputStream(baos)) {
                        dos.writeInt(-1); // Index lỗi
                        dos.writeInt(errorData.length);
                        dos.write(errorData);
                        dos.flush();
                        data = baos.toByteArray();
                    }
                }
                sendSSLBinary(socketChannel, data);
            } else if (request.startsWith("CHAT_MESSAGE")) {
                String[] messageParts = request.split("\\|", 3);
                String response;
                if (messageParts.length >= 3) {
                    Log.logInfo("Processing SSL chat message from " + messageParts[1] + ": " + messageParts[2]);
                    response = "CHAT_RECEIVED|" + messageParts[1] + "\n";
                } else {
                    response = "ERROR|Invalid chat format\n";
                }
                sendSSLResponse(socketChannel, response);
            } else {
                sendSSLResponse(socketChannel, "UNKNOWN_REQUEST\n");
            }
        } catch (IOException e) {
            Log.logError("Error processing request: " + e.getMessage(), e);
        }
    }

    private byte[] getChunkData(String fileHash, int chunkIndex) {
        FileInfo fileInfo = findFileByHash(fileHash);
        if (fileInfo == null) {
            return "FILE_NOT_FOUND\n".getBytes();
        }

        String appPath = main.java.utils.AppPaths.getAppDataDirectory();
        String filePath = appPath + "/shared_files/" + fileInfo.getFileName();
        File file = new File(filePath);

        if (file.exists() && file.canRead()) {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r");
                 ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 DataOutputStream dos = new DataOutputStream(baos)) {
                byte[] chunkData = new byte[Config.CHUNK_SIZE];
                raf.seek((long) chunkIndex * (long) Config.CHUNK_SIZE);
                int byteRead = raf.read(chunkData);

                if (byteRead > 0) {
                    byte[] actualData = byteRead == Config.CHUNK_SIZE ? chunkData : Arrays.copyOf(chunkData, byteRead);
                    dos.writeInt(chunkIndex);
                    dos.writeInt(actualData.length);
                    dos.write(actualData);
                    dos.flush();
                    return baos.toByteArray();
                }
            } catch (IOException e) {
                Log.logError("Error reading chunk data: " + e.getMessage(), e);
            }
        }
        return "CHUNK_ERROR\n".getBytes();
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
