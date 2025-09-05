package model;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

import utils.Infor;

import static utils.Log.*;

import utils.LogTag;
import utils.RequestInfor;

public class TrackerModel {
    private final CopyOnWriteArraySet<String> knownPeers;
    private final Set<FileInfor> publicSharedFiles;
    private final ConcurrentHashMap<String, Set<String>> publicFileToPeers;
    private final ConcurrentHashMap<FileInfor, Set<PeerInfor>> privateSharedFile;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Selector selector;
    private final ScheduledExecutorService pingExecutor;

    public TrackerModel() throws IOException {
        privateSharedFile = new ConcurrentHashMap<>();
        publicFileToPeers = new ConcurrentHashMap<>();
        publicSharedFiles = ConcurrentHashMap.newKeySet();
        knownPeers = new CopyOnWriteArraySet<>();
        selector = Selector.open();
        pingExecutor = Executors.newScheduledThreadPool(1);
        pingExecutor.scheduleAtFixedRate(this::pingPeers, 0, 60, TimeUnit.SECONDS);
    }

    public void startTracker() {
        try (ServerSocketChannel serverSocketChannel = ServerSocketChannel.open()) {
            serverSocketChannel.bind(new InetSocketAddress(Infor.TRACKER_PORT));
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            logInfo("[TRACKER]: Tracker start on " + Infor.TRACKER_PORT + " - " + getCurrentTime());

            while (true) {
                selector.select();
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();

                    if (!key.isValid()) continue;

                    if (key.isAcceptable()) {
                        handleAccept(key);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isWritable()) {
                        handleWrite(key);
                    } else {
                        logError("[TRACKER]: Unknown Error: " + key + " on " + getCurrentTime(), null);
                    }
                }
            }
        } catch (IOException e) {
            logError("[TRACKER]: Tracker Error: " + e.getMessage() + " on " + getCurrentTime(), e);
        } finally {
            pingExecutor.shutdown();
            try {
                selector.close();
            } catch (IOException e) {
                logError("[TRACKER]: Close selector error: " + e.getMessage() + " on " + getCurrentTime(), e);
            }
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
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
        if (!(channel instanceof SocketChannel)) {
            logError("[TRACKER]: Channel isn't SocketChannel in handleWrite: " + channel + " on " + getCurrentTime(), null);
            key.cancel();
            return;
        }

        SocketChannel client = (SocketChannel) channel;
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

        String[] parts = request.split("\\|");
        if (request.startsWith(RequestInfor.REGISTER)) {
            if (parts.length == 3) {
                return registerPeer(parts);
            }
            logInfo("[TRACKER]: Invalid REGISTER request: " + request + " on " + getCurrentTime());
            return "Định dạng yêu cầu REGISTER không hợp lệ. Sử dụng: REGISTER|<peerIp>|<peerPort>";
        } else if (request.startsWith(RequestInfor.SHARED_LIST)) {
            if (parts.length == 5) {
                return receiveFileList(parts, request);
            }
            logInfo("[TRACKER]: Invalid SHARE_LIST request:" + request + " on " + getCurrentTime());
            return "Định dạng yêu cầu SHARE_LIST không hợp lệ. Sử dụng: SHARE_LIST|<count>|<fileNames>|<peerIp>|<peerPort>";
        } else if (request.startsWith(RequestInfor.SHARE)) {
            if (parts.length == 6) {
                return shareFile(parts);
            }
            logInfo("[TRACKER]: Invalid SHARE request: " + request + " on " + getCurrentTime());
            return "Định dạng yêu cầu SHARE không hợp lệ. Sử dụng: SHARE|<fileName>|<peerIp>|<peerPort>";
        } else if (request.startsWith(RequestInfor.QUERY)) {
            if (parts.length >= 2) {
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
            if (parts.length == 2) {
                String fileHash = parts[1];
                return sendPeerList(fileHash);
            }
        }
        logInfo("[TRACKER]: Unkhown command: " + request + " on " + getCurrentTime());
        return "Lệnh không xác định";
    }

    private String sendPeerList(String fileHash) {
        List<String> peers = new ArrayList<>();
        for (FileInfor file : publicSharedFiles) {
            if (file.getFileHash().equals(fileHash)) {
                peers.add(file.getPeerInfor().getIp() + Infor.FILE_SEPARATOR + file.getPeerInfor().getPort());
            }
        }
        if (peers == null || peers.isEmpty()) {
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
        String peerInfor = peerIp + "|" + peerPort;

        publicSharedFiles.removeIf(file -> file.getFileHash().equals(fileHash) && file.getPeerInfor().toString().equals(peerInfor));
        Set<String> peers = publicFileToPeers.get(fileName);
        peers.remove(peerInfor);

        if (peers.isEmpty()) {
            publicFileToPeers.remove(fileName);
            logInfo("[TRACKER]: Removed file " + fileName + " from sharing list on " + getCurrentTime());
        } else {
            logInfo("[TRACKER]: Removed peer " + peerInfor + " from file " + fileName + " on " + getCurrentTime());
        }
        logInfo(publicSharedFiles.toString());
        logInfo(publicFileToPeers.toString());
        return LogTag.S_SUCCESS;
    }

    private String queryFile(String[] parts) {
        StringBuilder keywordBuilder = new StringBuilder(parts[1]);
        for (int i = 2; i < parts.length; i++) {
            keywordBuilder.append("|").append(parts[i]);
        }
        String keyword = keywordBuilder.toString();

        Set<FileInfor> resultPeers = new HashSet<>();
        for (FileInfor file : publicSharedFiles) {
            if (file.getFileName().toLowerCase().contains(keyword.toLowerCase())) {
                resultPeers.add(file);
            }
        }

        List<FileInfor> files = new ArrayList<>(resultPeers);
        logInfo("[TRACKER]: QUERY for file containing \"" + keyword + "\": return peers " + files + " on " + getCurrentTime());
        String response = RequestInfor.QUERY + "|" + files.size() + "|";
        if (files.isEmpty()) {
            logInfo("[TRACKER]: No peers found for file containing \"" + keyword + "\" on " + getCurrentTime());
            return response + "No files found.";
        }

        for (FileInfor file : files) {
            response += file.getFileName() + "'" + file.getFileSize() + "'" + file.getFileHash()
                    + "'" + file.getPeerInfor().getIp() + "'" + file.getPeerInfor().getPort() + Infor.LIST_SEPARATOR;
        }
        if (response.endsWith(Infor.LIST_SEPARATOR)) {
            response = response.substring(0, response.length() - Infor.LIST_SEPARATOR.length());
        }
        response += "\n";
        return response;
    }

    private String receiveFileList(String[] parts, String request) {
        int count;
        try {
            count = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            logInfo("[TRACKER]: Invalid count in SHARE_LIST: " + parts[1] + " on " + getCurrentTime());
            return "Số lượng không hợp lệ.";
        }
        List<String> files = Arrays.asList(parts[2].split(Infor.LIST_SEPARATOR));
        String peerIp = parts[3];
        int peerPort;
        try {
            peerPort = Integer.parseInt(parts[4]);
        } catch (NumberFormatException e) {
            logInfo("[TRACKER]: Invalid port SHARE_LIST: " + parts[4] + " on " + getCurrentTime());
            return "Cổng không hợp lệ.";
        }
        String peerInfor = peerIp + "|" + peerPort;

        if (files.isEmpty()) {
            logInfo("[TRACKER]: Invalid file in SHARE_LIST: " + request + " on " + getCurrentTime());
            return "Không có file nào được chỉ định trong yêu cầu SHARE_LIST.";
        }
        if (count <= 0) {
            logInfo("[TRACKER]: Invalid count in SHARE_LIST: " + count + " on " + getCurrentTime());
            return "Số lượng phải lớn hơn 0.";
        }
        if (count != files.size()) {
            logInfo("[TRACKER]: Count doesn't match with SHARE_LIST: " + count + " vs " + files.size() + " on " + getCurrentTime());
            return "Số lượng không khớp với số file được chỉ định.";
        }

        for (String file : files) {
            if (file.isEmpty()) {
                logInfo("[TRACKER]: Empty filename in SHARE_LIST: " + request + " on " + getCurrentTime());
                continue;
            }
            String[] fileInfor = file.split("'");
            if (fileInfor.length != 3) {
                logInfo("[TRACKER]: Invalid file format in SHARE_LIST: " + file + " on " + getCurrentTime());
                continue;
            }
            String fileName = fileInfor[0];
            long fileSize;
            try {
                fileSize = Long.parseLong(fileInfor[1]);
            } catch (NumberFormatException e) {
                logInfo("[TRACKER]: Invalid file size in SHARE_LIST: " + fileInfor[1] + " on " + getCurrentTime());
                continue;
            }

            String fileHash = fileInfor[2];

            publicFileToPeers.computeIfAbsent(fileName, k -> new CopyOnWriteArraySet<>()).add(peerInfor);
            publicSharedFiles.add(new FileInfor(fileName, fileSize, fileHash, new PeerInfor(peerIp, peerPort)));
            logInfo("[TRACKER]: File " + fileName + "shared by " + peerInfor + " on " + getCurrentTime());
        }
        logInfo("[TRACKER]: SHARE_LIST processed for " + peerInfor + " on " + getCurrentTime());
        logInfo(publicSharedFiles.toString());
        return "Files shared successfully by " + peerInfor;
    }

    private String shareFile(String[] parts) {
        String fileName = parts[1];
        long fileSize = Long.parseLong(parts[2]);
        String fileHash = parts[3];
        String peerIp = parts[4];
        String peerPort = parts[5];
        String peerInfor = peerIp + "|" + peerPort;
        publicFileToPeers.computeIfAbsent(fileName, k -> new CopyOnWriteArraySet<>()).add(peerInfor);
        publicSharedFiles.add(new FileInfor(fileName, fileSize, fileHash, new PeerInfor(peerIp, Integer.parseInt(peerPort))));
        logInfo("[TRACKER]: File " + fileName + " shared by " + peerInfor + " on " + getCurrentTime());
        return "File " + fileName + " được chia sẻ thành công bởi " + peerInfor;
    }

    private String registerPeer(String[] parts) {
        String peerIp = parts[1];
        String peerPort;
        try {
            peerPort = parts[2];
            Integer.parseInt(peerPort); // Validate port
        } catch (NumberFormatException e) {
            logInfo("[TRACKER]: Invalid Port in REGISTER: " + parts[2] + " on " + getCurrentTime());
            return LogTag.S_INVALID;
        }
        String peerInfor = peerIp + "|" + peerPort;
        knownPeers.add(peerInfor);
        logInfo("[TRACKER]: Peer registered: " + peerInfor + " on " + getCurrentTime());

        String shareListResponse = sendShareList(peerIp, Integer.parseInt(peerPort), false);
        if (!shareListResponse.startsWith(RequestInfor.SHARED_LIST)) {
            return RequestInfor.REGISTERED;
        }
        return shareListResponse;
    }

    private String sendShareList(String peerIp, int peerPort, boolean isRefresh) {
        if (publicSharedFiles.isEmpty()) {
            logInfo("[TRACKER]: No shared files to send to " + peerIp + "|" + peerPort + " on " + getCurrentTime());
            return RequestInfor.FILE_NOT_FOUND;
        }

        StringBuilder msgBuilder = new StringBuilder();
        for (FileInfor file : publicSharedFiles) {
            msgBuilder.append(file.getFileName())
                    .append("'").append(file.getFileSize())
                    .append("'").append(file.getFileHash())
                    .append("'").append(file.getPeerInfor().getIp())
                    .append("'").append(file.getPeerInfor().getPort())
                    .append(Infor.LIST_SEPARATOR);
        }
        if (msgBuilder.charAt(msgBuilder.length() - 1) == ',') {
            msgBuilder.deleteCharAt(msgBuilder.length() - 1);
        }
        String shareList = msgBuilder.toString();
        logInfo("[TRACKER]: Sending share list to " + peerIp + "|" + peerPort + " on " + getCurrentTime());
        return (isRefresh ? RequestInfor.REFRESHED : RequestInfor.SHARED_LIST) + "|" + publicSharedFiles.size() + "|" + shareList;
    }

    private void pingPeers() {
        try (DatagramChannel channel = DatagramChannel.open()) {
            channel.configureBlocking(false);
            channel.socket().setSoTimeout(Infor.SOCKET_TIMEOUT_MS);

            Map<String, Integer> retryCount = new ConcurrentHashMap<>();
            Set<String> alivePeers = ConcurrentHashMap.newKeySet();

            for (String peer : knownPeers) {
                retryCount.put(peer, 0);
            }

            long startTime = System.currentTimeMillis();
            while (!retryCount.isEmpty() && System.currentTimeMillis() - startTime < 5000) {
                for (String peer : retryCount.keySet()) {
                    if (alivePeers.contains(peer) || retryCount.get(peer) >= 3) continue;

                    String[] peerInfor = peer.split(Infor.FIELD_SEPARATOR_REGEX);
                    String peerIp = peerInfor[0];
                    int peerPort;
                    try {
                        peerPort = Integer.parseInt(peerInfor[1]);
                    } catch (NumberFormatException e) {
                        logError("[TRACKER]: Invalid port for peer " + peer + ": " + e.getMessage() + " on " + getCurrentTime(), e);
                        retryCount.remove(peer);
                        continue;
                    }
                    ByteBuffer pingMessage = ByteBuffer.wrap(RequestInfor.PING.getBytes());
                    try {
                        channel.send(pingMessage, new InetSocketAddress(peerIp, peerPort));
                        retryCount.compute(peer, (k, v) -> v + 1);
                        logInfo("[TRACKER]: Sent PING to " + peer + " on " + getCurrentTime());
                    } catch (IOException e) {
                        logError("[TRACKER]: Failed to send PING to " + peer + ": " + e.getMessage() + " on " + getCurrentTime(), e);
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
                                logInfo("[TRACKER]: Received PONG from " + peerInfor + " on " + getCurrentTime());
                            }
                        }
                    }
                } catch (IOException e) {
                    logError("[TRACKER]: Error receiving PONG: " + e.getMessage() + " on " + getCurrentTime(), e);
                }

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            updateKnownPeers(alivePeers);
            logInfo("[TRACKER]: Ping completed. Alive peers: " + alivePeers.size() + " on " + getCurrentTime());

        } catch (IOException e) {
            logError("[TRACKER]: Ping error: " + e.getMessage() + " on " + getCurrentTime(), e);
        }
    }

    private void updateKnownPeers(Set<String> alivePeers) {
        synchronized (this) {
            knownPeers.retainAll(alivePeers);
            knownPeers.addAll(alivePeers);
            logInfo("[TRACKER]: Updated known peers: " + knownPeers + " on " + getCurrentTime());

            Iterator<Map.Entry<String, Set<String>>> iterator = publicFileToPeers.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Set<String>> entry = iterator.next();
                String fileName = entry.getKey();
                Set<String> peers = entry.getValue();
                peers.retainAll(alivePeers);
                if (peers.isEmpty()) {
                    iterator.remove();
                    logInfo("[TRACKER]: Removed file " + fileName + " from tracker as no alive peers found on " + getCurrentTime());
                }
                publicSharedFiles.removeIf(fileInfor ->
                        fileInfor.getFileName().equals(fileName) &&
                                !alivePeers.contains(fileInfor.getPeerInfor().toString()));
            }
        }
    }

    private String getCurrentTime() {
        return LocalDateTime.now().format(formatter);
    }
}