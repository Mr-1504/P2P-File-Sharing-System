package main.domain.repository;

public interface INetworkRepository {
    void initializeServerSocket(String username) throws Exception;
    void startServer();
    void startUDPServer();
    int registerWithTracker();
    void processRequest(String request, String clientIP, io.netty.channel.Channel channel);
}
