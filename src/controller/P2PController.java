package controller;

import model.FileBase;
import model.FileInfor;
import model.PeerInfor;
import model.PeerModel;
import utils.GetDir;
import utils.Infor;
import utils.LogTag;
import view.P2PView;

import javax.swing.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;


public class P2PController {
    private static final int TIMEOUT_SECONDS = 10;
    private final PeerModel peerModel;
    private final P2PView view;

    public P2PController(PeerModel peerModel, P2PView view) {
        this.peerModel = peerModel;
        this.view = view;
        setupListeners();
    }

    public void start() {
        view.setVisible(true);
        view.appendLog("Khởi động hệ thống P2P...");

        ExecutorService executor = Executors.newFixedThreadPool(10);
        executor.submit(() -> {
            try {
                initializeServer();
                registerWithTracker();
                view.appendLog("Hệ thống P2P đã khởi động hoàn tất.");
                showSharedFile();
                peerModel.loadSharedFiles();
                showSharedFile();
                peerModel.shareFileList();
            } finally {
                shutdownExecutor(executor);
            }
        });
    }

    private void registerWithTracker() {
        int result = peerModel.registerWithTracker();

        switch (result) {
            case LogTag.I_SUCCESS -> view.appendLog("Đăng ký thành công với tracker.");
            case LogTag.I_NOT_FOUND -> view.appendLog("Đăng ký thành công với tracker nhưng không có file chia sẻ.");
            case LogTag.I_ERROR -> view.appendLog("Lỗi khi đăng ký với tracker.");
            default -> {
                logError("Đăng ký với tracker không thành công.", null);
                throw new RuntimeException("Đăng ký với tracker không thành công.");
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

    private void logError(String message, Exception e) {
        SwingUtilities.invokeLater(() -> {
            view.appendLog(message);
            view.showMessage(message, true);
        });
        if (e != null) {
            e.printStackTrace();
        }
    }

    private void showSharedFile() {
        Set<FileBase> sharedFiles = peerModel.getSharedFileNames();

        for (Map.Entry<String, FileInfor> entry : peerModel.getMySharedFiles().entrySet()) {
            FileInfor fileInfo = entry.getValue();
            if (isInvalidFileInfo(fileInfo))
                continue;

            if (!sharedFiles.contains(new FileBase(fileInfo.getFileName(), fileInfo.getFileSize(), fileInfo.getPeerInfor()))) {
                sharedFiles.add(new FileBase(fileInfo.getFileName(), fileInfo.getFileSize(), fileInfo.getPeerInfor()));
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
        view.clearTable();
        view.appendLog("Đang làm mới danh sách file...");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                int result = peerModel.refreshSharedFileNames();
                handleRefreshResult(result);
            } catch (Exception e) {
                logError("Lỗi khi làm mới danh sách: " + e.getMessage(), e);
            } finally {
                shutdownExecutor(executor);
            }
        });
    }

    private void handleRefreshResult(int result) {
        switch (result) {
            case LogTag.I_SUCCESS -> {
                view.appendLog("Làm mới danh sách file thành công.");
                Set<FileBase> sharedFiles = peerModel.getSharedFileNames();
                if (sharedFiles.isEmpty()) {
                    view.appendLog("Không có file nào được chia sẻ.");
                } else {
                    view.displayData(sharedFiles);
                }
            }
            case LogTag.I_INVALID -> view.appendLog("Lỗi truy vấn.");
            case LogTag.I_ERROR -> view.appendLog("Lỗi khi làm mới danh sách file.");
            default -> logError("Làm mới danh sách file không thành công.", null);
        }
    }

    private void handleMenuItemClick() {
        int selected = view.getFileTable().getSelectedRow();
        if (selected == -1) {
            view.showMessage("Vui lòng chọn một file.", true);
            return;
        }
        String fileName = (String) view.getTableModel().getValueAt(selected, 0);
        String peerInfor = (String) view.getTableModel().getValueAt(selected, 2);

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

        if (!peerModel.isMe(peer.getIp(), peer.getPort())) {
            ExecutorService executorService = Executors.newSingleThreadExecutor();
            executorService.submit(this::downloadFile);
        } else if (view.showConfirmation(
                "Bạn có chắc chắn muốn dừng chia sẻ file: " + fileName + " không?"))
            try {
                peerModel.stopSharingFile(fileName);
                view.appendLog("Đã dừng chia sẻ file: " + fileName);
                view.removeFileFromView(fileName, peerInfor);
            } catch (Exception e) {
                view.appendLog("Lỗi khi dừng chia sẻ file: " + e.getMessage());
                e.printStackTrace();
            }
        else {
            view.appendLog("Dừng chia sẻ file đã bị hủy.");
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
        Set<FileBase> sharedFiles = peerModel.getSharedFileNames();

        if (sharedFiles.isEmpty()) {
            view.appendLog("Không có file nào được chia sẻ.");
            return;
        }

        view.displayData(sharedFiles);
        view.appendLog("Đã cập nhật danh sách file chia sẻ.");
    }

    private void setupFileTableMouseListener() {
        view.getFileTable().addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = view.getFileTable().rowAtPoint(e.getPoint());
                    if (row >= 0 && row < view.getTableModel().getRowCount()) {
                        view.setRowSelectionInterval(row, row);
                        String fileName = (String) view.getTableModel().getValueAt(row, 0);
                        String peerInfor = (String) view.getTableModel().getValueAt(row, 2);
                        if (!fileName.isEmpty() && !peerInfor.isEmpty()) {
                            view.appendLog("Đã chọn file: " + fileName + " để tải xuống.");
                            PeerInfor peer = parsePeerInfo(peerInfor);
                            if (peer != null) {
                                view.showMenu(!peerModel.isMe(peer.getIp(), peer.getPort()));
                            }
                        } else {
                            view.appendLog("Không có tên file để tải xuống.");
                        }
                    }
                }
            }
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
            view.displayFileInfo(fileInfo.getFileName(), fileInfo.getFileSize(),
                    fileInfo.getPeerInfor().getIp() + ":" + fileInfo.getPeerInfor().getPort());
        }
        view.appendLog("Đã cập nhật danh sách file chia sẻ.");
    }

