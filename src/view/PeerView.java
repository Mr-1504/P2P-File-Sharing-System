package view;

import model.FileInfor;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class PeerView {
    private JFrame frame;
    private JTextField searchField;
    private JList<String> fileList;
    private JList<String> peerList;
    private JButton searchButton;
    private JButton downloadButton;
    private JButton refreshPeerButton;
    private JLabel statusLabel;
    private List<FileInfor> fileData = new ArrayList<>();
    private DefaultListModel<String> fileListModel;
    private DefaultListModel<String> peerListModel;

    public PeerView() {
        frame = new JFrame("P2P File Sharing");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(400, 600);
        frame.setLayout(new BorderLayout(10, 10));

        // Bắc: Tìm kiếm
        JPanel searchPanel = new JPanel(new BorderLayout(5, 5));
        searchPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        searchField = new JTextField();
        searchField.setToolTipText("Nhập tên file để tìm kiếm");
        searchButton = new JButton("Tìm kiếm");
        searchPanel.add(new JLabel("Tìm kiếm file:"), BorderLayout.NORTH);
        searchPanel.add(searchField, BorderLayout.CENTER);
        searchPanel.add(searchButton, BorderLayout.EAST);

        // Trung: Danh sách file và peer
        JPanel centerPanel = new JPanel(new GridLayout(2, 1, 10, 10));
        fileListModel = new DefaultListModel<>();
        fileList = new JList<>(fileListModel);
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane fileScrollPane = new JScrollPane(fileList);
        centerPanel.add(new JLabel("Danh sách file:"));
        centerPanel.add(fileScrollPane);

        peerListModel = new DefaultListModel<>();
        peerList = new JList<>(peerListModel);
        peerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane peerScrollPane = new JScrollPane(peerList);
        centerPanel.add(new JLabel("Danh sách peer:"));
        centerPanel.add(peerScrollPane);

        // Nam: Tải xuống, làm mới peer và trạng thái
        JPanel southPanel = new JPanel(new BorderLayout(5, 5));
        southPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        downloadButton = new JButton("Tải xuống");
        refreshPeerButton = new JButton("Làm mới peer");
        statusLabel = new JLabel("Trạng thái: Sẵn sàng");
        JPanel buttonPanel = new JPanel(new GridLayout(2, 1, 5, 5));
        buttonPanel.add(downloadButton);
        buttonPanel.add(refreshPeerButton);
        southPanel.add(buttonPanel, BorderLayout.NORTH);
        southPanel.add(statusLabel, BorderLayout.CENTER);

        frame.add(searchPanel, BorderLayout.NORTH);
        frame.add(centerPanel, BorderLayout.CENTER);
        frame.add(southPanel, BorderLayout.SOUTH);
    }

    public void show() {
        frame.setVisible(true);
    }

    public void updateFileList(List<FileInfor> files) {
        fileData.clear(); // Xóa dữ liệu file cũ
        fileListModel.clear(); // Xóa danh sách hiển thị cũ

        files.forEach(file -> {
            fileData.add(file); // Lưu file vào danh sách thực
            String displayText = file.getFileName() + " (Peer: " +
                    file.getPeerInfor().getHost() + ":" + file.getPeerInfor().getPort() + ")";
            fileListModel.addElement(displayText); // Hiển thị chuỗi thông tin
        });
    }

    public void updatePeerList(List<String> peers) {
        peerListModel.clear();
        peers.forEach(peerListModel::addElement);
    }

    public void setStatus(String status) {
        statusLabel.setText("Trạng thái: " + status);
    }

    public String getSearchQuery() {
        return searchField.getText();
    }

    public FileInfor getSelectedFile() {
        int selectedIndex = fileList.getSelectedIndex();
        if (selectedIndex >= 0 && selectedIndex < fileData.size()) {
            return fileData.get(selectedIndex);
        }
        return null;
    }

    public void setSearchAction(Runnable action) {
        searchButton.addActionListener(e -> action.run());
    }

    public void setDownloadAction(Runnable action) {
        downloadButton.addActionListener(e -> action.run());
    }

    public void setRefreshPeerAction(Runnable action) {
        refreshPeerButton.addActionListener(e -> action.run());
    }

    public void close() {
        frame.dispose();
    }
}