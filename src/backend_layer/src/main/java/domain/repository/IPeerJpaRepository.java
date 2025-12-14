package domain.repository;

import domain.entity.Peer;

import java.util.List;
import java.util.UUID;

/**
 * Repository interface for Peer JPA operations.
 */
public interface IPeerJpaRepository {

    Peer savePeer(Peer peer);
    Peer findPeerById(UUID id);
    Peer findPeerByIpAndPort(String ip, int port);
    Peer findByPublicKey(String publicKey);
    Peer findByTrackerPeerId(String trackerPeerId);
    List<Peer> findAllPeers();
    void updatePeer(Peer peer);
    void deletePeer(UUID id);
}
