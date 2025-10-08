package src.model;

import java.io.*;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.Scanner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import src.adapter.FileInfoAdapter;
import src.utils.Infor;
import src.utils.SSLUtils;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import static src.utils.Log.*;

import src.utils.LogTag;
import src.utils.RequestInfor;

import java.math.BigInteger;

public class TrackerModel {
    private final CopyOnWriteArraySet<PeerInfo> knownPeers;
    private final ConcurrentHashMap<String, Set<FileInfo>> publicFiles; // fileName -> set of file info (shared by different peers)
    private final ConcurrentHashMap<FileInfo, Set<PeerInfo>> privateSharedFiles; // file info -> set of peer info (who can access)
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Selector selector;
    private final ScheduledExecutorService pingExecutor;

    public TrackerModel() throws IOException {
        publicFiles = new ConcurrentHashMap<>();
        privateSharedFiles = new ConcurrentHashMap<>();
        knownPeers = new CopyOnWriteArraySet<>();
        selector = Selector.open();
        pingExecutor = Executors.newScheduledThreadPool(1);
        pingExecutor.scheduleAtFixedRate(this::pingPeers, 0, 60, TimeUnit.SECONDS);
    }

    public void startTracker() {
        try {
            startSSLServer();
        } catch (Exception e) {
            logError("[TRACKER]: SSL Server error: " + e.getMessage() + " on " + getCurrentTime(), e);
            throw new RuntimeException("Failed to start SSL Tracker server", e);
        } finally {
            pingExecutor.shutdown();
        }
    }

    private void startSSLServer() throws Exception {
        SSLServerSocket sslServerSocket = (SSLServerSocket) SSLUtils.createSSLServerSocketFactory().createServerSocket(SSLUtils.SSL_TRACKER_PORT);
        sslServerSocket.setNeedClientAuth(true);
        sslServerSocket.setUseClientMode(false);

        logInfo("[TRACKER]: SSL Tracker started on " + SSLUtils.SSL_TRACKER_PORT + " - " + getCurrentTime());

        while (true) {
            try {
                SSLSocket clientSocket = (SSLSocket) sslServerSocket.accept();
                clientSocket.setUseClientMode(false);
                clientSocket.setNeedClientAuth(true);

                logInfo("[TRACKER]: SSL connection accepted from: " + clientSocket.getRemoteSocketAddress() + " on " + getCurrentTime());

                // Handle SSL connection in a separate thread
                ExecutorService sslHandler = Executors.newSingleThreadExecutor();
                sslHandler.submit(() -> handleSSLConnection(clientSocket));
                sslHandler.shutdown();

            } catch (IOException e) {
                logError("[TRACKER]: SSL accept error: " + e.getMessage() + " on " + getCurrentTime(), e);
            }
        }
    }

