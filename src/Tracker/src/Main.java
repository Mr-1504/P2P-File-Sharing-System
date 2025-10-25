import java.io.IOException;

import src.model.TrackerModel;


public class Main {
    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");
        try {
            new TrackerModel().startTracker();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}