package view;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

public class P2PView extends JFrame {
    private JTextField filePathField;
    private JTextField fileNameField;
    private JTextField savePathField;
    private JTextArea logArea;
    private JTable fileTable;
    private DefaultTableModel tableModel;
    private JButton shareButton;
    private JButton searchButton;
    private JButton downloadButton;

    public P2PView() {
        setTitle("Hệ thống chia sẻ file P2P");
        setSize(600, 400);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Panel nhập liệu
        JPanel inputPanel = new JPanel(new GridLayout(4, 2, 5, 5));
        inputPanel.add(new JLabel("Đường dẫn file để chia sẻ:"));
        filePathField = new JTextField();
        inputPanel.add(filePathField);
        inputPanel.add(new JLabel("Tên file để tìm kiếm:"));
        fileNameField = new JTextField();
        inputPanel.add(fileNameField);
        inputPanel.add(new JLabel("Đường dẫn lưu file:"));
        savePathField = new JTextField();
        inputPanel.add(savePathField);

        shareButton = new JButton("Chia sẻ");
        searchButton = new JButton("Tìm kiếm");
        downloadButton = new JButton("Tải xuống");
        JPanel buttonPanel = new JPanel();
        buttonPanel.add(shareButton);
        buttonPanel.add(searchButton);
        buttonPanel.add(downloadButton);
        inputPanel.add(buttonPanel);

        add(inputPanel, BorderLayout.NORTH);

        // Bảng hiển thị file và peer
        tableModel = new DefaultTableModel(new String[]{"Tên file", "Kích thước", "Số chunk", "Peers"}, 0);
        fileTable = new JTable(tableModel);
        JScrollPane tableScroll = new JScrollPane(fileTable);
        add(tableScroll, BorderLayout.CENTER);

        // Log area
        logArea = new JTextArea(5, 30);
        logArea.setEditable(false);
        JScrollPane logScroll = new JScrollPane(logArea);
        add(logScroll, BorderLayout.SOUTH);
    }

    public String getFilePath() {
        return filePathField.getText();
    }

    public String getFileName() {
        return fileNameField.getText();
    }

    public String getSavePath() {
        return savePathField.getText();
    }

    public void setShareButtonListener(Runnable listener) {
        shareButton.addActionListener(e -> listener.run());
    }

    public void setSearchButtonListener(Runnable listener) {
        searchButton.addActionListener(e -> listener.run());
    }

    public void setDownloadButtonListener(Runnable listener) {
        downloadButton.addActionListener(e -> listener.run());
    }

    public void displayMessage(String message) {
        logArea.append(message + "\n");
    }

    public void displayFileInfo(String fileName, long fileSize, int chunkCount, List<String> peers) {
        tableModel.addRow(new Object[]{fileName, fileSize, chunkCount, String.join(",", peers)});
    }

    public void clearTable() {
        tableModel.setRowCount(0);
    }
}