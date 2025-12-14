package service;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import utils.Config;
import utils.Log;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket client service for direct P2P messaging.
 * Establishes WebSocket connections to other peers for real-time messaging.
 */
public class WebSocketClientService {

    private static final int DIRECT_MESSAGE_TIMEOUT_SECONDS = 5;

    /**
     * Send message directly to peer via WebSocket connection.
     * @param peerIp Target peer IP address
     * @param peerPort Target peer WebSocket port (8999 by default)
     * @param encryptedMessage The encrypted message payload
     * @return true if sent successfully, false otherwise
     */
    public boolean sendMessageDirect(String peerIp, int peerPort, String encryptedMessage) {
        try {
            Log.logInfo("Attempting WebSocket connection to peer: " + peerIp + ":" + peerPort);

            String wsUrl = "ws://" + peerIp + ":" + 8999;
            URI uri = new URI(wsUrl);

            CountDownLatch connectionLatch = new CountDownLatch(1);
            CountDownLatch messageLatch = new CountDownLatch(1);

            WebSocketClient wsClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    Log.logInfo("WebSocket client connected to peer: " + peerIp + ":" + peerPort);
                    connectionLatch.countDown();
                }

                @Override
                public void onMessage(String message) {
                    Log.logInfo("WebSocket client received response from peer: " + message);
                    messageLatch.countDown();
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.logInfo("WebSocket client connection closed. Code: " + code + ", Reason: " + reason);
                    messageLatch.countDown();
                }

                @Override
                public void onError(Exception ex) {
                    Log.logError("WebSocket client error connecting to peer: " + peerIp + ":" + peerPort, ex);
                    connectionLatch.countDown();
                    messageLatch.countDown();
                }
            };

            // Connect to peer's WebSocket server
            wsClient.connect();

            // Wait for connection
            if (!connectionLatch.await(3, TimeUnit.SECONDS)) {
                Log.logInfo("WebSocket connection timeout to peer: " + peerIp + ":" + peerPort);
                wsClient.close();
                return false;
            }

            // Send encrypted message with own identification using Config format
            String messageToSend = utils.Config.SERVER_IP + ":" + utils.Config.PEER_PORT + "|" + encryptedMessage;
            wsClient.send(messageToSend);

            // Wait for response
            if (!messageLatch.await(2, TimeUnit.SECONDS)) {
                Log.logInfo("WebSocket message send timeout to peer: " + peerIp + ":" + peerPort);
                wsClient.close();
                return false;
            }

            wsClient.close();
            Log.logInfo("WebSocket direct message sent successfully to peer: " + peerIp + ":" + peerPort);
            return true;

        } catch (Exception e) {
            Log.logError("WebSocket direct messaging failed for peer: " + peerIp + ":" + peerPort, e);
            return false;
        }
    }

    /**
     * Check if peer is reachable via port connection (for pre-flight check)
     * @param ip peer IP address
     * @param port peer port
     * @return true if reachable
     */
    public static boolean isPeerReachable(String ip, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(ip, port), 2000); // 2 second timeout
            return true;
        } catch (IOException e) {
            Log.logInfo("Peer not reachable: " + ip + ":" + port);
            return false;
        }
    }
}
