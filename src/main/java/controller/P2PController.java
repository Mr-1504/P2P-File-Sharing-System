package main.java.controller;

import javafx.application.Platform;
import javafx.scene.control.TableRow;
import javafx.scene.input.MouseButton;
import javafx.stage.Stage;
import main.java.model.FileInfor;
import main.java.model.PeerInfor;
import main.java.model.PeerModel;
import main.java.utils.AppPaths;
import main.java.utils.Infor;
import main.java.utils.LanguageLoader;
import main.java.utils.LogTag;
import main.java.view.P2PView;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static main.java.utils.Log.logError;
import static main.java.utils.Log.logInfo;


public class P2PController {
    private final PeerModel peerModel;
    private final P2PView view;
    private boolean isConnected = false;
    private boolean isLoadSharedFiles = false;
    private boolean isRetrying = false;
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    public P2PController(PeerModel peerModel, P2PView view) {
        this.peerModel = peerModel;
        this.view = view;
        setupListeners();
    }

    public void start() {
        view.show();
        taskInitialization();
        taskTrackerRegistration();
    }

    private void taskInitialization() {
        executor.submit(() -> {
            try {
                initializeServer();
                showSharedFile();
                peerModel.loadSharedFiles();
                isLoadSharedFiles = true;
                showSharedFile();
            } catch (Exception e) {
                logError(": " + e.getMessage(), e);
                Platform.runLater(() -> view.showMessage(LanguageLoader.msgBundle.getString("msg.err.sys.start") + ": " + e.getMessage(), true));
            }
        });
    }

    private void taskTrackerRegistration() {
        executor.submit(() -> {
            try {
                registerWithTracker();
                while (!isLoadSharedFiles) {
                    logInfo(LanguageLoader.msgBundle.getString("msg.log.noti.waiting_load_shared_files"));
                    sleep(1000);
                }
                peerModel.shareFileList();
            } catch (Exception e) {
                logError(LanguageLoader.msgBundle.getString("msg.log.err.cannot_register_peer") + ": " + e.getMessage(), e);
                Platform.runLater(() -> view.showMessage(
                        LanguageLoader.msgBundle.getString("msg.log.err.cannot_register_peer") + ": " + e.getMessage(), true));
            }
        });
    }

    private void registerWithTracker() {
        while (!isConnected) {
            int result = peerModel.registerWithTracker();
            switch (result) {
                case LogTag.I_SUCCESS -> {
                    view.appendLog(LanguageLoader.msgBundle.getString("msg.log.noti.registered_peer"));
                    view.showNotification(LanguageLoader.msgBundle.getString("msg.log.noti.registered_peer"), false);
                    isConnected = true;
                }
                case LogTag.I_NOT_FOUND -> {
                    view.appendLog(LanguageLoader.msgBundle.getString("msg.log.noti.registered_no_shared_file"));
                    view.showNotification(LanguageLoader.msgBundle.getString("msg.log.noti.registered_no_shared_file"), false);
                    isConnected = true;
                }
                case LogTag.I_ERROR -> {
                    view.appendLog(LanguageLoader.msgBundle.getString("msg.log.err.cannot_register_peer"));
                    view.showNotification(LanguageLoader.msgBundle.getString("msg.log.err.cannot_register_peer"), true);
                    sleep(5000);
                }
                default -> {
                    view.appendLog(LanguageLoader.msgBundle.getString("msg.log.noti.retrying"));
                    view.showNotification(LanguageLoader.msgBundle.getString("msg.log.noti.retrying"), true);
                    sleep(5000);
                }
            }
        }
    }

    private void initializeServer() {
        try {
            peerModel.startServer();
        } catch (Exception e) {
            logError(LanguageLoader.msgBundle.getString("msg.log.err.cannot_start_server") + " TCP: " + e.getMessage(), e);
        }

        try {
            peerModel.startUDPServer();
        } catch (Exception e) {
            logError(LanguageLoader.msgBundle.getString("msg.log.err.cannot_start_server") + " UDP: " + e.getMessage(), e);
        }
    }

