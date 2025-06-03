package model;

import utils.Infor;
import utils.RequestInfor;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;

public class TrackerModel {
    private final int TRACKER_PORT = Infor.TRACKER_PORT;
    private Map<String, List<String>> fileToPeers; // Maps file names to a list of peer IPs

    public TrackerModel() {
        fileToPeers = new HashMap<>();
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
                            fileToPeers.computeIfAbsent("peers", k -> new java.util.ArrayList<>()).add(peerIp);
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
}
