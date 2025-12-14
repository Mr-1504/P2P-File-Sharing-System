package service;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import utils.Log;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket server for direct P2P messaging.
 * Handles real-time message exchange between peers.
 */
public class P2PWebSocketServer extends WebSocketServer {

    private ChatService chatService;
    private WebSocketClientService webSocketClientService;

    // Connection management: peerId -> WebSocket
    private Map<String, WebSocket> peerConnections = new ConcurrentHashMap<>();

    // WebSocket -> peer information (IP:port)
    private Map<WebSocket, String> connectionPeerInfo = new ConcurrentHashMap<>();

    public P2PWebSocketServer(int port, ChatService chatService) {
        super(new InetSocketAddress(port));
        this.chatService = chatService;
        this.webSocketClientService = new WebSocketClientService();
        Log.logInfo("P2P WebSocket Server initialized on port: " + port);
    }

    @Override
    public void onStart() {
        Log.logInfo("WebSocket Server started successfully");
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String peerAddress = conn.getRemoteSocketAddress().toString();
        Log.logInfo("New WebSocket connection from: " + peerAddress);

        // Store connection info - peer identifier will be determined by first message or handshake
        connectionPeerInfo.put(conn, peerAddress);

        // Send welcome message
        conn.send("Welcome to P2P Chat Server");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        Log.logInfo("Received WebSocket message: " + message + " from " + conn.getRemoteSocketAddress());

        try {
            // Parse message format: "peerId|message" or just "message"
            // For now, assume direct encrypted payload
            String[] parts = message.split("\\|", 2);
            if (parts.length == 2) {
                // Message with peer identification
                String senderPeerId = parts[0];
                String encryptedPayload = parts[1];

                // Update connection mapping
                peerConnections.put(senderPeerId, conn);
                connectionPeerInfo.put(conn, senderPeerId);

                // Process encrypted message
                chatService.enqueueMessageForProcessing(encryptedPayload);
            } else {
                // Direct encrypted payload
                chatService.enqueueMessageForProcessing(message);
            }

            Log.logInfo("WebSocket message processed successfully");

        } catch (Exception e) {
            Log.logError("Error processing WebSocket message", e);
            conn.send("ERROR: Failed to process message");
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String peerAddress = conn.getRemoteSocketAddress().toString();
        Log.logInfo("WebSocket connection closed: " + peerAddress + " Code: " + code + " Reason: " + reason);

        // Clean up connection mappings
        if (connectionPeerInfo.containsKey(conn)) {
            String peerId = connectionPeerInfo.get(conn);
            peerConnections.remove(peerId);
            connectionPeerInfo.remove(conn);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        String peerAddress = conn != null ? conn.getRemoteSocketAddress().toString() : "unknown";
        Log.logError("WebSocket error from " + peerAddress, ex);
    }

    /**
     * Send message to specific peer by their ID (e.g., "192.168.1.100:8080")
     */
    public boolean sendToPeer(String peerId, String message) {
        try {
            WebSocket targetConn = peerConnections.get(peerId);
            if (targetConn != null && targetConn.isOpen()) {
                targetConn.send(message);
                Log.logInfo("Sent WebSocket message to peer: " + peerId);
                return true;
            } else {
                Log.logInfo("Peer not connected via WebSocket: " + peerId);
                return false;
            }
        } catch (Exception e) {
            Log.logError("Failed to send WebSocket message to peer: " + peerId, e);
            return false;
        }
    }

    /**
     * Send message to peer by IP and port
     */
    public boolean sendToPeer(String peerIp, int peerPort, String message) {
        String peerId = peerIp + ":" + peerPort;
        return sendToPeer(peerId, message);
    }

    /**
     * Get number of active connections
     */
    public int getConnectionCount() {
        return peerConnections.size();
    }

    /**
     * Check if peer is connected
     */
    public boolean isPeerConnected(String peerId) {
        WebSocket conn = peerConnections.get(peerId);
        return conn != null && conn.isOpen();
    }

    /**
     * Stop WebSocket server
     */
    @Override
    public void stop() throws InterruptedException {
        Log.logInfo("Stopping WebSocket server...");
        super.stop();
        Log.logInfo("WebSocket server stopped");
    }
}