    private void showSharedFile() {
        Set<FileInfor> sharedFiles = peerModel.getSharedFileNames();

        for (Map.Entry<String, FileInfor> entry : peerModel.getMySharedFiles().entrySet()) {
            FileInfor fileInfo = entry.getValue();
            if (isInvalidFileInfo(fileInfo))
                continue;

            if (!sharedFiles.contains(fileInfo))
                sharedFiles.add(fileInfo);
        }
        peerModel.setSharedFileNames(sharedFiles);
        view.displayData(peerModel.getSharedFileNames());
    }

    private boolean isInvalidFileInfo(FileInfor fileInfo) {
        if (fileInfo == null || fileInfo.getFileName() == null || fileInfo.getFileName().isEmpty()) {
            return true;
        }
        return fileInfo.getPeerInfor() == null || fileInfo.getPeerInfor().getIp() == null || fileInfo.getPeerInfor().getPort() <= 0;
    }

    private void setupListeners() {
        view.setSearchButtonListener(this::searchFile);
        view.setChooseFileButtonListener(this::shareFile);
        view.setChooseDownload(this::handleMenuItemClick);
        view.setMyFilesButtonListener(this::showMyFiles);
        view.setAllFilesButtonListener(this::showAllFiles);
        view.setRefreshButtonListener(this::refreshFileList);
        this.setupFileTableMouseListener();
    }

    private void refreshFileList() {
        if (!isConnected) {
            view.showMessage(LanguageLoader.msgBundle.getString("msg.log.err.cannot_load_shared_files") + ". "
                    + LanguageLoader.msgBundle.getString("msg.log.noti.reconnecting"), true);
            retryConnectToTracker();
            return;
        }
        view.clearTable();
        view.appendLog(LanguageLoader.msgBundle.getString("msg.notification.file.refresh"));
        executor.submit(() -> {
            try {
                int result = peerModel.refreshSharedFileNames();
                handleRefreshResult(result);
            } catch (Exception e) {
                logError(LanguageLoader.msgBundle.getString("msg.log.err.cannot_load_shared_files") + ": " + e.getMessage(), e);
            }
        });
    }

    private void handleRefreshResult(int result) {
        switch (result) {
            case LogTag.I_SUCCESS -> {
                view.appendLog(LanguageLoader.msgBundle.getString("msg.notification.file.refreshed"));
                Set<FileInfor> sharedFiles = peerModel.getSharedFileNames();
                if (sharedFiles.isEmpty()) {
                    view.appendLog(LanguageLoader.msgBundle.getString("msg.notification.file.empty"));
                } else {
                    view.displayData(sharedFiles);
                }
            }
            case LogTag.I_INVALID -> view.appendLog(LanguageLoader.msgBundle.getString("msg.log.err.query_tracker"));
            case LogTag.I_ERROR -> {
                view.appendLog(LanguageLoader.msgBundle.getString("msg.log.err.cannot_load_shared_files"));
                isConnected = false;
                taskTrackerRegistration();
            }
        }
    }

    private void handleMenuItemClick() {
        int selected = view.getFileTable().getSelectionModel().getSelectedIndex();
        if (selected == -1) {
            view.showMessage(LanguageLoader.msgBundle.getString("msg.notification.file.please_choose"), true);
            return;
        }
        FileInfor selectedFile = view.getFileTable().getItems().get(selected);
        String fileName = selectedFile.getFileName();
        String peerInfor = selectedFile.getPeerInfor().getIp() + ":" + selectedFile.getPeerInfor().getPort();

        if (isInvalidFileOrPeer(fileName, peerInfor)) {
            view.showMessage(LanguageLoader.msgBundle.getString("msg.err.invalid_infor"), true);
            return;
        }

        PeerInfor peer = parsePeerInfo(peerInfor);
        if (peer == null) {
            view.showMessage(LanguageLoader.msgBundle.getString("msg.err.invalid_peer") + peerInfor, true);
            return;
        }

        if (!isConnected) {
            view.showMessage(LanguageLoader.msgBundle.getString("msg.log.noti.reconnecting"), true);
            retryConnectToTracker();
            return;
        }

        if (!peerModel.isMe(peer.getIp(), peer.getPort())) {
            executor.submit(this::downloadFile);
        } else if (view.showConfirmation(LanguageLoader.msgBundle.getString("msg.notification.file.stop_share.confirm") + "\n" + fileName)) {
            removeFile(fileName, peerInfor);
        } else {
            view.showNotification(LanguageLoader.msgBundle.getString("msg.notification.file.stop_share.cancelled"), true);
        }
    }

