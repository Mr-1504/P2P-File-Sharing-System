import delivery.api.P2PApi;
import delivery.controller.P2PController;
import infras.repository.PeerRepository;
import service.FileService;
import service.NetworkService;
import service.P2PWebSocketServer;
import utils.HibernateUtil;

import java.io.IOException;

import static utils.Log.logError;

public class Main {
    public static void main(String[] args) throws IOException {
        System.setProperty("file.encoding", "UTF-8");

        // Initialize the existing PeerModel (infrastructure layer)

        try (PeerRepository peerModel = new PeerRepository()) {
            // Initialize Hibernate and run Flyway migrations
            HibernateUtil.getSessionFactory();

            P2PApi api = new P2PApi();

            NetworkService networkService = new NetworkService(peerModel);
            FileService fileService = new FileService(peerModel);
            service.ChatService chatService = new service.ChatService(networkService);

            // Set up direct message handling in API
            api.setRouteForDirectMessage(chatService::enqueueMessageForProcessing);

            // Create and start WebSocket server for P2P messaging
            int wsPort = 8999; // WebSocket port
            P2PWebSocketServer webSocketServer = new P2PWebSocketServer(wsPort, chatService);
            webSocketServer.start();

            // Create controller (interface adapters layer)
            P2PController controller = new P2PController(fileService, networkService, chatService, api);

            // Start the application
            controller.start();
        } catch (Exception e) {
            logError("Error when initialize peer model", e);
            System.exit(1);
        }

        // Initialize API for frontend communication

    }
}
