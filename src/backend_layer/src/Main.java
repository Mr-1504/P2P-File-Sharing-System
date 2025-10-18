import main.java.delivery.api.P2PApi;
import main.java.delivery.controller.P2PController;
import main.java.infras.repository.PeerRepository;
import main.java.service.FileService;
import main.java.service.NetworkService;

import java.io.IOException;

import static main.java.utils.Log.logError;

public class Main {
    public static void main(String[] args) throws IOException {
        System.setProperty("file.encoding", "UTF-8");

        // Initialize the existing PeerModel (infrastructure layer)

        try (PeerRepository peerModel = new PeerRepository()) {
            P2PApi api = new P2PApi();

            NetworkService networkService = new NetworkService(peerModel);
            FileService fileService = new FileService(peerModel);

            // Create controller (interface adapters layer)
            P2PController controller = new P2PController(fileService, networkService, api);

            // Start the application
            controller.start();
        } catch (Exception e) {
            logError("Error when initialize peer model", e);
            System.exit(1);
        }

        // Initialize API for frontend communication

    }
}
