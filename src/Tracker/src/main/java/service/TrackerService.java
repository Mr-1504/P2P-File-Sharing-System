package service;

import dto.Peer;

import java.util.UUID;

public interface TrackerService {
    Peer onCsrSigned(Peer peer) throws Exception;
    Peer findPeerById(UUID id);
}
