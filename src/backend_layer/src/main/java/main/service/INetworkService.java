package main.service;

import main.domain.entity.PeerInfo;

import java.util.Set;

public interface INetworkService {
    void initializeServerSocket() throws Exception;
    void startServer();
    void startUDPServer();
    int registerWithTracker();
    Set<PeerInfo> queryOnlinePeerList();
}
