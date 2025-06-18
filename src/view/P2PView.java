package view;

import model.FileBase;
import model.FileInfor;
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
    private final JTextPane logArea;
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
    private final JProgressBar progressBar;
    private final JLabel progressLabel;
    private final JButton cancelButton;
    private Runnable cancelAction;

    public P2PView() {
        // Thiết lập Look and Feel
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        } catch (Exception e) {
            e.printStackTrace();
        }

        String path = System.getProperty("user.dir");
        File file = new File(path);
        String projectName = file.getName();
        setTitle("Hệ thống chia sẻ file P2P - " + projectName);
        setSize(900, 600);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        getContentPane().setBackground(new Color(240, 240, 240));

        // Panel nhập liệu
        JPanel inputPanel = new JPanel();
        inputPanel.setLayout(new BoxLayout(inputPanel, BoxLayout.Y_AXIS));
        inputPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        inputPanel.setBackground(new Color(240, 240, 240));

        // Panel cho chọn file
        JPanel filePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        filePanel.setBackground(new Color(240, 240, 240));
        filePanel.add(new JLabel("<html><b>Chọn file để chia sẻ:</b></html>"));
        chooseFileButton = new JButton("<html><font color='blue'>Chọn file</font></html>");
        filePanel.add(chooseFileButton);
        inputPanel.add(filePanel);

        // Panel cho tìm kiếm
        JPanel searchPanel = new JPanel(new GridBagLayout());
        searchPanel.setBackground(new Color(240, 240, 240));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = 0;
        searchPanel.add(new JLabel("<html><b>Tên file để tìm kiếm:</b></html>"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        fileNameField = new JTextField(30);
        fileNameField.setToolTipText("Nhập tên file để tìm kiếm trong hệ thống P2P");
        searchPanel.add(fileNameField, gbc);
        inputPanel.add(searchPanel);

        // Panel cho các nút
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        buttonPanel.setBackground(new Color(240, 240, 240));
        searchButton = new JButton("<html><font color='blue'><b>Tìm kiếm</b></font></html>");
        myFilesButton = new JButton("<html><font color='green'>File của tôi</font></html>");
        refreshButton = new JButton("<html><font color='orange'>Làm mới</font></html>");
        allFilesButton = new JButton("<html><font color='purple'>Tất cả file</font></html>");
        buttonPanel.add(searchButton);
        buttonPanel.add(myFilesButton);
        buttonPanel.add(allFilesButton);
        buttonPanel.add(refreshButton);
        inputPanel.add(buttonPanel);

        add(inputPanel, BorderLayout.NORTH);

        // Bảng hiển thị file và peer
        tableModel = new DefaultTableModel(new String[]{"Tên file", "Kích thước", "Peers"}, 0);
        tableModel.addRow(new Object[]{"<html>Đang tải danh sách chia sẻ...</html>", "", ""});
        fileTable = new JTable(tableModel);
        fileTable.setRowHeight(30);
        fileTable.setGridColor(Color.LIGHT_GRAY);
        fileTable.setShowGrid(true);
        fileTable.setBackground(Color.WHITE);
        fileTable.getColumnModel().getColumn(0).setPreferredWidth(250);
        fileTable.getColumnModel().getColumn(1).setPreferredWidth(120);
        fileTable.getColumnModel().getColumn(2).setPreferredWidth(180);
        fileTable.setToolTipText("Danh sách các file chia sẻ trong hệ thống P2P");
        JScrollPane tableScroll = new JScrollPane(fileTable);
        tableScroll.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        add(tableScroll, BorderLayout.CENTER);

        // Panel cho log và thanh tiến trình
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBackground(new Color(240, 240, 240));
        logArea = new JTextPane();
        logArea.setContentType("text/html");
        logArea.setEditable(false);
        logArea.setBackground(new Color(245, 245, 245));
        logArea.setText("<html><body style='font-family: Arial; font-size: 12px;'></body></html>");
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        bottomPanel.add(logScroll, BorderLayout.CENTER);

        JPanel progressPanel = new JPanel(new BorderLayout());
        progressPanel.setBackground(new Color(240, 240, 240));
        progressLabel = new JLabel("<html><b>Sẵn sàng</b></html>");
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setValue(0);
        cancelButton = new JButton("<html><font color='red'>Hủy</font></html>");
        cancelButton.setEnabled(false);
        cancelButton.addActionListener(e -> {
            boolean isCancelled = JOptionPane.showConfirmDialog(
                    this, "<html><b>Bạn có chắc chắn muốn hủy tác vụ hiện tại?</b></html>",
                    "Xác nhận hủy",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
            if (isCancelled && cancelAction != null) {
                cancelAction.run();
                cancelButton.setEnabled(false);
                progressError("Tác vụ", "Đã hủy tác vụ");
            }
        });

        JPanel progressBarPanel = new JPanel(new BorderLayout());
        progressBarPanel.setBackground(new Color(240, 240, 240));
        progressBarPanel.add(progressBar, BorderLayout.CENTER);
        progressBarPanel.add(cancelButton, BorderLayout.EAST);
        progressPanel.add(progressLabel, BorderLayout.NORTH);
        progressPanel.add(progressBarPanel, BorderLayout.CENTER);
        progressPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        bottomPanel.add(progressPanel, BorderLayout.SOUTH);

        add(bottomPanel, BorderLayout.SOUTH);

        // Menu ngữ cảnh
        popupMenu = new JPopupMenu();
        menuItem = new JMenuItem("<html><font color='blue'>Tải xuống</font></html>");
        popupMenu.add(menuItem);
    }

    public void setCancelAction(Runnable action) {
        this.cancelAction = action;
        cancelButton.setEnabled(action != null);
    }

    public void setCancelButtonEnabled(boolean enabled) {
        cancelButton.setEnabled(enabled);
    }

    public String openFileChooserForShare() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setCurrentDirectory(new File(System.getProperty("user.home")));
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            String selectedFile = fileChooser.getSelectedFile().getAbsolutePath();
            try {
                Path sharedDir = Paths.get("shared_files");
                if (!Files.exists(sharedDir)) {
                    Files.createDirectories(sharedDir);
                }
                return selectedFile;
            } catch (IOException e) {
                String errorMessage = "Lỗi khi sao chép hoặc chia sẻ file: " + e.getMessage();
                appendLog("<font color='red'>" + errorMessage + "</font>");
                JOptionPane.showMessageDialog(this, "<html><font color='red'>" + errorMessage + "</font></html>",
                        "Lỗi", JOptionPane.ERROR_MESSAGE);
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
                appendLog("<font color='blue'>Bắt đầu tải file: " + fileName + " vào " + saveFile.getAbsolutePath() + "</font>");
                return saveFile.getAbsolutePath();
            } catch (Exception e) {
                String errorMessage = "Lỗi khi tải file: " + e.getMessage();
                appendLog("<font color='red'>" + errorMessage + "</font>");
                JOptionPane.showMessageDialog(this, "<html><font color='red'>" + errorMessage + "</font></html>",
                        "Lỗi", JOptionPane.ERROR_MESSAGE);
                return LogTag.S_ERROR;
            }
        }
        return LogTag.S_CANCELLED;
    }

    public void appendLog(String message) {
        String currentText = logArea.getText();
        String newText = currentText.replace("</body></html>", "<p>" + message + "</p></body></html>");
        logArea.setText(newText);
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

    public JDialog createLoadingDialog(String message, Runnable onCancel) {
        JDialog dialog = new JDialog(this, "Đang xử lý", true);
        dialog.setUndecorated(true);

        JLabel label = new JLabel(String.format("<html><body style='width: 250px; font-size: 14px;'><font color='blue'><b>%s</b></font></body></html>", message));
        label.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);

        JButton cancelButton = new JButton("<html><font color='red'>Hủy</font></html>");
        cancelButton.addActionListener(e -> {
            if (onCancel != null) onCancel.run();
            dialog.dispose();
        });

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Color.WHITE);
        panel.add(label, BorderLayout.NORTH);
        panel.add(progressBar, BorderLayout.CENTER);
        panel.add(cancelButton, BorderLayout.SOUTH);

        dialog.getContentPane().add(panel);
        dialog.pack();

        Dimension size = dialog.getSize();
        size.width = Math.min(size.width, 350);
        dialog.setSize(size);
        dialog.setLocationRelativeTo(this);

        return dialog;
    }

    public void displayFileInfo(String fileName, long fileSize, String peer) {
        double size = fileSize / (1024.0 * 1024.0);
        String formatSize;
        if (size < 1) {
            size = (double) fileSize / 1024;
            size = Math.round(size * 100.0) / 100.0;
            formatSize = String.format("<html><font color='blue'>%.2f KB</font></html>", size);
        } else {
            size = Math.round(size * 100.0) / 100.0;
            formatSize = String.format("<html><font color='blue'>%.2f MB</font></html>", size);
        }
        tableModel.addRow(new Object[]{
                "<html><b>" + fileName + "</b></html>",
                formatSize,
                "<html><font color='green'>" + peer + "</font></html>"
        });
    }

    public void clearTable() {
        tableModel.setRowCount(0);
    }

    public void showMessage(String message, boolean isError) {
        JOptionPane.showMessageDialog(this,
                "<html><font color='" + (isError ? "red" : "blue") + "'>" + message + "</font></html>",
                isError ? "Lỗi" : "Thông báo",
                isError ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
        appendLog("<font color='" + (isError ? "red" : "blue") + "'>" + message + "</font>");
    }

    public int showMessageWithOptions(String message, boolean isError) {
        String[] options = {"Tiếp tục", "Thay thế", "Hủy"};
        int result = JOptionPane.showOptionDialog(
                this,
                "<html><font color='" + (isError ? "red" : "blue") + "'>" + message + "</font></html>",
                isError ? "Lỗi" : "Thông báo",
                JOptionPane.YES_NO_CANCEL_OPTION,
                isError ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE,
                null,
                options,
                options[0]
        );
        appendLog("<font color='" + (isError ? "red" : "blue") + "'>" + message + " - Tùy chọn được chọn: " + options[result] + "</font>");
        return result;
    }

    public void showMenu(boolean isDownload) {
        String menuText = isDownload ? "<html><font color='blue'>Tải xuống</font></html>" : "<html><font color='red'>Dừng chia sẻ</font></html>";
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

    public void displayData(Set<FileInfor> sharedFileNames) {
        clearTable();
        for (FileBase file : sharedFileNames) {
            displayFileInfo(file.getFileName(), file.getFileSize(), file.getPeerInfor().getIp() + ":" + file.getPeerInfor().getPort());
        }
        appendLog("<font color='green'>Đã cập nhật danh sách file chia sẻ.</font>");
    }

    public boolean showConfirmation(String message) {
        int result = JOptionPane.showConfirmDialog(
                this,
                "<html><b>" + "</font></html>",
                message,
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        return result == JOptionPane.YES_OPTION;
    }

    public void removeFileFromView(String fileName, String peerInfor) {
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            String currentFileName = ((String) tableModel.getValueAt(i, 0)).replaceAll("<[^>]+>", "");
            String currentPeerInfor = ((String) tableModel.getValueAt(i, 2)).replaceAll("<[^>]+>", "");
            if (currentFileName.equals(fileName) && currentPeerInfor.equals(peerInfor)) {
                tableModel.removeRow(i);
                appendLog("<font color='red'>Đã xóa file: " + fileName + " khỏi danh sách chia sẻ.</font>");
                return;
            }
        }
        appendLog("<font color='red'>Không tìm thấy file: " + fileName + " để xóa.</font>");
    }

    public void updateProgress(String taskName, int progress, long bytesTransferred, long totalBytes) {
        SwingUtilities.invokeLater(() -> {
            String text;
            if (totalBytes < 1024 * 1024) {
                double transferredKB = bytesTransferred / 1024.0;
                double totalKB = totalBytes / 1024.0;
                text = String.format("<html><font color='blue'><b>%s</b></font>: %.2f / %.2f KB</html>", taskName, transferredKB, totalKB);
            } else {
                double transferredMB = bytesTransferred / (1024.0 * 1024);
                double totalMB = totalBytes / (1024.0 * 1024);
                text = String.format("<html><font color='blue'><b>%s</b></font>: %.2f / %.2f MB</html>", taskName, transferredMB, totalMB);
            }
            progressLabel.setText(text);
            progressBar.setValue(progress);
            if (progress >= 100) {
                cancelButton.setEnabled(false);
                progressLabel.setText(String.format("<html><font color='green'>Hoàn tất: %s</font></html>", taskName));
                appendLog("<font color='green'>Hoàn tất: " + taskName + "</font>");
            }
        });
    }

    public void resetProgress() {
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(0);
            progressLabel.setText("<html><b>Sẵn sàng</b></html>");
        });
    }

    public void progressError(String taskName, String errorMessage) {
        SwingUtilities.invokeLater(() -> {
            progressLabel.setText(String.format("<html><font color='red'>Lỗi: %s - %s</font></html>", taskName, errorMessage));
            progressBar.setValue(0);
            appendLog(String.format("<font color='red'>Lỗi trong quá trình: %s - %s</font>", taskName, errorMessage));
        });
    }
}