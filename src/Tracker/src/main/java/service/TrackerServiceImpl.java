package service;

import dto.Peer;
import repository.PeerRepository;
import repository.PeerRepositoryImpl;

import java.time.LocalDateTime;

public class TrackerServiceImpl implements TrackerService{
    private final PeerRepository peerRepository = new PeerRepositoryImpl();
    @Override
    public Peer onCsrSigned(Peer peer) throws Exception {
        // Check if public key already exists for another peer
        Peer existingByKey = peerRepository.findByPublicKey(peer.getPublicKey());
        if (existingByKey != null) {
            if (!existingByKey.getIp().equals(peer.getIp()) || existingByKey.getPort() != peer.getPort()) {
                // Public key already in use by different peer
                throw new Exception("Public key already exists for another peer");
            }
            // Same peer already has this key, return it
            return existingByKey;
        }

        // Check if peer with same ip/port exists
        Peer existingByIpPort = peerRepository.findByIpAndPort(peer.getIp(), peer.getPort());
        LocalDateTime currentTime = LocalDateTime.now();
        if (existingByIpPort != null) {
            // Update existing peer's public key
            existingByIpPort.setPublicKey(peer.getPublicKey());
            existingByIpPort.setOnline(true);
            existingByIpPort.setLastSeen(currentTime);
            return peerRepository.save(existingByIpPort);
        } else {
            // New peer
            peer.setCreatedAt(currentTime);
            peer.setLastSeen(currentTime);
            peer.setOnline(true);
            return peerRepository.save(peer);
        }
    }

    @Override
    public Peer findPeerById(java.util.UUID id) {
        return peerRepository.findById(id);
    }
}
