package main.java.data.repository;

import main.java.domain.repository.PeerRepository;
import main.java.model.IPeerModel;

import java.io.IOException;

public class PeerRepositoryImpl implements PeerRepository {
    private final IPeerModel peerModel;

    public PeerRepositoryImpl(IPeerModel peerModel) {
        this.peerModel = peerModel;
    }

    @Override
    public void initializeServerSocket() throws Exception {
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
