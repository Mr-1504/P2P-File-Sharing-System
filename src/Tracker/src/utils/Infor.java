package src.utils;

/**
 * This interface defines constants for server and tracker configurations.
 * It includes the server port, tracker port, server IP address, and tracker IP address.
 */
public interface Infor {
    int TRACKER_PORT = 5001;
    int PEER_PORT = 5000;
    int SOCKET_TIMEOUT_MS = 5000;
    String BROADCAST_IP = NetworkUtils.getBroadcastIp();
    String LIST_SEPARATOR = ",";
}
