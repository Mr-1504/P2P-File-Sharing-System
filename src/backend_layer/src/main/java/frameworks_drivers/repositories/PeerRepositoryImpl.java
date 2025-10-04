package main.java.frameworks_drivers.repositories;

import main.java.domain.repositories.PeerRepository;
import main.java.model.IPeerModel;

import java.io.IOException;

public class PeerRepositoryImpl implements PeerRepository {
    private final IPeerModel peerModel;

    public PeerRepositoryImpl(IPeerModel peerModel) {
        this.peerModel = peerModel;
    }

    @Override
    public void initializeServerSocket() throws IOException {
        peerModel.initializeServerSocket();
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
}
