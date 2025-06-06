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

            if (result == LogTag.SUCCESS) {
                System.out.println("Đăng ký thành công với tracker.");
                view.displayMessage("Đăng ký thành công với tracker.");

                Set<FileBase> sharedFiles = peerModel.getSharedFileNames();

                for(Map.Entry<String, FileInfor> entry : peerModel.getMySharedFiles().entrySet()) {
                    FileInfor fileInfo = entry.getValue();
                    sharedFiles.add(new FileBase(fileInfo.getFileName(), fileInfo.getFileSize(), fileInfo.getPeerInfor()));
                }

                peerModel.setSharedFileNames(sharedFiles);

                view.displayData(peerModel.getSharedFileNames());
            } else if (result == LogTag.NOT_FOUND) {
                System.out.println("Đăng ký thành công với tracker nhưng không có file chia sẻ.");
                view.displayMessage("Đăng ký thành công với tracker nhưng không có file chia sẻ.");
            } else if (result == LogTag.iERROR) {
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

            peerModel.shareFileList();
        });
    }

    private void setupListeners() {
        view.setSearchButtonListener(this::searchFile);
        view.setChooseFileButtonListener(this::shareFile);
        view.setChooseDownload(this::downloadFile);
        view.setMyFilesButtonListener(this::showMyFiles);
        view.setAllFilesButtonListener(this::showAlliles);
        this.setChooseFileForDownload();
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
                        if (!fileName.isEmpty()) {
                            view.appendLog("Đã chọn file: " + fileName + " để tải xuống.");
                            view.showMenu();
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

        if (fileName.isEmpty() || fileName.equals(LogTag.CANCELLED) || fileName.equals(LogTag.ERROR)) {
            view.displayMessage("Vui lòng chọn file file.");
            return;
        }
        String filePath = GetDir.getDir() + "\\shared_files\\" + fileName;
        File file = new File(filePath);
        if (!file.exists()) {
            view.showError("File không tồn tại: " + filePath);
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
            view.showError("Vui lòng chọn file hợp lệ.");
            return;
        }

        String savePath = view.openFileChooserForDownload(fileName);
        if (savePath.isEmpty() || savePath.equals(LogTag.CANCELLED) || savePath.equals(LogTag.ERROR)) {
            view.displayMessage("Đường dẫn lưu không hợp lệ.");
            view.showError("Vui lòng chọn đường dẫn lưu hợp lệ.");
            return;
        }
        try {
            peerModel.downloadFile(fileName, savePath);
            view.displayMessage("Đã bắt đầu tải file: " + fileName);
        } catch (Exception e) {
            view.displayMessage("Lỗi khi tải file: " + e.getMessage());
            e.printStackTrace();
        }
    }
}