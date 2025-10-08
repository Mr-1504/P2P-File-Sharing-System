package main.java.service;

import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.domain.entity.ProgressInfo;
import main.java.domain.repository.FileRepository;

import java.io.File;
import java.util.List;

public class DownloadFileUseCase {
    private final FileRepository fileRepository;

    public DownloadFileUseCase(FileRepository fileRepository) {
        this.fileRepository = fileRepository;
    }

    public String execute(FileInfo fileInfo, String savePath) {
        try {
            File saveFile = new File(savePath);

            if (!saveFile.getParentFile().exists()) {
                return "Save directory does not exist";
            }
            if (!saveFile.getParentFile().canWrite()) {
                return "No write permission for save directory";
            }

            List<PeerInfo> peers = fileRepository.getPeersWithFile(fileInfo.getFileHash());
            if (peers == null || peers.isEmpty()) {
                return "No peers found with this file";
            }

            String progressId = ProgressInfo.generateProgressId();
            ProgressInfo progressInfo = new ProgressInfo(progressId, ProgressInfo.ProgressStatus.STARTING, fileInfo.getFileName());
            fileRepository.setProgress(progressInfo);

            fileRepository.downloadFile(fileInfo, saveFile, peers, progressId);
            return progressId;
        } catch (Exception e) {
            return "Internal server error";
        }
    }
}
