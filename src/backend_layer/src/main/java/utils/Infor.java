package main.java.utils;

/**
 * This interface defines constants for server and tracker configurations.
 * It includes the server port, tracker port, server IP address, and tracker IP address.
 */
public interface Infor {
    int PEER_PORT = EnvConf.getEnvInt("PEER_PORT", 5000);
    int TRACKER_PORT = EnvConf.getEnvInt("SSL_TRACKER_PORT", 6001);
    String SERVER_IP = NetworkUtils.getCurrentIp();
    String TRACKER_IP = NetworkUtils.getCurrentIp();
    int CHUNK_SIZE = EnvConf.getEnvInt("CHUNK_SIZE", 1024 * 1024 * 2);
    int SOCKET_TIMEOUT_MS = EnvConf.getEnvInt("SOCKET_TIMEOUT_MS", 5000);
    int MAX_RETRIES = EnvConf.getEnvInt("MAX_RETRIES", 3);
}
