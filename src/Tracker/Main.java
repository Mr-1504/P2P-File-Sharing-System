import java.io.IOException;
import java.util.PriorityQueue;

import model.PeerInfor;
import model.TrackerModel;


public class Main {
    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");

        // Khởi động Tracker
        Thread trackerThread = new Thread(() -> {
            try {
                new TrackerModel().startTracker();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        trackerThread.setDaemon(false); // Đặt tracker thread là daemon để dừng khi đóng GUI
        trackerThread.start();
    }
}