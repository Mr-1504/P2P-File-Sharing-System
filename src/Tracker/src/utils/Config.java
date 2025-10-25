package utils;

/**
 * This interface defines constants for server and tracker configurations.
 * It includes the server port, tracker port, server IP address, and tracker IP address.
 */
public interface Config {
    String CA_KEYSTORE = EnvUtils.getEnv("CA_KEYSTORE", "tracker-ca-keystore.jks");
    String CA_ALIAS = EnvUtils.getEnv("CA_ALIAS", "tracker-ca");
    String ROOT_CA_ALIAS = EnvUtils.getEnv("ROOT_CA_ALIAS", "ca");
    String KEYSTORE = EnvUtils.getEnv("KEYSTORE", "tracker-keystore.jks");
    String TRUSTSTORE = EnvUtils.getEnv("TRUSTSTORE", "tracker-truststore.jks");
    String KEYSTORE_PASSWORD = EnvUtils.getEnv("KEYSTORE_PASSWORD", "p2ppassword");
    int TRACKER_ENROLLMENT_PORT = EnvUtils.getEnvInt("TRACKER_ENROLLMENT_PORT", 9091);
    int SSL_TRACKER_PORT = EnvUtils.getEnvInt("SSL_TRACKER_PORT", 6001);
    int PEER_PORT = EnvUtils.getEnvInt("PEER_PORT", 5000);
    int SOCKET_TIMEOUT_MS = EnvUtils.getEnvInt("SOCKET_TIMEOUT_MS", 5000);
    String BROADCAST_IP = NetworkUtils.getBroadcastIp();
    String LIST_SEPARATOR = ",";
}
