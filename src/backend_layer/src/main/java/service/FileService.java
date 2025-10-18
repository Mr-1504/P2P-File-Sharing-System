package main.java.service;

import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.domain.entity.ProgressInfo;
import main.java.infras.PeerRepository;
import main.java.utils.AppPaths;
import main.java.utils.LogTag;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FileService implements IFileService {
    private final PeerRepository peerModel;

    public FileService(PeerRepository peerModel){
        this.peerModel = peerModel;
    }


    @Override
    public String downloadFile(FileInfo fileInfo, String savePath) {
        try {
            File saveFile = new File(savePath);

            if (!saveFile.getParentFile().exists()) {
                return "Save directory does not exist";
            }
            if (!saveFile.getParentFile().canWrite()) {
                return "No write permission for save directory";
            }

            List<PeerInfo> peers = peerModel.getPeersWithFile(fileInfo.getFileHash());
            if (peers == null || peers.isEmpty()) {
                return "No peers found with this file";
            }

            String progressId = ProgressInfo.generateProgressId();
            ProgressInfo progressInfo = new ProgressInfo(progressId, ProgressInfo.ProgressStatus.STARTING, fileInfo.getFileName());
            peerModel.setProgress(progressInfo);

            peerModel.downloadFile(fileInfo, saveFile, peers, progressId);
            return progressId;
        } catch (Exception e) {
            return "Internal server error";
        }

    }

    @Override
    public String sharePublicFile(String filePath, int isReplace, String fileName, String progressId) {
        try {
            File file = new File(filePath);
            ProgressInfo progressInfo = peerModel.getProgress().get(progressId);

            boolean result = AppPaths.copyFileToShare(file, fileName, progressInfo);

            if (!result) {
                progressInfo.setStatus(ProgressInfo.ProgressStatus.FAILED);
                return LogTag.S_ERROR;
            }

            FileInfo oldFileInfo = peerModel.getPublicSharedFiles().get(file.getName());
            if (oldFileInfo == null) {
                oldFileInfo = peerModel.getPrivateSharedFiles().keySet().stream()
                        .filter(f -> f.getFileName().equals(file.getName()))
                        .findFirst()
                        .orElse(null);
            }
            peerModel.sharePublicFile(file, fileName, progressId, isReplace, oldFileInfo);
            return progressId;
        } catch (Exception e) {
            return "Internal server error";
        }
    }

    @Override
    public String sharePrivateFile
            (String filePath, int isReplace, String fileName, List<String> peersList, String progressId) {
        File file = new File(filePath);
        ProgressInfo progressInfo = peerModel.getProgress().get(progressId);

        boolean result = AppPaths.copyFileToShare(file, fileName, progressInfo);

        if (!result) {
            progressInfo.setStatus(ProgressInfo.ProgressStatus.FAILED);
            return LogTag.S_ERROR;
        }

        FileInfo oldFileInfo = peerModel.getPublicSharedFiles().get(file.getName());
        if (oldFileInfo == null) {
            oldFileInfo = peerModel.getPrivateSharedFiles().keySet().stream()
                    .filter(f -> f.getFileName().equals(file.getName()))
                    .findFirst()
                    .orElse(null);
        }

        peerModel.sharePrivateFile(file, oldFileInfo, isReplace, progressId, peersList);
        return progressId;
    }

    @Override
    public int refreshFiles() {
        return peerModel.refreshFiles();
    }

    @Override
    public Set<FileInfo> getFiles() {
        return peerModel.getFiles();
    }

    @Override
    public void shareFileList() {
        peerModel.shareFileList(peerModel.getPublicFiles(), peerModel.getPrivateSharedFiles());
    }

    @Override
    public int stopSharingFile(String fileName) {
        return peerModel.stopSharingFile(fileName);
    }

    @Override
    public Map<String, ProgressInfo> getProgress() {
        return peerModel.getProgress();
    }

    @Override
    public void setProgress(ProgressInfo progressInfo) {
        peerModel.setProgress(progressInfo);
    }

    @Override
    public void cleanupProgress(List<String> progressIds) {
        peerModel.cleanupProgress(progressIds);
    }
}
