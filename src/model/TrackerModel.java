package model;

import utils.*;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

import static utils.Log.*;

public class TrackerModel {
    private final String KEYSTORE_PATH = EnvConf.getEnv("KEYSTORE_PATH", "tracker-keystore.jks");
    private final String TRUSTSTORE_PATH = EnvConf.getEnv("TRUSTSTORE_PATH", "tracker-truststore.jks");
    private final String STORE_PASSWORD = EnvConf.getEnv("STORE_PASSWORD", "password");
    private final int TRACKER_PORT = Infor.TRACKER_PORT;
    private final ConcurrentHashMap<String, Set<String>> fileToPeers;
    private final CopyOnWriteArraySet<String> knownPeers;
    private final Set<FileBase> sharedFiles;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Selector selector;
    private final ScheduledExecutorService pingExecutor;
    private final SSLContext sslContext;

    public TrackerModel() throws IOException {
        fileToPeers = new ConcurrentHashMap<>();
        sharedFiles = ConcurrentHashMap.newKeySet();
        knownPeers = new CopyOnWriteArraySet<>();
        selector = Selector.open();
        pingExecutor = Executors.newScheduledThreadPool(1);
        sslContext = initSSLContext();
        pingExecutor.scheduleAtFixedRate(this::pingPeers, 0, 60, TimeUnit.SECONDS);
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

            SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
            return sslContext;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SSL context", e);
        }
    }

    public void startTracker() {
        try (ServerSocketChannel serverSocketChannel = ServerSocketChannel.open()) {
            serverSocketChannel.bind(new InetSocketAddress(TRACKER_PORT));
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            logInfo("Tracker khởi động trên cổng " + TRACKER_PORT + " lúc " + getCurrentTime());

            while (true) {
                selector.select();
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();

                    if (!key.isValid()) continue;

                    try {
                        if (key.isAcceptable()) {
                            handleAccept(key);
                        } else if (key.isReadable()) {
                            handleRead(key);
                        } else if (key.isWritable()) {
                            handleWrite(key);
                        }
                    } catch (IOException e) {
                        logError("Lỗi xử lý key: " + e.getMessage() + " lúc " + getCurrentTime(), e);
                        key.cancel();
                        closeChannel(key.channel());
                    }
                }
            }
        } catch (IOException e) {
            logError("Lỗi tracker: " + e.getMessage() + " lúc " + getCurrentTime(), e);
        } finally {
            shutdown();
        }
    }

    private void shutdown() {
        pingExecutor.shutdown();
        try {
            selector.close();
        } catch (IOException e) {
            logError("Error closing selector: " + e.getMessage(), e);
        }
        logInfo("Tracker shutdown completed.");
    }

    private void closeChannel(SelectableChannel channel) {
        try {
            if (channel != null) channel.close();
        } catch (IOException e) {
            logError("Error closing channel: " + e.getMessage(), e);
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        if (client != null) {
            client.configureBlocking(false);
            String clientAddress = client.getRemoteAddress().toString();
            SSLEngine sslEngine = sslContext.createSSLEngine();
            sslEngine.setUseClientMode(false);
            sslEngine.setNeedClientAuth(true);
            sslEngine.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
            sslEngine.beginHandshake();

            SSLChannelWrapper sslWrapper = new SSLChannelWrapper(client, sslEngine, selector);

            sslWrapper.getKey().interestOps(SelectionKey.OP_READ);
            logInfo("Kết nối mới từ: " + clientAddress + " lúc " + getCurrentTime());
        }
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        SSLChannelWrapper wrapper = (SSLChannelWrapper) key.attachment();
        String clientAddress = client.getRemoteAddress().toString();

        try {
            String request = wrapper.read();
            if (request != null) {
                logInfo("Received request from " + clientAddress + ": " + request + " at " + getCurrentTime());

                String response = processRequest(request);
                wrapper.write(response + "\n");
                key.interestOps(SelectionKey.OP_WRITE);
                logInfo("Sent response to " + clientAddress + ": " + response + " at " + getCurrentTime());
            }
        } catch (IOException e) {
            logError("Error reading from client " + clientAddress + ": " + e.getMessage() + " lúc " + getCurrentTime(), e);
            key.cancel();
            closeChannel(client);
            wrapper.close();
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SSLChannelWrapper wrapper = (SSLChannelWrapper) key.attachment();
        SocketChannel client = (SocketChannel) key.channel();
        String clientAddress = client.getRemoteAddress().toString();

        try {
            if (wrapper.flush()) {
                key.interestOps(SelectionKey.OP_READ);
                logInfo("Successfully sent data to " + clientAddress + " at " + getCurrentTime());
            }
        } catch (IOException e) {
            logError("Error writing to client " + clientAddress + ": " + e.getMessage() + " at " + getCurrentTime(), e);
            key.cancel();
            closeChannel(client);
            wrapper.close();
        }
    }

    private String processRequest(String request) {
        if (request.isEmpty()) {
            System.out.println("Yêu cầu rỗng nhận được lúc " + getCurrentTime());
            return "Yêu cầu rỗng";
        }

        String[] parts = request.split(Infor.FIELD_SEPARATOR_REGEX);

        if (parts.length < 1) {
            logError("Yêu cầu không hợp lệ: " + request + " lúc " + getCurrentTime(), null);
            return LogTag.S_INVALID;
        }

        switch (parts[0]) {
            case RequestInfor.REGISTER:
                if (parts.length == 3) return registerPeer(parts);
                break;
            case RequestInfor.SHARED_LIST:
                if (parts.length >= 5) return receiveFileList(parts, request);
                break;
            case RequestInfor.SHARE:
                if (parts.length == 4) return shareFile(parts);
                break;
            case RequestInfor.QUERY:
                if (parts.length >= 2) return queryFile(parts);
                break;
            case RequestInfor.UNSHARED_FILE:
                if (parts.length == 4) return unshareFile(parts);
                break;
            case RequestInfor.REFRESH:
                if (parts.length == 3) {
                    String peerIp = parts[1];
                    int peerPort = Integer.parseInt(parts[2]);
                    return sendShareList(peerIp, peerPort, true);
                }
                break;
            default:
                logError("Lệnh không xác định: " + request + " lúc " + getCurrentTime(), null);
                return LogTag.S_UNKNOWN;
        }
        logError("Yêu cầu không hợp lệ: " + request + " lúc " + getCurrentTime(), null);
        return LogTag.S_INVALID;
    }

    private String unshareFile(String[] parts) {
        String fileName = parts[1];
        String peerIp = parts[2];
        String peerPort = parts[3];
        String peerInfor = peerIp + Infor.FIELD_SEPARATOR + peerPort;
        Set<String> peers = fileToPeers.get(fileName);
        if (peers == null || !peers.remove(peerInfor)) {
            logInfo("Không tìm thấy file " + fileName + " được chia sẻ bởi " + peerInfor + " lúc " + getCurrentTime());
            return LogTag.S_NOT_FOUND;
        }

        fileToPeers.get(fileName).remove(peerInfor);
        sharedFiles.removeIf(fileBase -> fileBase.getFileName().equals(fileName) && fileBase.getPeerInfor().toString().equals(peerInfor));
        if (peers.isEmpty()) {
            fileToPeers.remove(fileName);
            logInfo("Đã xóa file " + fileName + " khỏi danh sách chia sẻ lúc " + getCurrentTime());
        }
        logInfo("UNSHARED_FILE: " + fileName + " bởi " + peerInfor + " lúc " + getCurrentTime());
        return LogTag.S_SUCCESS;
    }

    private String queryFile(String[] parts) {
        StringBuilder fileName = new StringBuilder(parts[1]);
        for (int i = 2; i < parts.length; i++) {
            fileName.append(Infor.FIELD_SEPARATOR).append(parts[i]);
        }
        List<String> peers = new ArrayList<>(fileToPeers.getOrDefault(fileName.toString(), Collections.emptySet()));
        logInfo("QUERY cho file " + fileName + ": trả về peers " + peers + " lúc " + getCurrentTime());
        return String.join(Infor.LIST_SEPARATOR, peers);
    }

    private String receiveFileList(String[] parts, String request) {
        int count;
        try {
            count = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            logError("Số lượng không hợp lệ trong SHARE_LIST: " + parts[1] + " lúc " + getCurrentTime(), e);
            return LogTag.S_INVALID_COUNT;
        }
        List<String> files = Arrays.asList(parts[2].split(","));
        String peerIp = parts[3];
        int peerPort;
        try {
            peerPort = Integer.parseInt(parts[4]);
        } catch (NumberFormatException e) {
            logError("Cổng không hợp lệ trong SHARE_LIST: " + parts[4] + " lúc " + getCurrentTime(), e);
            return LogTag.S_INVALID;
        }
        String peerInfor = peerIp + Infor.FIELD_SEPARATOR + peerPort;

        if (count <= 0) {
            logError("Số lượng không hợp lệ trong SHARE_LIST: " + count + " lúc " + getCurrentTime(), null);
            return LogTag.S_INVALID_COUNT;
        }
        if (count != files.size()) {
            logError("Số lượng không khớp với số file trong SHARE_LIST: " + count + " vs " + files.size() + " lúc " + getCurrentTime(), null);
            return LogTag.S_INVALID;
        }

        for (String file : files) {
            if (file.isEmpty()) {
                logError("Tên file rỗng trong SHARE_LIST: " + request + " lúc " + getCurrentTime(), null);
                continue;
            }
            String[] fileInfor = file.split(Infor.FILE_SEPARATOR);
            if (fileInfor.length != 2) {
                logError("Định dạng file không hợp lệ trong SHARE_LIST: " + file + " lúc " + getCurrentTime(), null);
                continue;
            }
            String fileName = fileInfor[0];
            long fileSize;
            try {
                fileSize = Long.parseLong(fileInfor[1]);
            } catch (NumberFormatException e) {
                logError("Kích thước file không hợp lệ trong SHARE_LIST: " + fileInfor[1] + " lúc " + getCurrentTime(), e);
                continue;
            }
            fileToPeers.computeIfAbsent(fileName, k -> new CopyOnWriteArraySet<>()).add(peerInfor);
            sharedFiles.add(new FileBase(fileName, fileSize, new PeerInfor(peerIp, peerPort)));
            logInfo("File " + fileName + " được chia sẻ bởi " + peerInfor + " lúc " + getCurrentTime());
        }
        logInfo("SHARE_LIST được xử lý cho " + peerInfor + " lúc " + getCurrentTime());
        return LogTag.S_SUCCESS;
    }

    private String shareFile(String[] parts) {
        String fileName = parts[1];
        String peerIp = parts[2];
        String peerPort = parts[3];
        String peerInfor = peerIp + "|" + peerPort;
        fileToPeers.computeIfAbsent(fileName, k -> new CopyOnWriteArraySet<>()).add(peerInfor);
        sharedFiles.add(new FileBase(fileName, 0, new PeerInfor(peerIp, Integer.parseInt(peerPort))));
        logInfo("File " + fileName + " được chia sẻ bởi " + peerInfor + " lúc " + getCurrentTime());
        return LogTag.S_SUCCESS;
    }

    private String registerPeer(String[] parts) {
        String peerIp = parts[1];
        String peerPort;
        try {
            peerPort = parts[2];
            Integer.parseInt(peerPort); // Validate port
        } catch (NumberFormatException e) {
            logError("Cổng không hợp lệ trong REGISTER: " + parts[2] + " lúc " + getCurrentTime(), e);
            return LogTag.S_INVALID;
        }
        String peerInfor = peerIp + Infor.FIELD_SEPARATOR + peerPort;
        knownPeers.add(peerInfor);
        logInfo("Đăng ký peer: " + peerInfor + " lúc " + getCurrentTime());

        String shareListResponse = sendShareList(peerIp, Integer.parseInt(peerPort), false);
        return shareListResponse.startsWith(RequestInfor.SHARED_LIST) ? shareListResponse : RequestInfor.REGISTERED;
    }

    private String sendShareList(String peerIp, int peerPort, boolean isRefresh) {
        if (sharedFiles.isEmpty()) {
            logInfo("Không có file nào được chia sẻ tới peer: " + peerIp + "|" + peerPort + " lúc " + getCurrentTime());
            return RequestInfor.FILE_NOT_FOUND;
        }

        StringBuilder msgBuilder = new StringBuilder();
        for (FileBase file : sharedFiles) {
            msgBuilder.append(file.getFileName())
                    .append(Infor.LIST_SEPARATOR).append(file.getFileSize())
                    .append(Infor.LIST_SEPARATOR).append(file.getPeerInfor().getIp())
                    .append(Infor.LIST_SEPARATOR).append(file.getPeerInfor().getPort())
                    .append(Infor.LIST_SEPARATOR);
        }
        if (msgBuilder.charAt(msgBuilder.length() - 1) == Infor.LIST_SEPARATOR.charAt(0)) {
            msgBuilder.deleteCharAt(msgBuilder.length() - 1);
        }
        String shareList = msgBuilder.toString();
        logInfo("Gửi SHARE_LIST đến " + peerIp + "|" + peerPort + ": " + shareList + " lúc " + getCurrentTime());
        return (isRefresh ? RequestInfor.REFRESHED :
                RequestInfor.SHARED_LIST) + Infor.FIELD_SEPARATOR + sharedFiles.size() + Infor.FIELD_SEPARATOR + shareList;
    }

    private void pingPeers() {
        try (DatagramChannel channel = DatagramChannel.open()) {
            channel.configureBlocking(false);
            channel.socket().setSoTimeout(5000);

            Map<String, Integer> retryCount = new ConcurrentHashMap<>();
            Set<String> alivePeers = ConcurrentHashMap.newKeySet();

            for (String peer : knownPeers) {
                retryCount.put(peer, 0);
            }

            long startTime = System.currentTimeMillis();
            while (!retryCount.isEmpty() && System.currentTimeMillis() - startTime < 5000) {
                for (String peer : retryCount.keySet()) {
                    if (alivePeers.contains(peer) || retryCount.get(peer) >= Infor.MAX_RETRIES) continue;

                    String[] peerInfor = peer.split(Infor.FIELD_SEPARATOR_REGEX);
                    String peerIp = peerInfor[0];
                    int peerPort;
                    try {
                        peerPort = Integer.parseInt(peerInfor[1]);
                    } catch (NumberFormatException e) {
                        logError("Cổng không hợp lệ cho peer " + peer + " lúc " + getCurrentTime(), e);
                        retryCount.remove(peer);
                        continue;
                    }
                    ByteBuffer pingMessage = ByteBuffer.wrap(RequestInfor.PING.getBytes());
                    try {
                        channel.send(pingMessage, new InetSocketAddress(peerIp, peerPort));
                        retryCount.compute(peer, (k, v) -> v + 1);
                        logInfo("Gửi PING đến " + peer + " (lần thử " + retryCount.get(peer) + ") lúc " + getCurrentTime());
                    } catch (IOException e) {
                        logError("Lỗi khi gửi PING đến " + peer + ": " + e.getMessage() + " lúc " + getCurrentTime(), e);
                        retryCount.compute(peer, (k, v) -> v + 1);
                    }
                }

                try {
                    ByteBuffer buffer = ByteBuffer.allocate(1024);
                    InetSocketAddress address = (InetSocketAddress) channel.receive(buffer);
                    if (address != null) {
                        buffer.flip();
                        String response = new String(buffer.array(), 0, buffer.limit()).trim();
                        if (response.equals(RequestInfor.PONG)) {
                            String peerInfor = address.getAddress().getHostAddress() + Infor.FIELD_SEPARATOR + address.getPort();
                            if (retryCount.containsKey(peerInfor)) {
                                alivePeers.add(peerInfor);
                                logInfo("Nhận PONG từ " + peerInfor + " lúc " + getCurrentTime());
                            }
                        }
                    }
                } catch (IOException e) {
                    logError("Lỗi khi nhận phản hồi PONG: " + e.getMessage() + " lúc " + getCurrentTime(), e);
                }

                sleep(1000);
            }
            updateKnownPeers(alivePeers);
            logInfo("Chu kỳ ping hoàn tất. Peer còn sống: " + alivePeers + " lúc " + getCurrentTime());

        } catch (IOException e) {
            logError("Lỗi trong pingPeers: " + e.getMessage() + " lúc " + getCurrentTime(), e);
        }
    }

    private void updateKnownPeers(Set<String> alivePeers) {
        synchronized (this) {
            knownPeers.retainAll(alivePeers);
            knownPeers.addAll(alivePeers);
            logInfo("Danh sách peer được cập nhật. Số lượng hiện tại: " + knownPeers.size() + " lúc " + getCurrentTime());

            Iterator<Map.Entry<String, Set<String>>> iterator = fileToPeers.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Set<String>> entry = iterator.next();
                String fileName = entry.getKey();
                Set<String> peers = entry.getValue();
                peers.retainAll(alivePeers);
                if (peers.isEmpty()) {
                    iterator.remove();
                    logInfo("Xóa mục file rỗng: " + fileName + " lúc " + getCurrentTime());
                }
                sharedFiles.removeIf(fileInfor ->
                        fileInfor.getFileName().equals(fileName) &&
                                !alivePeers.contains(fileInfor.getPeerInfor().toString()));
            }
        }
    }

    private String getCurrentTime() {
        return LocalDateTime.now().format(formatter);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}