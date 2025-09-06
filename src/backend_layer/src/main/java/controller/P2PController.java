package main.java.controller;

import main.java.api.IP2PApi;
import main.java.model.*;
import main.java.utils.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static main.java.utils.Log.logError;
import static main.java.utils.Log.logInfo;


public class P2PController {
    private final IPeerModel peerModel;
    private final IP2PApi api;
    private boolean isConnected = false;
    private boolean isLoadSharedFiles = false;
    private boolean isRetrying = false;
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    public P2PController(IPeerModel peerModel, IP2PApi view) {
        this.peerModel = peerModel;
        this.api = view;
        setupListeners();
    }

    public void start() {
        taskInitialization();
        taskTrackerRegistration();
    }

    private void taskInitialization() {
        executor.submit(() -> {
            try {
                initializeServer();
                getSharedFiles();
                peerModel.loadSharedFiles();
                isLoadSharedFiles = true;
                getSharedFiles();
            } catch (Exception e) {
                logError(": " + e.getMessage(), e);
            }
        });
    }

    private void taskTrackerRegistration() {
        executor.submit(() -> {
            try {
                registerWithTracker();
                while (!isLoadSharedFiles) {
                    logInfo(ConfigLoader.msgBundle.getString("msg.log.noti.waiting_load_shared_files"));
                    sleep(1000);
                }
                peerModel.shareFileList();
            } catch (Exception e) {
                logError(ConfigLoader.msgBundle.getString("msg.log.err.cannot_register_peer") + ": " + e.getMessage(), e);
            }
        });
    }

    private void registerWithTracker() {
        while (!isConnected) {
            int result = peerModel.registerWithTracker();
            switch (result) {
                case LogTag.I_SUCCESS -> isConnected = true;
                case LogTag.I_NOT_FOUND -> isConnected = true;
                default -> {
                    sleep(5000);
                }
            }
        }
    }

    private void initializeServer() {
        try {
            peerModel.startServer();
        } catch (Exception e) {
            logError(ConfigLoader.msgBundle.getString("msg.log.err.cannot_start_server") + " TCP: " + e.getMessage(), e);
        }

        try {
            peerModel.startUDPServer();
        } catch (Exception e) {
            logError(ConfigLoader.msgBundle.getString("msg.log.err.cannot_start_server") + " UDP: " + e.getMessage(), e);
        }
    }

    private void getSharedFiles() {
        Set<FileInfor> sharedFiles = peerModel.getSharedFileNames();
        List<FileInfor> files = new ArrayList<>();
        for (Map.Entry<String, FileInfor> entry : peerModel.getMySharedFiles().entrySet()) {
            FileInfor fileInfo = entry.getValue();
            if (isInvalidFileInfo(fileInfo))
                continue;

            if (!sharedFiles.contains(fileInfo))
                sharedFiles.add(fileInfo);
        }
        peerModel.setSharedFileNames(sharedFiles);
        for (FileInfor fileInfor : sharedFiles) {
            if (fileInfor.getPeerInfor().equals(new PeerInfor(Infor.SERVER_IP, Infor.SERVER_PORT)))
                fileInfor.setSharedByMe(true);
            files.add(fileInfor);
        }
        api.setFiles(files);
    }

    private boolean isInvalidFileInfo(FileInfor fileInfo) {
        if (fileInfo == null || fileInfo.getFileName() == null || fileInfo.getFileName().isEmpty()) {
            return true;
        }
        return fileInfo.getPeerInfor() == null || fileInfo.getPeerInfor().getIp() == null || fileInfo.getPeerInfor().getPort() <= 0;
    }

