import controller.P2PController;
import model.PeerModel;
import model.TrackerModel;
import view.P2PView;

import javax.swing.*;
import java.io.IOException;


public class Main {
    public static void main(String[] args) {
        // Khởi động Tracker
//        Thread trackerThread = new Thread(() -> {
//            try {
//                new TrackerModel().startTracker();
//            } catch (IOException e) {
//                throw new RuntimeException(e);
//            }
//        });
//        trackerThread.setDaemon(true); // Đặt tracker thread là daemon để dừng khi đóng GUI
//        trackerThread.start();
//
//        // Đợi tracker khởi động
//        try {
//            Thread.sleep(1000); // Chờ 1 giây để tracker sẵn sàng
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }

        // Khởi động Peer với Swing UI
        SwingUtilities.invokeLater(() -> {
            try {
                P2PView view = new P2PView();
                PeerModel peerModel = new PeerModel(view);
                P2PController controller = new P2PController(peerModel, view);
                controller.start();
            } catch (IOException e) {
                JOptionPane.showMessageDialog(null, "Lỗi khi khởi động hệ thống: " + e.getMessage());
                e.printStackTrace();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "Lỗi không xác định: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
}