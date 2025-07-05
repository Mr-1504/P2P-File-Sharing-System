package main.java.controller;

import javafx.application.Platform;
import javafx.scene.control.TableRow;
import javafx.scene.input.MouseButton;
import javafx.stage.Stage;
import main.java.model.FileInfor;
import main.java.model.PeerInfor;
import main.java.model.PeerModel;
import main.java.utils.GetDir;
import main.java.utils.Infor;
import main.java.utils.LogTag;
import main.java.view.P2PView;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static main.java.utils.Log.logError;
import static main.java.utils.Log.logInfo;


public class P2PController {
    private static final int TIMEOUT_SECONDS = Infor.SOCKET_TIMEOUT_MS / 1000;
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
        view.appendLog("Khởi động hệ thống P2P...");
        taskInitialization();
        taskTrackerRegistration();
    }

    private void taskInitialization() {
        executor.submit(() -> {
            try {
                initializeServer();
                view.appendLog("Hệ thống P2P đã khởi động hoàn tất.");
                showSharedFile();
                peerModel.loadSharedFiles();
                isLoadSharedFiles = true;
                showSharedFile();
            } catch (Exception e) {
                logError("Lỗi khi khởi động hệ thống P2P: " + e.getMessage(), e);
                Platform.runLater(() -> view.showMessage("Lỗi khi khởi động hệ thống P2P: " + e.getMessage(), true));
            }
        });
    }

    private void taskTrackerRegistration() {
        executor.submit(() -> {
            try {
                registerWithTracker();
                while (!isLoadSharedFiles) {
                    logInfo("Đang tải danh sách file chia sẻ từ tracker...");
                    sleep(1000);
                }
                peerModel.shareFileList();
            } catch (Exception e) {
                logError("Lỗi khi đăng ký với tracker: " + e.getMessage(), e);
                Platform.runLater(() -> view.showMessage("Lỗi khi đăng ký với tracker: " + e.getMessage(), true));
            }
        });
    }

    private void registerWithTracker() {
        while (!isConnected) {
            int result = peerModel.registerWithTracker();
            switch (result) {
                case LogTag.I_SUCCESS -> {
                    view.appendLog("Đăng ký thành công với tracker.");
                    isConnected = true;
                }
                case LogTag.I_NOT_FOUND -> {
                    view.appendLog("Đăng ký thành công với tracker nhưng không có file chia sẻ.");
                    isConnected = true;
                }
                case LogTag.I_ERROR -> {
                    view.appendLog("Lỗi khi đăng ký với tracker.");
                    view.appendLog("Đăng ký với tracker không thành công. Thử lại sau 5 giây...");
                    sleep(5000);
                }
                default -> {
                    logError("Đăng ký với tracker không thành công.", null);
                    view.appendLog("Đăng ký với tracker không thành công. Thử lại sau 5 giây...");
                    sleep(5000);
                }
            }
        }
    }

    private void initializeServer() {
        try {
            peerModel.startServer();
            view.appendLog("Server đã khởi động.");
        } catch (Exception e) {
            logError("Lỗi khi khởi động server: " + e.getMessage(), e);
        }

        try {
            peerModel.startUDPServer();
            view.appendLog("UDP Server đã khởi động.");
        } catch (Exception e) {
            logError("Lỗi khi khởi động UDP Server: " + e.getMessage(), e);
        }
    }

    private void showSharedFile() {
        Set<FileInfor> sharedFiles = peerModel.getSharedFileNames();

        for (Map.Entry<String, FileInfor> entry : peerModel.getMySharedFiles().entrySet()) {
            FileInfor fileInfo = entry.getValue();
            if (isInvalidFileInfo(fileInfo))
                continue;

            if (!sharedFiles.contains(fileInfo)) {
                sharedFiles.add(fileInfo);
            } else {
                view.appendLog("File đã tồn tại trong danh sách chia sẻ: " + fileInfo.getFileName());
            }

        }
        peerModel.setSharedFileNames(sharedFiles);
        view.displayData(peerModel.getSharedFileNames());
    }

    private boolean isInvalidFileInfo(FileInfor fileInfo) {
        if (fileInfo == null || fileInfo.getFileName() == null || fileInfo.getFileName().isEmpty()) {
            view.appendLog("Tên file không hợp lệ: " + (fileInfo != null ? fileInfo.getFileName() : "null"));
            return true;
        }
        if (fileInfo.getPeerInfor() == null || fileInfo.getPeerInfor().getIp() == null || fileInfo.getPeerInfor().getPort() <= 0) {
            view.appendLog("Thông tin peer không hợp lệ cho file: " + fileInfo.getFileName());
            return true;
        }
        return false;
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
            view.showMessage("Không thể làm mới danh sách file. Đang chờ kết nối lại với tracker.", true);
            retryConnectToTracker();
            return;
        }
        view.clearTable();
        view.appendLog("Đang làm mới danh sách file...");
        executor.submit(() -> {
            try {
                int result = peerModel.refreshSharedFileNames();
                handleRefreshResult(result);
            } catch (Exception e) {
                logError("Error refreshing file list: " + e.getMessage(), e);
            }
        });
    }

    private void handleRefreshResult(int result) {
        switch (result) {
            case LogTag.I_SUCCESS -> {
                view.appendLog("Làm mới danh sách file thành công.");
                Set<FileInfor> sharedFiles = peerModel.getSharedFileNames();
                if (sharedFiles.isEmpty()) {
                    view.appendLog("Không có file nào được chia sẻ.");
                } else {
                    view.displayData(sharedFiles);
                }
            }
            case LogTag.I_INVALID -> view.appendLog("Lỗi truy vấn.");
            case LogTag.I_ERROR -> {
                view.appendLog("Lỗi khi làm mới danh sách file.");
                isConnected = false;
                taskTrackerRegistration();
            }
        }
    }

    private void handleMenuItemClick() {
        int selected = view.getFileTable().getSelectionModel().getSelectedIndex();
        if (selected == -1) {
            view.showMessage("Vui lòng chọn một file.", true);
            return;
        }
        FileInfor selectedFile = view.getFileTable().getItems().get(selected);
        String fileName = selectedFile.getFileName();
        String peerInfor = selectedFile.getPeerInfor().getIp() + ":" + selectedFile.getPeerInfor().getPort();

        if (isInvalidFileOrPeer(fileName, peerInfor)) {
            view.showMessage("Tên file hoặc thông tin peer không hợp lệ.", true);
            view.appendLog("Vui lòng chọn một file và peer hợp lệ.");
            return;
        }

        PeerInfor peer = parsePeerInfo(peerInfor);
        if (peer == null) {
            view.showMessage("Thông tin peer không hợp lệ: " + peerInfor, true);
            view.appendLog("Thông tin peer không hợp lệ: " + peerInfor);
            return;
        }

        if (!isConnected) {
            view.showMessage("Không thể xử lý. Đang chờ kết nối lại với tracker.", true);
            retryConnectToTracker();
            return;
        }

        if (!peerModel.isMe(peer.getIp(), peer.getPort())) {
            executor.submit(this::downloadFile);
        } else if (view.showConfirmation(
                "Bạn có chắc chắn muốn dừng chia sẻ file: " + fileName + " không?"))
            removeFile(fileName, peerInfor);
        else {
            view.appendLog("Dừng chia sẻ file đã bị hủy.");
        }
    }

    private void removeFile(String fileName, String peerInfor) {
        try {
            int result = peerModel.stopSharingFile(fileName);
            handleRemoveFileResult(result, fileName, peerInfor);
        } catch (Exception e) {
            view.appendLog("Lỗi khi dừng chia sẻ file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleRemoveFileResult(int result, String fileName, String peerInfor) {
        switch (result) {
            case LogTag.I_SUCCESS -> {
                view.appendLog("Đã dừng chia sẻ file: " + fileName);
                view.removeFileFromView(fileName, peerInfor);
            }
            case LogTag.I_NOT_FOUND -> {
                view.appendLog("Không tìm thấy file để dừng chia sẻ: " + fileName);
                view.showMessage("Không tìm thấy file để dừng chia sẻ: " + fileName, true);
                view.removeFileFromView(fileName, peerInfor);
            }
            case LogTag.I_ERROR -> {
                view.appendLog("Lỗi kết nối với Tracker");
                view.showMessage("Lỗi kết nối với Tracker", true);
                isConnected = false;
                retryConnectToTracker();
                view.appendLog("Đã kết nối lại với Tracker.");
            }
            default -> view.appendLog("Không thể dừng chia sẻ file: " + fileName);
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
            view.appendLog("Không có file nào được chia sẻ.");
            return;
        }

        view.displayData(sharedFiles);
        view.appendLog("Đã cập nhật danh sách file chia sẻ.");
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

                    if (fileName != null && !fileName.isEmpty() && peerInfo != null && !peerInfo.isEmpty()) {
                        view.appendLog("Đã chọn file: " + fileName + " để tải xuống.");
                        PeerInfor peer = parsePeerInfo(peerInfo);
                        if (peer != null) {
                            boolean isDownload = !peerModel.isMe(peer.getIp(), peer.getPort());
                            view.showMenu(isDownload);
                        }
                    } else {
                        view.appendLog("Không có tên file để tải xuống.");
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
            view.appendLog("Không có file nào được chia sẻ.");
            return;
        }
        for (FileInfor fileInfo : sharedFiles.values()) {
            view.getFileTable().getItems().add(fileInfo);
        }
        view.appendLog("Đã cập nhật danh sách file chia sẻ.");
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
                view.showMessage("Không thể chia sẻ file. Đang chờ kết nối lại với tracker.", true);
                retryConnectToTracker();
                return;
            }

            int result = prepareFileForSharing(file, fileName, isReplace, dialog, isCancelled);
            if (result == LogTag.I_ERROR || result == LogTag.I_NOT_FOUND) {
                return;
            }
            view.setCancelButtonEnabled(true);
            Future<Boolean> res = peerModel.shareFileAsync(file, fileName);
            if (res == null) {
                view.showMessage("Lỗi khi chia sẻ file: " + fileName, true);
                view.appendLog("Lỗi khi chia sẻ file: " + fileName);
                return;
            } else {
                try {
                    if (res.get() == false) {
                        view.showMessage("Chia sẻ file đã bị hủy.", true);
                        view.appendLog("Chia sẻ file đã bị hủy.");
                        return;
                    }
                } catch (Exception e){
                    logError("Error while waiting for file sharing result: " + e.getMessage(), e);
                }
            }
            if (isReplace.get() == 1)
                return;
            FileInfor fileInfor = peerModel.getMySharedFiles().get(fileName);
            Platform.runLater(() -> {
                view.getFileTable().getItems().add(fileInfor);
                view.appendLog("Đã chia sẻ file: " + fileName);
            });
        });
    }

    private int prepareFileForSharing
            (File file, String fileName, AtomicInteger isReplace, AtomicReference<Stage> dialog, AtomicBoolean isCancelled) {
        String filePath = file.getAbsolutePath();
        if (!file.exists() || !file.isFile()) {
            Platform.runLater(() -> {
                view.showMessage("File không tồn tại: " + filePath, true);
                view.appendLog("File không tồn tại: " + filePath);
            });
            return LogTag.I_NOT_FOUND;
        }
        Platform.runLater(() -> {
            dialog.set(view.createLoadingDialog(
                    "Đang chuẩn bị file: " + filePath + "...\nFile lớn có thể mất nhiều thời gian", () -> {
                        isCancelled.set(true);
                    }));
            dialog.get().show();
        });

        boolean res = GetDir.copyFileToShare(file, fileName, isCancelled);
        if (!res) {
            Platform.runLater(() -> {
                view.showMessage("Lỗi khi sao chép file vào thư mục chia sẻ.", true);
                view.appendLog("Lỗi khi sao chép file vào thư mục chia sẻ.");
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
                view.appendLog("Vui lòng chọn file.");
                view.showMessage("Vui lòng chọn file để chia sẻ.", true);
                return LogTag.S_CANCELLED;
            }
            return filePath;
        } catch (Exception e) {
            view.appendLog("Lỗi khi chọn file: " + e.getMessage());
            view.showMessage("Lỗi khi chọn file: " + e.getMessage(), true);
            return LogTag.S_ERROR;
        }
    }

    private String processFileNameForShare(File file, AtomicInteger isReplace, AtomicReference<Stage> dialog) {
        String fileName = file.getName();
        Path filePathObj = new File(GetDir.getShareDir(fileName)).toPath();
        if (Files.exists(filePathObj)) {
            String finalFileName = fileName;
            CountDownLatch latch = new CountDownLatch(1);
            Platform.runLater(() -> {
                isReplace.set(view.showMessageWithOptions("File đã tồn tại trong thư mục chia sẻ: " + finalFileName, true));
                view.appendLog("File đã tồn tại trong thư mục chia sẻ: " + finalFileName);
                latch.countDown();
            });

            try {
                latch.await();
            } catch (InterruptedException e) {
                logError("Interrupted while waiting for user input: " + e.getMessage(), e);
            }
            String resultFileName = handleResultGetFileName(isReplace, fileName, dialog);
            return resultFileName;
        }
        return fileName;
    }

    private String handleResultGetFileName(AtomicInteger isReplace, String fileName, AtomicReference<Stage> dialog) {
        switch (isReplace.get()) {
            case 0: {
                fileName = GetDir.incrementFileName(fileName);
                view.appendLog("Đã đổi tên file thành: " + fileName);
                return fileName;
            }
            case 1:
                view.appendLog("Đã ghi đè file: " + fileName);
                return fileName;
            case 2:
                view.appendLog("Hủy file: " + fileName);
                Platform.runLater(() -> {
                    dialog.get().close();
                    view.showMessage("Đã hủy chia sẻ file.", true);
                    view.appendLog("Đã hủy chia sẻ file.");
                });
                return LogTag.S_CANCELLED;
        }
        return LogTag.S_ERROR;
    }


    private void searchFile() {
        String fileName = view.getFileName();
        if (fileName.isEmpty()) {
            view.showMessage("Vui lòng nhập tên file để tìm kiếm.", true);
            return;
        }
        if (!isConnected) {
            view.showMessage("Không thể tìm kiếm file. Đang chờ kết nối lại với tracker.", true);
            retryConnectToTracker();
            return;
        }

        view.clearTable();
        view.appendLog("Đang tìm kiếm file: " + fileName + "...");

        executor.submit(() -> {
            List<FileInfor> files = peerModel.queryTracker(fileName);
            if (files == null) {
                Platform.runLater(() -> view.showMessage("Lỗi tìm file: " + fileName + ". Đang kết nối lại", true));
                retryConnectToTracker();
                return;
            }
            if (files.isEmpty()) {
                Platform.runLater(() -> view.showMessage("Không tìm thấy file: " + fileName, true));
                return;
            }

            processFileList(files, fileName);
        });
    }

    private void processFileList(List<FileInfor> files, String fileName) {
        Set<FileInfor> sharedFiles = peerModel.getSharedFileNames();
        for (FileInfor file : files) {
            if (!sharedFiles.contains(file)) {
                view.appendLog("File đã tồn tại trong danh sách chia sẻ: " + file.getFileName());
                sharedFiles.add(file);
            }
            Platform.runLater(() -> view.getFileTable().getItems().add(file));
        }
        peerModel.setSharedFileNames(sharedFiles);
        view.appendLog("Đã tìm thấy " + files.size() + " file với tên: " + fileName);
    }

    private String formatFileSize(long fileSize) {
        double size = fileSize / (1024.0 * 1024.0);
        if (size < 1) {
            size = fileSize / 1024.0;
            size = Math.round(size * 100.0) / 100.0;
            return size + " KB";
        } else {
            size = Math.round(size * 100.0) / 100.0;
            return size + " MB";
        }
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
            logInfo("File not found in shared files: " + fileName);
            return;
        }

        List<PeerInfor> peers = peerModel.getPeersWithFile(fileInfor.getFileHash());
        if (peers == null || peers.isEmpty()) {
            view.showMessage("Không tìm thấy peer nào chia sẻ file: " + fileName, true);
            return;
        }

        try {
            view.setCancelButtonEnabled(true);
            Future<Integer> result = peerModel.downloadFile(fileInfor, savePath, peers);
            int status = result.get();
            handleDownloadResult(status, fileName, peerInfor);
        } catch (Exception e) {
            logError("Lỗi khi tải file: " + e.getMessage(), e);
            view.showMessage("Lỗi khi tải file: " + e.getMessage(), true);
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
            view.showMessage("File không hợp lệ.", true);
            return LogTag.S_INVALID;
        }

        String savePath = view.openFileChooserForDownload(fileName);
        if (savePath.isEmpty() || savePath.equals(LogTag.S_CANCELLED) || savePath.equals(LogTag.S_ERROR)) {
            view.showMessage("Vui lòng chọn đường dẫn lưu hợp lệ.", true);
            return LogTag.S_INVALID;
        }
        return savePath + Infor.FIELD_SEPARATOR + peerInfor + Infor.FIELD_SEPARATOR + fileName;
    }

    private void handleDownloadResult(int status, String fileName, String peerInfor) {
        String message;
        boolean isError = switch (status) {
            case LogTag.I_SUCCESS -> {
                message = "Tải file thành công: " + fileName;
                yield false;
            }
            case LogTag.I_NOT_READY -> {
                message = "File không tìm thấy trên bất kỳ peer nào: " + fileName + ". Vui lòng làm mới và thử lại.";
                yield true;
            }
            case LogTag.I_NOT_FOUND -> {
                message = "File " + fileName + " không tìm thấy trên peer " + peerInfor + ". Vui lòng làm mới và thử lại.";
                yield true;
            }
            default -> {
                message = "Lỗi khi tải file: " + fileName + ". Vui lòng làm mới và thử lại.";
                yield true;
            }
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
            view.appendLog("Đang chờ kết nối lại với tracker...");
            return;
        }
        isRetrying = true;
        view.appendLog("Đang kết nối lại với tracker...");
        taskTrackerRegistration();
    }
}