    private void removeFile(String fileName, String peerInfor) {
        try {
            int result = peerModel.stopSharingFile(fileName);
            handleRemoveFileResult(result, fileName, peerInfor);
        } catch (Exception e) {
            view.appendLog(LanguageLoader.msgBundle.getString("msg.err.file.stop") + ": " + e.getMessage());
        }
    }

    private void handleRemoveFileResult(int result, String fileName, String peerInfor) {
        switch (result) {
            case LogTag.I_SUCCESS -> {
                view.appendLog(LanguageLoader.msgBundle.getString("msg.notification.file.stop_share.done") + ": " + fileName);
                view.removeFileFromView(fileName, peerInfor);
            }
            case LogTag.I_NOT_FOUND -> {
                view.appendLog(LanguageLoader.msgBundle.getString("msg.err.cannot.find") + ": " + fileName);
                view.showMessage(LanguageLoader.msgBundle.getString("msg.err.cannot.find") + ": " + fileName, true);
                view.removeFileFromView(fileName, peerInfor);
            }
            case LogTag.I_ERROR -> {
                view.showMessage(LanguageLoader.msgBundle.getString("msg.log.err.cannot_connect_tracker"), true);
                view.showNotification(LanguageLoader.msgBundle.getString("msg.log.err.cannot_connect_tracker"), true);
                isConnected = false;
                retryConnectToTracker();

            }
            default -> view.appendLog(LanguageLoader.msgBundle.getString("msg.err.file.stop") + ": " + fileName);
        }
    }

    private boolean isInvalidFileOrPeer(String fileName, String peerInfo) {
        return fileName == null || fileName.isEmpty() || peerInfo == null || peerInfo.isEmpty();
    }

