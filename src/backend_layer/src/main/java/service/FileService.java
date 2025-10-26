package service;

import domain.entity.FileInfo;
import domain.entity.PeerInfo;
import domain.entity.ProgressInfo;
import domain.repository.IPeerRepository;
import utils.AppPaths;
import utils.Log;
import utils.LogTag;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FileService implements IFileService {
    private final IPeerRepository peerModel;

    public FileService(IPeerRepository peerModel) {
        this.peerModel = peerModel;
    }

    @Override
    public List<PeerInfo> getSharedPeers(String filename) {
        return peerModel.getSharedPeers(filename);
    }

    @Override
    public boolean editPermission(FileInfo targetFile, String permission, List<PeerInfo> peersList){
        return this.peerModel.editPermission(targetFile, permission,peersList);
    }

    @Override
    public String downloadFile(FileInfo fileInfo, String savePath) {
        try {
            File saveFile = new File(savePath);

            if (!saveFile.getParentFile().exists()) {
                Log.logError("Save directory does not exist", null);
                return "Save directory does not exist";
            }
            if (!saveFile.getParentFile().canWrite()) {
                Log.logError("No write permission for save directory", null);
                return "No write permission for save directory";
            }

            List<PeerInfo> peers = peerModel.getPeersWithFile(fileInfo.getFileHash());
            if (peers == null || peers.isEmpty()) {
                Log.logError("No peers found with this file", null);
                return "No peers found with this file";
            }

            String progressId = ProgressInfo.generateProgressId();
            ProgressInfo progressInfo = new ProgressInfo(progressId, ProgressInfo.ProgressStatus.STARTING, fileInfo.getFileName(), ProgressInfo.TaskType.DOWNLOAD);
            progressInfo.setSavePath(savePath); // Set save path for metadata management
            progressInfo.setFileHash(fileInfo.getFileHash()); // Set file hash for metadata management
            progressInfo.setResumable(true); // Mark as resumable
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
            (String filePath, int isReplace, String fileName, List<PeerInfo> peersList, String progressId) {
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
    public Map<String, FileInfo> getPublicSharedFiles() {
        return peerModel.getPublicSharedFiles();
    }

    @Override
    public Map<FileInfo, Set<PeerInfo>> getPrivateSharedFiles() {
        return peerModel.getPrivateSharedFiles();
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

    @Override
    public boolean pauseDownload(String progressId) {
        Map<String, ProgressInfo> progressMap = peerModel.getProgress();
        if (progressMap.containsKey(progressId)) {
            ProgressInfo progress = progressMap.get(progressId);
            if (ProgressInfo.ProgressStatus.DOWNLOADING.equals(progress.getStatus()) ||
                ProgressInfo.ProgressStatus.STARTING.equals(progress.getStatus())) {
                progress.setStatus(ProgressInfo.ProgressStatus.PAUSED);
                // Save metadata when pausing
                peerModel.pauseDownload(progressId);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean resumeDownload(String progressId) {
        Map<String, ProgressInfo> progressMap = peerModel.getProgress();
        Log.logInfo("Resuming download for progressId: " + progressId);
        if (progressMap.containsKey(progressId)) {
            Log.logInfo("Found progress info for progressId: " + progressId);
            ProgressInfo progress = progressMap.get(progressId);
            if (ProgressInfo.ProgressStatus.PAUSED.equals(progress.getStatus()) ||
                ProgressInfo.ProgressStatus.STALLED.equals(progress.getStatus()) ||
                ProgressInfo.ProgressStatus.TIMEOUT.equals(progress.getStatus()) ||
                ProgressInfo.ProgressStatus.RESUMABLE.equals(progress.getStatus())) {
                // Check if resumable download exists
                if (progress.canResume()) {
                    progress.setStatus(ProgressInfo.ProgressStatus.DOWNLOADING);
                    progress.updateProgressTime();
                    progress.resetFailedChunksCount();
                    // Resume the download
                    peerModel.resumeDownload(progressId);
                    return true;
                }
            }
        }
        Log.logError("Cannot resume download for progressId: " + progressId, null);
        return false;
    }
}
