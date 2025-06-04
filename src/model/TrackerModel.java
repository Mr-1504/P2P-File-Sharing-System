package model;

import utils.Infor;
import utils.RequestInfor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TrackerModel {
    private final int TRACKER_PORT = Infor.TRACKER_PORT;
    private final ConcurrentHashMap<String, Set<String>> fileToPeers;
    private final CopyOnWriteArraySet<String> knownPeers;
    private final ExecutorService executor;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public TrackerModel() {
        fileToPeers = new ConcurrentHashMap<>();
        knownPeers = new CopyOnWriteArraySet<>();
        executor = Executors.newCachedThreadPool();
        ScheduledExecutorService pingExecutor = Executors.newScheduledThreadPool(1);
        pingExecutor.scheduleAtFixedRate(this::pingPeers, 0, 60, TimeUnit.SECONDS);
    }

    public void startTracker() {
        try (ServerSocket serverSocket = new ServerSocket(TRACKER_PORT)) {
            System.out.println("Tracker started on port " + TRACKER_PORT + " at " + getCurrentTime());
            while (true) {
                try (Socket client = serverSocket.accept()) {
                    System.out.println("New client connected: " + client.getInetAddress().getHostAddress() + " at " + getCurrentTime());

                    BufferedReader in = new BufferedReader(new java.io.InputStreamReader(client.getInputStream()));
                    PrintWriter out = new PrintWriter(client.getOutputStream(), true);
                    String request = in.readLine();
                    System.out.println("Received request: " + request + " at " + getCurrentTime());

                    String[] parts = request.split("\\|");
                    if (request.startsWith(RequestInfor.REGISTER)) {
                        if (parts.length == 3) {
                            registerPeer(parts, out);
                        } else {
                            out.println("Invalid REGISTER request format. Use: REGISTER|<peerIp>|<peerPort>");
                            System.out.println("Invalid REGISTER request: " + request + " at " + getCurrentTime());
                        }
                    } else if (request.startsWith(RequestInfor.SHARE)) {
                        if (parts.length == 4) {
                            shareFile(parts, out);
                        } else {
                            out.println("Invalid SHARE request format. Use: SHARE|<fileName>|<peerIp>|<peerPort>");
                            System.out.println("Invalid SHARE request: " + request + " at " + getCurrentTime());
                        }
                    } else if (request.startsWith(RequestInfor.SHARE_LIST)) {
                        if (parts.length == 5) {
                            shareListFile(parts, out, request);
                        } else {
                            out.println("Invalid SHARE_LIST request format. Use: SHARE_LIST|<count>|<fileNames>|<peerIp>|<peerPort>");
                            System.out.println("Invalid SHARE_LIST request: " + request + " at " + getCurrentTime());
                        }
                    } else if (request.startsWith(RequestInfor.QUERY)) {
                        if (parts.length >= 2) {
                            queryFile(parts, out);
                        } else {
                            out.println("Invalid QUERY request format. Use: QUERY|<fileName>");
                            System.out.println("Invalid QUERY request: " + request + " at " + getCurrentTime());
                        }
                    } else {
                        out.println("Unknown command");
                        System.out.println("Unknown command: " + request + " at " + getCurrentTime());
                    }
                } catch (Exception e) {
                    System.err.println("Error handling client connection: " + e.getMessage() + " at " + getCurrentTime());
                }
            }
        } catch (IOException e) {
            System.err.println("Tracker error: " + e.getMessage() + " at " + getCurrentTime());
            e.printStackTrace();
        }
    }

    private void queryFile(String[] parts, PrintWriter out) {
        StringBuilder fileName = new StringBuilder(parts[1]);
        for (int i = 2; i < parts.length; i++) {
            fileName.append("|").append(parts[i]);
        }
        List<String> peers = new ArrayList<>(fileToPeers.getOrDefault(fileName.toString(), Collections.emptySet()));
        out.println(String.join(",", peers));
        System.out.println("QUERY for file " + fileName + ": returned peers " + peers + " at " + getCurrentTime());
    }

    private void shareListFile(String[] parts, PrintWriter out, String request) {
        int count = Integer.parseInt(parts[1]);
        List<String> files = Arrays.asList(parts[2].split(","));
        String peerIp = parts[3];
        int peerPort = Integer.parseInt(parts[4]);
        String peerInfor = peerIp + "|" + peerPort;

        if (files.isEmpty()) {
            out.println("No files specified for SHARE_LIST request.");
            System.out.println("No files specified for SHARE_LIST: " + request + " at " + getCurrentTime());
            return;
        }
        if (count <= 0) {
            out.println("Count must be greater than 0.");
            System.out.println("Invalid count for SHARE_LIST: " + count + " at " + getCurrentTime());
            return;
        }
        if (count != files.size()) {
            out.println("Count does not match the number of files specified.");
            System.out.println("Count mismatch for SHARE_LIST: " + count + " vs " + files.size() + " at " + getCurrentTime());
            return;
        }

        for (String fileName : files) {
            fileToPeers.computeIfAbsent(fileName, k -> new CopyOnWriteArraySet<>()).add(peerInfor);
            System.out.println("File " + fileName + " shared by " + peerInfor + " at " + getCurrentTime());
        }
        out.println("Files shared successfully by " + peerInfor);
        System.out.println("SHARE_LIST processed for " + peerInfor + " at " + getCurrentTime());
    }

    private void shareFile(String[] parts, PrintWriter out) {
        String fileName = parts[1];
        String peerIp = parts[2];
        String peerPort = parts[3];
        String peerInfor = peerIp + "|" + peerPort;
        fileToPeers.computeIfAbsent(fileName, k -> new CopyOnWriteArraySet<>()).add(peerInfor);
        out.println("File " + fileName + " shared successfully by " + peerInfor);
        System.out.println("File " + fileName + " shared by " + peerInfor + " at " + getCurrentTime());
    }

    private void registerPeer(String[] parts, PrintWriter out) {
        String peerIp = parts[1];
        String peerPort = parts[2];
        String peerInfor = peerIp + "|" + peerPort;
        knownPeers.add(peerInfor);
        out.println(peerInfor);
        System.out.println("Registered peer: " + peerInfor + " at " + getCurrentTime());
    }

    private void pingPeers() {
        executor.submit(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(2000); // Timeout 2 giây mỗi lần ping
                Map<String, Integer> retryCount = new ConcurrentHashMap<>();
                Set<String> alivePeers = ConcurrentHashMap.newKeySet();

                // Khởi tạo số lần thử cho mỗi peer
                for (String peer : knownPeers) {
                    retryCount.put(peer, 0);
                }

                // Gửi PING và nhận PONG
                while (!retryCount.isEmpty()) {
                    // Gửi PING cho các peer chưa alive
                    for (String peer : retryCount.keySet()) {
                        if (alivePeers.contains(peer)) continue; // Bỏ qua peer đã alive
                        int attempts = retryCount.get(peer);
                        if (attempts >= 3) continue; // Bỏ qua peer đã thử 3 lần

                        String[] peerInfor = peer.split("\\|");
                        String peerIp = peerInfor[0];
                        int peerPort = Integer.parseInt(peerInfor[1]);
                        byte[] pingMessage = RequestInfor.PING.getBytes();
                        DatagramPacket packet = new DatagramPacket(pingMessage, pingMessage.length,
                                InetAddress.getByName(peerIp), peerPort);

                        try {
                            socket.send(packet);
                            retryCount.put(peer, attempts + 1);
                            System.out.println("Sent PING to " + peer + " (attempt " + (attempts + 1) + ") at " + getCurrentTime());
                        } catch (IOException e) {
                            System.err.println("Error sending PING to " + peer + ": " + e.getMessage() + " at " + getCurrentTime());
                            retryCount.put(peer, attempts + 1);
                        }
                    }

                    // Nhận PONG trong 2 giây
                    long startTime = System.currentTimeMillis();
                    while (System.currentTimeMillis() - startTime < 2000) {
                        try {
                            byte[] buffer = new byte[1024];
                            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                            socket.receive(response);

                            String responseMessage = new String(response.getData(), 0, response.getLength());
                            if (responseMessage.equals(RequestInfor.PONG)) {
                                String ip = response.getAddress().getHostAddress();
                                int port = response.getPort();
                                String peerInfor = ip + "|" + port;
                                if (retryCount.containsKey(peerInfor)) {
                                    alivePeers.add(peerInfor);
                                    System.out.println("Received PONG from " + peerInfor + " at " + getCurrentTime());
                                }
                            }
                        } catch (SocketTimeoutException e) {
                            break; // Hết timeout, chuyển sang vòng lặp tiếp
                        } catch (IOException e) {
                            System.err.println("Error receiving PONG: " + e.getMessage() + " at " + getCurrentTime());
                        }
                    }

                    // Xóa các peer đã thử đủ 3 lần hoặc alive
                    retryCount.entrySet().removeIf(entry -> {
                        String peer = entry.getKey();
                        int attempts = entry.getValue();
                        return alivePeers.contains(peer) || attempts >= 3;
                    });
                }

                // Cập nhật danh sách peer
                updateKnownPeers(alivePeers);
                System.out.println("Ping cycle completed. Alive peers: " + alivePeers + " at " + getCurrentTime());

            } catch (IOException e) {
                System.err.println("Error in pingPeers: " + e.getMessage() + " at " + getCurrentTime());
                e.printStackTrace();
            }
        });
    }

    private void updateKnownPeers(Set<String> alivePeers) {
        synchronized (this) {
            // Cập nhật knownPeers
            knownPeers.retainAll(alivePeers);
            knownPeers.addAll(alivePeers);
            System.out.println("Known peers updated. Current count: " + knownPeers.size() + " at " + getCurrentTime());

            // Cập nhật fileToPeers
            Iterator<Map.Entry<String, Set<String>>> iterator = fileToPeers.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Set<String>> entry = iterator.next();
                String fileName = entry.getKey();
                Set<String> peers = entry.getValue();
                peers.retainAll(alivePeers);
                if (peers.isEmpty()) {
                    iterator.remove();
                    System.out.println("Removed empty file entry: " + fileName + " at " + getCurrentTime());
                }
            }
        }
    }

    private String getCurrentTime() {
        return LocalDateTime.now().format(formatter);
    }
}