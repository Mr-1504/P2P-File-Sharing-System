package main.java.view;

import javafx.beans.property.SimpleStringProperty;
import main.java.model.FileInfor;
import main.java.utils.AppPaths;
import main.java.utils.EnvConf;
import main.java.utils.ConfigLoader;
import main.java.utils.LogTag;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static main.java.utils.ConfigLoader.msgBundle;
import static main.java.utils.Log.logError;

public class P2PView {
    private final Stage primaryStage;
    private TextArea logArea;
    private TextField fileNameField;
    private ContextMenu contextMenu;
    private MenuItem menuItem;
    private TableView<FileInfor> fileTable;
    private Button chooseFileButton;
    private Button searchButton;
    private Button myFilesButton;
    private Button refreshButton;
    private Button allFilesButton;
    private ProgressBar progressBar;
    private Label progressLabel;
    private Button cancelButton;
    private StackPane root;
    private ComboBox<String> languageComboBox;
    private Runnable cancelAction;
    private ResourceBundle lbBundle;
    private VBox inputPanel;
    private BorderPane mainLayout;
    private VBox bottomPanel;

    public P2PView(Stage stage) {
        primaryStage = stage;
        intinitializePriUI();
        intinitializeInputUI();
        intinitializeFileTable();
        intinitializeLogPanel();
        intinitializeProgressPanel();
        intinitializeContextMenu();
    }

    private void intinitializeContextMenu() {
        contextMenu = new ContextMenu();
        menuItem = new MenuItem(lbBundle.getString("app.label.download"), createIcon("/icons/download.png", 16));
        menuItem.getStyleClass().add("menu-item");
        contextMenu.getItems().add(menuItem);
        fileTable.setContextMenu(contextMenu);
    }

    private void intinitializeProgressPanel() {
        VBox progressPanel = new VBox(8);
        progressPanel.setAlignment(Pos.CENTER_LEFT);
        progressLabel = new Label(lbBundle.getString("app.label.progress.ready"));
        progressLabel.getStyleClass().add("label");
        progressBar = new ProgressBar(0);
        progressBar.getStyleClass().add("progress-bar");
        HBox.setHgrow(progressBar, Priority.ALWAYS);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        cancelButton = new Button(lbBundle.getString("app.label.cancel"), createIcon("/icons/cancel.png", 20));
        cancelButton.getStyleClass().add("secondary-button");
        cancelButton.setDisable(true);
        cancelButton.setOnAction(e -> setCancelButtonAction());

        HBox progressBarPanel = new HBox(10);
        progressBarPanel.setAlignment(Pos.CENTER_LEFT);
        progressBarPanel.getChildren().addAll(progressBar, cancelButton);
        progressPanel.getChildren().addAll(progressLabel, progressBarPanel);
        bottomPanel.getChildren().add(progressPanel);
        VBox.setVgrow(logArea, Priority.ALWAYS);
        mainLayout.setBottom(bottomPanel);
    }

