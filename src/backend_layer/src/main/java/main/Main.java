package main.java.main;

import main.java.api.P2PApi;
import main.java.frameworks_drivers.repositories.FileRepositoryImpl;
import main.java.frameworks_drivers.repositories.PeerRepositoryImpl;
import main.java.interface_adapters.controllers.P2PController;
import main.java.model.PeerModel;

import java.io.IOException;

import static main.java.utils.Log.logError;

public class Main {
    public static void main(String[] args) throws IOException {
        System.setProperty("file.encoding", "UTF-8");

        // Initialize the existing PeerModel (infrastructure layer)
        PeerModel peerModel = null;
        try {
            peerModel = new PeerModel();
        } catch (IOException e) {
            logError("Error when initialize peer model", e);
            System.exit(1);
        }

        // Initialize API for frontend communication
        P2PApi api = new P2PApi();

        // Create repository implementations (frameworks & drivers layer)
        FileRepositoryImpl fileRepository = new FileRepositoryImpl(peerModel);
        PeerRepositoryImpl peerRepository = new PeerRepositoryImpl(peerModel);

        // Create controller (interface adapters layer)
        P2PController controller = new P2PController(peerRepository, fileRepository, api);

        // Start the application
        controller.start();
    }
}
