package src.utils;


import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

public class AppPaths {
    private static final String APP_NAME = EnvUtils.getEnv("APP_NAME", "P2P Tracker");
    private static final String CERT_FOLDER = "certificates";
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Path LOG_DIR = initLogDir();


    private static Path initLogDir() {
        String os = System.getProperty("os.name").toLowerCase();
        String userHome = System.getProperty("user.home");
        Path logDir;

        if (os.contains("win")) {
            logDir = Paths.get(System.getenv("LOCALAPPDATA"), APP_NAME, "logs");
        } else if (os.contains("mac")) {
            logDir = Paths.get(userHome, "Library", "Logs", APP_NAME);
        } else {
            logDir = Paths.get("/var/log", APP_NAME);
            if (!Files.isWritable(Paths.get("/var/log"))) {
                logDir = Paths.get(userHome, APP_NAME, "logs");
            }
        }

        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            System.err.println("[ERROR] Cannot create log directory: " + e.getMessage());
        }

        return logDir;
    }

    public static Path getLogFilePath() {
        String date = LocalDateTime.now().format(FILE_DATE_FORMAT);
        String fileName = "tracker-" + date + ".log";
        return LOG_DIR.resolve(fileName);
    }

    public static String getAppDataDirectory() {
        String os = System.getProperty("os.name").toLowerCase();
        String userHome = System.getProperty("user.home");
        File appDir;

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            appDir = new File(appData, APP_NAME);
        } else if (os.contains("mac")) {
            appDir = new File(userHome + "/Library/Application Support", APP_NAME);
        } else {
            appDir = new File(userHome + "/.config", APP_NAME);
        }

        if (!appDir.exists()) {
            try {
                Files.createDirectories(appDir.getAbsoluteFile().toPath());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return appDir.getAbsolutePath();
    }

    public static Path getCertificatePath() {
        Path certPath = Paths.get(getAppDataDirectory(), CERT_FOLDER);
        if (!Files.exists(certPath)) {
            try {
                Files.createDirectories(certPath);
            } catch (IOException e) {
                return null;
            }
        }
        return certPath;
    }

    public static String getSharedFile(String fileName) {
        return Paths.get(getAppDataDirectory(), "shared_files", fileName).toString();
    }
}
