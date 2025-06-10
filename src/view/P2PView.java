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
    private final JMenuItem menuItem;
    private final JTable fileTable;
    private final DefaultTableModel tableModel;
    private final JButton chooseFileButton;
    private final JButton searchButton;
    private final JButton myFilesButton;
    private final JButton refreshButton;
    private final JButton allFilesButton;

    public P2PView() {
        String path = System.getProperty("user.dir");
        File file = new File(path);
        String projectName = file.getName();
        setTitle("Hệ thống chia sẻ file P2P - " + projectName);
        setSize(800, 500); // Tăng kích thước cửa sổ để có thêm không gian
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Panel nhập liệu
        JPanel inputPanel = new JPanel();
        inputPanel.setLayout(new BoxLayout(inputPanel, BoxLayout.Y_AXIS));
        inputPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); // Margin 10px hai bên và trên dưới

        // Panel cho chọn file
        JPanel filePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        filePanel.add(new JLabel("Chọn file để chia sẻ:"));
        chooseFileButton = new JButton("Chọn file");
        filePanel.add(chooseFileButton);
        inputPanel.add(filePanel);

        // Panel cho tìm kiếm
        JPanel searchPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 1, 5, 5); // Khoảng cách giữa các thành phần
        gbc.anchor = GridBagConstraints.WEST; // Căn lề trái
        gbc.gridx = 0;
        gbc.gridy = 0;
        searchPanel.add(new JLabel("Tên file để tìm kiếm:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0; // Cho phép ô nhập văn bản co giãn theo chiều ngang
        gbc.fill = GridBagConstraints.HORIZONTAL; // Điền đầy theo chiều ngang
        fileNameField = new JTextField(30); // Ô nhập văn bản với 30 cột
        fileNameField.setToolTipText("Nhập tên file để tìm kiếm trong hệ thống P2P");
        searchPanel.add(fileNameField, gbc);
        inputPanel.add(searchPanel);

        // Panel cho các nút
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5)); // Khoảng cách giữa các nút
        searchButton = new JButton("Tìm kiếm");
        myFilesButton = new JButton("File của tôi");
        refreshButton = new JButton("Làm mới");
        allFilesButton = new JButton("Tất cả file");
        buttonPanel.add(searchButton);
        buttonPanel.add(myFilesButton);
        buttonPanel.add(allFilesButton);
        buttonPanel.add(refreshButton);
        inputPanel.add(buttonPanel);

        add(inputPanel, BorderLayout.NORTH);

        // Bảng hiển thị file và peer
        tableModel = new DefaultTableModel(new String[]{"Tên file", "Kích thước", "Peers"}, 0);
        fileTable = new JTable(tableModel);
        fileTable.setRowHeight(30); // Tăng chiều cao hàng lên 30px
        fileTable.setGridColor(Color.LIGHT_GRAY); // Thêm viền lưới
        fileTable.setShowGrid(true); // Hiển thị lưới
        fileTable.getColumnModel().getColumn(0).setPreferredWidth(200); // Cột Tên file
        fileTable.getColumnModel().getColumn(1).setPreferredWidth(100); // Cột Kích thước
        fileTable.getColumnModel().getColumn(2).setPreferredWidth(150); // Cột Peers
        fileTable.setToolTipText("Danh sách các file chia sẻ trong hệ thống P2P");
        JScrollPane tableScroll = new JScrollPane(fileTable);
        add(tableScroll, BorderLayout.CENTER);

        // Log area
        logArea = new JTextArea(5, 30);
        logArea.setEditable(false);
        JScrollPane logScroll = new JScrollPane(logArea);
        add(logScroll, BorderLayout.SOUTH);

        // Menu
        popupMenu = new JPopupMenu();
        menuItem = new JMenuItem("Tải xuống");
        popupMenu.add(menuItem);
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
                return LogTag.S_ERROR;
            }
        }

        return LogTag.S_CANCELLED;
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
                return LogTag.S_ERROR;
            }
        }
        return LogTag.S_CANCELLED;
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
        menuItem.addActionListener(e -> listener.run());
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

    public void displayFileInfo(String fileName, long fileSize, String peer) {
        double size = fileSize / (1024.0 * 1024.0);
        String formatSize;
        if (size < 1) {
            size = (double) fileSize / 1024;
            size = Math.round(size * 100.0) / 100.0;
            formatSize = size + " KB";
        } else {
            size = Math.round(size * 100.0) / 100.0;
            formatSize = size + " MB";
        }
        tableModel.addRow(new Object[]{fileName, formatSize, peer});
    }

    public void clearTable() {
        tableModel.setRowCount(0);
    }

    public void showMessage(String message, boolean isError) {
        JOptionPane.showMessageDialog(this, message, "Lỗi",
                isError ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
        appendLog(message);
    }

    public void showMenu(boolean isDownload) {
        String menuText = isDownload ? "Tải xuống" : "Dừng chia sẻ";
        menuItem.setText(menuText);
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

    public boolean showConfirmation(String message) {
        int result = JOptionPane.showConfirmDialog(
                this, message, "Xác nhận", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        return result == JOptionPane.YES_OPTION;
    }

    public void removeFileFromView(String fileName, String peerInfor) {
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            String currentFileName = (String) tableModel.getValueAt(i, 0);
            String currentPeerInfor = (String) tableModel.getValueAt(i, 2);
            if (currentFileName.equals(fileName) && currentPeerInfor.equals(peerInfor)) {
                tableModel.removeRow(i);
                appendLog("Đã xóa file: " + fileName + " khỏi danh sách chia sẻ.");
                return;
            }
        }
        appendLog("Không tìm thấy file: " + fileName + " để xóa.");
    }
}