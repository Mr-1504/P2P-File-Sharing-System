package main.java.service;

import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.domain.entity.ProgressInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface IFileService {
    String downloadFile(FileInfo fileInfo, String savePath);

    String sharePublicFile(String filePath, int isReplace, String fileName, String progressId);

    int refreshFiles();

    Set<FileInfo> getFiles();

    String sharePrivateFile(String filePath, int isReplace, String fileName, List<PeerInfo> peersList, String progressId);

    void shareFileList();

    int stopSharingFile(String fileName);

    Map<String, ProgressInfo> getProgress();

    void setProgress(ProgressInfo progressInfo);

    void cleanupProgress(List<String> progressIds);
}
