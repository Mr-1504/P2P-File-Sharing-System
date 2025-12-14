package service;

import domain.entity.Peer;
import domain.entity.PeerInfo;

import java.util.Set;

public interface INetworkService {
    void initializeServerSocket() throws Exception;
    void startServer();
    void startUDPServer();
    int registerWithTracker();
    Set<PeerInfo> queryOnlinePeerList();
    Set<Peer> queryAllPeers();
    Set<PeerInfo> queryAllPeerInfo();
    String requestPublicKey(String ip, int port);
}
