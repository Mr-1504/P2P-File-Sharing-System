package view;

import model.FileBase;
import utils.LogTag;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

public class P2PView extends JFrame {
    private final JTextArea logArea;
    private final JTextField fileNameField;
    private final JPopupMenu popupMenu;
    private final JMenuItem downloadItem;
    private final JTable fileTable;
    private final DefaultTableModel tableModel;
    private final JButton chooseFileButton;
    private final JButton searchButton;
    private final JButton myFilesButton;
    private final JButton refreshButton;
    private final JButton allFilesButton;

    public P2PView() {
        setTitle("Hệ thống chia sẻ file P2P");
        setSize(600, 400);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Panel nhập liệu
        JPanel inputPanel = new JPanel(new GridLayout(4, 2, 5, 5));
        inputPanel.add(new JLabel("Chọn file để chia sẻ:"));
        chooseFileButton = new JButton("Chọn file");
        inputPanel.add(chooseFileButton);
        inputPanel.add(new JLabel("Tên file để tìm kiếm:"));
        fileNameField = new JTextField();
        inputPanel.add(fileNameField);
        searchButton = new JButton("Tìm kiếm");
        myFilesButton = new JButton("File của tôi");
        refreshButton = new JButton("Làm mới");
        allFilesButton = new JButton("Tất cả file");
        JPanel buttonPanel = new JPanel();
        buttonPanel.add(searchButton);
        buttonPanel.add(myFilesButton);
        buttonPanel.add(allFilesButton);
        buttonPanel.add(refreshButton);
        inputPanel.add(buttonPanel);

        add(inputPanel, BorderLayout.NORTH);

        // Bảng hiển thị file và peer
        tableModel = new DefaultTableModel(new String[]{"Tên file", "Kích thước", "Peers"}, 0);
        fileTable = new JTable(tableModel);
        JScrollPane tableScroll = new JScrollPane(fileTable);
        add(tableScroll, BorderLayout.CENTER);

        // Log area
        logArea = new JTextArea(5, 30);
        logArea.setEditable(false);
        JScrollPane logScroll = new JScrollPane(logArea);
        add(logScroll, BorderLayout.SOUTH);

        // menu
        popupMenu = new JPopupMenu();
        downloadItem = new JMenuItem("Tải xuống");
        popupMenu.add(downloadItem);

    }

    public String openFileChooserForShare() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setCurrentDirectory(new File(System.getProperty("user.home")));
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            try {
                // Sao chép file vào shared_files
                Path sharedDir = Paths.get("shared_files");
                if (!Files.exists(sharedDir)) {
                    Files.createDirectories(sharedDir);
                }
                Path destPath = sharedDir.resolve(selectedFile.getName());
                Files.copy(selectedFile.toPath(), destPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                // Chia sẻ file từ shared_files
                appendLog("Chia sẻ file: " + selectedFile.getName() + " (đã sao chép vào shared_files)");
                return selectedFile.getName();
            } catch (IOException e) {
                String errorMessage = "Lỗi khi sao chép hoặc chia sẻ file: " + e.getMessage();
                appendLog(errorMessage);
                JOptionPane.showMessageDialog(this, errorMessage, "Lỗi", JOptionPane.ERROR_MESSAGE);
                return LogTag.ERROR;
            }
        }

        return LogTag.CANCELLED;
    }


    public String openFileChooserForDownload(String fileName) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setCurrentDirectory(new File(System.getProperty("user.home")));
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fileChooser.setSelectedFile(new File(fileName));
        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File saveFile = fileChooser.getSelectedFile();
            try {
                appendLog("Bắt đầu tải file: " + fileName + " vào " + saveFile.getAbsolutePath());
                return saveFile.getAbsolutePath();
            } catch (Exception e) {
                String errorMessage = "Lỗi khi tải file: " + e.getMessage();
                appendLog(errorMessage);
                JOptionPane.showMessageDialog(this, errorMessage, "Lỗi", JOptionPane.ERROR_MESSAGE);
                return LogTag.ERROR;
            }
        }
        return LogTag.CANCELLED;
    }

    public void appendLog(String message) {
        logArea.append(message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    public String getFileName() {
        return fileNameField.getText();
    }

    public void setSearchButtonListener(Runnable listener) {
        searchButton.addActionListener(e -> listener.run());
    }

    public void setChooseDownload(Runnable listener) {
        downloadItem.addActionListener(e -> listener.run());
    }
    public void setMyFilesButtonListener(Runnable listener) {
        myFilesButton.addActionListener(e -> listener.run());
    }
    public void setAllFilesButtonListener(Runnable listener) {
        allFilesButton.addActionListener(e -> listener.run());
    }
    public void setRefreshButtonListener(Runnable listener) {
        refreshButton.addActionListener(e -> listener.run());
    }

    public void setChooseFileButtonListener(Runnable listener) {
        chooseFileButton.addActionListener(e -> listener.run());
    }

    public void displayMessage(String message) {
        logArea.append(message + "\n");
    }

    public void displayFileInfo(String fileName, long fileSize, String peer) {
        tableModel.addRow(new Object[]{fileName, fileSize, peer});
    }

    public void clearTable() {
        tableModel.setRowCount(0);
    }

    public void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Lỗi", JOptionPane.ERROR_MESSAGE);
        appendLog(message);
    }

    public void showMenu() {
        popupMenu.show(fileTable, fileTable.getMousePosition().x, fileTable.getMousePosition().y);
    }

    public void setRowSelectionInterval(int row, int row1) {
        fileTable.setRowSelectionInterval(row, row1);
    }

    public JTable getFileTable() {
        return fileTable;
    }

    public DefaultTableModel getTableModel() {
        return tableModel;
    }

    public void displayData(Set<FileBase> sharedFileNames) {
        clearTable();
        for (FileBase file : sharedFileNames) {
            displayFileInfo(file.getFileName(), file.getFileSize(), file.getPeerInfor().getIp() + ":" + file.getPeerInfor().getPort());
        }
        appendLog("Đã cập nhật danh sách file chia sẻ.");
    }
}