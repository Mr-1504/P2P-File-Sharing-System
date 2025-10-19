package main.java.service;

import main.java.domain.entity.PeerInfo;

import java.util.List;
import java.util.Set;

public interface INetworkService {
    void initializeServerSocket() throws Exception;
    void startServer();
    void startUDPServer();
    int registerWithTracker();
    Set<PeerInfo> queryOnlinePeerList();
}
