package main.java.infras.subrepo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import main.java.domain.adapter.PeerInfoAdapter;
import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.domain.repository.IPeerDiscoveryRepository;
import main.java.domain.repository.IPeerRepository;
import main.java.utils.Config;
import main.java.utils.Log;
import main.java.infras.utils.SSLUtils;
import main.java.utils.RequestInfor;

import javax.net.ssl.SSLSocket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PeerDiscoveryRepository implements IPeerDiscoveryRepository {

    private final IPeerRepository peerModel;

    public PeerDiscoveryRepository(IPeerRepository peerModel) {
        this.peerModel = peerModel;
    }

    @Override
    public Set<PeerInfo> queryOnlinePeerList() {
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        try (SSLSocket sslSocket = SSLUtils.createSecureSocket(new PeerInfo(Config.TRACKER_IP, Config.TRACKER_PORT))) {
            Log.logInfo("Established SSL connection to tracker for getting known peers");

            String request = "GET_KNOWN_PEERS\n";
            sslSocket.getOutputStream().write(request.getBytes());
            Log.logInfo("SSL Requesting known peers from tracker, message: " + request);
            BufferedReader buff = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));
            String response = buff.readLine();
            if (response == null || response.isEmpty()) {
                Log.logInfo("No SSL response from tracker for known peers");
                return Collections.emptySet();
            } else {
                String[] parts = response.split("\\|");
                if (parts.length != 3 || !parts[0].equals("GET_KNOWN_PEERS")) {
                    Log.logInfo("Invalid SSL response format from tracker: " + response);
                    return Collections.emptySet();
                } else {
                    int peerCount = Integer.parseInt(parts[1]);
                    if (peerCount == 0) {
                        Log.logInfo("No known peers found via SSL");
                        return Collections.emptySet();
                    } else {
                        Type setType = new TypeToken<Set<PeerInfo>>() {
                        }.getType();
                        Gson gson = new GsonBuilder().registerTypeAdapter(PeerInfo.class, new PeerInfoAdapter()).create();

                        Set<PeerInfo> peerInfos = gson.fromJson(parts[2], setType);
                        if (peerInfos.isEmpty()) {
                            Log.logInfo("No valid known peers found via SSL");
                            return Collections.emptySet();
                        } else if (peerCount != peerInfos.size()) {
                            Log.logInfo("Known peer count mismatch via SSL: expected " + peerCount + ", found " + peerInfos.size());
                            return Collections.emptySet();
                        } else {
                            Log.logInfo("Received known peers from tracker via SSL: " + response);
                            return peerInfos;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.logError("SSL Error getting known peers from tracker", e);
            return Collections.emptySet();
        }
    }

    @Override
    public List<PeerInfo> getPeersWithFile(String fileHash) {
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        try (SSLSocket sslSocket = SSLUtils.createSecureSocket(new PeerInfo(Config.TRACKER_IP, Config.TRACKER_PORT))) {
            Log.logInfo("Established SSL connection to tracker for getting peers with file hash");

            String request = RequestInfor.GET_PEERS + fileHash + "|" + Config.SERVER_IP + "|" + Config.PEER_PORT + "\n";
            sslSocket.getOutputStream().write(request.getBytes());
            Log.logInfo("SSL Requesting peers with file hash: " + fileHash + ", message: " + request);
            BufferedReader buff = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));
            String response = buff.readLine();
            if (response == null || response.isEmpty()) {
                Log.logInfo("No SSL response from tracker for file hash: " + fileHash);
                return Collections.emptyList();
            } else {
                String[] parts = response.split("\\|");
                if (parts.length != 3 || !parts[0].equals(RequestInfor.GET_PEERS)) {
                    Log.logInfo("Invalid SSL response format from tracker: " + response);
                    return Collections.emptyList();
                } else {
                    int peerCount = Integer.parseInt(parts[1]);
                    if (peerCount == 0) {
                        Log.logInfo("No peers found via SSL for file hash: " + fileHash);
                        return Collections.emptyList();
                    } else {
                        Type setType = new TypeToken<Set<PeerInfo>>() {
                        }.getType();
                        Gson gson = new GsonBuilder().registerTypeAdapter(PeerInfo.class, new PeerInfoAdapter()).create();
                        Set<PeerInfo> peers = gson.fromJson(parts[2], setType);

                        if (peers.isEmpty()) {
                            Log.logInfo("No valid peers found via SSL for file hash: " + fileHash);
                            return Collections.emptyList();
                        } else if (peerCount != peers.size()) {
                            Log.logInfo("Peer count mismatch via SSL: expected " + peerCount + ", found " + peers.size());
                            return Collections.emptyList();
                        } else {
                            Log.logInfo("Received SSL response from tracker: " + response);
                            return peers.stream().toList();
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.logError("SSL Error getting peers with file hash: " + fileHash, e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<PeerInfo> getSelectivePeers(String fileHash) {
        List<PeerInfo> peers = new ArrayList<>();
        for (Map.Entry<FileInfo, Set<PeerInfo>> entry : peerModel.getPrivateSharedFiles().entrySet()) {
            FileInfo fileInfo = entry.getKey();
            if (fileInfo.getFileHash().equals(fileHash)) {
                peers.addAll(entry.getValue());
                break;
            }
        }
        return peers;
    }
}
