package main.java.main;

import main.java.controller.P2PController;
import main.java.model.PeerModel;
import main.java.utils.LanguageLoader;
import main.java.view.P2PView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.ResourceBundle;

import static main.java.utils.Log.logInfo;

public class Main extends Application {
    private static ResourceBundle resourceBundle;

    public static void main(String[] args) {
        // Đặt mã hóa file
        System.setProperty("file.encoding", "UTF-8");
        resourceBundle = ResourceBundle.getBundle("lan.labels.labels", new Locale(LanguageLoader.getCurrentLangCode()));

//        // Khởi động Tracker trong một luồng riêng
//        Thread trackerThread = new Thread(() -> {
//            try {
//                new TrackerModel().startTracker();
//            } catch (IOException e) {
//                Platform.runLater(() -> {
//                    showErrorAlert("Lỗi khi khởi động tracker: " + e.getMessage());
//                    e.printStackTrace();
//                });
//                throw new RuntimeException(e);
//            }
//        });
//        trackerThread.setDaemon(true); // Đặt tracker thread là daemon để dừng khi đóng ứng dụng
//        trackerThread.start();
//
//        // Đợi tracker khởi động
//        try {
//            Thread.sleep(1000); // Chờ 1 giây để tracker sẵn sàng
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }

        // Khởi động ứng dụng JavaFX
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        try {
            LanguageLoader.intialize();
            P2PView view = new P2PView(primaryStage);
            String path = System.getProperty("user.dir");
            File file = new File(path);
            String projectName = file.getName();
            primaryStage.setTitle(resourceBundle.getString("app.name") + " - " + projectName);
            primaryStage.setMinWidth(600);
            primaryStage.setMinHeight(600);
            PeerModel peerModel = new PeerModel(view);
            P2PController controller = new P2PController(peerModel, view);
            controller.start();

            view.show();
            primaryStage.setOnCloseRequest(event -> {
                logInfo("Close app.");
                Platform.exit();
                System.exit(0);
            });

        } catch (IOException e) {
            Platform.runLater(() -> {
                showErrorAlert("Lỗi khi khởi động hệ thống: " + e.getMessage());
                e.printStackTrace();
            });
        } catch (Exception e) {
            Platform.runLater(() -> {
                showErrorAlert("Lỗi không xác định: " + e.getMessage());
                e.printStackTrace();
            });
        }
    }

    private static void showErrorAlert(String message) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
        alert.setTitle("Lỗi");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}