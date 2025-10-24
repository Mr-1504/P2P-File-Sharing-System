package infras.subrepo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import domain.adapter.FileInfoAdapter;
import domain.entity.FileInfo;
import domain.entity.PeerInfo;
import domain.repository.INetworkRepository;
import domain.repository.IPeerRepository;
import infras.utils.FileUtils;
import utils.AppPaths;
import utils.Config;
import utils.Log;
import utils.LogTag;
import infras.utils.SSLUtils;

import javax.net.ssl.*;
import java.io.*;
import java.lang.reflect.Type;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NetworkRepository implements INetworkRepository {

    private final IPeerRepository peerModel;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final ChannelGroup allChannels;
    private SslContext sslContext;
    private boolean isRunning;
    private final ExecutorService executorService;

    public NetworkRepository(IPeerRepository peerModel) {
        this.isRunning = true;
        this.peerModel = peerModel;
        this.executorService = Executors.newFixedThreadPool(8);
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
        this.allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    }

    @Override
    public void initializeServerSocket(String username) throws Exception {
        FileUtils.loadData(username, peerModel.getPublicSharedFiles(), peerModel.getPrivateSharedFiles());

        // Create Netty SSL context - SỬA: Chain trustManager đúng cách
        KeyStore keyStore = KeyStore.getInstance("JKS");
        File keyStoreFile = new java.io.File(SSLUtils.CERT_DIRECTORY.toFile().getAbsolutePath() + "/peer-keystore.jks");
        try (FileInputStream fis = new FileInputStream(keyStoreFile)) {
            keyStore.load(fis, SSLUtils.KEYSTORE_PASSWORD.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, SSLUtils.KEYSTORE_PASSWORD.toCharArray());

        KeyStore trustStore = KeyStore.getInstance("JKS");
        File trustStoreFile = new java.io.File(SSLUtils.CERT_DIRECTORY.toFile().getAbsolutePath() + "/peer-truststore.jks");
        try (FileInputStream fis = new FileInputStream(trustStoreFile)) {
            trustStore.load(fis, SSLUtils.TRUSTSTORE_PASSWORD.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        // SỬA: Sử dụng .trustManager(tmf) sau forServer(kmf)
        this.sslContext = SslContextBuilder.forServer(kmf)
                .trustManager(tmf)
                .build();

        Log.logInfo("Netty SSL context initialized for server on " + Config.SERVER_IP + ":" + Config.PEER_PORT);
    }

    @Override
    public void startServer() {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {  // SỬA: Import đúng SocketChannel của Netty
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(sslContext.newHandler(ch.alloc()));
                        ch.pipeline().addLast(new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter()));
                        ch.pipeline().addLast(new StringDecoder(StandardCharsets.UTF_8));
                        ch.pipeline().addLast(new StringEncoder(StandardCharsets.UTF_8));
                        ch.pipeline().addLast(new ServerHandler(NetworkRepository.this));
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        try {
            ChannelFuture f = b.bind(Config.PEER_PORT).sync();
            Log.logInfo("Netty SSL server started on port " + Config.PEER_PORT);
            allChannels.add(f.channel());
            f.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            Log.logError("Server interrupted: " + e.getMessage(), e);
            Thread.currentThread().interrupt();
        } finally {
            shutdown();
        }
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
                Type publicFileType = new TypeToken<Map<String, FileInfo>>() {}.getType();
                String publicFileToPeersJson = gson.toJson(peerModel.getPublicSharedFiles(), publicFileType);
                Type privateFileType = new TypeToken<Map<FileInfo, Set<PeerInfo>>>() {}.getType();
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

        // Close all channels
        allChannels.close().awaitUninterruptibly();

        // Shutdown Netty groups
        workerGroup.shutdownGracefully();
        bossGroup.shutdownGracefully();

        Log.logInfo("SSL server shutdown complete.");
    }

    // XÓA: Các method raw NIO không dùng nữa (processSSLData, processApplicationData, queuePendingData, pollPendingBuffer, wrapAndQueue, handleHandshakeStatus, isHandshakeComplete, sendSSLResponse, sendSSLBinary, runHandshakeTasks, resizeApplicationBuffer, processPendingHandshakes)
    // Lý do: Netty SslHandler + Encoder/Decoder handle hết, không cần manual unwrap/wrap/queue.

    private void sendPongResponse(DatagramSocket udpSocket, DatagramPacket receivedPacket) throws IOException {
        String response = "PONG|" + AppPaths.loadUsername();
        byte[] pongData = response.getBytes();
        DatagramPacket pongPacket = new DatagramPacket(pongData, pongData.length, receivedPacket.getAddress(), receivedPacket.getPort());
        udpSocket.send(pongPacket);
        Log.logInfo("Pong response: " + response + " sent to " + receivedPacket.getAddress() + ":" + receivedPacket.getPort());
    }

    @Override
    public void processRequest(String request, String clientIP, Channel channel) {
        try {
            PeerInfo clientIdentifier = new PeerInfo(clientIP, Config.PEER_PORT);

            Log.logInfo("Received request: " + request);
            if (request.startsWith("SEARCH")) {
                String response = getString(request);
                channel.writeAndFlush(Unpooled.copiedBuffer(response, StandardCharsets.UTF_8));
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
                    } catch (Exception e) {
                        Log.logError("Error creating error data: " + e.getMessage(), e);
                        channel.close();
                        return;
                    }
                }
                channel.writeAndFlush(Unpooled.wrappedBuffer(data));
            } else if (request.startsWith("CHAT_MESSAGE")) {
                String[] messageParts = request.split("\\|", 3);
                String response;
                if (messageParts.length >= 3) {
                    Log.logInfo("Processing chat message from " + messageParts[1] + ": " + messageParts[2]);
                    response = "CHAT_RECEIVED|" + messageParts[1] + "\n";
                } else {
                    Log.logError("Invalid chat format", null);
                    response = "ERROR|Invalid chat format\n";
                }
                channel.writeAndFlush(Unpooled.copiedBuffer(response, StandardCharsets.UTF_8));
            } else {
                Log.logError("Unknown request: " + request, null);
                String response = "UNKNOWN_REQUEST\n";
                channel.writeAndFlush(Unpooled.copiedBuffer(response, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.logError("Error processing request: " + e.getMessage(), e);
            try {
                channel.close();
            } catch (Exception closeEx) {
                Log.logError("Error closing channel: " + closeEx.getMessage(), closeEx);
            }
        }
    }

    private String getString(String request) {
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
        return response;
    }

    private byte[] getChunkData(String fileHash, int chunkIndex) {
        FileInfo fileInfo = findFileByHash(fileHash);
        if (fileInfo == null) {
            return "FILE_NOT_FOUND\n".getBytes();
        }

        String appPath = AppPaths.getAppDataDirectory();
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

    public static class ServerHandler extends SimpleChannelInboundHandler<String> {
        private final NetworkRepository networkRepository;

        public ServerHandler(NetworkRepository networkRepository) {
            this.networkRepository = networkRepository;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            Log.logInfo("SSL connection established with: " + ctx.channel().remoteAddress());
            networkRepository.allChannels.add(ctx.channel());
            super.channelActive(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            Log.logInfo("SSL connection closed: " + ctx.channel().remoteAddress());
            super.channelInactive(ctx);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String msg) {
            // SỬA: Parse clientIP đúng với Netty Channel (remoteAddress là SocketAddress)
            String clientIP = ctx.channel().remoteAddress().toString().split(":")[0].replace("/", "");
            networkRepository.processRequest(msg.trim(), clientIP, ctx.channel());  // THÊM: trim() để loại bỏ \n thừa nếu có
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Log.logError("Exception in SSL channel handler: " + cause.getMessage(), null);
            ctx.close();
        }
    }
}