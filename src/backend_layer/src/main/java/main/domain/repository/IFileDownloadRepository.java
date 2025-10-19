package main.domain.repository;

import main.domain.entity.FileInfo;
import main.domain.entity.PeerInfo;
import main.domain.entity.ProgressInfo;

import java.io.File;
import java.util.List;
import java.util.Map;

public interface IFileDownloadRepository {
    void downloadFile(FileInfo fileInfo, File saveFile, List<PeerInfo> peers, String progressId);
    Map<String, ProgressInfo> getProgress();
    void setProgress(ProgressInfo progressInfo);
    void cleanupProgress(List<String> progressIds);
}
