package main.java.domain.repository;

import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.domain.entity.ProgressInfo;

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

//    void loadSharedFiles();

    int refreshSharedFileNames();

    List<String> getKnownPeers();

    Set<FileInfo> getSharedFileNames();

    Map<String, FileInfo> getPublicSharedFiles();

    Map<FileInfo, Set<PeerInfo>> getPrivateSharedFiles();

    int stopSharingFile(String fileName);

    Map<String, ProgressInfo> getProgress();

    void setProgress(ProgressInfo progressInfo);

    void cleanupProgress(List<String> progressIds);
}