    private void setupListeners() {
        api.setRouteForRefresh(() -> {
            if (!isConnected) {
                retryConnectToTracker();
                return LogTag.I_NOT_CONNECTION;
            }
            refreshFileList();
            return 0;
        });

        api.setRouteForShareFile((filePath, isReplace, isCancelled) -> {
            if (!isConnected) {
                retryConnectToTracker();
                return LogTag.S_NOT_CONNECTION;
            }
            return shareFile(filePath, isReplace, isCancelled);
        });

        api.setRouteForRemoveFile((filename) -> {
            if (!isConnected) {
                retryConnectToTracker();
                return LogTag.I_NOT_CONNECTION;
            }
            int res = peerModel.stopSharingFile(filename);
            if (res == LogTag.I_SUCCESS) {
                refreshFileList();
            }
            return res;
        });

        api.setRouteForDownloadFile((file, savePath, isCanncelled) -> {
            if (!isConnected) {
                retryConnectToTracker();
                return LogTag.S_NOT_CONNECTION;
            }

            List<PeerInfor> peers = peerModel.getPeersWithFile(file.getFileHash());
            if (peers == null || peers.isEmpty()) {
                return LogTag.S_NOT_FOUND;
            }

            File saveFile = new File(savePath);
            if (!saveFile.getParentFile().exists()) {
                logInfo("Lỗi: Thư mục lưu không tồn tại: " + saveFile.getParent());
                return LogTag.S_INVALID;
            }
            if (!saveFile.getParentFile().canWrite()) {
                logInfo("Lỗi: Không có quyền ghi vào thư mục: " + saveFile.getParent());
                return LogTag.S_NOT_PERMISSION;
            }
            String progressId = ProgressInfor.generateProgressId();
            peerModel.setProgress(new ProgressInfor(progressId, ProgressInfor.ProgressStatus.STARTING, file.getFileName()));
            peerModel.downloadFile(file, saveFile, peers, progressId);
            return progressId;
        });

        api.setRouteForCheckFile(AppPaths::isExistSharedFile);

        api.setRouteForGetProgress(peerModel::getProgress);
        api.setRouteForCleanupProgress((request) -> peerModel.cleanupProgress(request.taskIds));
        api.setRouteForCancelTask((progressId) -> {
            Map<String, ProgressInfor> progressMap = peerModel.getProgress();
            if (progressMap.containsKey(progressId)) {
                ProgressInfor progress = progressMap.get(progressId);
                progress.setStatus(ProgressInfor.ProgressStatus.CANCELLED);
                logInfo("Task with progress ID " + progressId + " has been marked as cancelled.");
                executor.submit(() -> {
                    sleep(3000);
                    peerModel.cleanupProgress(Collections.singletonList(progressId));
                });
            } else {
                logInfo("No task found with progress ID " + progressId + " to cancel.");
            }
        });

        api.setRouteForShareToPeers((fileHash, peerList) -> {
            if (!isConnected) {
                retryConnectToTracker();
                return LogTag.S_NOT_CONNECTION;
            }
            return shareFileToPeers(fileHash, peerList);
        });

        api.setRouteForGetKnownPeers(() -> {
            if (!isConnected) {
                retryConnectToTracker();
                return Collections.emptyList();
            }
            return getKnownPeers();
        });
    }

    private void refreshFileList() {
        executor.submit(() -> {
            try {
                peerModel.refreshSharedFileNames();
                getSharedFiles();
            } catch (Exception e) {
                logError(ConfigLoader.msgBundle.getString("msg.log.err.cannot_load_shared_files") + ": " + e.getMessage(), e);
            }
        });
    }

    private String shareFile(String filePath, int isReplace, AtomicBoolean isCancelled) {
        File file = new File(filePath);

        String fileName = handleResultGetFileName(isReplace, file.getName());
        if (fileName.equals(LogTag.S_ERROR) || fileName.equals(LogTag.S_NOT_FOUND)) {
            return fileName;
        }
        if (!isConnected) {
            retryConnectToTracker();
            return LogTag.S_NOT_CONNECTION;
        }
        String progressId = ProgressInfor.generateProgressId();
        ProgressInfor progressInfor = new ProgressInfor(progressId, ProgressInfor.ProgressStatus.STARTING, fileName);
        peerModel.setProgress(progressInfor);
        executor.submit(() -> {
            AppPaths.copyFileToShare(file, fileName, progressInfor);
            peerModel.shareFileAsync(file, fileName, progressId);
        });
        return progressId;
    }

    private String handleResultGetFileName(int isReplace, String fileName) {
        return switch (isReplace) {
            case 0 -> {
                fileName = AppPaths.incrementFileName(fileName);
                yield fileName;
            }
            case 1 -> fileName;
            default -> LogTag.S_ERROR;
        };
    }

    private void sleep(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logError("Thread was interrupted during sleep: " + e.getMessage(), e);
        }
    }

    private String shareFileToPeers(String filePathAndReplace, List<String> peerList) {
        if (peerList == null || peerList.isEmpty()) {
            return LogTag.S_INVALID;
        }

        String[] parts = filePathAndReplace.split("\\|");
        if (parts.length != 2) {
            return LogTag.S_INVALID;
        }

        String filePath = parts[0];
        int isReplace = Integer.parseInt(parts[1]);

        File file = new File(filePath);
        String fileName = handleResultGetFileName(isReplace, file.getName());
        if (fileName.equals(LogTag.S_ERROR) || fileName.equals(LogTag.S_NOT_FOUND)) {
            return fileName;
        }

        String progressId = ProgressInfor.generateProgressId();
        ProgressInfor progressInfor = new ProgressInfor(progressId, ProgressInfor.ProgressStatus.STARTING, fileName);
        peerModel.setProgress(progressInfor);

        executor.submit(() -> {
            AppPaths.copyFileToShare(file, fileName, progressInfor);
            peerModel.shareFileToPeers(file, progressId, peerList);
        });
        return progressId;
    }

    private List<String> getKnownPeers() {
        return ((PeerModel) peerModel).getKnownPeers();
    }

    private void retryConnectToTracker() {
        if (isRetrying) {
            return;
        }
        isRetrying = true;
        taskTrackerRegistration();
    }
}
