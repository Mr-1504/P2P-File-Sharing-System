package main.java.service;

import com.google.gson.reflect.TypeToken;
import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.utils.Infor;
import main.java.utils.Log;
import main.java.utils.LogTag;
import main.java.utils.SSLUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import main.java.domain.adapter.FileInfoAdapter;

import javax.net.ssl.SSLSocket;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.net.ConnectException;
import java.util.*;

public class TrackerConnector {
    private final PeerInfo SERVER_HOST;
    private final PeerInfo TRACKER_HOST;
    private final PeerConnector peerConnector; // for peer-to-peer communication if needed later

    public TrackerConnector(PeerInfo serverHost, PeerInfo trackerHost) {
        this.SERVER_HOST = serverHost;
        this.TRACKER_HOST = trackerHost;
        this.peerConnector = new PeerConnector(); // Initialize peer connector if needed
    }

    public int registerWithTracker() {
        // SSL is now mandatory - throw error if certificates not available
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for secure communication.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        int count = 0;

        while (count < Infor.MAX_RETRIES) {
            try (SSLSocket sslSocket = peerConnector.createSecureSocket(TRACKER_HOST)) {
                Log.logInfo("Established SSL connection to tracker");

                // Send registration message over SSL
                Gson gson = new GsonBuilder().registerTypeAdapter(FileInfo.class, new FileInfoAdapter())
                        .enableComplexMapKeySerialization().create();

                // Note: Need to get these from FileManager in coordinator
                // For now, assume empty collections
                Map<String, FileInfo> publicSharedFiles = new HashMap<>();
                Map<FileInfo, Set<PeerInfo>> privateSharedFiles = new HashMap<>();

                Type publicFileType = new TypeToken<Map<String, FileInfo>>() {
                }.getType();
                String publicFileToPeersJson = gson.toJson(publicSharedFiles, publicFileType);
                Type privateFileType = new TypeToken<Map<FileInfo, Set<PeerInfo>>>() {
                }.getType();
                String privateSharedFileJson = gson.toJson(privateSharedFiles, privateFileType);

                String message = "REGISTER|" + this.SERVER_HOST.getIp() + "|" + this.SERVER_HOST.getPort() +
                        "|" + publicFileToPeersJson +
                        "|" + privateSharedFileJson +
                        "\n";
                sslSocket.getOutputStream().write(message.getBytes());
                Log.logInfo("Registered with tracker via SSL with data structures: " + message);
                BufferedReader reader = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));
                String response = reader.readLine();
                Log.logInfo("SSL Tracker response: " + response);
                if (response != null) {
                    if (response.startsWith("REGISTERED")) {
                        return LogTag.I_NOT_FOUND;
                    } else if (response.startsWith("SHARED_LIST")) {
                        Log.logInfo("SSL Tracker registration successful: " + response);
                        return 1;
                    } else {
                        Log.logInfo("SSL Tracker registration failed: " + response);
                        return 0;
                    }
                } else {
                    Log.logInfo("SSL Tracker did not respond.");
                    return 0;
                }
            } catch (ConnectException e) {
                Log.logError("SSL Tracker connection failed: " + e.getMessage(), e);
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                ++count;
            } catch (IOException e) {
                Log.logError("SSL Error connecting to tracker: " + e.getMessage(), e);
                return 0;
            } catch (Exception e) {
                Log.logError("SSL Unexpected error during tracker registration: " + e.getMessage(), e);
                return 0;
            }
        }

        Log.logError("Cannot establish SSL connection to tracker after multiple attempts.", null);
        return 0;
    }

    public boolean shareFileList(List<FileInfo> publicFiles, Map<FileInfo, Set<PeerInfo>> privateFiles) {
        // SSL is now mandatory for all communications
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        try (SSLSocket sslSocket = peerConnector.createSecureSocket(TRACKER_HOST)) {
            Log.logInfo("Established SSL connection to tracker for sharing files");
            StringBuilder messageBuilder = new StringBuilder("SHARE|");
            messageBuilder.append(publicFiles.size()).append("|")
                    .append(privateFiles.size()).append("|");

            Gson gson = new GsonBuilder().registerTypeAdapter(FileInfo.class, new FileInfoAdapter())
                    .enableComplexMapKeySerialization().create();
            Type listType = new TypeToken<List<FileInfo>>() {
            }.getType();
            String publicFileToPeersJson = gson.toJson(publicFiles, listType);
            Type mapType = new TypeToken<Map<FileInfo, Set<PeerInfo>>>() {
            }.getType();
            String privateSharedFileJson = gson.toJson(privateFiles, mapType);

            messageBuilder.append(publicFileToPeersJson).append("|")
                    .append(privateSharedFileJson).append("\n");

            String message = messageBuilder.toString();
            Log.logInfo("Sent request: " + message);
            sslSocket.getOutputStream().write(message.getBytes());
            // response
            BufferedReader reader = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));
            String response = reader.readLine();
            Log.logInfo("Shared file list with tracker, response: " + response);
            return response.startsWith(LogTag.S_SUCCESS);
        } catch (Exception e) {
            Log.logError("Error sharing file list with tracker: " + e.getMessage(), e);
            return false;
        }
    }

    public List<String> getKnownPeers() {
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        try (SSLSocket sslSocket = peerConnector.createSecureSocket(TRACKER_HOST)) {
            Log.logInfo("Established SSL connection to tracker for getting known peers");

            String request = "GET_KNOWN_PEERS\n";
            sslSocket.getOutputStream().write(request.getBytes());
            Log.logInfo("SSL Requesting known peers from tracker, message: " + request);
            BufferedReader buff = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));
            String response = buff.readLine();
            if (response == null || response.isEmpty()) {
                Log.logInfo("No SSL response from tracker for known peers");
                return Collections.emptyList();
            } else {
                String[] parts = response.split("\\|");
                if (parts.length != 3 || !parts[0].equals("GET_KNOWN_PEERS")) {
                    Log.logInfo("Invalid SSL response format from tracker: " + response);
                    return Collections.emptyList();
                } else {
                    int peerCount = Integer.parseInt(parts[1]);
                    if (peerCount == 0) {
                        Log.logInfo("No known peers found via SSL");
                        return Collections.emptyList();
                    } else {
                        String[] peerParts = parts[2].split(",");
                        ArrayList<String> peerInfos = new ArrayList<>();
                        Collections.addAll(peerInfos, peerParts);
                        if (peerInfos.isEmpty()) {
                            Log.logInfo("No valid known peers found via SSL");
                            return Collections.emptyList();
                        } else if (peerCount != peerInfos.size()) {
                            Log.logInfo("Known peer count mismatch via SSL: expected " + peerCount + ", found " + peerInfos.size());
                            return Collections.emptyList();
                        } else {
                            Log.logInfo("Received known peers from tracker via SSL: " + response);
                            return peerInfos;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.logError("SSL Error getting known peers from tracker", e);
            return Collections.emptyList();
        }
    }

    public int unshareFile(FileInfo fileInfo) {
        // SSL is now mandatory for all communications
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        try (SSLSocket sslSocket = peerConnector.createSecureSocket(TRACKER_HOST)) {
            Log.logInfo("Established SSL connection to tracker for unsharing file");

            String query = "UNSHARED_FILE" + "|" + fileInfo.getFileName() + "|" + fileInfo.getFileSize() + "|" + fileInfo.getFileHash() + "|" + this.SERVER_HOST.getIp() + "|" + this.SERVER_HOST.getPort();
            sslSocket.getOutputStream().write(query.getBytes());
            Log.logInfo("Notified tracker about shared file: " + fileInfo.getFileName() + " via SSL, message: " + query);

            sslSocket.close();
            return 1;
        } catch (Exception e) {
            Log.logError("SSL Error notifying tracker about shared file: " + fileInfo.getFileName(), e);
            return 0;
        }
    }

    public List<PeerInfo> getPeersWithFile(String fileHash) {
        // SSL is now mandatory for all communications
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        try (SSLSocket sslSocket = peerConnector.createSecureSocket(TRACKER_HOST)) {
            Log.logInfo("Established SSL connection to tracker for getting peers with file hash");

            String request = "GET_PEERS|" + fileHash + "|" + SERVER_HOST.getIp() + "|" + SERVER_HOST.getPort() + "\n";
            sslSocket.getOutputStream().write(request.getBytes());
            Log.logInfo("SSL Requesting peers with file hash: " + fileHash + ", message: " + request);
            BufferedReader buff = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));
            String response = buff.readLine();
            if (response == null || response.isEmpty()) {
                Log.logInfo("No SSL response from tracker for file hash: " + fileHash);
                return Collections.emptyList();
            } else {
                String[] parts = response.split("\\|");
                if (parts.length != 3 || !parts[0].equals("GET_PEERS")) {
                    Log.logInfo("Invalid SSL response format from tracker: " + response);
                    return Collections.emptyList();
                } else {
                    int peerCount = Integer.parseInt(parts[1]);
                    if (peerCount == 0) {
                        Log.logInfo("No peers found via SSL for file hash: " + fileHash);
                        return Collections.emptyList();
                    } else {
                        String[] peerInfos = parts[2].split(",");
                        ArrayList<PeerInfo> peers = new ArrayList<>();

                        for (String peerInfo : peerInfos) {
                            String[] peerParts = peerInfo.split("'");
                            if (peerParts.length != 2) {
                                Log.logInfo("Invalid peer info format via SSL: " + peerInfo);
                            } else {
                                String ip = peerParts[0];
                                int port = Integer.parseInt(peerParts[1]);
                                peers.add(new PeerInfo(ip, port));
                            }
                        }

                        if (peers.isEmpty()) {
                            Log.logInfo("No valid peers found via SSL for file hash: " + fileHash);
                            return Collections.emptyList();
                        } else if (peerCount != peers.size()) {
                            Log.logInfo("Peer count mismatch via SSL: expected " + peerCount + ", found " + peers.size());
                            return Collections.emptyList();
                        } else {
                            Log.logInfo("Received SSL response from tracker: " + response);
                            return peers;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.logError("SSL Error getting peers with file hash: " + fileHash, e);
            return Collections.emptyList();
        }
    }

    public boolean sendChatMessage(String peerIp, int peerPort, String message, String senderId) {
        // SSL is now mandatory for all communications
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        PeerInfo peerInfo = new PeerInfo(peerIp, peerPort);
        try (SSLSocket sslSocket = peerConnector.createSecureSocket(peerInfo)) {
            String request = "CHAT_MESSAGE|" + senderId + "|" + message + "\n";
            sslSocket.getOutputStream().write(request.getBytes());

            BufferedReader reader = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));
            String response = reader.readLine();

            if (response != null && response.startsWith("CHAT_RECEIVED")) {
                Log.logInfo("SSL Chat message sent successfully to " + peerIp + ":" + peerPort);
                return true;
            } else {
                Log.logInfo("SSL Failed to send chat message to " + peerIp + ":" + peerPort);
                return false;
            }
        } catch (Exception e) {
            Log.logError("SSL Error sending chat message to " + peerIp + ":" + peerPort + ": " + e.getMessage(), e);
            return false;
        }
    }

    public int refreshSharedFileNames(Map<String, FileInfo> publicSharedFiles, Map<FileInfo, Set<PeerInfo>> privateSharedFiles, Set<FileInfo> sharedFileNames) {
        // SSL is now mandatory for all communications
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        try (SSLSocket sslSocket = peerConnector.createSecureSocket(TRACKER_HOST)) {
            Log.logInfo("Established SSL connection to tracker for refreshing shared files");

            PrintWriter printWriter = new PrintWriter(sslSocket.getOutputStream(), true);
            String ip = this.SERVER_HOST.getIp();
            String request = "REFRESH|" + ip + "|" + this.SERVER_HOST.getPort() + "\n";
            printWriter.println(request);
            BufferedReader buffer = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));
            String response = buffer.readLine();
            if (response != null && response.startsWith("REFRESHED")) {
                String[] parts = response.split("\\|");
                if (parts.length != 3) {
                    Log.logInfo("Invalid response format from tracker: " + response);
                    return -1;
                } else {
                    int filesCount = Integer.parseInt(parts[1]);
                    String[] fileParts = parts[2].split(",");
                    if (fileParts.length != filesCount) {
                        Log.logInfo("File count mismatch: expected " + filesCount + ", got " + fileParts.length);
                        return -1;
                    } else {
                        sharedFileNames.clear();

                        for (String file : fileParts) {
                            String[] f = file.split("'");
                            if (f.length != 5) {
                                Log.logInfo("Invalid file info format: " + file);
                            } else {
                                String fileName = f[0];
                                long fileSize = Long.parseLong(f[1]);
                                String fileHash = f[2];
                                PeerInfo peerInfo = new PeerInfo(f[3], Integer.parseInt(f[4]));
                                // Check if this file is shared by us
                                boolean isSharedByMe = publicSharedFiles.containsKey(fileName) ||
                                        privateSharedFiles.keySet().stream().anyMatch(fi -> fi.getFileName().equals(fileName));
                                sharedFileNames.add(new FileInfo(fileName, fileSize, fileHash, peerInfo, isSharedByMe));
                            }
                        }

                        Log.logInfo("Refreshed shared file names from tracker: " + sharedFileNames.size() + " files found.");
                        return 1;
                    }
                }
            } else {
                Log.logInfo("Invalid response from tracker: " + response);
                return -1;
            }
        } catch (Exception e) {
            Log.logError("Error refreshing shared file names from tracker: " + e.getMessage(), e);
            return 0;
        }
    }

    // Helper class for peer-to-peer communication
    private static class PeerConnector {
        public SSLSocket createSecureSocket(PeerInfo peerInfo) throws Exception {
            SSLSocket sslSocket = SSLUtils.createSSLSocketFactory().createSocket(peerInfo.getIp(), peerInfo.getPort());
            sslSocket.setUseClientMode(true);
            sslSocket.setNeedClientAuth(true);
            sslSocket.setSoTimeout(Infor.SOCKET_TIMEOUT_MS);
            sslSocket.startHandshake();
            return sslSocket;
        }
    }
}