    private void handleSSLConnection(SSLSocket sslSocket) {
        try {
            sslSocket.startHandshake();
            logInfo("[TRACKER]: SSL handshake completed with " + sslSocket.getRemoteSocketAddress() + " on " + getCurrentTime());

            // Basic SSL handling - treat as blocking I/O for simplicity
            Scanner scanner = new Scanner(sslSocket.getInputStream());
            PrintWriter writer = new PrintWriter(sslSocket.getOutputStream(), true);

            while (scanner.hasNextLine()) {
                String request = scanner.nextLine().trim();
                logInfo("[TRACKER]: SSL Received request: " + request + " from " + sslSocket.getRemoteSocketAddress() + " on " + getCurrentTime());

                String response = processRequest(request);
                writer.println(response);
                logInfo("[TRACKER]: SSL Sent response to " + sslSocket.getRemoteSocketAddress() + " on " + getCurrentTime());
            }

        } catch (Exception e) {
            logError("[TRACKER]: SSL connection error: " + e.getMessage() + " on " + getCurrentTime(), e);
        } finally {
            try {
                sslSocket.close();
                logInfo("[TRACKER]: SSL connection closed: " + sslSocket.getRemoteSocketAddress() + " on " + getCurrentTime());
            } catch (IOException e) {
                logError("[TRACKER]: SSL socket close error: " + e.getMessage() + " on " + getCurrentTime(), e);
            }
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        logInfo("[TRACKER-PLAIN]: Handling new connection on " + getCurrentTime());
        SocketChannel client = null;
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        try {
            client = server.accept();
        } catch (IOException e) {
            logError("[TRACKER]: Accept error: " + e.getMessage() + " on " + getCurrentTime(), e);
            return;
        }

        if (client != null) {
            client.configureBlocking(false);
            String clientAddress = client.getRemoteAddress().toString();
            client.register(selector, SelectionKey.OP_READ, new ClientState(clientAddress));
            logInfo("[TRACKER]: New connection: " + clientAddress + " on " + getCurrentTime());
        }
    }

    private void handleRead(SelectionKey key) throws IOException {
        SelectableChannel channel = key.channel();
        if (!(channel instanceof SocketChannel)) {
            logError("[TRACKER]: Channel isn't SocketChannel in handleRead: " + channel + " on " + getCurrentTime(), null);
            key.cancel();
            return;
        }

        SocketChannel client = (SocketChannel) channel;
        ClientState state = (ClientState) key.attachment();
        ByteBuffer buffer = state.readBuffer;

        int bytesRead;
        try {
            bytesRead = client.read(buffer);
        } catch (IOException e) {
            logError("[TRACKER]: Read error " + state.clientAddress + ": " + e.getMessage() + " on " + getCurrentTime(), e);
            key.cancel();
            client.close();
            return;
        }

        if (bytesRead == -1) {
            logInfo("[TRACKER]: Client disconnected: " + state.clientAddress + " on " + getCurrentTime());
            key.cancel();
            client.close();
            return;
        }

        if (bytesRead > 0) {
            buffer.flip();
            String data = new String(buffer.array(), 0, bytesRead).trim();
            state.request.append(data);

            if (!data.isEmpty()) {
                String request = state.request.toString().trim();
                logInfo("[TRACKER]: Received request: " + request + " from " + state.clientAddress + " on " + getCurrentTime());
                String response = processRequest(request);

                state.writeBuffer = ByteBuffer.wrap((response + "\n").getBytes());
                key.interestOps(SelectionKey.OP_WRITE);
                state.request.setLength(0);
            }
            buffer.clear();
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SelectableChannel channel = key.channel();
        if (!(channel instanceof SocketChannel client)) {
            logError("[TRACKER]: Channel isn't SocketChannel in handleWrite: " + channel + " on " + getCurrentTime(), null);
            key.cancel();
            return;
        }

        ClientState state = (ClientState) key.attachment();
        ByteBuffer buffer = state.writeBuffer;

        if (buffer != null) {
            try {
                client.write(buffer);
                if (!buffer.hasRemaining()) {
                    state.writeBuffer = null;
                    key.interestOps(SelectionKey.OP_READ);
                    logInfo("[TRACKER]: Sent response to " + state.clientAddress + " on " + getCurrentTime());
                }
            } catch (IOException e) {
                logError("[TRACKER]: Send response to client error" + state.clientAddress + ": " + e.getMessage() + " on " + getCurrentTime(), e);
                key.cancel();
                client.close();
            }
        }
    }

    private String processRequest(String request) {
        if (request.isEmpty()) {
            logInfo("[TRACKER]: Received empty request on " + getCurrentTime());
            return "Yêu cầu rỗng";
        }
        logInfo("[TRACKER]: Request: " + request);

        String[] parts = request.split("\\|");
        if (request.startsWith(RequestInfor.REGISTER)) {
            if (parts.length == 5) {
                return registerPeer(parts);
            }
            logInfo("[TRACKER]: Invalid REGISTER request [" + parts.length + "]: " + request + " on " + getCurrentTime());
            return "Định dạng yêu cầu REGISTER không hợp lệ. Sử dụng: REGISTER|<peerIp>|<peerPort>";
        } else if (request.startsWith(RequestInfor.SHARE)) {
            if (parts.length == 5) {
                return shareFile(parts);
            }
            logInfo("[TRACKER]: Invalid SHARE request: " + request + " on " + getCurrentTime());
            return "Định dạng yêu cầu SHARE không hợp lệ. Sử dụng: SHARE|<fileName>|<peerIp>|<peerPort>";
        } else if (request.startsWith(RequestInfor.QUERY)) {
            if (parts.length == 4) {
                return queryFile(parts);
            }
            logInfo("[TRACKER]: Invalid QUERY request: " + request + " on " + getCurrentTime());
            return "Định dạng yêu cầu QUERY không hợp lệ. Sử dụng: QUERY|<fileName>";
        } else if (request.startsWith(RequestInfor.UNSHARED_FILE)) {
            if (parts.length == 6) {
                return unshareFile(parts);
            }
        } else if (request.startsWith(RequestInfor.REFRESH)) {
            if (parts.length == 3) {
                String peerIp = parts[1];
                int peerPort = Integer.parseInt(parts[2]);
                return sendShareList(peerIp, peerPort, true);
            }
        } else if (request.startsWith(RequestInfor.GET_PEERS)) {
            if (parts.length == 4) {
                String fileHash = parts[1];
                String peerIp = parts[2];
                int peerPort = Integer.parseInt(parts[3]);
                return sendPeerList(fileHash, new PeerInfo(peerIp, peerPort));
            }
        } else if (request.startsWith(RequestInfor.GET_SHARED_PEERS)) {
            if (parts.length == 2) {
                String fileHash = parts[1];
                return getSharedPeers(fileHash);
            }
            logInfo("[TRACKER]: Invalid GET_SHARED_PEERS request: " + request + " on " + getCurrentTime());
            return "Định dạng yêu cầu GET_SHARED_PEERS không hợp lệ. Sử dụng: GET_SHARED_PEERS|<fileHash>";
        } else if (request.startsWith(RequestInfor.GET_KNOWN_PEERS)) {
            return getKnownPeers();
        } else if (request.startsWith("CERT_REQUEST")) {
            if (parts.length == 2) {
                return processCertificateRequest(parts[1]);
            }
            logInfo("[TRACKER]: Invalid CERT_REQUEST: " + request + " on " + getCurrentTime());
            return "CERT_ERROR|Invalid certificate request format";
        }
        logInfo("[TRACKER]: Unkhown command: " + request + " on " + getCurrentTime());
        return "Lệnh không xác định";
    }

    private String sendPeerList(String fileHash, PeerInfo requester) {
        List<String> peers = new ArrayList<>();
        publicFiles.values().forEach(fileInfos -> fileInfos.forEach(fileInfo -> {
            if (fileInfo.getFileHash().equals(fileHash)) {
                peers.add(fileInfo.getPeerInfo().getIp() + "'" + fileInfo.getPeerInfo().getPort());
            }
        }));

        for (Map.Entry<FileInfo, Set<PeerInfo>> entry : privateSharedFiles.entrySet()) {
            FileInfo fileInfo = entry.getKey();
            if (fileInfo.getFileHash().equals(fileHash)) {
                Set<PeerInfo> peerInfos = entry.getValue();
                if (peerInfos.contains(requester)) {
                    peers.add(fileInfo.getPeerInfo().getIp() + "'" + fileInfo.getPeerInfo().getPort());
                }
            }
        }

        if (peers.isEmpty()) {
            logInfo("[TRACKER]: No peers found for file hash: " + fileHash + " on " + getCurrentTime());
            return RequestInfor.NOT_FOUND + "|No peers found for file hash: " + fileHash;
        }

        StringBuilder response = new StringBuilder(RequestInfor.GET_PEERS + "|" + peers.size() + "|");
        for (String peer : peers) {
            response.append(peer).append(Infor.LIST_SEPARATOR);
        }
        if (response.charAt(response.length() - 1) == Infor.LIST_SEPARATOR.charAt(0)) {
            response.deleteCharAt(response.length() - 1);
        }
        logInfo("[TRACKER]: Sending peer list for file hash: " + fileHash + " on " + getCurrentTime());
        return response.toString();
    }

    private String unshareFile(String[] parts) {
        String fileName = parts[1];
        long fileSize = Long.parseLong(parts[2]);
        String fileHash = parts[3];
        String peerIp = parts[4];
        String peerPort = parts[5];
        FileInfo fileInfo = new FileInfo(fileName, fileSize, fileHash, new PeerInfo(peerIp, Integer.parseInt(peerPort)));
        Set<FileInfo> fileInfos = publicFiles.get(fileName);
        if (fileInfos != null) {
            fileInfos.remove(fileInfo);
            if (fileInfos.isEmpty()) {
                publicFiles.remove(fileName);
            }
        }
        privateSharedFiles.remove(fileInfo);

        logInfo("[TRACKER]: File " + fileName + " unshared by " + peerIp + "|" + peerPort + " on " + getCurrentTime());
        logInfo(publicFiles.toString());
        logInfo(privateSharedFiles.toString());
        return LogTag.S_SUCCESS;
    }

    private String queryFile(String[] parts) {
        String keyword = parts[1];
        String peerIp = parts[2];
        int peerPort = Integer.parseInt(parts[3]);

        Set<FileInfo> resultFiles = new HashSet<>();
        publicFiles.values().forEach(fileInfos -> fileInfos.forEach(fileInfo -> {
            if (fileInfo.getFileName().toLowerCase().contains(keyword.toLowerCase())) {
                resultFiles.add(fileInfo);
            }
        }));

        PeerInfo requester = new PeerInfo(peerIp, peerPort);
        for (Map.Entry<FileInfo, Set<PeerInfo>> entry : privateSharedFiles.entrySet()) {
            FileInfo fileInfo = entry.getKey();
            if (fileInfo.getFileName().toLowerCase().contains(keyword.toLowerCase())) {
                Set<PeerInfo> peerInfos = entry.getValue();
                if (peerInfos.contains(requester)) {
                    resultFiles.add(fileInfo);
                }
            }
        }

        List<FileInfo> files = new ArrayList<>(resultFiles);
        logInfo("[TRACKER]: QUERY for file containing \"" + keyword + "\": return peers " + files + " on " + getCurrentTime());
        StringBuilder response = new StringBuilder(RequestInfor.QUERY + "|" + files.size() + "|");
        if (files.isEmpty()) {
            logInfo("[TRACKER]: No peers found for file containing \"" + keyword + "\" on " + getCurrentTime());
            return response + "No files found.";
        }

        for (FileInfo file : files) {
            response.append(file.getFileName()).append("'").append(file.getFileSize()).append("'").append(file.getFileHash()).append("'").append(file.getPeerInfo().getIp()).append("'").append(file.getPeerInfo().getPort()).append(Infor.LIST_SEPARATOR);
        }
        if (response.toString().endsWith(Infor.LIST_SEPARATOR)) {
            response = new StringBuilder(response.substring(0, response.length() - Infor.LIST_SEPARATOR.length()));
        }
        response.append("\n");
        return response.toString();
    }

    private String shareFile(String[] parts) {
        int publicCount = Integer.parseInt(parts[1]);
        int privateCount = Integer.parseInt(parts[2]);
        if (publicCount < 0 || privateCount < 0) {
            logInfo("[TRACKER]: Invalid counts in SHARE: publicCount=" + publicCount + ", privateCount=" + privateCount + " on " + getCurrentTime());
            return "Số lượng chia sẻ không hợp lệ.";
        }
        Gson gson = new GsonBuilder().registerTypeAdapter(FileInfo.class, new FileInfoAdapter())
                .enableComplexMapKeySerialization().create();
        if (publicCount > 0) {
            Type listType = new TypeToken<List<FileInfo>>() {
            }.getType();
            List<FileInfo> publicFileInfos = gson.fromJson(parts[3], listType);
            for (FileInfo fileInfo : publicFileInfos) {
                publicFiles.computeIfAbsent(fileInfo.getFileName(), k -> ConcurrentHashMap.newKeySet()).add(fileInfo);
            }
        }

        if (privateCount > 0) {
            Type mapType = new TypeToken<Map<FileInfo, Set<PeerInfo>>>() {
            }.getType();
            Map<FileInfo, Set<PeerInfo>> privateFileInfos = gson.fromJson(parts[4], mapType);
            privateSharedFiles.putAll(privateFileInfos);
        }
        logInfo("[TRACKER]: SHARE processed: publicCount=" + publicCount + ", privateCount=" + privateCount + " on " + getCurrentTime());
        return LogTag.S_SUCCESS + "|Files shared successfully.";
    }

    private String registerPeer(String[] parts) {
        if (parts.length != 5) {
            logInfo("[TRACKER]: Invalid REGISTER request format: expected at least 6 parts, got " + parts.length + " on " + getCurrentTime());
            return LogTag.S_INVALID;
        }

        String peerIp = parts[1];
        String peerPort;
        try {
            peerPort = parts[2];
            Integer.parseInt(peerPort);
        } catch (NumberFormatException e) {
            logInfo("[TRACKER]: Invalid Port in REGISTER: " + parts[2] + " on " + getCurrentTime());
            return LogTag.S_INVALID;
        }

        knownPeers.add(new PeerInfo(peerIp, Integer.parseInt(peerPort)));
        logInfo("[TRACKER]: Peer registered: " + peerIp + " on " + getCurrentTime());

        // Deserialize the data structures
        try {
            // Assuming parts[3] is publicFileToPeers JSON, parts[4] privateSharedFile JSON, parts[5] selectiveSharedFiles JSON
            // For simplicity, we'll just log them for now
            String publicFileToPeersJson = parts[3];
            String privateSharedFileJson = parts[4];

            logInfo("[TRACKER]: Received publicFileToPeers: " + publicFileToPeersJson + " on " + getCurrentTime());
            logInfo("[TRACKER]: Received privateSharedFile: " + privateSharedFileJson + " on " + getCurrentTime());

            Gson gson = new GsonBuilder().registerTypeAdapter(FileInfo.class, new FileInfoAdapter())
                    .enableComplexMapKeySerialization().create();

            Type publicType = new TypeToken<Map<String, FileInfo>>() {
            }.getType();
            Map<String, FileInfo> receivedPublicFileToPeers = gson.fromJson(publicFileToPeersJson, publicType);

            Type privateType = new TypeToken<Map<FileInfo, Set<PeerInfo>>>() {
            }.getType();
            Map<FileInfo, Set<PeerInfo>> receivedPrivateSharedFile = gson.fromJson(privateSharedFileJson, privateType);

            if (receivedPublicFileToPeers != null) {
                for (FileInfo fileInfo : receivedPublicFileToPeers.values()) {
                    publicFiles.putIfAbsent(fileInfo.getFileName(), ConcurrentHashMap.newKeySet());
                    publicFiles.get(fileInfo.getFileName()).add(fileInfo);
                }
            }
            if (receivedPrivateSharedFile != null) {
                privateSharedFiles.putAll(receivedPrivateSharedFile);
            }
            logInfo("[TRACKER]: Updated data structures from peer " + peerIp + " on " + getCurrentTime());
        } catch (Exception e) {
            logInfo("[TRACKER]: Error processing REGISTER data structures: " + e.getMessage() + " on " + getCurrentTime());
        }

        String shareListResponse = sendShareList(peerIp, Integer.parseInt(peerPort), false);
        if (!shareListResponse.startsWith(RequestInfor.SHARED_LIST)) {
            return RequestInfor.REGISTERED;
        }
        return shareListResponse;
    }


    private void pingPeers() {
        try (DatagramChannel channel = DatagramChannel.open()) {
            channel.configureBlocking(false);
            channel.socket().setSoTimeout(Infor.SOCKET_TIMEOUT_MS);

            for (int i = 0; i < 3; i++) {

                // ping to all peers with broadcast message
                String pingMessage = RequestInfor.PING;
                ByteBuffer buffer = ByteBuffer.wrap(pingMessage.getBytes());
                channel.send(buffer, new InetSocketAddress(Infor.BROADCAST_IP, Infor.PEER_PORT));
                logInfo("[TRACKER]: Sent ping to broadcast address on " + Infor.BROADCAST_IP);

                // Collect alive peers
                Set<PeerInfo> alivePeers = ConcurrentHashMap.newKeySet();
                long startTime = System.currentTimeMillis();
                while (System.currentTimeMillis() - startTime < Infor.SOCKET_TIMEOUT_MS) {
                    buffer.clear();
                    InetSocketAddress responder;

                    try {
                        responder = (InetSocketAddress) channel.receive(buffer);
                        if (responder != null) {
                            String response = new String(buffer.array(), 0, buffer.position());
                            alivePeers.add(new PeerInfo(responder.getAddress().getHostAddress(), responder.getPort()));
                            logInfo("[TRACKER]: Received response from " + responder + ": " + response + " on " + getCurrentTime());
                        }
                    } catch (IOException e) {
                        logError("[TRACKER]: Error receiving response: " + e.getMessage() + " on " + getCurrentTime(), e);
                    }
                }

                updateKnownPeers(alivePeers);
                logInfo("[TRACKER]: Ping completed. Alive peers: " + alivePeers.size() + " on " + getCurrentTime());
            }
        } catch (
                IOException e) {
            logError("[TRACKER]: Ping error: " + e.getMessage() + " on " + getCurrentTime(), e);
        }

    }

    private void updateKnownPeers(Set<PeerInfo> alivePeers) {
        synchronized (this) {
            knownPeers.retainAll(alivePeers);
            knownPeers.addAll(alivePeers);
            logInfo("[TRACKER]: Updated known peers: " + knownPeers + " on " + getCurrentTime());

            for (Map.Entry<String, Set<FileInfo>> entry : publicFiles.entrySet()) {
                entry.getValue().removeIf(fileInfo -> !knownPeers.contains(fileInfo.getPeerInfo()));
            }

            for (Map.Entry<FileInfo, Set<PeerInfo>> entry : privateSharedFiles.entrySet()) {
                if (!knownPeers.contains(entry.getKey().getPeerInfo())) {
                    privateSharedFiles.remove(entry.getKey());
                } else {
                    entry.getValue().retainAll(knownPeers);
                }
            }
        }
    }

    private String getKnownPeers() {
        if (knownPeers.isEmpty()) {
            logInfo("[TRACKER]: No known peers found on " + getCurrentTime());
            return RequestInfor.NOT_FOUND + "|No known peers found";
        }

        StringBuilder response = new StringBuilder(RequestInfor.GET_KNOWN_PEERS + "|" + knownPeers.size() + "|");
        for (PeerInfo peer : knownPeers) {
            response.append(peer.toString().replace('|', ':')).append(Infor.LIST_SEPARATOR);
        }
        if (response.charAt(response.length() - 1) == Infor.LIST_SEPARATOR.charAt(0)) {
            response.deleteCharAt(response.length() - 1);
        }
        logInfo("[TRACKER]: Sending known peers list on " + getCurrentTime());
        return response.toString();
    }

    private String getSharedPeers(String fileHash) {
        Set<PeerInfo> peers = new HashSet<>();
        publicFiles.values().forEach(fileInfos -> fileInfos.forEach(fileInfo -> {
            if (fileInfo.getFileHash().equals(fileHash)) {
                peers.add(fileInfo.getPeerInfo());
            }
        }));
        for (Map.Entry<FileInfo, Set<PeerInfo>> entry : privateSharedFiles.entrySet()) {
            FileInfo fileInfo = entry.getKey();
            if (fileInfo.getFileHash().equals(fileHash)) {
                peers.addAll(entry.getValue());
            }
        }
        if (peers.isEmpty()) {
            logInfo("[TRACKER]: No selective peers found for file hash: " + fileHash + " on " + getCurrentTime());
            return RequestInfor.NOT_FOUND + "|No selective peers found for file hash: " + fileHash;
        }

        StringBuilder response = new StringBuilder(RequestInfor.GET_SHARED_PEERS + "|" + peers.size() + "|");
        for (PeerInfo peer : peers) {
            response.append(peer).append(Infor.LIST_SEPARATOR);
        }
        if (response.charAt(response.length() - 1) == Infor.LIST_SEPARATOR.charAt(0)) {
            response.deleteCharAt(response.length() - 1);
        }
        logInfo("[TRACKER]: Sending selective peer list for file hash: " + fileHash + " on " + getCurrentTime());
        return response.toString();
    }

    String getCurrentTime() {
        return LocalDateTime.now().format(formatter);
    }

    void addKnownPeer(PeerInfo peer) {
        knownPeers.add(peer);
    }

    /**
     * Process certificate signing request from a peer
     * Acts as the Intermediate Certificate Authority (CA)
     */
    private String processCertificateRequest(String csrPem) {
        try {
            logInfo("[TRACKER]: Processing certificate request on " + getCurrentTime());

            // 1. Đọc CSR từ chuỗi PEM bằng Bouncy Castle
            PKCS10CertificationRequest csr;
            try (StringReader reader = new StringReader(csrPem); PEMParser pemParser = new PEMParser(reader)) {
                Object parsedObj = pemParser.readObject();
                if (parsedObj instanceof PKCS10CertificationRequest) {
                    csr = (PKCS10CertificationRequest) parsedObj;
                } else {
                    throw new IllegalArgumentException("Provided string is not a valid PKCS#10 CSR");
                }
            }

            // 2. Tải Keystore của CA (Intermediate CA)
            File trackerKeystoreFile = new File("certificates/tracker-ca-keystore.jks");
            if (!trackerKeystoreFile.exists()) {
                logError("[TRACKER]: Intermediate CA keystore not found! Cannot sign certificates on " + getCurrentTime(), null);
                return "CERT_ERROR|Intermediate CA keystore not available";
            }

            KeyStore caKeyStore = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(trackerKeystoreFile)) {
                caKeyStore.load(fis, "p2ppassword".toCharArray());
            }

            // 3. Lấy private key và chứng chỉ của Intermediate CA
            PrivateKey caPrivateKey = (PrivateKey) caKeyStore.getKey("tracker-ca", "p2ppassword".toCharArray());
            X509Certificate caCert = (X509Certificate) caKeyStore.getCertificate("tracker-ca");

            // 4. Tạo chứng chỉ mới cho Peer bằng Bouncy Castle Builder
            Instant now = Instant.now();
            Date validityBeginDate = Date.from(now);
            Date validityEndDate = Date.from(now.plus(365, ChronoUnit.DAYS)); // Hiệu lực 1 năm

            JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                    X500Name.getInstance(caCert.getSubjectX500Principal().getEncoded()), // Issuer là Intermediate CA
                    new BigInteger(128, new SecureRandom()), // Tạo số serial ngẫu nhiên
                    validityBeginDate,
                    validityEndDate,
                    csr.getSubject(), // Subject lấy từ CSR
                    csr.getSubjectPublicKeyInfo() // Public key lấy từ CSR
            );

            // 5. Ký chứng chỉ mới bằng private key của CA
            ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSA").build(caPrivateKey);
            X509Certificate peerCert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(certBuilder.build(contentSigner));

            // 6. Tạo chuỗi chứng chỉ PEM để gửi về cho Peer
            Certificate rootCaCert = caKeyStore.getCertificate("ca"); // Lấy Root CA cert từ keystore

            StringWriter stringWriter = new StringWriter();
            try (JcaPEMWriter pemWriter = new JcaPEMWriter(stringWriter)) {
                pemWriter.writeObject(peerCert);     // Chứng chỉ của Peer
                pemWriter.writeObject(caCert);       // Chứng chỉ của Intermediate CA
                pemWriter.writeObject(rootCaCert);   // Chứng chỉ của Root CA
            }

            String certificateChainPem = stringWriter.toString();
            logInfo("[TRACKER]: Certificate chain generated successfully for peer on " + getCurrentTime());

            return "CERT_RESPONSE|" + certificateChainPem;

        } catch (Exception e) {
            logError("[TRACKER]: Error processing certificate request: " + e.getMessage() + " on " + getCurrentTime(), e);
            return "CERT_ERROR|Failed to sign certificate: " + e.getMessage();
        }
    }

