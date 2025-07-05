package view;

import model.FileInfor;
import utils.GetDir;
import utils.LogTag;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class P2PView {
    private final Stage primaryStage;
    private final TextArea logArea;
    private final TextField fileNameField;
    private final ContextMenu contextMenu;
    private final MenuItem menuItem;
    private final TableView<FileInfor> fileTable;
    private final Button chooseFileButton;
    private final Button searchButton;
    private final Button myFilesButton;
    private final Button refreshButton;
    private final Button allFilesButton;
    private final ProgressBar progressBar;
    private final Label progressLabel;
    private final Button cancelButton;
    private Runnable cancelAction;

    public P2PView() {
        primaryStage = new Stage();
        String path = System.getProperty("user.dir");
        File file = new File(path);
        String projectName = file.getName();
        primaryStage.setTitle("Hệ thống chia sẻ file P2P - " + projectName);

        // Tạo bố cục chính
        BorderPane root = new BorderPane();
        Scene scene = new Scene(root, 900, 600);
        File cssFile = new File(GetDir.getDir() + "\\resources\\style.css");
        scene.getStylesheets().add(cssFile.toURI().toString());
        primaryStage.setScene(scene);

        // Panel nhập liệu
        VBox inputPanel = new VBox(15);
        inputPanel.setPadding(new Insets(20));
        inputPanel.setAlignment(Pos.CENTER_LEFT);
        inputPanel.getStyleClass().add("input-panel");

        // Panel chọn file
        HBox filePanel = new HBox(10);
        filePanel.setAlignment(Pos.CENTER_LEFT);
        Label chooseFileLabel = new Label("Chọn file để chia sẻ:");
        chooseFileLabel.getStyleClass().add("label");
        chooseFileButton = new Button("Chọn file");
        chooseFileButton.getStyleClass().add("primary-button");
        filePanel.getChildren().addAll(chooseFileLabel, chooseFileButton);
        inputPanel.getChildren().add(filePanel);

        // Panel tìm kiếm
        GridPane searchPanel = new GridPane();
        searchPanel.setHgap(10);
        searchPanel.setVgap(10);
        Label searchLabel = new Label("Tìm kiếm file:");
        searchLabel.getStyleClass().add("label");
        fileNameField = new TextField();
        fileNameField.setPromptText("Nhập tên file để tìm kiếm...");
        fileNameField.setPrefWidth(400);
        fileNameField.getStyleClass().add("text-field");
        searchPanel.add(searchLabel, 0, 0);
        searchPanel.add(fileNameField, 1, 0);
        inputPanel.getChildren().add(searchPanel);

        // Panel nút
        HBox buttonPanel = new HBox(10);
        buttonPanel.setAlignment(Pos.CENTER_LEFT);
        searchButton = new Button("Tìm kiếm");
        myFilesButton = new Button("File của tôi");
        refreshButton = new Button("Làm mới");
        allFilesButton = new Button("Tất cả file");
        searchButton.getStyleClass().add("primary-button");
        myFilesButton.getStyleClass().add("primary-button");
        refreshButton.getStyleClass().add("primary-button");
        allFilesButton.getStyleClass().add("primary-button");
        buttonPanel.getChildren().addAll(searchButton, myFilesButton, allFilesButton, refreshButton);
        inputPanel.getChildren().add(buttonPanel);

        root.setTop(inputPanel);

        // Bảng hiển thị file
        fileTable = new TableView<>();
        fileTable.getStyleClass().add("file-table");
        TableColumn<FileInfor, String> nameColumn = new TableColumn<>("Tên file");
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("fileName"));
        nameColumn.setPrefWidth(300);

        TableColumn<FileInfor, String> sizeColumn = new TableColumn<>("Kích thước");
        sizeColumn.setCellValueFactory(cellData -> {
            long size = cellData.getValue().getFileSize();
            double sizeMB = size / (1024.0 * 1024.0);
            String formatSize = sizeMB < 1 ?
                    String.format("%.2f KB", size / 1024.0) :
                    String.format("%.2f MB", sizeMB);
            return new javafx.beans.property.SimpleStringProperty(formatSize);
        });
        sizeColumn.setPrefWidth(120);

        TableColumn<FileInfor, String> peerColumn = new TableColumn<>("Peers");
        peerColumn.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleStringProperty(
                        cellData.getValue().getPeerInfor().getIp() + ":" + cellData.getValue().getPeerInfor().getPort()));
        peerColumn.setPrefWidth(200);

        fileTable.getColumns().addAll(nameColumn, sizeColumn, peerColumn);
        fileTable.setPlaceholder(new Label("Không có file nào được chia sẻ."));
        root.setCenter(fileTable);

        // Panel log và tiến trình
        VBox bottomPanel = new VBox(10);
        bottomPanel.setPadding(new Insets(15));
        bottomPanel.getStyleClass().add("bottom-panel");
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefRowCount(6);
        logArea.getStyleClass().add("log-area");
        bottomPanel.getChildren().add(logArea);

        VBox progressPanel = new VBox(8);
        progressLabel = new Label("Sẵn sàng");
        progressLabel.getStyleClass().add("label");
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(300);
        progressBar.getStyleClass().add("progress-bar");
        cancelButton = new Button("Hủy");
        cancelButton.getStyleClass().add("secondary-button");
        cancelButton.setDisable(true);
        cancelButton.setOnAction(e -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Xác nhận hủy");
            alert.setHeaderText("Bạn có chắc chắn muốn hủy tác vụ hiện tại?");
            alert.getButtonTypes().setAll(
                    new ButtonType("Có", ButtonBar.ButtonData.OK_DONE),
                    new ButtonType("Không", ButtonBar.ButtonData.CANCEL_CLOSE));
            if (alert.showAndWait().filter(b -> b.getButtonData() == ButtonBar.ButtonData.OK_DONE).isPresent() && cancelAction != null) {
                cancelAction.run();
                cancelButton.setDisable(true);
                progressError("Tác vụ", "Đã hủy tác vụ");
            }
        });

        HBox progressBarPanel = new HBox(10);
        progressBarPanel.setAlignment(Pos.CENTER_LEFT);
        progressBarPanel.getChildren().addAll(progressBar, cancelButton);
        progressPanel.getChildren().addAll(progressLabel, progressBarPanel);
        bottomPanel.getChildren().add(progressPanel);
        root.setBottom(bottomPanel);

        // Context menu
        contextMenu = new ContextMenu();
        menuItem = new MenuItem("Tải xuống");
        menuItem.getStyleClass().add("menu-item");
        contextMenu.getItems().add(menuItem);
        fileTable.setContextMenu(contextMenu);
    }

    private ImageView createIcon(String path, int size) {
        try {
            Image image = new Image(getClass().getResourceAsStream(path));
            ImageView imageView = new ImageView(image);
            imageView.setFitWidth(size);
            imageView.setFitHeight(size);
            return imageView;
        } catch (Exception e) {
            System.err.println("Không thể tải biểu tượng: " + path);
            return null;
        }
    }

    public void show() {
        primaryStage.show();
    }

    public void setCancelAction(Runnable action) {
        this.cancelAction = action;
        cancelButton.setDisable(action == null);
    }

    public void setCancelButtonEnabled(boolean enabled) {
        cancelButton.setDisable(!enabled);
    }

    public void clearTable() {
        fileTable.getItems().clear();
    }

    public CompletableFuture<String> openFileChooserForShare() {
        return CompletableFuture.supplyAsync(() -> {
            Platform.runLater(() -> appendLog("Bắt đầu mở FileChooser..."));
            if (primaryStage == null || !primaryStage.isShowing()) {
                Platform.runLater(() -> {
                    String errorMessage = "Lỗi: primaryStage is null or not showing";
                    appendLog(errorMessage);
                    showAlert(Alert.AlertType.ERROR, "Lỗi", errorMessage);
                });
                return LogTag.S_ERROR;
            }

            FileChooser fileChooser = new FileChooser();
            fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));
            Platform.runLater(() -> appendLog("Hiển thị FileChooser..."));
            File selectedFile = fileChooser.showOpenDialog(primaryStage);
            Platform.runLater(() -> appendLog("FileChooser trả về: " + (selectedFile != null ? selectedFile.getAbsolutePath() : "null")));

            if (selectedFile != null) {
                try {
                    Path sharedDir = Paths.get("shared_files");
                    if (!Files.exists(sharedDir)) {
                        Files.createDirectories(sharedDir);
                    }
                    return selectedFile.getAbsolutePath();
                } catch (IOException e) {
                    Platform.runLater(() -> {
                        String errorMessage = "Lỗi khi sao chép hoặc chia sẻ file: " + e.getMessage();
                        appendLog(errorMessage);
                        showAlert(Alert.AlertType.ERROR, "Lỗi", errorMessage);
                    });
                    return LogTag.S_ERROR;
                }
            }
            return LogTag.S_CANCELLED;
        }, Platform::runLater);
    }

    public String openFileChooserForDownload(String fileName) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));
        fileChooser.setInitialFileName(fileName);
        File saveFile = fileChooser.showSaveDialog(primaryStage);
        if (saveFile != null) {
            try {
                appendLog("Bắt đầu tải file: " + fileName + " vào " + saveFile.getAbsolutePath());
                return saveFile.getAbsolutePath();
            } catch (Exception e) {
                String errorMessage = "Lỗi khi tải file: " + e.getMessage();
                appendLog(errorMessage);
                showAlert(Alert.AlertType.ERROR, "Lỗi", errorMessage);
                return LogTag.S_ERROR;
            }
        }
        return LogTag.S_CANCELLED;
    }

    public void appendLog(String message) {
        Platform.runLater(() -> logArea.appendText(message + "\n"));
    }

    public String getFileName() {
        return fileNameField.getText();
    }

    public void setSearchButtonListener(Runnable listener) {
        searchButton.setOnAction(e -> listener.run());
    }

    public void setChooseDownload(Runnable listener) {
        menuItem.setOnAction(e -> listener.run());
    }

    public void setMyFilesButtonListener(Runnable listener) {
        myFilesButton.setOnAction(e -> listener.run());
    }

    public void setAllFilesButtonListener(Runnable listener) {
        allFilesButton.setOnAction(e -> listener.run());
    }

    public void setRefreshButtonListener(Runnable listener) {
        refreshButton.setOnAction(e -> listener.run());
    }

    public void setChooseFileButtonListener(Runnable listener) {
        chooseFileButton.setOnAction(e -> listener.run());
    }

    public Stage createLoadingDialog(String message, Runnable onCancel) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initStyle(StageStyle.TRANSPARENT);

        VBox panel = new VBox(15);
        panel.setPadding(new Insets(20));
        panel.getStyleClass().add("dialog-panel");
        Label label = new Label(message);
        label.getStyleClass().add("dialog-label");
        ProgressBar progressBar = new ProgressBar();
        progressBar.setPrefWidth(300);
        progressBar.setProgress(-1);
        progressBar.getStyleClass().add("progress-bar");
        Button cancelButton = new Button("Hủy");
        cancelButton.getStyleClass().add("secondary-button");
        cancelButton.setOnAction(e -> {
            if (onCancel != null) onCancel.run();
            dialog.close();
        });

        panel.getChildren().addAll(label, progressBar, cancelButton);
        Scene scene = new Scene(panel);
        File cssFile = new File(GetDir.getDir() + "\\resources\\style.css");
        scene.getStylesheets().add(cssFile.toURI().toString());
        scene.setFill(null);
        dialog.setScene(scene);
        dialog.sizeToScene();
        dialog.centerOnScreen();
        return dialog;
    }

    public void displayData(Set<FileInfor> sharedFileNames) {
        Platform.runLater(() -> {
            fileTable.getItems().clear();
            if (sharedFileNames == null || sharedFileNames.isEmpty()) {
                appendLog("Không có file nào được chia sẻ.");
                fileTable.setPlaceholder(new Label("Không có file nào được chia sẻ."));
            } else {
                fileTable.getItems().addAll(sharedFileNames);
                appendLog("Đã cập nhật danh sách file chia sẻ.");
            }
        });
    }

    public void showMessage(String message, boolean isError) {
        Platform.runLater(() -> {
            showAlert(isError ? Alert.AlertType.ERROR : Alert.AlertType.INFORMATION, isError ? "Lỗi" : "Thông báo", message);
            appendLog(message);
        });
    }

    public int showMessageWithOptions(String message, boolean isError) {
        Alert alert = new Alert(isError ? Alert.AlertType.ERROR : Alert.AlertType.INFORMATION);
        alert.setTitle(isError ? "Lỗi" : "Thông báo");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.getDialogPane().getStyleClass().add("alert-pane");

        ButtonType continueButton = new ButtonType("Tiếp tục");
        ButtonType replaceButton = new ButtonType("Thay thế");
        ButtonType cancelButton = new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(continueButton, replaceButton, cancelButton);

        int result = alert.showAndWait().map(button -> {
            if (button == continueButton) return 0;
            if (button == replaceButton) return 1;
            return 2;
        }).orElse(2);

        appendLog(message + " - Tùy chọn được chọn: " + (result == 0 ? "Tiếp tục" : result == 1 ? "Thay thế" : "Hủy"));
        return result;
    }

    public boolean showConfirmation(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Xác nhận");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.getDialogPane().getStyleClass().add("alert-pane");

        ButtonType yesButton = new ButtonType("Có", ButtonBar.ButtonData.OK_DONE);
        ButtonType noButton = new ButtonType("Không", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(yesButton, noButton);

        return alert.showAndWait()
                .map(buttonType -> buttonType == yesButton)
                .orElse(false);
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.getDialogPane().getStyleClass().add("alert-pane");
        alert.showAndWait();
    }

    public void showMenu(boolean isDownload) {
        String menuText = isDownload ? "Tải xuống" : "Dừng chia sẻ";
        menuItem.setText(menuText);
        menuItem.setGraphic(createIcon(isDownload ? "/icons/download.png" : "/icons/stop.png", 16));
        contextMenu.show(fileTable, fileTable.getScene().getWindow().getX() + 10, fileTable.getScene().getWindow().getY() + 50);
    }

    public void setRowSelectionInterval(int row, int row1) {
        Platform.runLater(() -> fileTable.getSelectionModel().selectRange(row, row1 + 1));
    }

    public TableView<FileInfor> getFileTable() {
        return fileTable;
    }

    public void removeFileFromView(String fileName, String peerInfor) {
        Platform.runLater(() -> {
            fileTable.getItems().removeIf(file ->
                    file.getFileName().equals(fileName) &&
                            (file.getPeerInfor().getIp() + ":" + file.getPeerInfor().getPort()).equals(peerInfor));
            appendLog("Đã xóa file: " + fileName + " khỏi danh sách chia sẻ.");
        });
    }

    public void updateProgress(String taskName, int progress, long bytesTransferred, long totalBytes) {
        Platform.runLater(() -> {
            if (totalBytes < 1024 * 1024) {
                double transferredKB = bytesTransferred / 1024.0;
                double totalKB = totalBytes / 1024.0;
                progressLabel.setText(String.format("%s: %.2f / %.2f KB", taskName, transferredKB, totalKB));
            } else {
                double transferredMB = bytesTransferred / (1024.0 * 1024);
                double totalMB = totalBytes / (1024.0 * 1024);
                progressLabel.setText(String.format("%s: %.2f / %.2f MB", taskName, transferredMB, totalMB));
            }
            progressBar.setProgress(progress / 100.0);
            if (progress >= 100) {
                cancelButton.setDisable(true);
                progressLabel.setText("Hoàn tất: " + taskName);
                appendLog("Hoàn tất: " + taskName);
            }
        });
    }

    public void resetProgress() {
        Platform.runLater(() -> {
            progressBar.setProgress(0);
            progressLabel.setText("Sẵn sàng");
        });
    }

    public void progressError(String taskName, String errorMessage) {
        Platform.runLater(() -> {
            progressLabel.setText("Lỗi: " + taskName + " - " + errorMessage);
            progressBar.setProgress(0);
            appendLog("Lỗi trong quá trình: " + taskName + " - " + errorMessage);
        });
    }
}