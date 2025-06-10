package utils;

/**
 * This interface defines constants for server and tracker configurations.
 * It includes the server port, tracker port, server IP address, and tracker IP address.
 */
public interface Infor {
    int SERVER_PORT = EnvConf.getEnvInt("SERVER_PORT", 5000);
    int TRACKER_PORT = EnvConf.getEnvInt("TRACKER_PORT", 5001);
    String SERVER_IP = GetLocalIP.getLocalIpAddress();
    String TRACKER_IP = EnvConf.getEnv("TRACKER_IP", "127.0.0.1");
    int CHUNK_SIZE = EnvConf.getEnvInt("CHUNK_SIZE", 1024 * 64);
    int SOCKET_TIMEOUT_MS = EnvConf.getEnvInt("SOCKET_TIMEOUT_MS", 5000);
    int DOWNLOAD_TIMEOUT_MS = 10000;
    int MAX_RETRIES = EnvConf.getEnvInt("MAX_RETRIES", 3);
    String FIELD_SEPARATOR = "\\|";
    String FILE_SEPARATOR = "'";
    String LIST_SEPARATOR = ",";
}
