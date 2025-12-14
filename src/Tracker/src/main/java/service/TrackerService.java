package service;

import dto.OfflineMessage;
import dto.Peer;
import model.PeerInfo;

import java.util.List;
import java.util.UUID;

public interface TrackerService {
    Peer onCsrSigned(Peer peer) throws Exception;
    Peer findPeerById(UUID id);
    Peer findPeerByIpAndPort(String ip, int port);
    Peer findPeerByPublicKey(String publicKey);
    OfflineMessage saveOfflineMessage(OfflineMessage message);
    String getOfflineMessagesJsonForPeer(UUID receiverId);
    void acknowledgeOfflineMessages(String messageIdsCsv) throws Exception;
    List<Peer> getAllPeers();
}
