package service;

import domain.entity.Message;
import domain.entity.Peer;
import domain.entity.PeerInfo;

import java.util.List;
import java.util.Set;

public interface INetworkService {
    void initializeServerSocket() throws Exception;
    void startServer();
    void startUDPServer();
    int registerWithTracker();
    Set<PeerInfo> queryOnlinePeerList();
    Set<Peer> queryAllPeers();
    Set<PeerInfo> queryAllPeerInfo();
    String requestPublicKey(String ip, int port);
    boolean sendMessageToTracker(String receiverId, String senderId, String groupId, String encryptedPayload);
    List<Message> getOfflineMessagesFromTracker(String receiverId);
    boolean acknowledgeOfflineMessages(String messageIds);
}
