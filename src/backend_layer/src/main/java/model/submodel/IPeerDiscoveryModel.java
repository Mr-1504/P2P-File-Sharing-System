package main.java.model.submodel;

import main.java.domain.entity.PeerInfo;

import java.util.List;

public interface IPeerDiscoveryModel {
    List<String> getKnownPeers();
    List<PeerInfo> getPeersWithFile(String fileHash);
    List<PeerInfo> getSelectivePeers(String fileHash);
}
