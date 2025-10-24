package src.model;

import java.io.*;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.Scanner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import src.adapter.FileInfoAdapter;
import src.adapter.PeerInfoAdapter;
import src.utils.*;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import static src.utils.Log.*;

public class TrackerModel {
    private final CopyOnWriteArraySet<PeerInfo> knownPeers;
    private final ConcurrentHashMap<String, Set<FileInfo>> publicFiles; // fileName -> set of file info (shared by different peers)
    private final ConcurrentHashMap<FileInfo, Set<PeerInfo>> privateSharedFiles; // file info -> set of peer info (who can access)
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Selector selector;
    private final ScheduledExecutorService pingExecutor;
    private final ExecutorService requestExecutor = Executors.newFixedThreadPool(10);

    public TrackerModel() throws IOException {
        publicFiles = new ConcurrentHashMap<>();
        privateSharedFiles = new ConcurrentHashMap<>();
        knownPeers = new CopyOnWriteArraySet<>();
        selector = Selector.open();
        pingExecutor = Executors.newScheduledThreadPool(1);
        pingExecutor.scheduleAtFixedRate(this::pingPeers, 0, 10, TimeUnit.SECONDS);
    }

    public void startTracker() {
        try {

            ExecutorService serverExecutor = Executors.newFixedThreadPool(2);
            serverExecutor.submit(this::startSSLServer);      // Chạy server chính
            serverExecutor.submit(this::startEnrollmentServer);
        } catch (Exception e) {
            logError("[TRACKER]: SSL Server error: " + e.getMessage() + " on " + getCurrentTime(), e);
            throw new RuntimeException("Failed to start SSL Tracker server", e);
        } finally {
            pingExecutor.shutdown();
        }
    }

    private void startSSLServer() {
        SSLServerSocket sslServerSocket = null;
        try {
            sslServerSocket = (SSLServerSocket) SSLUtils.createSSLServerSocketFactory().createServerSocket(Config.SSL_TRACKER_PORT);
        } catch (Exception e) {
            logError("[TRACKER]: SSL Server socket creation error: " + e.getMessage() + " on " + getCurrentTime(), e);
            throw new RuntimeException(e);
        }
        sslServerSocket.setNeedClientAuth(true);
        sslServerSocket.setUseClientMode(false);

        logInfo("[TRACKER]: SSL Tracker started on " + Config.SSL_TRACKER_PORT + " - " + getCurrentTime());

        while (true) {
            try {
                SSLSocket clientSocket = (SSLSocket) sslServerSocket.accept();
                clientSocket.setUseClientMode(false);
                clientSocket.setNeedClientAuth(true);

                logInfo("[TRACKER]: SSL connection accepted from: " + clientSocket.getRemoteSocketAddress() + " on " + getCurrentTime());
                requestExecutor.submit(() -> handleSSLConnection(clientSocket));
            } catch (IOException e) {
                logError("[TRACKER]: SSL accept error: " + e.getMessage() + " on " + getCurrentTime(), e);
            }
        }
    }

