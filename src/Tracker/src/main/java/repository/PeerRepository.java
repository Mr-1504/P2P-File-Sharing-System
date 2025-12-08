package repository;

import dto.Peer;

import java.util.UUID;

public interface PeerRepository {
    Peer save(Peer peer);
    Peer findById(UUID id);
    Peer findByIpAndPort(String ip, int port);
    Peer findByPublicKey(String publicKey);
}
