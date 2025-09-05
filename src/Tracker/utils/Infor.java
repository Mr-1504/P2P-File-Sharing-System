package utils;

/**
 * This interface defines constants for server and tracker configurations.
 * It includes the server port, tracker port, server IP address, and tracker IP address.
 */
public interface Infor {
    int SERVER_PORT = 5000;
    int TRACKER_PORT = 5001;
    String SERVER_IP = GetLocalIP.getCurrentIp();
    String TRACKER_IP = "127.0.0.1";
    int CHUNK_SIZE = 1024 * 1024*2;
    int SOCKET_TIMEOUT_MS = 5000;
    int DOWNLOAD_TIMEOUT_MS = 10000;
    int MAX_RETRIES = 3;
    String FIELD_SEPARATOR_REGEX = "\\|";
    String FIELD_SEPARATOR = "|";
    String FILE_SEPARATOR = "'";
    String LIST_SEPARATOR = ",";
}
