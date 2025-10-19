package main.java.domain.repository;

import java.nio.channels.SocketChannel;

public interface INetworkRepository {
    void initializeServerSocket(String username) throws Exception;
    void startServer();
    void startUDPServer();
    int registerWithTracker();
    void processSSLRequest(SocketChannel socketChannel, String request);
}
