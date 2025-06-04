package model;

import utils.Infor;
import utils.RequestInfor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.*;
import java.nio.Buffer;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

public class TrackerModel {
    private final int TRACKER_PORT = Infor.TRACKER_PORT;
    private final Map<String, List<String>> fileToPeers;
    private final Set<String> knownPeers;
    private final ExecutorService executor;

    public TrackerModel() {
        fileToPeers = new HashMap<>();
        knownPeers = new HashSet<>();
        executor = java.util.concurrent.Executors.newCachedThreadPool();
        ScheduledExecutorService pingExecutor = java.util.concurrent.Executors.newScheduledThreadPool(1);
        pingExecutor.scheduleAtFixedRate(this::pingPeers, 0, 60, java.util.concurrent.TimeUnit.SECONDS);
    }

    public void startTracker() {
        try (ServerSocket serverSocket = new ServerSocket(TRACKER_PORT)) {
            System.out.println("Tracker started on port " + TRACKER_PORT);
            while (true) {
                try (Socket client = serverSocket.accept()) {
                    System.out.println("New client connected: " + client.getInetAddress().getHostAddress());

                    BufferedReader in = new BufferedReader(new java.io.InputStreamReader(client.getInputStream()));
                    PrintWriter out = new PrintWriter(client.getOutputStream(), true);
                    String request = in.readLine();
                    System.out.println("Received request: " + request);

                    String[] parts = request.split("\\|");
                    if (request.startsWith(RequestInfor.REGISTER)) {
                        if (parts.length == 3) {
                            String peerIp = parts[1];
                            String peerPort = parts[2];
                            String peerInfor = peerIp + "|" + peerPort;
                            knownPeers.add(peerInfor);
                            out.println(peerInfor);
                        } else {
                            out.println("Invalid REGISTER request format. Use: REGISTER|<peerIp>|<peerPort>");
                        }
                    } else if (request.startsWith(RequestInfor.SHARE)) {
                        if (parts.length == 4) {
                            String fileName = parts[1];
                            String peerIp = parts[2];
                            String peerPort = parts[3];

                            String peerInfor = peerIp + "|" + peerPort;
                            fileToPeers.computeIfAbsent(fileName, k -> new java.util.ArrayList<>()).add(peerInfor);
                            out.println("File " + fileName + " shared successfully by " + peerInfor);
                            System.out.println("File " + fileName + " shared successfully by " + peerInfor);
                        } else {
                            out.println("Invalid SHARE request format. Use: SHARE|<fileName>|<fileSize>|<peerInfor>");
                        }
                    } else if (request.startsWith(RequestInfor.QUERY)) {
                        System.out.println(parts.length);
                        if (parts.length == 2) {
                            StringBuilder fileName = new StringBuilder(parts[1]);
                            for (int i = 2; i < parts.length; i++) {
                                fileName.append("|").append(parts[i]);
                            }
                            List<String> peers = fileToPeers.getOrDefault(fileName.toString(), Collections.emptyList());
                            out.println(String.join(",", peers));
                        } else {
                            out.println("Invalid QUERY request format. Use: QUERY|<fileName>");
                        }
                    } else {
                        out.println("Unknown command");
                    }
                } catch (Exception e) {
                    System.err.println("Error handling client connection: " + e.getMessage());
                }
            }
        } catch (
                Exception e) {
            e.printStackTrace();
        }
    }

    private void pingPeers() {
        executor.submit(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(10000);

                for (String peer : knownPeers) {
                    String[] peerInfor = peer.split("\\|");
                    String peerIp = peerInfor[0];
                    int peerPort = Integer.parseInt(peerInfor[1]);

                    byte[] pingMessage = RequestInfor.PING.getBytes();

                    DatagramPacket packet = new DatagramPacket(pingMessage, pingMessage.length, InetAddress.getByName(peerIp), peerPort);

                    socket.send(packet);
                }

                long startTime = System.currentTimeMillis();
                Set<String> alivePeers = new HashSet<>();

                while (System.currentTimeMillis() - startTime < 10000) {
                    try {
                        byte[] buffer = new byte[1024];
                        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                        socket.receive(response);

                        String responseMessage = new String(response.getData(), 0, response.getLength());
                        if (responseMessage.equals(RequestInfor.PONG)) {
                            String ip = response.getAddress().getHostAddress();
                            int port = response.getPort();
                            String peerInfor = ip + "|" + port;

                            alivePeers.add(peerInfor);
                            System.out.println("Received PONG from: " + peerInfor);
                        }
                    } catch (SocketTimeoutException e) {
                        break;
                    } catch (IOException e) {
                        System.err.println("Error receiving response: " + e.getMessage());
                    }
                }

                updateKnownPeers(alivePeers);

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void updateKnownPeers(Set<String> alivePeers) {
        knownPeers.retainAll(alivePeers);
        for (String peer : alivePeers) {
            if (!knownPeers.contains(peer)) {
                knownPeers.add(peer);
                System.out.println("New peer added: " + peer);
            }
        }
        System.out.println("Known peers updated. Current count: " + knownPeers.size());

        for(List<String> peers : fileToPeers.values()) {
            for(String peer : peers) {
                if (!alivePeers.contains(peer)) {
                    peers.remove(peer);
                    System.out.println("Removed dead peer: " + peer);
                }
                if (peers.isEmpty()) {
                    fileToPeers.remove(peers);
                    System.out.println("Removed empty file entry for: " + peers);
                }
            }
        }
    }
}