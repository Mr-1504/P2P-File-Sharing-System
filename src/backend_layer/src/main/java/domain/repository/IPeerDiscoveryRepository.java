package main.java.domain.repository;

import main.java.domain.entity.PeerInfo;

import java.util.List;

public interface IPeerDiscoveryRepository {
    List<String> queryOnlinePeerList();
    List<PeerInfo> getPeersWithFile(String fileHash);
    List<PeerInfo> getSelectivePeers(String fileHash);
}
