package main.java.controller;

import com.google.gson.Gson;
import main.java.model.FileInfor;
import main.java.model.PeerInfor;
import main.java.model.PeerModel;
import main.java.utils.*;
import main.java.api.P2PApi;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static main.java.utils.Log.logError;
import static main.java.utils.Log.logInfo;


public class P2PController {
    private final PeerModel peerModel;
    private final P2PApi api;
    private boolean isConnected = false;
    private boolean isLoadSharedFiles = false;
    private boolean isRetrying = false;
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    public P2PController(PeerModel peerModel, P2PApi view) {
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
        api.setCancelTask(peerModel::cancelAction);
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
            Integer res = peerModel.stopSharingFile(filename);
            if (res == LogTag.I_SUCCESS) {
                refreshFileList();
            }
            return res;
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

        int result = prepareFileForSharing(file, fileName, isCancelled);
        if (result != LogTag.I_SUCCESS) {
            return LogTag.S_ERROR;
        }
        Future<Boolean> res = peerModel.shareFileAsync(file, fileName);
        if (res == null) {
            return LogTag.S_ERROR;
        } else {
            try {
                if (res.get() == false) {
                    return LogTag.S_CANCELLED;
                }
            } catch (Exception e) {
                logError("", e);
            }
        }
        FileInfor fileInfor = peerModel.getMySharedFiles().get(fileName);
        Gson gson = new Gson();
        return gson.toJson(fileInfor);
    }

    private String handleResultGetFileName(int isReplace, String fileName) {
        switch (isReplace) {
            case 0: {
                fileName = AppPaths.incrementFileName(fileName);
                return fileName;
            }
            case 1:
                return fileName;
        }
        return LogTag.S_ERROR;
    }

    private int prepareFileForSharing(File file, String fileName, AtomicBoolean isCancelled) {
        String filePath = file.getAbsolutePath();
        if (!file.exists() || !file.isFile()) {
            return LogTag.I_NOT_FOUND;
        }

        boolean res = AppPaths.copyFileToShare(file, fileName, isCancelled);
        if (!res) {
            return LogTag.I_ERROR;
        }
        return LogTag.I_SUCCESS;
    }


    private void sleep(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logError("Thread was interrupted during sleep: " + e.getMessage(), e);
        }
    }

    private void retryConnectToTracker() {
        if (isRetrying) {
            return;
        }
        isRetrying = true;
        taskTrackerRegistration();
    }
}