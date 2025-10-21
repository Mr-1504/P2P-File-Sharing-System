package src.model;

import src.utils.LogTag;
import src.utils.RequestInfor;

import static src.utils.Log.logInfo;

public class RegisterHandler implements RequestHandler {
    private final TrackerModel tracker;

    public RegisterHandler(TrackerModel tracker) {
        this.tracker = tracker;
    }

    @Override
    public String handle(String[] parts) {
        if (parts.length < 6) {
            logInfo("[TRACKER]: Invalid REGISTER request format: expected at least 6 parts, got " + parts.length + " on " + tracker.getCurrentTime());
            return LogTag.S_INVALID;
        }

        String peerIp = parts[1];
        String peerPort;
        try {
            peerPort = parts[2];
            Integer.parseInt(peerPort);
        } catch (NumberFormatException e) {
            logInfo("[TRACKER]: Invalid Port in REGISTER: " + parts[2] + " on " + tracker.getCurrentTime());
            return LogTag.S_INVALID;
        }
        PeerInfo peerInfor = new PeerInfo(peerIp, Integer.parseInt(peerPort));
        tracker.addKnownPeer(peerInfor);
        logInfo("[TRACKER]: Peer registered: " + peerInfor + " on " + tracker.getCurrentTime());

        // Deserialize the data structures
        try {
            String publicFileToPeersJson = parts[3];
            String privateSharedFileJson = parts[4];
            String selectiveSharedFilesJson = parts[5];

            logInfo("[TRACKER]: Received publicFileToPeers: " + publicFileToPeersJson + " on " + tracker.getCurrentTime());
            logInfo("[TRACKER]: Received privateSharedFile: " + privateSharedFileJson + " on " + tracker.getCurrentTime());
            logInfo("[TRACKER]: Received selectiveSharedFiles: " + selectiveSharedFilesJson + " on " + tracker.getCurrentTime());

            // TODO: Deserialize and store the data structures
        } catch (Exception e) {
            logInfo("[TRACKER]: Error processing REGISTER data structures: " + e.getMessage() + " on " + tracker.getCurrentTime());
        }

        String shareListResponse = tracker.sendShareList(peerIp, Integer.parseInt(peerPort), false);
        if (!shareListResponse.startsWith(RequestInfor.SHARED_LIST)) {
            return RequestInfor.REGISTERED;
        }
        return shareListResponse;
    }
}
