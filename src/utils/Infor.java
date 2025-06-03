package utils;

/**
 * This interface defines constants for server and tracker configurations.
 * It includes the server port, tracker port, server IP address, and tracker IP address.
 */
public interface Infor {
    int SERVER_PORT = 5000;
    int TRACKER_PORT = 5001;
    String SERVER_IP = GetLocalIP.getLocalIpAddress();
    String TRACKER_IP = "127.0.0.1";
}
