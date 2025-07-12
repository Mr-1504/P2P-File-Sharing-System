package main.java.utils;

public class Log {
    public static void logInfo(String message) {
        System.out.println("[INFO] " + message);
    }

    public static void logError(String message, Exception e) {
        System.err.println("[ERROR] " + message);
        if (e != null) {
            System.err.println("[ERROR] Exception: " + e.getMessage());
        }
    }
}
