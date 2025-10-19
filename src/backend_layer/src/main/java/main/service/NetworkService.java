package main.service;

import main.domain.entity.PeerInfo;
import main.domain.repository.IPeerRepository;
import main.utils.AppPaths;

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
}
