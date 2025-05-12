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
            Map<String, Object> request = gson.fromJson(requestJson, Map.class);
            Map<String, Object> response = new HashMap<>();

            switch (request.get("type").toString()) {
                case "SEARCH":
                    response.put("files", fileManager.searchFiles(request.get("filename").toString()));
                    break;
                case "LIST":
                    response.put("files", fileManager.getFileList());
                    break;
                case "DOWNLOAD":
                    sendChunk(clientSocket, request.get("filename").toString(),
                            ((Double) request.get("chunk")).intValue());
                    return;
                case "PEER_LIST":
                    response.put("peers", peerList);
                    break;
            }
            response.put("status", "success");
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

    public List<String> searchFile(String filename) throws IOException {
        List<String> results = new ArrayList<>();
        for (PeerInfor peer : peerList) {
            try (Socket socket = new Socket(peer.getHost(), peer.getPort());
                 PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                Map<String, String> request = new HashMap<>();
                request.put("type", "SEARCH");
                request.put("filename", filename);
                writer.println(gson.toJson(request));

                Map<String, Object> response = gson.fromJson(reader.readLine(), Map.class);
                if ("success".equals(response.get("status"))) {
                    List<String> files = (List<String>) response.get("files");
                    results.addAll(files);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return results;
    }

    public void downloadFile(String filename, List<PeerInfor> peers) throws IOException, NoSuchAlgorithmException {
        File file = new File(fileManager.SHARED_DIR + filename);
        if (!file.exists()) throw new IOException("File không tồn tại");

        int chunkCount = (int) Math.ceil(file.length() / (double) fileManager.CHUNK_SIZE);
        List<byte[]> chunks = new ArrayList<>(Collections.nCopies(chunkCount, null));
        ExecutorService executor = Executors.newFixedThreadPool(peers.size());

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            int chunkIndex = i;
            PeerInfor peer = peers.get(i % peers.size());
            futures.add(executor.submit(() -> {
                try {
                    chunks.set(chunkIndex, downloadChunk(peer.getHost(), peer.getPort(), filename, chunkIndex));
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

        fileManager.saveFile(filename, chunks);
        executor.shutdown();
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