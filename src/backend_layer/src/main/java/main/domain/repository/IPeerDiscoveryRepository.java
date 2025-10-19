package main.domain.repository;

import main.domain.entity.PeerInfo;

import java.util.List;
import java.util.Set;

public interface IPeerDiscoveryRepository {
    Set<PeerInfo> queryOnlinePeerList();

    List<PeerInfo> getPeersWithFile(String fileHash);

    List<PeerInfo> getSelectivePeers(String fileHash);
}
