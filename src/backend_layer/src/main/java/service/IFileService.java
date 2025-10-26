package service;

import domain.entity.FileInfo;
import domain.entity.PeerInfo;
import domain.entity.ProgressInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface IFileService {
    List<PeerInfo> getSharedPeers(String fileName);

    String downloadFile(FileInfo fileInfo, String savePath);

    String sharePublicFile(String filePath, int isReplace, String fileName, String progressId);

    int refreshFiles();

    Set<FileInfo> getFiles();

    Map<String, FileInfo> getPublicSharedFiles();

    Map<FileInfo, Set<PeerInfo>> getPrivateSharedFiles();

    String sharePrivateFile(String filePath, int isReplace, String fileName, List<PeerInfo> peersList, String progressId);

    void shareFileList();

    int stopSharingFile(String fileName);

    Map<String, ProgressInfo> getProgress();

    void setProgress(ProgressInfo progressInfo);

    void cleanupProgress(List<String> progressIds);

    boolean editPermission(FileInfo targetFile, String permission, List<PeerInfo> peersList);
}
