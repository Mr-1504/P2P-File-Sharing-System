package main.java.domain.repository;

import java.io.IOException;

public interface PeerRepository {
    void initializeServerSocket() throws IOException;
    void startServer();
    void startUDPServer();
    int registerWithTracker();
}
