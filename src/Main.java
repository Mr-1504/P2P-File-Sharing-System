import controller.PeerController;
import model.PeerModel;
import view.PeerView;

import javax.swing.*;
import java.io.IOException;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                PeerModel model = new PeerModel("localhost", 5000);
                PeerView view = new PeerView();
                PeerController controller = new PeerController(model, view);

                model.startServer();
                view.show();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}