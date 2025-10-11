package main.java.service;

import main.java.domain.entity.PeerInfo;
import main.java.utils.Infor;
import main.java.utils.Log;
import main.java.utils.SSLUtils;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class PeerServer implements Runnable {
    private final int CHUNK_SIZE;
    private final PeerInfo SERVER_HOST;
    private Selector selector;
    private SSLContext sslContext;
    private ExecutorService executor;
    private final FileManager fileManager; // For file operations
    private volatile boolean isRunning;

    // NIO related structures
    private final ConcurrentHashMap<SocketChannel, SSLEngine> sslEngineMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SocketChannel, ByteBuffer> pendingData = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SocketChannel, Map<String, Object>> channelAttachments = new ConcurrentHashMap<>();

    public PeerServer(int chunkSize, PeerInfo serverHost, FileManager fileManager, ExecutorService executor) {
        this.CHUNK_SIZE = chunkSize;
        this.SERVER_HOST = serverHost;
        this.fileManager = fileManager;
        this.executor = executor;
        this.isRunning = true;
    }

    public void initializeServerSocket() throws Exception {
        // Initialize non-blocking SSL server with NIO
        this.selector = Selector.open();
        this.sslContext = SSLUtils.createSSLContext();

        // Create server socket channel for non-blocking operation
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.socket().bind(new InetSocketAddress(this.SERVER_HOST.getIp(), this.SERVER_HOST.getPort()));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        Log.logInfo("Non-blocking SSL server socket initialized on " + this.SERVER_HOST.getIp() + ":" + this.SERVER_HOST.getPort());
    }

    public void startServer() {
        this.executor.submit(this);
    }

    @Override
    public void run() {
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
    }

    public void shutdown() {
        Log.logInfo("Shutting down SSL server...");
        this.isRunning = false;

        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5L, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    Log.logInfo("Executor service forcefully shut down.");
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                Log.logError("Executor service interrupted during shutdown: " + e.getMessage(), e);
            }
        }

        // Close all channels
        for (SelectionKey key : selector.keys()) {
            closeChannel(key.channel());
            key.cancel();
        }
        try {
            selector.close();
        } catch (IOException e) {
            Log.logError("Error closing selector: " + e.getMessage(), e);
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
            sslEngine.setNeedClientAuth(true); // SECURITY: Require client authentication

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
        try {
            if (request.startsWith("SEARCH")) {
                String fileName = request.split("\\|")[1];
                String response = "FILE_NOT_FOUND|" + fileName + "\n";

                // Get file info from FileManager
                var fileInfo = fileManager.getPublicFileInfo(fileName);
                if (fileInfo != null) {
                    response = "FILE_INFO|" + fileName + "|" + fileInfo.getFileSize() + "|" +
                            fileInfo.getPeerInfo().getIp() + "|" + fileInfo.getPeerInfo().getPort() + "|" +
                            fileInfo.getFileHash() + "\n";
                }
                return response;

            } else if (request.startsWith("GET_CHUNK")) {
                String[] requestParts = request.split("\\|");
                String fileHash = requestParts[1];
                int chunkIndex = Integer.parseInt(requestParts[2]);
                if (fileManager.hasAccessToFile(clientIdentifier, fileHash)) {
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
        } catch (Exception e) {
            Log.logError("Error processing SSL request: " + e.getMessage(), e);
            return "ERROR|Processing failed\n";
        }

        return "UNKNOWN_REQUEST\n";
    }

    private String getChunkData(String fileHash, int chunkIndex) {
        // Delegate to FileManager for chunk serving
        // This would need to be modified in FileManager to return string response instead of sending directly
        // For now, return a simple response
        return "CHUNK_ERROR|Chunk serving handled by FileManager\n";
    }

    private void sendSSLResponse(SocketChannel socketChannel, String response) {
        SSLEngine sslEngine = sslEngineMap.get(socketChannel);
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
            try (DatagramSocket udpSocket = new java.net.DatagramSocket(this.SERVER_HOST.getPort())) {
                byte[] buffer = new byte[1024];
                Log.logInfo("UDP server started on " + this.SERVER_HOST.getIp() + ":" + this.SERVER_HOST.getPort());

                while (this.isRunning) {
                    java.net.DatagramPacket receivedPacket = new java.net.DatagramPacket(buffer, buffer.length);
                    udpSocket.receive(receivedPacket);
                    String receivedMessage = new String(receivedPacket.getData(), 0, receivedPacket.getLength());
                    Log.logInfo("Received UDP request: " + receivedMessage);
                    if (receivedMessage.equals("PING")) {
                        sendPongResponse(udpSocket, receivedPacket);
                    }
                }
            } catch (IOException udpException) {
                Log.logError("UDP server error: " + udpException.getMessage(), udpException);
            }
        });
    }

    private void sendPongResponse(java.net.DatagramSocket udpSocket, java.net.DatagramPacket receivedPacket) throws IOException {
        byte[] pongData = "PONG".getBytes();
        java.net.DatagramPacket pongPacket = new java.net.DatagramPacket(pongData, pongData.length, receivedPacket.getAddress(), receivedPacket.getPort());
        udpSocket.send(pongPacket);
        Log.logInfo("Pong response sent to " + receivedPacket.getAddress() + ":" + receivedPacket.getPort());
    }
}