    private void setCancelButtonAction() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(lbBundle.getString("app.label.cancel.confirm.title"));
        alert.setHeaderText(lbBundle.getString("app.label.cancel.confirm.message"));
        alert.getButtonTypes().setAll(
                new ButtonType(lbBundle.getString("app.label.yes"), ButtonBar.ButtonData.OK_DONE),
                new ButtonType(lbBundle.getString("app.label.no"), ButtonBar.ButtonData.CANCEL_CLOSE));
        if (alert.showAndWait().filter(b -> b.getButtonData() == ButtonBar.ButtonData.OK_DONE).isPresent() && cancelAction != null) {
            cancelAction.run();
            cancelButton.setDisable(true);
            progressError(lbBundle.getString("app.label.progress.task"), lbBundle.getString("app.label.progress.cancelled"));
        }
    }

    private void intinitializeLogPanel() {
        bottomPanel = new VBox(10);
        bottomPanel.setPadding(new Insets(15));
        bottomPanel.getStyleClass().add("bottom-panel");
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefRowCount(6);
        logArea.getStyleClass().add("log-area");
        bottomPanel.getChildren().add(logArea);
    }

    private void intinitializeFileTable() {
        fileTable = new TableView<>();
        fileTable.getStyleClass().add("file-table");
        fileTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        setupFileTableColumns();

        fileTable.setPlaceholder(new Label(lbBundle.getString("app.label.file.empty")));
        mainLayout.setCenter(fileTable);
    }

    private void setupFileTableColumns() {
        TableColumn<FileInfor, String> nameColumn = new TableColumn<>(lbBundle.getString("app.label.file.name"));
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("fileName"));
        nameColumn.setPrefWidth(300);

        TableColumn<FileInfor, String> sizeColumn = new TableColumn<>(lbBundle.getString("app.label.file.size"));
        sizeColumn.setCellValueFactory(cellData -> {
            long size = cellData.getValue().getFileSize();
            String formatted = formatFileSize(size);
            return new SimpleStringProperty(formatted);
        });
        sizeColumn.setPrefWidth(120);

        TableColumn<FileInfor, String> peerColumn = new TableColumn<>(lbBundle.getString("app.label.file.peer"));
        peerColumn.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleStringProperty(
                        cellData.getValue().getPeerInfor().getIp() + ":" + cellData.getValue().getPeerInfor().getPort()));
        peerColumn.setPrefWidth(200);
        fileTable.getColumns().addAll(nameColumn, sizeColumn, peerColumn);
    }

    public static String formatFileSize(long sizeInBytes) {
        double sizeMB = sizeInBytes / (1024.0 * 1024.0);
        if (sizeMB < 1) {
            return String.format("%.2f KB", sizeInBytes / 1024.0);
        } else {
            return String.format("%.2f MB", sizeMB);
        }
    }


    private void intinitializeInputUI() {
        inputPanel = new VBox(15);
        inputPanel.setPadding(new Insets(20));
        inputPanel.setAlignment(Pos.CENTER_LEFT);
        inputPanel.getStyleClass().add("input-panel");

        intinitializeChooseFileAndLang();
        intinitializeSearchFile();
        intinitializeButtonPanel();
        mainLayout.setTop(inputPanel);
    }

    private void intinitializeButtonPanel() {
        HBox buttonPanel = new HBox(10);
        buttonPanel.setAlignment(Pos.CENTER_LEFT);
        searchButton = new Button(lbBundle.getString("app.label.search"), createIcon("/icons/search.png", 20));
        myFilesButton = new Button(lbBundle.getString("app.label.file.mine"), createIcon("/icons/folder.png", 20));
        refreshButton = new Button(lbBundle.getString("app.label.refresh"), createIcon("/icons/refresh.png", 20));
        allFilesButton = new Button(lbBundle.getString("app.label.file.all"), createIcon("/icons/files.png", 20));
        searchButton.getStyleClass().add("primary-button");
        myFilesButton.getStyleClass().add("primary-button");
        refreshButton.getStyleClass().add("primary-button");
        allFilesButton.getStyleClass().add("primary-button");
        buttonPanel.getChildren().addAll(searchButton, myFilesButton, allFilesButton, refreshButton);
        inputPanel.getChildren().add(buttonPanel);
    }

    private void intinitializeSearchFile() {
        GridPane searchPanel = new GridPane();
        searchPanel.setHgap(10);
        searchPanel.setVgap(10);
        Label searchLabel = new Label(lbBundle.getString("app.label.search.file"));
        searchLabel.getStyleClass().add("label");
        fileNameField = new TextField();
        fileNameField.setPromptText(lbBundle.getString("app.label.search.placeholder"));
        fileNameField.setPrefWidth(400);
        fileNameField.getStyleClass().add("text-field");
        searchPanel.add(searchLabel, 0, 0);
        searchPanel.add(fileNameField, 1, 0);
        inputPanel.getChildren().add(searchPanel);
    }

    private void intinitializeChooseFileAndLang() {
        HBox topPanel = new HBox(15);
        topPanel.setAlignment(Pos.CENTER_LEFT);
        intinitializeChooseFile(topPanel);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        topPanel.getChildren().add(spacer);
        intinitializeLanguagePanel(topPanel);
        inputPanel.getChildren().add(topPanel);
    }

    private void intinitializeLanguagePanel(HBox topPanel) {
        HBox languagePanel = new HBox(10);
        languagePanel.setAlignment(Pos.CENTER_RIGHT);
        Label languageLabel = new Label(lbBundle.getString("app.label.language"));
        languageLabel.getStyleClass().add("label");
        languageComboBox = new ComboBox<>();
        languageComboBox.getItems().addAll(ConfigLoader.getAllLanguages().values());
        languageComboBox.setValue(ConfigLoader.getCurrentLangDisplay());
        languageComboBox.getStyleClass().add("combo-box");
        languageComboBox.setOnAction(e -> changeLanguage(languageComboBox.getValue()));
        languagePanel.getChildren().addAll(languageLabel, languageComboBox);
        topPanel.getChildren().add(languagePanel);
    }

    private void intinitializeChooseFile(HBox topPanel) {
        HBox filePanel = new HBox(10);
        filePanel.setAlignment(Pos.CENTER_LEFT);
        Label chooseFileLabel = new Label(lbBundle.getString("app.label.choose.forshare"));
        chooseFileLabel.getStyleClass().add("label");
        chooseFileButton = new Button(lbBundle.getString("app.label.choose.file"), createIcon("/icons/upload.png", 20));
        chooseFileButton.getStyleClass().add("primary-button");
        filePanel.getChildren().addAll(chooseFileLabel, chooseFileButton);
        topPanel.getChildren().add(filePanel);
    }

    private void intinitializePriUI() {
        lbBundle = ResourceBundle.getBundle("lan.labels.labels", new Locale(EnvConf.strLang));

        root = new StackPane();
        mainLayout = new BorderPane();
        Scene scene = new Scene(root, 900, 600);
        try {
            scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/style.css")).toExternalForm());
        } catch (NullPointerException e) {
            Platform.runLater(() -> {
                appendLog(msgBundle.getString("msg.err.cannot.find") + ": style.css");
                showAlert(Alert.AlertType.ERROR, msgBundle.getString("error.css"), msgBundle.getString("msg.err.cannot.load") + "style.css");
            });
        }
        primaryStage.setScene(scene);
        root.getChildren().add(mainLayout);
    }

    private void changeLanguage(String languageDisplayName) {
        Platform.runLater(() -> {
            try {
                Optional<Map.Entry<String, String>> selectedLanguage = ConfigLoader.getAllLanguages().entrySet().stream()
                        .filter(entry -> entry.getValue().equals(languageDisplayName))
                        .findFirst();

                if (selectedLanguage.isEmpty()) {
                    showNotification(msgBundle.getString("msg.err.language.missing"), true);
                    appendLog(msgBundle.getString("msg.err.language.missing") + ": " + languageDisplayName);
                    return;
                }

                String languageCode = selectedLanguage.get().getKey();
                if (languageCode.equals(EnvConf.strLang))
                    return;
                Locale newLocale = new Locale(languageCode);

                ConfigLoader.setCurrentLang(languageCode);
                EnvConf.strLang = languageCode;

                msgBundle = ResourceBundle.getBundle("lan.messages.messages", newLocale);
                lbBundle = ResourceBundle.getBundle("lan.labels.labels", newLocale);

                updateUILanguage();

                showNotification(msgBundle.getString("msg.notification.language.changed") + " " + languageDisplayName, false);
                appendLog(msgBundle.getString("msg.notification.language.changed") + " " + languageDisplayName);
            } catch (MissingResourceException e) {
                String errorMessage = msgBundle.getString("msg.err.language.load") + ": " + languageDisplayName;
                showNotification(errorMessage, true);
                appendLog(errorMessage);
                showAlert(Alert.AlertType.ERROR, msgBundle.getString("error"), errorMessage);
            }
        });
    }

    private void updateUILanguage() {
        fileTable.getColumns().get(0).setText(lbBundle.getString("app.label.file.name"));
        fileTable.getColumns().get(1).setText(lbBundle.getString("app.label.file.size"));
        fileTable.getColumns().get(2).setText(lbBundle.getString("app.label.file.peer"));
        fileTable.setPlaceholder(new Label(lbBundle.getString("app.label.file.empty")));

        chooseFileButton.setText(lbBundle.getString("app.label.choose.file"));
        ((Label) ((HBox) ((HBox) inputPanel.getChildren().get(0)).getChildren().get(0)).getChildren().get(0))
                .setText(lbBundle.getString("app.label.choose.forshare"));
        searchButton.setText(lbBundle.getString("app.label.search"));
        ((Label) ((GridPane) inputPanel.getChildren().get(1)).getChildren().get(0))
                .setText(lbBundle.getString("app.label.search.file"));
        fileNameField.setPromptText(lbBundle.getString("app.label.search.placeholder"));
        myFilesButton.setText(lbBundle.getString("app.label.file.mine"));
        refreshButton.setText(lbBundle.getString("app.label.refresh"));
        allFilesButton.setText(lbBundle.getString("app.label.file.all"));
        cancelButton.setText(lbBundle.getString("app.label.cancel"));
        progressLabel.setText(lbBundle.getString("app.label.progress.ready"));
        menuItem.setText(lbBundle.getString("app.label.download"));
        ((Label) ((HBox) ((HBox) inputPanel.getChildren().get(0)).getChildren().get(2)).getChildren().get(0))
                .setText(lbBundle.getString("app.label.language"));
    }

    private ImageView createIcon(String path, int size) {
        try {
            Image image = new Image(Objects.requireNonNull(getClass().getResourceAsStream(path)));
            ImageView imageView = new ImageView(image);
            imageView.setFitWidth(size);
            imageView.setFitHeight(size);
            return imageView;
        } catch (Exception e) {
            logError(msgBundle.getString("msg.err.cannot.load") + ": " + path, e);
            return null;
        }
    }

    public void showNotification(String message, boolean isError) {
        Platform.runLater(() -> {
            Label notificationLabel = new Label(message);
            String styleClass = isError ? "notification_err" : "notification";
            notificationLabel.getStyleClass().add(styleClass);
            notificationLabel.setWrapText(true);
            notificationLabel.setMaxWidth(300);
            notificationLabel.setMinHeight(60);
            StackPane.setAlignment(notificationLabel, Pos.TOP_RIGHT);
            StackPane.setMargin(notificationLabel, new Insets(20, 20, 0, 0));

            root.getChildren().add(notificationLabel);

            FadeTransition fadeOut = new FadeTransition(Duration.seconds(0.3), notificationLabel);
            fadeOut.setFromValue(1.0);
            fadeOut.setToValue(0.0);
            fadeOut.setDelay(Duration.seconds(2.0));
            fadeOut.setOnFinished(e -> root.getChildren().remove(notificationLabel));
            fadeOut.play();
        });
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
            if (primaryStage == null || !primaryStage.isShowing()) {
                Platform.runLater(() -> {
                    String errorMessage = msgBundle.getString("error") + ": "
                            + msgBundle.getString("msg.err.cannot.open.filechooser");
                    appendLog(errorMessage);
                    showAlert(Alert.AlertType.ERROR, msgBundle.getString("error"), errorMessage);
                    showNotification(errorMessage, true);
                });
                return LogTag.S_ERROR;
            }

            FileChooser fileChooser = new FileChooser();
            fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));
            File selectedFile = fileChooser.showOpenDialog(primaryStage);
            Platform.runLater(() -> {
                if (selectedFile != null)
                    showNotification(msgBundle.getString("msg.notification.file.choose.done") + selectedFile.getName(), false);
                else
                    showNotification(msgBundle.getString("msg.notification.file.choose.cancelled"), false);
            });

            if (selectedFile != null) {
                try {
                    Path sharedDir = Paths.get(AppPaths.getAppDataDirectory() + "//shared_files");
                    if (!Files.exists(sharedDir)) {
                        Files.createDirectories(sharedDir);
                    }
                    return selectedFile.getAbsolutePath();
                } catch (IOException e) {
                    Platform.runLater(() -> {
                        String errorMessage = msgBundle.getString("error") + ": "
                                + msgBundle.getString("msg.err.file.copyorshare") + e.getMessage();
                        appendLog(errorMessage);
                        showAlert(Alert.AlertType.ERROR, msgBundle.getString("error"), errorMessage);
                        showNotification(errorMessage, true);
                    });
                    return LogTag.S_ERROR;
                }
            }
            return LogTag.S_CANCELLED;
        }, Platform::runLater);
    }

    public CompletableFuture<String> openFileChooserForDownload(String fileName) {
        return CompletableFuture.supplyAsync(() -> {
            if (primaryStage == null || !primaryStage.isShowing()) {
                Platform.runLater(() -> {
                    String errorMessage = msgBundle.getString("error") + ": "
                            + msgBundle.getString("msg.err.cannot.open.filechooser");
                    appendLog(errorMessage);
                    showAlert(Alert.AlertType.ERROR, msgBundle.getString("error"), errorMessage);
                    showNotification(errorMessage, true);
                });
                return LogTag.S_ERROR;
            }

            FileChooser fileChooser = new FileChooser();
            fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));
            fileChooser.setInitialFileName(fileName);
            File saveFile = fileChooser.showSaveDialog(primaryStage);
            Platform.runLater(() -> {
                if (saveFile != null)
                    showNotification(msgBundle.getString("msg.notification.file.download.start") + ": " + fileName, false);
                else
                    showNotification(msgBundle.getString("msg.notification.file.download.cancelled"), false);
            });

            if (saveFile != null) {
                try {
                    return saveFile.getAbsolutePath();
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        String errorMessage = msgBundle.getString("msg.err.file.download") + e.getMessage();
                        appendLog(errorMessage);
                        showAlert(Alert.AlertType.ERROR, msgBundle.getString("error"), errorMessage);
                        showNotification(msgBundle.getString("error") + ": " + e.getMessage(), true);
                    });
                    return LogTag.S_ERROR;
                }
            }
            return LogTag.S_CANCELLED;
        }, Platform::runLater);
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
        myFilesButton.setOnAction(e -> {
            listener.run();
            showNotification(msgBundle.getString("msg.notification.file.mine.show"), false);
        });
    }

    public void setAllFilesButtonListener(Runnable listener) {
        allFilesButton.setOnAction(e -> {
            listener.run();
            showNotification(msgBundle.getString("msg.notification.file.all.show"), false);
        });
    }

    public void setRefreshButtonListener(Runnable listener) {
        refreshButton.setOnAction(e -> {
            listener.run();
            showNotification(msgBundle.getString("msg.notification.file.refresh"), false);
        });
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
        Button cancelButton = new Button(lbBundle.getString("app.label.cancel"), createIcon("/icons/cancel.png", 20));
        cancelButton.getStyleClass().add("secondary-button");
        cancelButton.setOnAction(e -> {
            if (onCancel != null) onCancel.run();
            dialog.close();
            showNotification(msgBundle.getString("msg.notification.progress.cancelled"), false);
        });

        panel.getChildren().addAll(label, progressBar, cancelButton);
        Scene scene = new Scene(panel);
        try {
            scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/style.css")).toExternalForm());
        } catch (NullPointerException e) {
            logError(msgBundle.getString("msg.err.cannot.load") + ": style.css", e);
        }
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
                fileTable.setPlaceholder(new Label(lbBundle.getString("app.label.file.empty")));
                showNotification(msgBundle.getString("msg.notification.file.empty"), false);
            } else {
                fileTable.getItems().addAll(sharedFileNames);
                showNotification(msgBundle.getString("msg.notification.file.refreshed"), false);
            }
        });
    }

    public void showMessage(String message, boolean isError) {
        Platform.runLater(() -> {
            showAlert(isError ? Alert.AlertType.ERROR : Alert.AlertType.INFORMATION, isError ?
                    msgBundle.getString("error") : msgBundle.getString("notification"), message);
            appendLog(message);
        });
    }

    public int showMessageWithOptions(String message, boolean isError) {
        Alert alert = new Alert(isError ? Alert.AlertType.ERROR : Alert.AlertType.INFORMATION);
        alert.setTitle(isError ? msgBundle.getString("error") : msgBundle.getString("notification"));
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.getDialogPane().getStyleClass().add("alert-pane");

        ButtonType continueButton = new ButtonType(lbBundle.getString("app.label.continue"));
        ButtonType replaceButton = new ButtonType(lbBundle.getString("app.label.replace"));
        ButtonType cancelButton = new ButtonType(lbBundle.getString("app.label.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(continueButton, replaceButton, cancelButton);

        int result = alert.showAndWait().map(button -> {
            if (button == continueButton) return 0;
            if (button == replaceButton) return 1;
            return 2;
        }).orElse(2);

        showNotification(message + " - " + (result == 0 ? lbBundle.getString("app.label.continue")
                : result == 1 ? lbBundle.getString("app.label.replace") : lbBundle.getString("app.label.cancel")), false);
        return result;
    }

    public boolean showConfirmation(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(msgBundle.getString("notification.title.confirm"));
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.getDialogPane().getStyleClass().add("alert-pane");

        ButtonType yesButton = new ButtonType(lbBundle.getString("app.label.yes"), ButtonBar.ButtonData.OK_DONE);
        ButtonType noButton = new ButtonType(lbBundle.getString("app.label.no"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(yesButton, noButton);

        boolean confirmed = alert.showAndWait()
                .map(buttonType -> buttonType == yesButton)
                .orElse(false);
        showNotification(confirmed ? msgBundle.getString("notification.title.confirm") + ": "
                + message : msgBundle.getString("notification.title.cancel") + ": " + message, false);
        return confirmed;
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
        String menuText = isDownload ? lbBundle.getString("app.label.file.download") : lbBundle.getString("app.label.file.stopshare");
        menuItem.setText(menuText);
        menuItem.setGraphic(createIcon(isDownload ? "/icons/download.png" : "/icons/stop.png", 16));
        contextMenu.show(fileTable, fileTable.getScene().getWindow().getX() + 10, fileTable.getScene().getWindow().getY() + 50);
    }

    public TableView<FileInfor> getFileTable() {
        return fileTable;
    }

    public void removeFileFromView(String fileName, String peerInfor) {
        Platform.runLater(() -> {
            fileTable.getItems().removeIf(file ->
                    file.getFileName().equals(fileName) &&
                            (file.getPeerInfor().getIp() + ":" + file.getPeerInfor().getPort()).equals(peerInfor));
            showNotification(msgBundle.getString("msg.notification.file.removed") + ": " + fileName, false);
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
                resetProgress();
                progressLabel.setText(msgBundle.getString("msg.notification.progress.done") + ": " + taskName);
                appendLog(msgBundle.getString("msg.notification.progress.done") + ": " + taskName);
                showNotification(msgBundle.getString("msg.notification.progress.done") + ": " + taskName, false);
            }
        });
    }

    public void resetProgress() {
        Platform.runLater(() -> {
            progressBar.setProgress(0);
            progressLabel.setText(lbBundle.getString("app.label.progress.ready"));
        });
    }

    public void progressError(String taskName, String errorMessage) {
        Platform.runLater(() -> {
            progressLabel.setText(msgBundle.getString("error") + ": " + taskName + " - " + errorMessage);
            progressBar.setProgress(0);
            appendLog(msgBundle.getString("msg.err.progress") + ": " + taskName + " - " + errorMessage);
            showNotification(msgBundle.getString("error") + ": " + errorMessage, true);
        });
    }
}