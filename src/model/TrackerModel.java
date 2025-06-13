package model;

import utils.Infor;
import utils.LogTag;
import utils.RequestInfor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

import static utils.Log.*;

public class TrackerModel {
    private final int TRACKER_PORT = Infor.TRACKER_PORT;
    private final ConcurrentHashMap<String, Set<String>> fileToPeers;
    private final CopyOnWriteArraySet<String> knownPeers;
    private final Set<FileBase> sharedFiles;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Selector selector;
    private final ScheduledExecutorService pingExecutor;

    public TrackerModel() throws IOException {
        fileToPeers = new ConcurrentHashMap<>();
        sharedFiles = ConcurrentHashMap.newKeySet();
        knownPeers = new CopyOnWriteArraySet<>();
        selector = Selector.open();
        pingExecutor = Executors.newScheduledThreadPool(1);
        pingExecutor.scheduleAtFixedRate(this::pingPeers, 0, 60, TimeUnit.SECONDS);
    }

    public void startTracker() {
        try (ServerSocketChannel serverSocketChannel = ServerSocketChannel.open()) {
            serverSocketChannel.bind(new InetSocketAddress(TRACKER_PORT));
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            logInfo("Tracker khởi động trên cổng " + TRACKER_PORT + " lúc " + getCurrentTime());

            while (true) {
                selector.select(); // Chặn cho đến khi có sự kiện
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
                        logError("Lỗi không xác định trong key: " + key + " lúc " + getCurrentTime(), null);
                    }
                }
            }
        } catch (IOException e) {
            logError("Lỗi tracker: " + e.getMessage() + " lúc " + getCurrentTime(), e);
        } finally {
            pingExecutor.shutdown();
            try {
                selector.close();
            } catch (IOException e) {
                logError("Lỗi khi đóng selector: " + e.getMessage() + " lúc " + getCurrentTime(), e);
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
            logInfo("Kết nối mới từ: " + clientAddress + " lúc " + getCurrentTime());
        }
    }

    private void handleRead(SelectionKey key) throws IOException {
        SelectableChannel channel = key.channel();
        if (!(channel instanceof SocketChannel)) {
            logError("Kênh không phải SocketChannel trong handleRead: " + channel + " lúc " + getCurrentTime(), null);
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
            logError("Lỗi khi đọc từ client " + state.clientAddress + ": " + e.getMessage() + " lúc " + getCurrentTime(), e);
            key.cancel();
            client.close();
            return;
        }

        if (bytesRead == -1) {
            logInfo("Client ngắt kết nối: " + state.clientAddress + " lúc " + getCurrentTime());
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
                logInfo("Nhận yêu cầu: " + request + " từ " + state.clientAddress + " lúc " + getCurrentTime());
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
            logError("Kênh không phải SocketChannel trong handleWrite: " + channel + " lúc " + getCurrentTime(), null);
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
                    logInfo("Gửi phản hồi đến " + state.clientAddress + " lúc " + getCurrentTime());
                }
            } catch (IOException e) {
                logError("Lỗi khi ghi dữ liệu đến client " + state.clientAddress + ": " + e.getMessage() + " lúc " + getCurrentTime(), e);
                key.cancel();
                client.close();
            }
        }
    }

    private String processRequest(String request) {
        if (request.isEmpty()) {
            logInfo("Yêu cầu rỗng nhận được lúc " + getCurrentTime());
            return "Yêu cầu rỗng";
        }

        String[] parts = request.split("\\|");
        if (request.startsWith(RequestInfor.REGISTER)) {
            if (parts.length == 3) {
                return registerPeer(parts);
            }
            logInfo("Yêu cầu REGISTER không hợp lệ: " + request + " lúc " + getCurrentTime());
            return "Định dạng yêu cầu REGISTER không hợp lệ. Sử dụng: REGISTER|<peerIp>|<peerPort>";
        } else if (request.startsWith(RequestInfor.SHARED_LIST)) {
            if (parts.length == 5) {
                return receiveFileList(parts, request);
            }
            logInfo("Yêu cầu SHARE_LIST không hợp lệ: " + request + " lúc " + getCurrentTime());
            return "Định dạng yêu cầu SHARE_LIST không hợp lệ. Sử dụng: SHARE_LIST|<count>|<fileNames>|<peerIp>|<peerPort>";
        } else if (request.startsWith(RequestInfor.SHARE)) {
            if (parts.length == 4) {
                return shareFile(parts);
            }
            logInfo("Yêu cầu SHARE không hợp lệ: " + request + " lúc " + getCurrentTime());
            return "Định dạng yêu cầu SHARE không hợp lệ. Sử dụng: SHARE|<fileName>|<peerIp>|<peerPort>";
        } else if (request.startsWith(RequestInfor.QUERY)) {
            if (parts.length >= 2) {
                return queryFile(parts);
            }
            logInfo("Yêu cầu QUERY không hợp lệ: " + request + " lúc " + getCurrentTime());
            return "Định dạng yêu cầu QUERY không hợp lệ. Sử dụng: QUERY|<fileName>";
        } else if (request.startsWith(RequestInfor.UNSHARED_FILE)) {
            if (parts.length == 4) {
                return unshareFile(parts);
            }
        }
        else if (request.startsWith(RequestInfor.REFRESH)) {
            if (parts.length == 3) {
                String peerIp = parts[1];
                int peerPort = Integer.parseInt(parts[2]);
                return sendShareList(peerIp, peerPort, true);
            }
        }
        logInfo("Lệnh không xác định: " + request + " lúc " + getCurrentTime());
        return "Lệnh không xác định";
    }

    private String unshareFile(String[] parts) {
        String fileName = parts[1];
        String peerIp = parts[2];
        String peerPort = parts[3];
        String peerInfor = peerIp + "|" + peerPort;
        Set<String> peers = fileToPeers.get(fileName);
        if (peers == null || !peers.remove(peerInfor)) {
            logInfo("Không tìm thấy file " + fileName + " được chia sẻ bởi " + peerInfor + " lúc " + getCurrentTime());
            return LogTag.S_NOT_FOUND;
        }

        // Xóa file khỏi danh sách chia sẻ
        fileToPeers.get(fileName).remove(peerInfor);

        sharedFiles.removeIf(fileBase -> fileBase.getFileName().equals(fileName) && fileBase.getPeerInfor().toString().equals(peerInfor));
        if (peers.isEmpty()) {
            fileToPeers.remove(fileName);
            logInfo("Đã xóa file " + fileName + " khỏi danh sách chia sẻ lúc " + getCurrentTime());
        } else {
            logInfo("Đã xóa peer " + peerInfor + " khỏi file " + fileName + " lúc " + getCurrentTime());
        }
        logInfo(sharedFiles.toString());
        logInfo(fileToPeers.toString());
        return LogTag.S_SUCCESS;
    }

    private String queryFile(String[] parts) {
        StringBuilder fileName = new StringBuilder(parts[1]);
        for (int i = 2; i < parts.length; i++) {
            fileName.append("|").append(parts[i]);
        }
        List<String> peers = new ArrayList<>(fileToPeers.getOrDefault(fileName.toString(), Collections.emptySet()));
        logInfo("QUERY cho file " + fileName + ": trả về peers " + peers + " lúc " + getCurrentTime());
        return String.join(",", peers);
    }

    private String receiveFileList(String[] parts, String request) {
        int count;
        try {
            count = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            logInfo("Số lượng không hợp lệ trong SHARE_LIST: " + parts[1] + " lúc " + getCurrentTime());
            return "Số lượng không hợp lệ.";
        }
        List<String> files = Arrays.asList(parts[2].split(","));
        String peerIp = parts[3];
        int peerPort;
        try {
            peerPort = Integer.parseInt(parts[4]);
        } catch (NumberFormatException e) {
            logInfo("Cổng không hợp lệ trong SHARE_LIST: " + parts[4] + " lúc " + getCurrentTime());
            return "Cổng không hợp lệ.";
        }
        String peerInfor = peerIp + "|" + peerPort;

        if (files.isEmpty()) {
            logInfo("Không có file nào được chỉ định trong SHARE_LIST: " + request + " lúc " + getCurrentTime());
            return "Không có file nào được chỉ định trong yêu cầu SHARE_LIST.";
        }
        if (count <= 0) {
            logInfo("Số lượng không hợp lệ trong SHARE_LIST: " + count + " lúc " + getCurrentTime());
            return "Số lượng phải lớn hơn 0.";
        }
        if (count != files.size()) {
            logInfo("Số lượng không khớp với số file trong SHARE_LIST: " + count + " vs " + files.size() + " lúc " + getCurrentTime());
            return "Số lượng không khớp với số file được chỉ định.";
        }

        for (String file : files) {
            if (file.isEmpty()) {
                logInfo("Tên file rỗng trong SHARE_LIST: " + request + " lúc " + getCurrentTime());
                continue;
            }
            String[] fileInfor = file.split("'");
            if (fileInfor.length != 2) {
                logInfo("Định dạng file không hợp lệ trong SHARE_LIST: " + file + " lúc " + getCurrentTime());
                continue;
            }
            String fileName = fileInfor[0];
            long fileSize;
            try {
                fileSize = Long.parseLong(fileInfor[1]);
            } catch (NumberFormatException e) {
                logInfo("Kích thước file không hợp lệ trong SHARE_LIST: " + fileInfor[1] + " lúc " + getCurrentTime());
                continue;
            }
            fileToPeers.computeIfAbsent(fileName, k -> new CopyOnWriteArraySet<>()).add(peerInfor);
            sharedFiles.add(new FileBase(fileName, fileSize, new PeerInfor(peerIp, peerPort)));
            logInfo("File " + fileName + " được chia sẻ bởi " + peerInfor + " lúc " + getCurrentTime());
        }
        logInfo("SHARE_LIST được xử lý cho " + peerInfor + " lúc " + getCurrentTime());
        logInfo(sharedFiles.toString());
        return "Các file được chia sẻ thành công bởi " + peerInfor;
    }

    private String shareFile(String[] parts) {
        String fileName = parts[1];
        String peerIp = parts[2];
        String peerPort = parts[3];
        String peerInfor = peerIp + "|" + peerPort;
        fileToPeers.computeIfAbsent(fileName, k -> new CopyOnWriteArraySet<>()).add(peerInfor);
        sharedFiles.add(new FileBase(fileName, 0, new PeerInfor(peerIp, Integer.parseInt(peerPort))));
        logInfo("File " + fileName + " được chia sẻ bởi " + peerInfor + " lúc " + getCurrentTime());
        return "File " + fileName + " được chia sẻ thành công bởi " + peerInfor;
    }

    private String registerPeer(String[] parts) {
        String peerIp = parts[1];
        String peerPort;
        try {
            peerPort = parts[2];
            Integer.parseInt(peerPort); // Validate port
        } catch (NumberFormatException e) {
            logInfo("Cổng không hợp lệ trong REGISTER: " + parts[2] + " lúc " + getCurrentTime());
            return LogTag.S_INVALID;
        }
        String peerInfor = peerIp + "|" + peerPort;
        knownPeers.add(peerInfor);
        logInfo("Đăng ký peer: " + peerInfor + " lúc " + getCurrentTime());

        String shareListResponse = sendShareList(peerIp, Integer.parseInt(peerPort), false);
        if (!shareListResponse.startsWith(RequestInfor.SHARED_LIST)) {
            return RequestInfor.REGISTERED;
        }
        return shareListResponse;
    }

    private String sendShareList(String peerIp, int peerPort, boolean isRefresh) {
        if (sharedFiles.isEmpty()) {
            logInfo("Không có file nào được chia sẻ tới peer: " + peerIp + "|" + peerPort + " lúc " + getCurrentTime());
            return RequestInfor.FILE_NOT_FOUND;
        }

        StringBuilder msgBuilder = new StringBuilder();
        for (FileBase file : sharedFiles) {
            msgBuilder.append(file.getFileName())
                    .append("'").append(file.getFileSize())
                    .append("'").append(file.getPeerInfor().getIp())
                    .append("'").append(file.getPeerInfor().getPort())
                    .append(",");
        }
        if (msgBuilder.charAt(msgBuilder.length() - 1) == ',') {
            msgBuilder.deleteCharAt(msgBuilder.length() - 1);
        }
        String shareList = msgBuilder.toString();
        logInfo("Gửi SHARE_LIST đến " + peerIp + "|" + peerPort + ": " + shareList + " lúc " + getCurrentTime());
        return (isRefresh ? RequestInfor.REFRESHED : RequestInfor.SHARED_LIST) + "|" + sharedFiles.size() + "|" + shareList;
    }

    private void pingPeers() {
        try (DatagramChannel channel = DatagramChannel.open()) {
            channel.configureBlocking(false);
            channel.socket().setSoTimeout(5000); // Tăng timeout lên 5 giây

            Map<String, Integer> retryCount = new ConcurrentHashMap<>();
            Set<String> alivePeers = ConcurrentHashMap.newKeySet();

            // Khởi tạo số lần thử cho mỗi peer
            for (String peer : knownPeers) {
                retryCount.put(peer, 0);
            }

            long startTime = System.currentTimeMillis();
            while (!retryCount.isEmpty() && System.currentTimeMillis() - startTime < 5000) {
                // Gửi PING
                for (String peer : retryCount.keySet()) {
                    if (alivePeers.contains(peer) || retryCount.get(peer) >= 3) continue;

                    String[] peerInfor = peer.split("\\|");
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

                // Nhận PONG
                try {
                    ByteBuffer buffer = ByteBuffer.allocate(1024);
                    InetSocketAddress address = (InetSocketAddress) channel.receive(buffer);
                    if (address != null) {
                        buffer.flip();
                        String response = new String(buffer.array(), 0, buffer.limit()).trim();
                        if (response.equals(RequestInfor.PONG)) {
                            String peerInfor = address.getAddress().getHostAddress() + "|" + address.getPort();
                            if (retryCount.containsKey(peerInfor)) {
                                alivePeers.add(peerInfor);
                                logInfo("Nhận PONG từ " + peerInfor + " lúc " + getCurrentTime());
                            }
                        }
                    }
                } catch (IOException e) {
                    // Bỏ qua timeout hoặc lỗi nhận
                }

                // Đợi một chút để tránh CPU overload
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // Cập nhật danh sách peer
            updateKnownPeers(alivePeers);
            logInfo("Chu kỳ ping hoàn tất. Peer còn sống: " + alivePeers + " lúc " + getCurrentTime());

        } catch (IOException e) {
            logError("Lỗi trong pingPeers: " + e.getMessage() + " lúc " + getCurrentTime(), e);
            e.printStackTrace();
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
}