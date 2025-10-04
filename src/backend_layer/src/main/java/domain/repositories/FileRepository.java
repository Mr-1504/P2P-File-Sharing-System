package main.java.domain.repositories;

import main.java.domain.entities.FileInfo;
import main.java.domain.entities.PeerInfo;
import main.java.domain.entities.ProgressInfo;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface FileRepository {
    void shareFileAsync(File file, String fileName, String progressId, int isReplace, FileInfo oldFileInfo);
    void shareFileList();
    void downloadFile(FileInfo fileInfo, File saveFile, List<PeerInfo> peers, String progressId);
    void resumeDownload(String progressId);
    List<PeerInfo> getPeersWithFile(String fileHash);
    boolean shareFileToPeers(File file, FileInfo oldFileInfo, int isReplace, String progressId, List<String> peerList);
    List<String> getSelectivePeers(String fileHash);
    List<String> getKnownPeers();
    void loadSharedFiles();
    int refreshSharedFileNames();
    Set<FileInfo> getSharedFileNames();
    void setSharedFileNames(Set<FileInfo> sharedFileNames);
    Map<String, FileInfo> getPuclicSharedFiles();
    Map<String, FileInfo> getPrivateSharedFiles();
    int stopSharingFile(String fileName);
    Map<String, ProgressInfo> getProgress();
    void setProgress(ProgressInfo progressInfo);
    void cleanupProgress(List<String> progressIds);
}
