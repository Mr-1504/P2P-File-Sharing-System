package main.java.delivery.controller;

import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.domain.entity.ProgressInfo;
import main.java.delivery.api.IP2PApi;
import main.java.service.*;
import main.java.utils.AppPaths;
import main.java.utils.LogTag;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class P2PController {
    private final IFileService service;
    private final INetworkService networkService;
    private final IP2PApi api;
    private final ExecutorService executor = Executors.newFixedThreadPool(10);
    private boolean isConnected = false;
    private boolean isLoadSharedFiles = false;
    private boolean isRetrying = false;

    // Username state management - blocks full initialization until set
    private String username = null;
    private boolean isInitialized = false;

    public P2PController(IFileService service, INetworkService networkService, IP2PApi api) {
        this.service = service;
        this.networkService = networkService;
        this.api = api;
        setupApiRoutes();
    }

    public synchronized void start() {
        if (!checkUsernameExists()) {
            return;
        }
        performFullInitialization();
    }

    private void performFullInitialization() {
        taskInitialization();
        taskTrackerRegistration();
        isInitialized = true;
    }

    public synchronized boolean setUsername(String inputUsername) {
        if (this.username != null) {
            return false; // Already set
        }

        if (inputUsername == null || inputUsername.trim().isEmpty()) {
            return false; // Invalid username
        }

        this.username = inputUsername.trim();

        // Save to backend
        if (!AppPaths.saveUsername(this.username)) {
            this.username = null;
            return false;
        }

        // Now start the full initialization if not already started
        if (!isInitialized) {
            performFullInitialization();
        }

        return true;
    }

    public synchronized boolean checkUsernameExists() {
        if (this.username == null) {
            this.username = AppPaths.loadUsername();
        }
        return this.username != null;
    }

    // Public API method for frontend to check initialization status
    public synchronized boolean isInitialized() {
        return isInitialized;
    }

    // Public API method to get current username
    public synchronized String getUsername() {
        return username;
    }

    private void taskInitialization() {
        try {
            String shareDirPath = AppPaths.getAppDataDirectory() + "/shared_files/";
            File shareDir = new File(shareDirPath);
            if (!shareDir.exists()) {
                shareDir.mkdir();
            }
            networkService.initializeServerSocket();
            networkService.startServer();
            networkService.startUDPServer();
//                fileRepository.loadSharedFiles();
            isLoadSharedFiles = true;
            service.shareFileList();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void taskTrackerRegistration() {
        try {
            registerWithTracker();
            while (!isLoadSharedFiles) {
                Thread.sleep(1000);
            }
            service.shareFileList();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void registerWithTracker() {
        while (!isConnected) {
            int result = networkService.registerWithTracker();
            switch (result) {
                case LogTag.I_SUCCESS, LogTag.I_NOT_FOUND -> isConnected = true;
                default -> {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    public String downloadFile(FileInfo fileInfo, String savePath) {
        return service.downloadFile(fileInfo, savePath);
    }

    public int removeFile(String fileName) {
        return service.stopSharingFile(fileName);
    }

    public Map<String, ProgressInfo> getProgress() {
        return service.getProgress();
    }

    public void cleanupProgress(List<String> taskIds) {
        service.cleanupProgress(taskIds);
    }

    public void cancelTask(String progressId) {
        Map<String, ProgressInfo> progressMap = service.getProgress();
        if (progressMap.containsKey(progressId)) {
            ProgressInfo progress = progressMap.get(progressId);
            progress.setStatus(ProgressInfo.ProgressStatus.CANCELLED);
            executor.submit(() -> {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                service.cleanupProgress(List.of(progressId));
            });
        }
    }

    public void resumeTask(String progressId) {
        Map<String, ProgressInfo> progressMap = service.getProgress();
        if (progressMap.containsKey(progressId)) {
            ProgressInfo progress = progressMap.get(progressId);
            if (progress.getStatus().equals(ProgressInfo.ProgressStatus.TIMEOUT) ||
                    progress.getStatus().equals(ProgressInfo.ProgressStatus.STALLED)) {
                progress.setStatus(ProgressInfo.ProgressStatus.DOWNLOADING);
                progress.updateProgressTime();
            }
        }
    }

    public void checkTimeouts() {
        Map<String, ProgressInfo> progressMap = service.getProgress();
        for (Map.Entry<String, ProgressInfo> entry : progressMap.entrySet()) {
            ProgressInfo progress = entry.getValue();
            if ((progress.getStatus().equals(ProgressInfo.ProgressStatus.DOWNLOADING) ||
                    progress.getStatus().equals(ProgressInfo.ProgressStatus.STARTING)) &&
                    progress.isTimedOut()) {
                progress.setStatus(ProgressInfo.ProgressStatus.TIMEOUT);
            }
        }
    }

    public void retryConnectToTracker() {
        if (isRetrying || username == null) {
            return;
        }
        isRetrying = true;
        taskTrackerRegistration();
    }

    private void setupApiRoutes() {
        // Set up API routes for frontend communication
        api.setRouteForRefresh(() -> {
            if (!isConnected) {
                retryConnectToTracker();
                return -1; // Not connected
            }
            refreshFileList();
            return 0;
        });

        api.setRouteForSharePublicFile((filePath, isReplace, isCancelled) -> {
            if (!isConnected) {
                retryConnectToTracker();
                return LogTag.S_NOT_CONNECTION;
            }
            File file = new File(filePath);
            if (!file.exists()) {
                return LogTag.S_NOT_FOUND;
            }

            String fileName = file.getName();
            if (isReplace == 0) {
                fileName = AppPaths.incrementFileName(file.getName());
            }
            String progressId = ProgressInfo.generateProgressId();
            ProgressInfo newProgress = new ProgressInfo(progressId, ProgressInfo.ProgressStatus.STARTING, fileName);
            service.setProgress(newProgress);
            String finalFileName = fileName;
            executor.submit(() -> {
                service.sharePublicFile(filePath, isReplace, finalFileName, progressId);
            });

            return progressId;
        });

        api.setRouteForRemoveFile((filename) -> {
            if (!isConnected) {
                retryConnectToTracker();
                return -1;
            }
            return removeFile(filename);
        });

        api.setRouteForDownloadFile((file, savePath, isCancelled) -> {
            if (!isConnected) {
                retryConnectToTracker();
                return "Not connected";
            }
            return downloadFile(file, savePath);
        });

        api.setRouteForCheckFile(fileName -> {
            Set<FileInfo> sharedFiles = service.getFiles();
            return sharedFiles.stream().anyMatch(file -> file.getFileName().equals(fileName) && file.isSharedByMe());
        });

        api.setRouteForGetProgress(this::getProgress);

        api.setRouteForCleanupProgress((request) -> cleanupProgress(request.taskIds));

        api.setRouteForCancelTask(this::cancelTask);

        api.setRouteForResumeTask(this::resumeTask);

        api.setRouteForSharePrivateFile((filePath, isReplace, peerList) -> {
            if (!isConnected) {
                retryConnectToTracker();
                return LogTag.S_NOT_CONNECTION;
            }
            File file = new File(filePath);
            if (!file.exists()) {
                return LogTag.S_NOT_FOUND;
            }

            String fileName = file.getName();
            if (isReplace == 0) {
                fileName = AppPaths.incrementFileName(file.getName());
            }
            String progressId = ProgressInfo.generateProgressId();
            ProgressInfo newProgress = new ProgressInfo(progressId, ProgressInfo.ProgressStatus.STARTING, fileName);
            service.setProgress(newProgress);
            String finalFileName = fileName;
            executor.submit(() -> {
                service.sharePrivateFile(filePath, isReplace, finalFileName, peerList, progressId);
            });

            return progressId;
        });

        // Username management routes (highest priority - called during startup)
        api.setRouteForCheckUsername(this::checkUsernameExists);

        // Synchronous setter returns boolean indicating success
        api.setRouteForSetUsername(this::setUsername);

        api.setRouteForGetKnownPeers(this::getKnownPeers);
        // Start periodic timeout checker
        startTimeoutChecker();

        // Update API with current files
        updateApiFiles();
    }

    public Set<PeerInfo> getKnownPeers() {
        return networkService.queryOnlinePeerList();
    }

    private void refreshFileList() {
        executor.submit(() -> {
            try {
                service.refreshFiles();
                updateApiFiles();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void updateApiFiles() {
        Set<FileInfo> domainFiles = service.getFiles();
        List<FileInfo> modelFiles = domainFiles.stream()
                .map(this::convertToModelFileInfo)
                .collect(java.util.stream.Collectors.toList());
        api.setFiles(modelFiles);
    }

    private FileInfo convertToDomainFileInfo(FileInfo modelFile) {
        return new FileInfo(
                modelFile.getFileName(),
                modelFile.getFileSize(),
                modelFile.getFileHash(),
                new PeerInfo(modelFile.getPeerInfo().getIp(), modelFile.getPeerInfo().getPort(), modelFile.getPeerInfo().getUsername()),
                modelFile.isSharedByMe()
        );
    }

    private void startTimeoutChecker() {
        executor.submit(() -> {
            while (true) {
                try {
                    Thread.sleep(30000); // Check every 30 seconds
                    checkTimeouts();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private FileInfo convertToModelFileInfo(FileInfo domainFile) {
        return new FileInfo(
                domainFile.getFileName(),
                domainFile.getFileSize(),
                domainFile.getFileHash(),
                new PeerInfo(domainFile.getPeerInfo().getIp(), domainFile.getPeerInfo().getPort(), domainFile.getPeerInfo().getUsername()),
                domainFile.isSharedByMe()
        );
    }
}
