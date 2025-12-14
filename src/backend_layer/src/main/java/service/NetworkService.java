package service;

import domain.entity.Peer;
import domain.entity.PeerInfo;
import domain.repository.IPeerRepository;
import utils.AppPaths;

import java.util.Set;

public class NetworkService implements INetworkService {
    private final IPeerRepository peerModel;

    public NetworkService(IPeerRepository peerModel) {
        this.peerModel = peerModel;
    }

    @Override
    public void initializeServerSocket() throws Exception {
        String username = AppPaths.loadUsername();
        peerModel.initializeServerSocket(username);
    }

    @Override
    public void startServer() {
        peerModel.startServer();
    }

    @Override
    public Set<PeerInfo> queryAllPeerInfo() {
        return peerModel.queryAllPeerInfo();
    }

    @Override
    public void startUDPServer() {
        peerModel.startUDPServer();
    }

    @Override
    public int registerWithTracker() {
        return peerModel.registerWithTracker();
    }

    @Override
    public Set<PeerInfo> queryOnlinePeerList() {
        return peerModel.queryOnlinePeerList();
    }

    @Override
    public Set<Peer> queryAllPeers() {
        return peerModel.queryAllPeers();
    }

    @Override
    public String requestPublicKey(String ip, int port) {
        return peerModel.requestPublicKey(ip, port);
    }
}