    private PeerInfor parsePeerInfo(String peerInfo) {
        if (peerInfo == null || peerInfo.isEmpty()) return null;
        String[] parts = peerInfo.split(":");
        if (parts.length != 2) return null;
        try {
            return new PeerInfor(parts[0], Integer.parseInt(parts[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void showAllFiles() {
        view.clearTable();
        Set<FileInfor> sharedFiles = peerModel.getSharedFileNames();

        if (sharedFiles.isEmpty()) {
            view.appendLog(LanguageLoader.msgBundle.getString("msg.notification.file.empty"));
            return;
        }

        view.displayData(sharedFiles);
        view.appendLog(LanguageLoader.msgBundle.getString("msg.notification.file.refreshed"));
    }

    private void setupFileTableMouseListener() {
        view.getFileTable().setRowFactory(tv -> {
            TableRow<FileInfor> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.SECONDARY && !row.isEmpty()) {
                    FileInfor fileInfor = row.getItem();

                    // Lấy dữ liệu dòng
                    String fileName = fileInfor.getFileName();
                    String peerInfo = fileInfor.getPeerInfor().getIp() + ":" + fileInfor.getPeerInfor().getPort();

                    if (fileName != null && !fileName.isEmpty()) {
                        PeerInfor peer = parsePeerInfo(peerInfo);
                        if (peer != null) {
                            boolean isDownload = !peerModel.isMe(peer.getIp(), peer.getPort());
                            view.showMenu(isDownload);
                        }
                    }
                }
            });
            return row;
        });
    }


    private void showMyFiles() {
        view.clearTable();
        Map<String, FileInfor> sharedFiles = peerModel.getMySharedFiles();

        if (sharedFiles.isEmpty()) {
            view.appendLog(LanguageLoader.msgBundle.getString("msg.notification.file.empty"));
            return;
        }
        for (FileInfor fileInfo : sharedFiles.values()) {
            view.getFileTable().getItems().add(fileInfo);
        }
        view.appendLog(LanguageLoader.msgBundle.getString("msg.notification.file.refreshed"));
    }

    private void shareFile() {
        executor.submit(() -> {
            String filePath = getFilePath();
            if (filePath.equals(LogTag.S_CANCELLED))
                return;

            AtomicReference<Stage> dialog = new AtomicReference<>(null);
            AtomicBoolean isCancelled = new AtomicBoolean(false);
            File file = new File(filePath);

            AtomicInteger isReplace = new AtomicInteger(1);
            String fileName = processFileNameForShare(file, isReplace, dialog);
            if (fileName.equals(LogTag.S_CANCELLED) || fileName.equals(LogTag.S_NOT_FOUND)) {
                return;
            }

            if (!isConnected) {
                view.showMessage(LanguageLoader.msgBundle.getString("msg.log.err.cannot_connect_tracker"), true);
                retryConnectToTracker();
                return;
            }

            int result = prepareFileForSharing(file, fileName, dialog, isCancelled);
            if (result == LogTag.I_ERROR || result == LogTag.I_NOT_FOUND) {
                return;
            }
            view.setCancelButtonEnabled(true);
            Future<Boolean> res = peerModel.shareFileAsync(file, fileName);
            if (res == null) {
                view.showMessage(LanguageLoader.msgBundle.getString("msg.err.file.share") + ": " + fileName, true);
                view.appendLog(LanguageLoader.msgBundle.getString("msg.err.file.share") +  ": " + fileName);
                return;
            } else {
                try {
                    if (res.get() == false) {
                        view.showNotification(LanguageLoader.msgBundle.getString("msg.notification.file.share.cancelled") + ": " + fileName, false);
                        view.appendLog(LanguageLoader.msgBundle.getString("msg.err.file.share"));
                        return;
                    }
                } catch (Exception e) {
                    logError(LanguageLoader.msgBundle.getString("msg.log.err.cannot_wait_for_shared_files") + ": " + e.getMessage(), e);
                }
            }
            if (isReplace.get() == 1)
                return;
            FileInfor fileInfor = peerModel.getMySharedFiles().get(fileName);
            Platform.runLater(() -> {
                view.getFileTable().getItems().add(fileInfor);
                view.appendLog(LanguageLoader.msgBundle.getString("msg.notification.file.share.done") + ": " + fileName);
            });
        });
    }

    private int prepareFileForSharing
            (File file, String fileName, AtomicReference<Stage> dialog, AtomicBoolean isCancelled) {
        String filePath = file.getAbsolutePath();
        if (!file.exists() || !file.isFile()) {
            Platform.runLater(() -> {
                view.showMessage(LanguageLoader.msgBundle.getString("msg.err.cannot.find") + ": " + filePath, true);
                view.appendLog(LanguageLoader.msgBundle.getString("msg.err.cannot.find") + ": " + filePath);
            });
            return LogTag.I_NOT_FOUND;
        }
        Platform.runLater(() -> {
            dialog.set(view.createLoadingDialog(
                    LanguageLoader.msgBundle.getString("msg.notification.progress.prepare") +
                            ": " + filePath + "...\n" + LanguageLoader.msgBundle.getString("msg.notification.progress.large_file"),
                    () -> isCancelled.set(true)));
            dialog.get().show();
        });

        boolean res = AppPaths.copyFileToShare(file, fileName, isCancelled);
        if (!res) {
            Platform.runLater(() -> {
                view.showMessage(LanguageLoader.msgBundle.getString("msg.err.file.copy"), true);
                view.appendLog(LanguageLoader.msgBundle.getString("msg.err.file.copy"));
            });
            return LogTag.I_ERROR;
        }
        Platform.runLater(() -> dialog.get().close());
        return LogTag.I_SUCCESS;
    }

    private String getFilePath() {
        try {
            String filePath = view.openFileChooserForShare().join();

            if (filePath.equals(LogTag.S_ERROR) || filePath.equals(LogTag.S_CANCELLED)) {
                view.appendLog(LanguageLoader.msgBundle.getString("msg.notification.file.please_choose"));
                view.showMessage(LanguageLoader.msgBundle.getString("msg.notification.file.please_choose"), true);
                return LogTag.S_CANCELLED;
            }
            return filePath;
        } catch (Exception e) {
            view.appendLog(LanguageLoader.msgBundle.getString("msg.err.file.choose") + ": " + e.getMessage());
            view.showMessage(LanguageLoader.msgBundle.getString("msg.err.file.choose") + ": " + e.getMessage(), true);
            return LogTag.S_ERROR;
        }
    }

    private String processFileNameForShare(File file, AtomicInteger isReplace, AtomicReference<Stage> dialog) {
        String fileName = file.getName();
        Path filePathObj = new File(AppPaths.getSharedFile(fileName)).toPath();
        if (Files.exists(filePathObj)) {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.runLater(() -> {
                isReplace.set(view.showMessageWithOptions(LanguageLoader.msgBundle.getString("msg.err.file.exist") + ": " + fileName, true));
                view.appendLog(LanguageLoader.msgBundle.getString("msg.err.file.exist") + ": " + fileName);
                latch.countDown();
            });

            try {
                latch.await();
            } catch (InterruptedException e) {
                logError(LanguageLoader.msgBundle.getString("msg.log.err.interupted_while_waiting_user_input") + ": " + e.getMessage(), e);
            }
            return handleResultGetFileName(isReplace, fileName, dialog);
        }
        return fileName;
    }

    private String handleResultGetFileName(AtomicInteger isReplace, String fileName, AtomicReference<Stage> dialog) {
        return switch (isReplace.get()) {
            case 0 -> {
                fileName = AppPaths.incrementFileName(fileName);
                yield fileName;
            }
            case 1 -> fileName;
            case 2 -> {
                Platform.runLater(() -> {
                    dialog.get().close();
                    view.showNotification(LanguageLoader.msgBundle.getString("msg.notification.file.share.cancelled"), false);
                    view.appendLog(LanguageLoader.msgBundle.getString("msg.notification.file.share.cancelled"));
                });
                yield LogTag.S_CANCELLED;
            }
            default -> LogTag.S_ERROR;
        };
    }


    private void searchFile() {
        String fileName = view.getFileName();
        if (fileName.isEmpty()) {
            view.showNotification(LanguageLoader.msgBundle.getString("msg.err.file.please_enter_file_name"), true);
            return;
        }
        view.showNotification(fileName, true);
        if (!isConnected) {
            view.showNotification(LanguageLoader.msgBundle.getString("msg.log.err.cannot_connect_tracker"), true);
            retryConnectToTracker();
            return;
        }

        view.clearTable();

        executor.submit(() -> {
            List<FileInfor> files = peerModel.queryTracker(fileName);
            if (files == null) {
                Platform.runLater(() -> view.showNotification(LanguageLoader.msgBundle.getString("msg.log.err.cannot_connect_tracker"), true));
                retryConnectToTracker();
                return;
            }
            if (files.isEmpty()) {
                Platform.runLater(() -> view.showNotification(LanguageLoader.msgBundle.getString("msg.err.cannot.find") + ": " + fileName, false));
                return;
            }

            processFileList(files);
        });
    }

    private void processFileList(List<FileInfor> files) {
        Set<FileInfor> sharedFiles = peerModel.getSharedFileNames();
        for (FileInfor file : files) {
            if (!sharedFiles.contains(file)) {
                sharedFiles.add(file);
            }
            Platform.runLater(() -> view.getFileTable().getItems().add(file));
        }
        peerModel.setSharedFileNames(sharedFiles);
    }

    private void downloadFile() {
        String prepareStr = prepareFileForDownload();
        if (prepareStr.equals(LogTag.S_INVALID)) {
            return;
        }
        String[] parts = prepareStr.split(Infor.FIELD_SEPARATOR_REGEX);
        String savePath = parts[0];
        String peerInfor = parts[1];
        String fileName = parts[2];
        FileInfor fileInfor = getFileInfor(fileName, peerInfor);
        if (fileInfor == null) {
            logInfo(LanguageLoader.msgBundle.getString("msg.err.cannot.find") + ": " + fileName);
            return;
        }

        List<PeerInfor> peers = peerModel.getPeersWithFile(fileInfor.getFileHash());
        if (peers == null || peers.isEmpty()) {
            view.showMessage(LanguageLoader.msgBundle.getString("msg.err.not_shared") + ": " + fileName, true);
            return;
        }

        try {
            view.setCancelButtonEnabled(true);
            Future<Integer> result = peerModel.downloadFile(fileInfor, savePath, peers);
            int status = result.get();
            handleDownloadResult(status, fileName, peerInfor);
        } catch (Exception e) {
            logError(LanguageLoader.msgBundle.getString("msg.err.file,download") + ": " + e.getMessage(), e);
            view.showMessage(LanguageLoader.msgBundle.getString("msg.err.file,download") + ": " + e.getMessage(), true);
        }
    }

    private FileInfor getFileInfor(String fileName, String peerInfor) {
        FileInfor fileInfor = null;
        for (FileInfor file : peerModel.getSharedFileNames()) {
            if (file.getFileName().equals(fileName) && file.getPeerInfor().toString().equals(peerInfor.replace(":", "|"))) {
                fileInfor = file;
                break;
            }
        }
        return fileInfor;
    }

    private String prepareFileForDownload() {
        int selected = view.getFileTable().getSelectionModel().getSelectedIndex();
        String fileName = "";
        String peerInfor = "";
        if (selected != -1) {
            FileInfor fileInfor = view.getFileTable().getItems().get(selected);
            fileName = fileInfor.getFileName();
            peerInfor = fileInfor.getPeerInfor().getIp() + ":" + fileInfor.getPeerInfor().getPort();
        }

        if (fileName.isEmpty()) {
            view.showMessage(LanguageLoader.msgBundle.getString("msg.err.invalid_file"), true);
            return LogTag.S_INVALID;
        }

        String savePath = view.openFileChooserForDownload(fileName).join();
        if (savePath.isEmpty() || savePath.equals(LogTag.S_CANCELLED) || savePath.equals(LogTag.S_ERROR)) {
            view.showMessage(LanguageLoader.msgBundle.getString("msg.err.invalid_path"), true);
            return LogTag.S_INVALID;
        }
        return savePath + Infor.FIELD_SEPARATOR + peerInfor + Infor.FIELD_SEPARATOR + fileName;
    }

    private void handleDownloadResult(int status, String fileName, String peerInfor) {
        String message;
        boolean isError = switch (status) {
            case LogTag.I_SUCCESS -> {
                message = LanguageLoader.msgBundle.getString("msg.notification.file.download.done") + ": " + fileName;
                yield false;
            }
            case LogTag.I_NOT_READY -> {
                message = LanguageLoader.msgBundle.getString("msg.err.cannot.find") + ": " + fileName + ". "
                        + LanguageLoader.msgBundle.getString("msg.notification.please_refresh");
                yield true;
            }
            case LogTag.I_NOT_FOUND -> {
                message = peerInfor + LanguageLoader.msgBundle.getString("msg.err.cannot.find") + ": " + fileName + ". "
                        + LanguageLoader.msgBundle.getString("msg.notification.please_refresh");
                yield true;
            }
            default -> throw new IllegalStateException("Unexpected value: " + status);
        };

        view.appendLog(message);
        view.showMessage(message, isError);
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