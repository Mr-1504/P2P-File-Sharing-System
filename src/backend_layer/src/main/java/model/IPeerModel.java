package main.java.model;

import main.java.domain.entities.FileInfo;
import main.java.domain.entities.PeerInfo;
import main.java.domain.entities.ProgressInfo;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface IPeerModel {
    void initializeServerSocket() throws IOException;

    void startServer();

    void startUDPServer();

    int registerWithTracker();

    List<String> getKnownPeers();

    boolean sharePublicFile(File file, String fileName, String progressId, int isReplace, FileInfo oldFileInfo);

    boolean shareFileList(List<FileInfo> publicFiles, Map<FileInfo, Set<PeerInfo>> privateFiles);

    void downloadFile(FileInfo fileInfo, File saveFile, List<PeerInfo> peers, String progressId);

    void resumeDownload(String progressId);

    List<PeerInfo> getPeersWithFile(String fileHash);

    boolean shareFileToPeers(File file, FileInfo oldFileInfo, int isReplace, String progressId, List<String> peerList);

    List<PeerInfo> getSelectivePeers(String fileHash);

//    void loadSharedFiles();

    int refreshSharedFileNames();

    Set<FileInfo> getSharedFileNames();

    void setSharedFileNames(Set<FileInfo> sharedFileNames);

    Map<String, FileInfo> getPublicSharedFiles();

    Map<FileInfo, Set<PeerInfo>> getPrivateSharedFiles();

    int stopSharingFile(String fileName);

    Map<String, ProgressInfo> getProgress();

    void setProgress(ProgressInfo progressInfo);

    void cleanupProgress(List<String> progressIds);

    List<FileInfo> getPublicFiles();
}
