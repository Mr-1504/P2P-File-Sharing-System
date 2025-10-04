package main.java.domain.repositories;

import java.io.IOException;

public interface PeerRepository {
    void initializeServerSocket() throws IOException;
    void startServer();
    void startUDPServer();
    int registerWithTracker();
}
