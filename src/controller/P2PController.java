package controller;

import model.FileBase;
import model.FileInfor;
import model.PeerModel;
import utils.GetDir;
import utils.LogTag;
import view.P2PView;

import javax.swing.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;


public class P2PController {
    private PeerModel peerModel;
    private P2PView view;

    public P2PController(PeerModel peerModel, P2PView view) {
        this.peerModel = peerModel;
        this.view = view;
        setupListeners();
    }

    public void start() {
        // Hiển thị giao diện trước để đảm bảo người dùng thấy UI
        System.out.println("Hiển thị giao diện Swing...");
        view.setVisible(true);
        view.displayMessage("Khởi động hệ thống P2P...");


        ExecutorService executor = Executors.newCachedThreadPool();
        executor.submit(() -> {
            try {
                System.out.println("Bắt đầu khởi động server...");
                peerModel.startServer();
                System.out.println("Server đã khởi động.");
                view.displayMessage("Server đã khởi động.");
            } catch (Exception e) {
                System.err.println("Lỗi khi khởi động server: " + e.getMessage());
                e.printStackTrace();
                view.displayMessage("Lỗi khi khởi động server: " + e.getMessage());
            }

            try {
                System.out.println("Đang khởi động UDP Server...");
                peerModel.startUDPServer();
                System.out.println("UDP Server đã khởi động.");
                view.displayMessage("UDP Server đã khởi động.");
            } catch (Exception e) {
                System.err.println("Lỗi khi khởi động UDP Server: " + e.getMessage());
                e.printStackTrace();
                view.displayMessage("Lỗi khi khởi động UDP Server: " + e.getMessage());
            }
            System.out.println("Đang đăng ký với tracker...");
            int result = peerModel.registerWithTracker();

            if (result == LogTag.I_SUCCESS) {
                System.out.println("Đăng ký thành công với tracker.");
                view.displayMessage("Đăng ký thành công với tracker.");
            } else if (result == LogTag.I_NOT_FOUND) {
                System.out.println("Đăng ký thành công với tracker nhưng không có file chia sẻ.");
                view.displayMessage("Đăng ký thành công với tracker nhưng không có file chia sẻ.");
            } else if (result == LogTag.I_ERROR) {
                System.err.println("Lỗi khi đăng ký với tracker.");
                view.displayMessage("Lỗi khi đăng ký với tracker.");
                return; // Dừng khởi động nếu không đăng ký được
            } else {
                System.err.println("Đăng ký với tracker không thành công.");
                view.displayMessage("Đăng ký với tracker không thành công.");
                return; // Dừng khởi động nếu không đăng ký được
            }
            System.out.println("Đã đăng ký với tracker.");
            view.displayMessage("Đã đăng ký với tracker.");

            System.out.println("Hệ thống P2P đã khởi động hoàn tất.");
            view.displayMessage("Hệ thống P2P đã khởi động hoàn tất.");

            showFile();

            peerModel.shareFileList();
        });
    }

    private void showFile() {
        Set<FileBase> sharedFiles = peerModel.getSharedFileNames();

        for (Map.Entry<String, FileInfor> entry : peerModel.getMySharedFiles().entrySet()) {
            FileInfor fileInfo = entry.getValue();
            boolean exists = sharedFiles.stream()
                    .anyMatch(file -> file.getFileName().equals(fileInfo.getFileName()) &&
                            file.getPeerInfor().equals(fileInfo.getPeerInfor().getIp() + ":" + fileInfo.getPeerInfor().getPort()));

            if (!exists) {
                if (fileInfo.getFileName() == null || fileInfo.getFileName().isEmpty()) {
                    view.displayMessage("Tên file không hợp lệ: " + fileInfo.getFileName());
                    continue;
                }
                if (fileInfo.getPeerInfor() == null || fileInfo.getPeerInfor().getIp() == null || fileInfo.getPeerInfor().getPort() <= 0) {
                    view.displayMessage("Thông tin peer không hợp lệ cho file: " + fileInfo.getFileName());
                    continue;
                }
                sharedFiles.add(new FileBase(fileInfo.getFileName(), fileInfo.getFileSize(), fileInfo.getPeerInfor()));
            }

            peerModel.setSharedFileNames(sharedFiles);
            view.displayData(peerModel.getSharedFileNames());
        }
    }

    private void setupListeners() {
        view.setSearchButtonListener(this::searchFile);
        view.setChooseFileButtonListener(this::shareFile);
        view.setChooseDownload(this::clickMenuItem);
        view.setMyFilesButtonListener(this::showMyFiles);
        view.setAllFilesButtonListener(this::showAlliles);
        this.setChooseFileForDownload();
    }

