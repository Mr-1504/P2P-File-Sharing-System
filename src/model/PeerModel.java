package model;

import com.google.gson.Gson;

import java.io.*;
import java.net.*;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class PeerModel {
    private List<PeerInfor> peerList;
    private FileManager fileManager;
    private Gson gson;
    private String host;
    private int port;
    private ServerSocket serverSocket;
    private MulticastSocket multicastSocket;
    private boolean running;
    private static final String MULTICAST_ADDRESS = "230.0.0.1";
    private static final int MULTICAST_PORT = 4446;

    public PeerModel(String host, int port) {
        this.host = host;
        this.port = port;
        this.peerList = new ArrayList<>();
        this.peerList.add(new PeerInfor(host, port));
        this.fileManager = new FileManager();
        this.gson = new Gson();
        this.running = true;
    }

    public void startServer() throws IOException {
        serverSocket = new ServerSocket(port);
        ExecutorService executor = Executors.newFixedThreadPool(10);
        System.out.println("Server chạy tại " + host + ":" + port);

        new Thread(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    executor.submit(() -> handleClient(clientSocket));
                } catch (IOException e) {
                    if (!serverSocket.isClosed()) e.printStackTrace();
                }
            }
        }).start();

        startPeerDiscovery();
    }

    private void startPeerDiscovery() throws IOException {
        multicastSocket = new MulticastSocket(MULTICAST_PORT);
        InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);
        multicastSocket.joinGroup(group);

        // Luồng gửi tin nhắn DISCOVER
        new Thread(() -> {
            while (running) {
                try {
                    Map<String, Object> discoverMsg = new HashMap<>();
                    discoverMsg.put("type", "DISCOVER");
                    discoverMsg.put("host", host);
                    discoverMsg.put("port", port);
                    byte[] buffer = gson.toJson(discoverMsg).getBytes();
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, MULTICAST_PORT);
                    multicastSocket.send(packet);
                    Thread.sleep(5000); // Gửi mỗi 5 giây
                } catch (IOException | InterruptedException e) {
                    if (running) e.printStackTrace();
                }
            }
        }).start();

        // Luồng nhận tin nhắn DISCOVER
        new Thread(() -> {
            byte[] buffer = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            while (running) {
                try {
                    multicastSocket.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength());
                    Map<String, Object> discoverMsg = gson.fromJson(msg, Map.class);
                    if ("DISCOVER".equals(discoverMsg.get("type"))) {
                        String peerHost = (String) discoverMsg.get("host");
                        int peerPort = ((Double) discoverMsg.get("port")).intValue();
                        PeerInfor peer = new PeerInfor(peerHost, peerPort);
                        updatePeerList(peer);
                    }
                } catch (IOException e) {
                    if (running) e.printStackTrace();
                }
            }
        }).start();
    }

    private void handleClient(Socket clientSocket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)) {
            String requestJson = reader.readLine();
            if (requestJson == null || requestJson.trim().isEmpty()) {
                sendErrorResponse(writer, "Yêu cầu rỗng");
                return;
            }

            Map<String, Object> request;
            try {
                request = gson.fromJson(requestJson, Map.class);
            } catch (Exception e) {
                sendErrorResponse(writer, "Yêu cầu không phải JSON hợp lệ");
                return;
            }

            Map<String, Object> response = new HashMap<>();
            String requestType = (String) request.get("type");
            if (requestType == null) {
                sendErrorResponse(writer, "Loại yêu cầu không được chỉ định");
                return;
            }

            switch (requestType) {
                case "SEARCH":
                    String filename = (String) request.get("filename");
                    if (filename == null) {
                        sendErrorResponse(writer, "Tên file không được chỉ định");
                        return;
                    }
                    response.put("files", fileManager.searchFiles(filename));
                    response.put("status", "success");
                    break;
                case "LIST":
                    response.put("files", fileManager.getFileList());
                    response.put("status", "success");
                    break;
                case "DOWNLOAD":
                    String downloadFile = (String) request.get("filename");
                    Object chunkObj = request.get("chunk");
                    if (downloadFile == null || chunkObj == null) {
                        sendErrorResponse(writer, "Thiếu thông tin file hoặc chunk");
                        return;
                    }
                    sendChunk(clientSocket, downloadFile, ((Double) chunkObj).intValue());
                    return;
                case "GET_SIZE":
                    String fileName = (String) request.get("filename");
                    if (fileName == null) {
                        sendErrorResponse(writer, "Tên file không được chỉ định");
                        return;
                    }
                    File file = new File(fileManager.SHARED_DIR + fileName);
                    if (!file.exists()) {
                        sendErrorResponse(writer, "File không tồn tại trên peer này");
                        return;
                    }
                    response.put("size", file.length());
                    response.put("status", "success");
                    break;
                case "PEER_LIST":
                    response.put("peers", peerList);
                    response.put("status", "success");
                    break;
                default:
                    sendErrorResponse(writer, "Loại yêu cầu không hợp lệ: " + requestType);
                    return;
            }
            writer.println(gson.toJson(response));
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void sendErrorResponse(PrintWriter writer, String message) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("status", "error");
        errorResponse.put("message", message);
        writer.println(gson.toJson(errorResponse));
    }

    private void sendChunk(Socket socket, String filename, int chunkIndex) throws IOException {
        try {
            byte[] chunk = fileManager.getChunk(filename, chunkIndex);
            if (chunk != null) {
                try (OutputStream out = socket.getOutputStream()) {
                    out.write(chunk);
                    out.write(fileManager.getChunkHash(chunk).getBytes());
                }
            }
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }

    public List<FileInfor> searchFile(String filename) throws IOException {
        List<FileInfor> results = new ArrayList<>();
        for (PeerInfor peer : peerList) {
            try (Socket socket = new Socket(peer.getHost(), peer.getPort());
                 PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                Map<String, String> request = new HashMap<>();
                request.put("type", "SEARCH");
                request.put("filename", filename);
                writer.println(gson.toJson(request));

                String responseJson = reader.readLine();
                if (responseJson == null || responseJson.trim().isEmpty()) {
                    System.err.println("Phản hồi rỗng từ " + peer.getHost() + ":" + peer.getPort());
                    continue;
                }

                // Kiểm tra xem chuỗi có phải JSON object không
                if (!responseJson.startsWith("{")) {
                    System.err.println("Phản hồi không phải JSON object từ " + peer.getHost() + ":" + peer.getPort() + ": " + responseJson);
                    continue;
                }

                Map<String, Object> response;
                try {
                    response = gson.fromJson(responseJson, Map.class);
                } catch (Exception e) {
                    System.err.println("Lỗi parse JSON từ " + peer.getHost() + ":" + peer.getPort() + ": " + responseJson);
                    e.printStackTrace();
                    continue;
                }

                if ("success".equals(response.get("status"))) {
                    List<String> files = (List<String>) response.get("files");
                    if (files != null) {
                        for (String fileName : files) {
                            if (fileName != null) {
                                FileInfor fileInfo = new FileInfor(fileName, peer);
                                results.add(fileInfo);
                            }
                        }
                    }
                } else {
                    System.err.println("Phản hồi không thành công từ " + peer.getHost() + ":" + peer.getPort() + ": " + response.get("message"));
                }
            } catch (IOException e) {
                System.err.println("Lỗi kết nối tới " + peer.getHost() + ":" + peer.getPort());
                e.printStackTrace();
            }
        }
        return results;
    }

    public void downloadFile(FileInfor fileInfor, List<PeerInfor> peers) throws IOException, NoSuchAlgorithmException {
        // Lấy kích thước file từ peer chứa file
        long fileSize = getFileSize(fileInfor.getPeerInfor(), fileInfor.getFileName());
        int chunkCount = (int) Math.ceil(fileSize / (double) fileManager.CHUNK_SIZE);
        List<byte[]> chunks = new ArrayList<>(Collections.nCopies(chunkCount, null));
        ExecutorService executor = Executors.newFixedThreadPool(peers.size());

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            int chunkIndex = i;
            PeerInfor peer = peers.get(i % peers.size());
            futures.add(executor.submit(() -> {
                try {
                    chunks.set(chunkIndex, downloadChunk(
                            fileInfor.getPeerInfor().getHost(),
                            fileInfor.getPeerInfor().getPort(),
                            fileInfor.getFileName(),
                            chunkIndex)
                    );
                } catch (IOException | NoSuchAlgorithmException e) {
                    e.printStackTrace();
                }
                return null;
            }));
        }

        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        fileManager.saveFile(fileInfor.getFileName(), chunks);
        executor.shutdown();
    }

    public long getFileSize(PeerInfor peer, String filename) throws IOException {
        try (Socket socket = new Socket(peer.getHost(), peer.getPort());
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            Map<String, String> request = new HashMap<>();
            request.put("type", "GET_SIZE");
            request.put("filename", filename);
            writer.println(gson.toJson(request));

            String responseJson = reader.readLine();
            if (responseJson == null || responseJson.trim().isEmpty()) {
                throw new IOException("Phản hồi rỗng từ " + peer.getHost() + ":" + peer.getPort());
            }

            // Kiểm tra xem chuỗi có phải JSON object không
            if (!responseJson.startsWith("{")) {
                throw new IOException("Phản hồi không phải JSON object từ " + peer.getHost() + ":" + peer.getPort() + ": " + responseJson);
            }

            Map<String, Object> response;
            try {
                response = gson.fromJson(responseJson, Map.class);
            } catch (Exception e) {
                throw new IOException("Lỗi parse JSON từ " + peer.getHost() + ":" + peer.getPort() + ": " + responseJson, e);
            }

            if ("success".equals(response.get("status"))) {
                Object sizeObj = response.get("size");
                if (sizeObj instanceof Number) {
                    return ((Number) sizeObj).longValue();
                } else {
                    throw new IOException("Kích thước file không phải là số từ " + peer.getHost() + ":" + peer.getPort());
                }
            } else {
                throw new IOException("Không thể lấy kích thước file từ " + peer.getHost() + ":" + peer.getPort() + ": " + response.get("message"));
            }
        }
    }

    private byte[] downloadChunk(String host, int port, String filename, int chunkIndex)
            throws IOException, NoSuchAlgorithmException {
        try (Socket socket = new Socket(host, port);
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
             InputStream in = socket.getInputStream()) {
            Map<String, Object> request = new HashMap<>();
            request.put("type", "DOWNLOAD");
            request.put("filename", filename);
            request.put("chunk", chunkIndex);
            writer.println(gson.toJson(request));

            byte[] buffer = new byte[fileManager.CHUNK_SIZE];
            int bytesRead = in.read(buffer);
            byte[] chunk = Arrays.copyOf(buffer, bytesRead);

            byte[] receivedHash = new byte[64];
            in.read(receivedHash);
            String computedHash = fileManager.getChunkHash(chunk);
            if (!computedHash.equals(new String(receivedHash))) {
                throw new IOException("Chunk bị lỗi");
            }
            return chunk;
        }
    }

    public synchronized void updatePeerList(PeerInfor newPeer) {
        if (!peerList.contains(newPeer)) {
            peerList.add(newPeer);
        }
    }

    public synchronized List<PeerInfor> getPeerList() {
        return new ArrayList<>(peerList);
    }

    public void stopServer() throws IOException {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
        if (multicastSocket != null) {
            multicastSocket.close();
        }
    }
}