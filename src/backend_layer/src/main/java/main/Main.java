package main.java.main;

import main.java.controller.P2PController;
import main.java.model.PeerModel;
import main.java.utils.ConfigLoader;
import main.java.api.P2PApi;

import java.io.IOException;

import static main.java.utils.Log.logError;

public class Main {
    public static void main(String[] args) throws IOException {
        System.setProperty("file.encoding", "UTF-8");

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

        ConfigLoader.intialize();
        P2PApi api = new P2PApi();
        PeerModel peerModel = null;
        try {
            peerModel = new PeerModel();
        } catch (IOException e) {
            logError("Error when initialize peer model", e);
            System.exit(1);
        }
        P2PController controller = new P2PController(peerModel, api);
        controller.start();
    }
}