    private void shareFile() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            String filePath = view.openFileChooserForShare();

            if (filePath.isEmpty() || filePath.equals(LogTag.S_CANCELLED) || filePath.equals(LogTag.S_ERROR)) {
                view.appendLog("Vui lòng chọn file file.");
                view.showMessage("Vui lòng chọn file để chia sẻ.", true);
                return;
            }
            AtomicReference<JDialog> dialog = new AtomicReference<>(null);
            AtomicBoolean isCancelled = new AtomicBoolean(false);
            SwingUtilities.invokeLater(() -> {
                dialog.set(view.createLoadingDialog(
                        "Đang chuẩn bị file: " + filePath + "...\nFile lớn có thể mất nhiều thời gian", () -> {
                            isCancelled.set(true);
                        }));
                dialog.get().setVisible(true);
            });
            File file = new File(filePath);
            boolean res = GetDir.copyFileToShare(file, isCancelled);
            if (!res) {
                SwingUtilities.invokeLater(() -> {
                    view.showMessage("Lỗi khi sao chép file vào thư mục chia sẻ.", true);
                    view.appendLog("Lỗi khi sao chép file vào thư mục chia sẻ.");
                });
                return;
            }
            SwingUtilities.invokeLater(() -> dialog.get().dispose());
            if (!file.exists()) {
                SwingUtilities.invokeLater(() -> {
                    view.showMessage("File không tồn tại: " + filePath, true);
                    view.appendLog("File không tồn tại: " + filePath);
                });
                return;
            }
            view.setCancelButtonEnabled(true);
            peerModel.shareFileAsync(file);
            SwingUtilities.invokeLater(() -> {
                view.displayFileInfo(file.getName(), file.length(), Infor.SERVER_IP + ":" + Infor.SERVER_PORT);
                view.appendLog("Đã chia sẻ file: " + filePath);
            });
        });
    }


    private void searchFile() {
        String fileName = view.getFileName();
        if (fileName.isEmpty()) {
            view.showMessage("Vui lòng nhập tên file để tìm kiếm.", true);
            return;
        }

        view.clearTable();
        view.appendLog("Đang tìm kiếm file: " + fileName + "...");

        ExecutorService executor = Executors.newFixedThreadPool(10);
        executor.submit(() -> {
            try {
                List<String> peers = peerModel.queryTracker(fileName);
                if (peers.isEmpty()) {
                    SwingUtilities.invokeLater(() -> view.showMessage("Không tìm thấy file: " + fileName, true));
                    return;
                }

                Map<String, Integer> peerToRowIndex = new HashMap<>();
                int rowIndex = 0;
                for (String peer : peers) {
                    String peerStr = peer.replace("|", ":");
                    PeerInfor peerInfor = parsePeerInfo(peerStr);
                    if (peerInfor == null) {
                        SwingUtilities.invokeLater(() -> view.showMessage("Thông tin peer không hợp lệ: " + peerStr, true));
                        continue;
                    }
                    SwingUtilities.invokeLater(() -> view.getTableModel().addRow(new Object[]{fileName, "Đang tìm kiếm...", peerStr}));
                    peerToRowIndex.put(peerStr, rowIndex++);

                    executor.submit(() -> queryPeerForFile(fileName, peerInfor, peerToRowIndex.get(peerStr)));
                }
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> view.showMessage("Lỗi khi tìm kiếm file: " + e.getMessage(), true));
                e.printStackTrace();
            } finally {
                shutdownExecutor(executor);
            }
        });
    }

    private void queryPeerForFile(String fileName, PeerInfor peerInfor, int index) {
        try {
            Future<FileInfor> result = peerModel.getFileInforFromPeers(new PeerInfor(peerInfor.getIp(), peerInfor.getPort()), fileName);
            FileInfor fileInfo = result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (fileInfo != null && fileInfo.getFileSize() >= 0) {
                String formatSize = formatFileSize(fileInfo.getFileSize());
                SwingUtilities.invokeLater(() -> {
                    view.appendLog("Đã tìm thấy file: " + fileName + " trên peer: " + peerInfor.getIp() + ":" + peerInfor.getPort());
                    view.getTableModel().setValueAt(formatSize, index, 1);
                });
            } else {
                updateTableWithError(index, "Không tìm thấy file");
            }
        } catch (TimeoutException e) {
            updateTableWithError(index, "Peer không phản hồi");
        } catch (Exception e) {
            updateTableWithError(index, "Lỗi khi truy vấn");
        }
    }

    private void updateTableWithError(int index, String errorMessage) {
        SwingUtilities.invokeLater(() -> {
            view.appendLog(errorMessage);
            view.getTableModel().setValueAt(errorMessage, index, 1);
        });
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
        int selected = view.getFileTable().getSelectedRow();
        String fileName = "";
        String peerInfor = "";
        if (selected != -1) {
            fileName = (String) view.getTableModel().getValueAt(selected, 0);
            peerInfor = (String) view.getTableModel().getValueAt(selected, 2);
        }

        if (fileName.isEmpty()) {
            view.appendLog("Tên file không hợp lệ.");
            view.showMessage("File không hợp lệ.", true);
            return;
        }

        String savePath = view.openFileChooserForDownload(fileName);
        if (savePath.isEmpty() || savePath.equals(LogTag.S_CANCELLED) || savePath.equals(LogTag.S_ERROR)) {
            view.appendLog("Đường dẫn lưu không hợp lệ.");
            view.showMessage("Vui lòng chọn đường dẫn lưu hợp lệ.", true);
            return;
        }

        PeerInfor peer = parsePeerInfo(peerInfor);
        if (peer == null) {
            view.showMessage("Thông tin peer không hợp lệ.", true);
            return;
        }

        try {
            view.setCancelButtonEnabled(true);
            Future<Integer> result = peerModel.downloadFile(fileName, savePath, peer);
            view.appendLog("Đã bắt đầu tải file: " + fileName);

            int status = result.get();
            handleDownloadResult(status, fileName, peerInfor);
        } catch (Exception e) {
            logError("Lỗi khi tải file: " + e.getMessage(), e);
            view.showMessage("Lỗi khi tải file: " + e.getMessage(), true);
        }
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

    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}