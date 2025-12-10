package domain.repository;

public interface INetworkRepository {
    void initializeServerSocket(String username) throws Exception;
    void startServer();
    void startUDPServer();
    int registerWithTracker();
    void processRequest(String request, String clientIP, io.netty.channel.Channel channel);

    // Chat-related tracker communication
    String requestPublicKey(String ip, int port);
    boolean sendMessageToTracker(String senderId, String receiverId, String groupId, String payloadBase64);
    String getOfflineMessages(String receiverId);
    boolean acknowledgeOfflineMessages(String messageIds);
}