    String sendShareList(String peerIp, int peerPort, boolean isRefresh) {
        Set<FileInfo> filesToSend = new HashSet<>();

        // Add all public shared files
        for (Set<FileInfo> fileInfos : publicFiles.values()) {
            filesToSend.addAll(fileInfos);
        }


        PeerInfo peerInfo = new PeerInfo(peerIp, peerPort);
        for (Map.Entry<FileInfo, Set<PeerInfo>> entry : privateSharedFiles.entrySet()) {
            FileInfo fileInfo = entry.getKey();
            Set<PeerInfo> allowedPeers = entry.getValue();
            if (allowedPeers.contains(peerInfo)) {
                filesToSend.add(fileInfo);
            }
        }

        if (filesToSend.isEmpty()) {
            logInfo("[TRACKER]: No shared files to send to " + peerIp + "|" + peerPort + " on " + getCurrentTime());
            return RequestInfor.FILE_NOT_FOUND;
        }

        StringBuilder msgBuilder = new StringBuilder();
        for (FileInfo file : filesToSend) {
            msgBuilder.append(file.getFileName()).append("'").append(file.getFileSize()).append("'").append(file.getFileHash()).append("'").append(file.getPeerInfo().getIp()).append("'").append(file.getPeerInfo().getPort()).append(Infor.LIST_SEPARATOR);
        }

        if (msgBuilder.charAt(msgBuilder.length() - 1) == ',') {
            msgBuilder.deleteCharAt(msgBuilder.length() - 1);
        }
        String shareList = msgBuilder.toString();
        logInfo("[TRACKER]: Sending share list (" + filesToSend.size() + " files) to " + peerIp + "|" + peerPort + " on " + getCurrentTime());
        return (isRefresh ? RequestInfor.REFRESHED : RequestInfor.SHARED_LIST) + "|" + filesToSend.size() + "|" + shareList;
    }

}
