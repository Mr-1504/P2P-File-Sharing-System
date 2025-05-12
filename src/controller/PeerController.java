package controller;

import model.PeerInfor;
import model.PeerModel;
import view.PeerView;

import javax.swing.*;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Collectors;

public class PeerController {
    private PeerModel model;
    private PeerView view;

    public PeerController(PeerModel model, PeerView view) {
        this.model = model;
        this.view = view;

        view.setSearchAction(this::handleSearch);
        view.setDownloadAction(this::handleDownload);
        view.setRefreshPeerAction(this::updatePeerList);

        updatePeerList();

        // Cập nhật danh sách peer định kỳ (mỗi 10 giây)
        new Timer(10000, e -> updatePeerList()).start();
    }

    private void handleSearch() {
        String query = view.getSearchQuery();
        if (query.isEmpty()) {
            view.setStatus("Vui lòng nhập tên file");
            return;
        }
        try {
            view.setStatus("Đang tìm kiếm...");
            List<String> files = model.searchFile(query);
            view.updateFileList(files);
            view.setStatus("Tìm kiếm hoàn tất");
        } catch (IOException e) {
            view.setStatus("Lỗi khi tìm kiếm: " + e.getMessage());
        }
    }

    private void handleDownload() {
        String selectedFile = view.getSelectedFile();
        if (selectedFile == null) {
            view.setStatus("Vui lòng chọn file");
            return;
        }
        try {
            view.setStatus("Đang tải " + selectedFile + "...");
            model.downloadFile(selectedFile, model.getPeerList());
            view.setStatus("Tải xuống hoàn tất");
        } catch (IOException | NoSuchAlgorithmException e) {
            view.setStatus("Lỗi khi tải: " + e.getMessage());
        }
    }

    private void updatePeerList() {
        view.updatePeerList(model.getPeerList().stream()
                .map(PeerInfor::toString)
                .collect(Collectors.toList()));
        view.setStatus("Danh sách peer đã cập nhật");
    }
}