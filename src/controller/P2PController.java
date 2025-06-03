package controller;

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

        System.out.println("Đang đăng ký với tracker...");
        peerModel.registerWithTracker();
        System.out.println("Đã đăng ký với tracker.");
        view.displayMessage("Đã đăng ký với tracker.");

        try {
            System.out.println("Đang khám phá peer...");
            peerModel.discoverPeers();
            System.out.println("Đã khám phá peer.");
            view.displayMessage("Đã khám phá peer.");
        } catch (Exception e) {
            System.err.println("Lỗi khi khám phá peer: " + e.getMessage());
            e.printStackTrace();
            view.displayMessage("Lỗi khi khám phá peer: " + e.getMessage());
        }

        System.out.println("Hệ thống P2P đã khởi động hoàn tất.");
        view.displayMessage("Hệ thống P2P đã khởi động hoàn tất.");
    }

    private void setupListeners() {
        view.setSearchButtonListener(this::searchFile);
        view.setChooseFileButtonListener(this::shareFile);
        view.setChooseDownload(this::downloadFile);
        this.setChooseFileForDownload();
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
                    view.displayFileInfo(fileInfo.getFileName(), fileInfo.getFileSize(), fileInfo.getChunkHashes().size(), peers);
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