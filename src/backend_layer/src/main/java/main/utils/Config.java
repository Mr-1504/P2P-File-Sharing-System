package main.utils;

import main.infras.utils.NetworkUtils;

/**
 * This interface defines constants for server and tracker configurations.
 * It includes the server port, tracker port, server IP address, and tracker IP address.
 */
public interface Config {
    int PEER_PORT = EnvUtils.getEnvInt("PEER_PORT", 5000);
    int TRACKER_ENROLL_PORT = EnvUtils.getEnvInt("TRACKER_ENROLLMENT_PORT", 9091);
    int TRACKER_PORT = EnvUtils.getEnvInt("SSL_TRACKER_PORT", 6001);
    String SERVER_IP = NetworkUtils.getCurrentIp();
    String TRACKER_IP = NetworkUtils.getCurrentIp();
    int CHUNK_SIZE = EnvUtils.getEnvInt("CHUNK_SIZE", 1024 * 1024 * 2);
    int SOCKET_TIMEOUT_MS = EnvUtils.getEnvInt("SOCKET_TIMEOUT_MS", 5000);
    int MAX_RETRIES = EnvUtils.getEnvInt("MAX_RETRIES", 3);
    String USERNAME = EnvUtils.getEnvString("USERNAME");
}
