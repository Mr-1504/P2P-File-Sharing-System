package main.java.service;

import main.java.domain.repository.IPeerRepository;
import main.java.utils.AppPaths;

import java.util.List;

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
    public List<String> queryOnlinePeerList() {
        return peerModel.queryOnlinePeerList();
    }
}
