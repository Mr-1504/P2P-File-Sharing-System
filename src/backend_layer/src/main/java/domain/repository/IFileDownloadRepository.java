package domain.repository;

import domain.entity.FileInfo;
import domain.entity.PeerInfo;
import domain.entity.ProgressInfo;

import java.io.File;
import java.util.List;
import java.util.Map;

public interface IFileDownloadRepository {
    void downloadFile(FileInfo fileInfo, File saveFile, List<PeerInfo> peers, String progressId);
    Map<String, ProgressInfo> getProgress();
    void setProgress(ProgressInfo progressInfo);
    void cleanupProgress(List<String> progressIds);
}