    private void clickMenuItem() {
        int selected = view.getFileTable().getSelectedRow();
        if (selected == -1) {
            view.displayMessage("Vui lòng chọn một file.");
            return;
        }
        String fileName = (String) view.getTableModel().getValueAt(selected, 0);
        String peerInfor = (String) view.getTableModel().getValueAt(selected, 2);

        if (fileName == null || fileName.isEmpty() || peerInfor == null || peerInfor.isEmpty()) {
            view.displayMessage("Tên file hoặc thông tin peer không hợp lệ.");
            return;
        }

        String[] peerParts = peerInfor.split(":");
        if (peerParts.length != 2) {
            view.displayMessage("Thông tin peer không hợp lệ.");
            return;
        }

        String peerIp = peerParts[0];
        int peerPort;
        try {
            peerPort = Integer.parseInt(peerParts[1]);
        } catch (NumberFormatException e) {
            view.displayMessage("Cổng peer không hợp lệ.");
            return;
        }

        if (!peerModel.isMe(peerIp, peerPort)) {
            downloadFile();
        } else {
            boolean isConfirm = view.showConfirmation(
                    "Bạn có chắc chắn muốn dừng chia sẻ file: " + fileName + " không?");
            if (isConfirm) {
                ;
                try {
                    peerModel.stopSharingFile(fileName);
                    view.displayMessage("Đã dừng chia sẻ file: " + fileName);
                    view.removeFileFromView(fileName, peerInfor);
                } catch (Exception e) {
                    view.displayMessage("Lỗi khi dừng chia sẻ file: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                view.displayMessage("Dừng chia sẻ file đã bị hủy.");
            }
        }
    }

    private void showAlliles() {
        view.clearTable();
        Set<FileBase> sharedFiles = peerModel.getSharedFileNames();

        if (sharedFiles.isEmpty()) {
            view.displayMessage("Không có file nào được chia sẻ.");
            return;
        }

        view.displayData(sharedFiles);
        view.displayMessage("Đã cập nhật danh sách file chia sẻ.");
    }

    private void setChooseFileForDownload() {
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
                            String[] peerParts = peerInfor.split(":");
                            if (peerParts.length == 2) {
                                String peerIp = peerParts[0];
                                int peerPort = Integer.parseInt(peerParts[1]);
                                view.showMenu(!peerModel.isMe(peerIp, peerPort));
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
            view.displayMessage("Không có file nào được chia sẻ.");
            return;
        }
        for (Map.Entry<String, FileInfor> entry : sharedFiles.entrySet()) {
            FileInfor fileInfo = entry.getValue();
            view.displayFileInfo(fileInfo.getFileName(), fileInfo.getFileSize(), fileInfo.getPeerInfor().getIp() + ":" + fileInfo.getPeerInfor().getPort());
        }
        view.displayMessage("Đã cập nhật danh sách file chia sẻ.");
    }

    private void shareFile() {
        String fileName = view.openFileChooserForShare();

        if (fileName.isEmpty() || fileName.equals(LogTag.S_CANCELLED) || fileName.equals(LogTag.S_ERROR)) {
            view.displayMessage("Vui lòng chọn file file.");
            return;
        }
        String filePath = GetDir.getDir() + "\\shared_files\\" + fileName;
        File file = new File(filePath);
        if (!file.exists()) {
            view.showMessage("File không tồn tại: " + filePath, true);
            return;
        }
        peerModel.shareFile(filePath);
        view.displayMessage("Đã chia sẻ file: " + filePath);
    }

    private void searchFile() {
        String fileName = view.getFileName();
        if (fileName.isEmpty()) {
            view.displayMessage("Vui lòng nhập tên file.");
            return;
        }
        try {
            List<String> peers = peerModel.queryTracker(fileName);
            view.clearTable();
            if (peers.isEmpty()) {
                view.displayMessage("Không tìm thấy file: " + fileName);
            } else {
                FileInfor fileInfo = peerModel.getFileInforFromPeers(peers.get(0), fileName);
                if (fileInfo != null) {
                    for (String peer : peers) {
                        view.getTableModel().addRow(new Object[]{fileInfo.getFileName(), fileInfo.getFileSize(), peer});
                    }
                    view.displayMessage("Đã tìm thấy file: " + fileName);
                } else {
                    view.displayMessage("Không lấy được thông tin file.");
                }
            }
        } catch (IOException e) {
            view.displayMessage("Lỗi khi tìm kiếm: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void downloadFile() {
        int selected = view.getFileTable().getSelectedRow();
        String fileName = "";
        if (selected != -1) {
            fileName = (String) view.getTableModel().getValueAt(selected, 0);
        }

        if (fileName == null || fileName.isEmpty()) {
            view.displayMessage("Tên file không hợp lệ.");
            view.showMessage("File không hợp lệ.", true);
            return;
        }

        String savePath = view.openFileChooserForDownload(fileName);
        if (savePath.isEmpty() || savePath.equals(LogTag.S_CANCELLED) || savePath.equals(LogTag.S_ERROR)) {
            view.displayMessage("Đường dẫn lưu không hợp lệ.");
            view.showMessage("Vui lòng chọn đường dẫn lưu hợp lệ.", true);
            return;
        }
        try {
            Future<Integer> result = peerModel.downloadFile(fileName, savePath);
            view.displayMessage("Đã bắt đầu tải file: " + fileName);

            try {
                int status = result.get();
                if (status == LogTag.I_SUCCESS) {
                    view.displayMessage("Tải file thành công: " + fileName);
                    view.showMessage("Tải file thành công: " + fileName, false);
                } else if (status == LogTag.I_NOT_FOUND) {
                    String errorMessage = "File không tìm thấy trên bất kỳ peer nào. Vui lòng làm mới và thử lại.";
                    view.showMessage(errorMessage, true);
                } else {
                    view.displayMessage("Lỗi khi tải file: " + fileName);
                    view.showMessage("Lỗi khi tải file: " + fileName, true);
                }
            } catch (Exception e) {
                view.displayMessage("Lỗi khi chờ kết quả tải file: " + e.getMessage());
                e.printStackTrace();
            }
        } catch (Exception e) {
            view.displayMessage("Lỗi khi tải file: " + e.getMessage());
            view.showMessage("Lỗi khi tải file: " + e.getMessage(), true);
            e.printStackTrace();
        }
    }
}