    private void startEnrollmentServer() {
        try {
            SSLServerSocket sslServerSocket = (SSLServerSocket) SSLUtils.createSSLServerSocketFactory().createServerSocket(Config.TRACKER_ENROLLMENT_PORT);
            sslServerSocket.setNeedClientAuth(false);
            sslServerSocket.setUseClientMode(false);

            logInfo("[TRACKER-ENROLL]: Enrollment SSL Server started on " + Config.TRACKER_ENROLLMENT_PORT);

            while (true) {
                try {
                    SSLSocket clientSocket = (SSLSocket) sslServerSocket.accept();
                    logInfo("[TRACKER-ENROLL]: Enrollment connection accepted from: " + clientSocket.getRemoteSocketAddress() + " on " + getCurrentTime());
                    requestExecutor.submit(() -> handleEnrollmentConnection(clientSocket));
                } catch (IOException e) {
                    logError("[TRACKER-ENROLL]: Enrollment accept error: " + e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            logError("[TRACKER-ENROLL]: Enrollment Server error: " + e.getMessage(), e);
        }
    }


    private void handleEnrollmentConnection(SSLSocket sslSocket) {
        // Sử dụng try-with-resources cho cả socket để đảm bảo nó luôn được đóng
        try (sslSocket;
             Scanner scanner = new Scanner(sslSocket.getInputStream(), StandardCharsets.UTF_8);
             PrintWriter writer = new PrintWriter(sslSocket.getOutputStream(), true)) {

            StringBuilder sb = new StringBuilder();
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if (line.equals("END_OF_REQUEST")) break;
                sb.append(line).append("\n");
            }

            String rawRequest = sb.toString().trim();
            logInfo("[TRACKER-ENROLL]: Received raw data length: " + rawRequest.length() + " on " + getCurrentTime());
            logInfo("[TRACKER-ENROLL]: Raw request: " + rawRequest);

            // 2. Xử lý request đã nhận
            String response;
            if (rawRequest.isEmpty()) {
                response = "CERT_ERROR|Empty request received.";
            } else if (rawRequest.startsWith("CERT_REQUEST|")) {
                // Tách phần payload (CSR) ra khỏi command
                String escapedCsrPem = rawRequest.substring("CERT_REQUEST|".length());

                if (escapedCsrPem.isEmpty()) {
                    response = "CERT_ERROR|Invalid CERT_REQUEST format. CSR is missing.";
                } else {
                    response = processCertificateRequest(escapedCsrPem);
                }
            } else {
                response = "CERT_ERROR|This port only accepts CERT_REQUEST commands.";
            }

            String finalResponse = response + "\nEND_OF_RESPONSE\n";
            writer.write(finalResponse);
            writer.flush();

            logInfo("[TRACKER-ENROLL]: Sent response to " + sslSocket.getRemoteSocketAddress());

        } catch (Exception e) {
            logError("[TRACKER-ENROLL]: Connection error: " + e.getMessage(), e);
        }
        // Không cần khối finally để đóng socket vì nó đã nằm trong try-with-resources
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

    private String processRequest(String request) {
        if (request.isEmpty()) {
            logInfo("[TRACKER]: Received empty request on " + getCurrentTime());
            return "Yêu cầu rỗng";
        }
        logInfo("[TRACKER]: Request: " + request);

        String[] parts = request.split("\\|");
        if (request.startsWith(RequestInfor.REGISTER)) {
            if (parts.length == 4) {
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
            if (parts.length == 2) {
                return unshareFile(parts);
            }
        } else if (request.startsWith(RequestInfor.REFRESH)) {
            if (parts.length == 3) {
                String peerIp = parts[1];
                int peerPort = Integer.parseInt(parts[2]);
                return sendShareList(new PeerInfo(peerIp, peerPort), true);
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
        }
        logInfo("[TRACKER]: Unkhown command: " + request + " on " + getCurrentTime());
        return "Lệnh không xác định";
    }

    private String sendPeerList(String fileHash, PeerInfo requester) {
        Set<PeerInfo> peers = new HashSet<>();
        publicFiles.values().forEach(fileInfos -> fileInfos.forEach(fileInfo -> {
            if (fileInfo.getFileHash().equals(fileHash)) {
                peers.add(fileInfo.getPeerInfo());
            }
        }));

        for (Map.Entry<FileInfo, Set<PeerInfo>> entry : privateSharedFiles.entrySet()) {
            FileInfo fileInfo = entry.getKey();
            if (fileInfo.getFileHash().equals(fileHash)) {
                Set<PeerInfo> peerInfos = entry.getValue();
                if (peerInfos.contains(requester)) {
                    peers.add(fileInfo.getPeerInfo());
                }
            }
        }

        if (peers.isEmpty()) {
            logInfo("[TRACKER]: No peers found for file hash: " + fileHash + " on " + getCurrentTime());
            return RequestInfor.NOT_FOUND + "|No peers found for file hash: " + fileHash;
        }

        Type setType = new TypeToken<Set<PeerInfo>>() {
        }.getType();
        Gson gson = new GsonBuilder().registerTypeAdapter(PeerInfo.class, new PeerInfoAdapter()).create();
        String peersJson = gson.toJson(peers, setType);
        logInfo("[TRACKER]: Sending peer list for file hash: " + fileHash + " on " + getCurrentTime());
        return RequestInfor.GET_PEERS + "|" + peers.size() + "|" + peersJson;
    }

    private String unshareFile(String[] parts) {
        Type fileInfoType = new TypeToken<FileInfo>() {
        }.getType();
        Gson gson = new GsonBuilder().registerTypeAdapter(FileInfo.class, new FileInfoAdapter()).create();
        FileInfo fileInfo = gson.fromJson(parts[1], fileInfoType);
        Set<FileInfo> fileInfos = publicFiles.get(fileInfo.getFileName());
        if (fileInfos != null) {
            fileInfos.remove(fileInfo);
            if (fileInfos.isEmpty()) {
                publicFiles.remove(fileInfo.getFileName());
            }
        }
        privateSharedFiles.remove(fileInfo);

        logInfo("[TRACKER]: File " + fileInfo.getFileName() + " unshared by " + fileInfo.getPeerInfo().toString() + " on " + getCurrentTime());
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
            response.append(file.getFileName()).append("'").append(file.getFileSize()).append("'").append(file.getFileHash()).append("'").append(file.getPeerInfo().getIp()).append("'").append(file.getPeerInfo().getPort()).append(Config.LIST_SEPARATOR);
        }
        if (response.toString().endsWith(Config.LIST_SEPARATOR)) {
            response = new StringBuilder(response.substring(0, response.length() - Config.LIST_SEPARATOR.length()));
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
        if (parts.length != 4) {
            logInfo("[TRACKER]: Invalid REGISTER request format: expected at least 6 parts, got " + parts.length + " on " + getCurrentTime());
            return LogTag.S_INVALID;
        }

        Type peerType = new TypeToken<PeerInfo>() {
        }.getType();
        Gson peerGson = new GsonBuilder().registerTypeAdapter(PeerInfo.class, new PeerInfoAdapter()).create();
        PeerInfo registeringPeer = peerGson.fromJson(parts[1], peerType);

        knownPeers.add(registeringPeer);
        logInfo("[TRACKER]: Peer registered: " + registeringPeer.getIp() + " on " + getCurrentTime());

        // Deserialize the data structures
        try {
            // Assuming parts[3] is publicFileToPeers JSON, parts[4] privateSharedFile JSON, parts[5] selectiveSharedFiles JSON
            // For simplicity, we'll just log them for now
            String publicFileToPeersJson = parts[2];
            String privateSharedFileJson = parts[3];

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
            logInfo("[TRACKER]: Updated data structures from peer " + registeringPeer.getIp() + " on " + getCurrentTime());
        } catch (Exception e) {
            logInfo("[TRACKER]: Error processing REGISTER data structures: " + e.getMessage() + " on " + getCurrentTime());
        }

        String shareListResponse = sendShareList(registeringPeer, false);
        if (!shareListResponse.startsWith(RequestInfor.SHARED_LIST)) {
            return RequestInfor.REGISTERED;
        }
        return shareListResponse;
    }


    private void pingPeers() {
        try (DatagramChannel channel = DatagramChannel.open()) {
            channel.configureBlocking(false);
            channel.setOption(StandardSocketOptions.SO_BROADCAST, true);
            channel.socket().setSoTimeout(Config.SOCKET_TIMEOUT_MS);

            for (int i = 0; i < 3; i++) {
                String pingMessage = RequestInfor.PING;
                ByteBuffer sendBuffer = ByteBuffer.wrap(pingMessage.getBytes());

                InetSocketAddress broadcastAddr = new InetSocketAddress(Config.BROADCAST_IP, Config.PEER_PORT);
                channel.send(sendBuffer, broadcastAddr);
                logInfo("[TRACKER]: Sent PING broadcast to " + Config.BROADCAST_IP + ":" + Config.PEER_PORT);

                Set<PeerInfo> alivePeers = ConcurrentHashMap.newKeySet();
                long startTime = System.currentTimeMillis();

                ByteBuffer recvBuffer = ByteBuffer.allocate(1024);

                while (System.currentTimeMillis() - startTime < Config.SOCKET_TIMEOUT_MS) {
                    recvBuffer.clear();
                    InetSocketAddress responder = (InetSocketAddress) channel.receive(recvBuffer);

                    if (responder != null) {
                        recvBuffer.flip();
                        String response = new String(recvBuffer.array(), 0, recvBuffer.limit()).trim();

                        if (response.startsWith(RequestInfor.PONG)) {
                            String[] parts = response.split("\\|", 2);
                            String username = parts.length > 1 ? parts[1] : "unknown";

                            PeerInfo peer = new PeerInfo(responder.getAddress().getHostAddress(), responder.getPort(), username);
                            alivePeers.add(peer);

                            logInfo("[TRACKER]: Received PONG from " + responder.getAddress().getHostAddress()
                                    + " (" + username + ") at " + getCurrentTime());
                        }
                    }

                    Thread.sleep(50);
                }

                updateKnownPeers(alivePeers);
                logInfo("[TRACKER]: Ping round " + (i + 1) + " completed. Alive peers: " + alivePeers.size()
                        + " on " + getCurrentTime());
            }

        } catch (IOException | InterruptedException e) {
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
        Type setType = new TypeToken<Set<PeerInfo>>() {
        }.getType();
        Gson gson = new GsonBuilder().registerTypeAdapter(PeerInfo.class, new PeerInfoAdapter()).create();
        String knownPeersJson = gson.toJson(knownPeers, setType);

        logInfo("[TRACKER]: Sending known peers list on " + getCurrentTime());
        return RequestInfor.GET_KNOWN_PEERS + "|" + knownPeers.size() + "|" + knownPeersJson;
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

        Type setType = new TypeToken<Set<PeerInfo>>() {
        }.getType();
        Gson gson = new GsonBuilder().registerTypeAdapter(PeerInfo.class, new PeerInfoAdapter()).create();
        String peersJson = gson.toJson(peers, setType);

        logInfo("[TRACKER]: Sending selective peer list for file hash: " + fileHash + " on " + getCurrentTime());

        return RequestInfor.GET_SHARED_PEERS + "|" + peers.size() + "|" + peersJson;
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
            long startTime = System.currentTimeMillis();
            logInfo("[TRACKER-ENROLL]: Processing certificate request on " + getCurrentTime());

            String certificateChainPem = SSLUtils.signCertificateForPeer(csrPem);
            logInfo("[TRACKER-ENROLL]: Certificate chain generated successfully for peer on " + getCurrentTime());

            return "CERT_RESPONSE|" + certificateChainPem;

        } catch (Exception e) {
            logError("[TRACKER-ENROLL]: Error processing certificate request: " + e.getMessage() + " on " + getCurrentTime(), e);
            return "CERT_ERROR|Failed to sign certificate: " + e.getMessage();
        }
    }

    String sendShareList(PeerInfo peerInfo, boolean isRefresh) {
        Set<FileInfo> filesToSend = new HashSet<>();

        // Add all public shared files
        for (Set<FileInfo> fileInfos : publicFiles.values()) {
            filesToSend.addAll(fileInfos);
        }


        for (Map.Entry<FileInfo, Set<PeerInfo>> entry : privateSharedFiles.entrySet()) {
            FileInfo fileInfo = entry.getKey();
            Set<PeerInfo> allowedPeers = entry.getValue();
            if (allowedPeers.contains(peerInfo)) {
                filesToSend.add(fileInfo);
            }
        }

        if (filesToSend.isEmpty()) {
            logInfo("[TRACKER]: No shared files to send to " + peerInfo.getIp() + "|" + peerInfo.getPort() + " on " + getCurrentTime());
            return RequestInfor.FILE_NOT_FOUND;
        }

        Type setType = new TypeToken<Set<FileInfo>>() {
        }.getType();
        Gson gson = new GsonBuilder().registerTypeAdapter(FileInfo.class, new FileInfoAdapter()).create();

        String peers = gson.toJson(filesToSend, setType);
        logInfo("[TRACKER]: Sending share list (" + filesToSend.size() + " files) to " + peerInfo.getIp() + "|" + peerInfo.getPort() + " on " + getCurrentTime());
        return (isRefresh ? RequestInfor.REFRESHED : RequestInfor.SHARED_LIST) + "|" + filesToSend.size() + "|" + peers;
    }

}
