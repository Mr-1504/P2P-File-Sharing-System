package main.java.interface_adapters.controllers;

import main.java.domain.entities.FileInfo;
import main.java.domain.entities.PeerInfo;
import main.java.domain.entities.ProgressInfo;
import main.java.domain.repositories.FileRepository;
import main.java.domain.repositories.PeerRepository;
import main.java.api.IP2PApi;
import main.java.usecases.DownloadFileUseCase;
import main.java.usecases.ShareFileUseCase;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class P2PController {
    private final PeerRepository peerRepository;
    private final FileRepository fileRepository;
    private final IP2PApi api;
    private final ShareFileUseCase shareFileUseCase;
    private final DownloadFileUseCase downloadFileUseCase;
    private final ExecutorService executor = Executors.newFixedThreadPool(10);
    private boolean isConnected = false;
    private boolean isLoadSharedFiles = false;
    private boolean isRetrying = false;

    public P2PController(PeerRepository peerRepository, FileRepository fileRepository, IP2PApi api) {
        this.peerRepository = peerRepository;
        this.fileRepository = fileRepository;
        this.api = api;
        this.shareFileUseCase = new ShareFileUseCase(fileRepository);
        this.downloadFileUseCase = new DownloadFileUseCase(fileRepository);
        setupApiRoutes();
    }

    public void start() {
        taskInitialization();
        taskTrackerRegistration();
    }

    private void taskInitialization() {
        executor.submit(() -> {
            try {
                peerRepository.initializeServerSocket();
                peerRepository.startServer();
                peerRepository.startUDPServer();
                fileRepository.loadSharedFiles();
                isLoadSharedFiles = true;
                fileRepository.shareFileList();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void taskTrackerRegistration() {
        executor.submit(() -> {
            try {
                registerWithTracker();
                while (!isLoadSharedFiles) {
                    Thread.sleep(1000);
                }
                fileRepository.shareFileList();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void registerWithTracker() {
        while (!isConnected) {
            int result = peerRepository.registerWithTracker();
            switch (result) {
                case 0 -> isConnected = true;
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

    public String shareFile(String filePath, int isReplace) {
        return shareFileUseCase.execute(filePath, isReplace);
    }

    public String downloadFile(FileInfo fileInfo, String savePath) {
        return downloadFileUseCase.execute(fileInfo, savePath);
    }

    public int removeFile(String fileName) {
        return fileRepository.stopSharingFile(fileName);
    }

    public Map<String, ProgressInfo> getProgress() {
        return fileRepository.getProgress();
    }

    public void cleanupProgress(List<String> taskIds) {
        fileRepository.cleanupProgress(taskIds);
    }

    public void cancelTask(String progressId) {
        Map<String, ProgressInfo> progressMap = fileRepository.getProgress();
        if (progressMap.containsKey(progressId)) {
            ProgressInfo progress = progressMap.get(progressId);
            progress.setStatus(ProgressInfo.ProgressStatus.CANCELLED);
            executor.submit(() -> {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                fileRepository.cleanupProgress(List.of(progressId));
            });
        }
    }

    public Set<FileInfo> getSharedFiles() {
        return fileRepository.getSharedFileNames();
    }

    public List<String> getKnownPeers() {
        return fileRepository.getKnownPeers();
    }

    public boolean isConnected() {
        return isConnected;
    }

    public void retryConnectToTracker() {
        if (isRetrying) {
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

        api.setRouteForShareFile((filePath, isReplace, isCancelled) -> {
            if (!isConnected) {
                retryConnectToTracker();
                return "Not connected";
            }
            return shareFile(filePath, isReplace);
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
            // Convert model FileInfor to domain FileInfo
            FileInfo domainFile = convertToDomainFileInfo(file);
            return downloadFile(domainFile, savePath);
        });

        api.setRouteForCheckFile(fileName -> true); // Simplified check

        api.setRouteForGetProgress(() -> getProgress());

        api.setRouteForCleanupProgress((request) -> cleanupProgress(request.taskIds));

        api.setRouteForCancelTask((progressId) -> cancelTask(progressId));

        api.setRouteForShareToPeers((fileHash, peerList) -> "Not implemented");

        api.setRouteForGetKnownPeers(() -> getKnownPeers());

        // Update API with current files
        updateApiFiles();
    }

    private void refreshFileList() {
        executor.submit(() -> {
            try {
                fileRepository.refreshSharedFileNames();
                updateApiFiles();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void updateApiFiles() {
        Set<FileInfo> domainFiles = fileRepository.getSharedFileNames();
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
                new PeerInfo(modelFile.getPeerInfo().getIp(), modelFile.getPeerInfo().getPort()),
                modelFile.isSharedByMe()
        );
    }

    private FileInfo convertToModelFileInfo(FileInfo domainFile) {
        return new FileInfo(
                domainFile.getFileName(),
                domainFile.getFileSize(),
                domainFile.getFileHash(),
                new PeerInfo(domainFile.getPeerInfo().getIp(), domainFile.getPeerInfo().getPort()),
                domainFile.isSharedByMe()
        );
    }
}
