package main.java.domain.repository;

import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.domain.entity.ProgressInfo;

import java.io.File;
import java.util.List;
import java.util.Map;

public interface IFileDownloadRepository {
    void downloadFile(FileInfo fileInfo, File saveFile, List<PeerInfo> peers, String progressId);
    void resumeDownload(String progressId);
    Map<String, ProgressInfo> getProgress();
    void setProgress(ProgressInfo progressInfo);
    void cleanupProgress(List<String> progressIds);
}
