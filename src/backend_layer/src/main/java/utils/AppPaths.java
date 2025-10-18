package main.java.utils;

import main.java.domain.entity.ProgressInfo;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

import static main.java.utils.Log.logInfo;

public class AppPaths {
    private static final String APP_NAME = EnvUtils.getEnv("APP_NAME", "p2p-file-sharing");
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
        String fileName = "application-" + date + ".log";
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

    public static boolean copyFileToShare(File sourceFile, String newfileName, ProgressInfo progressInfor) {
        long start = System.currentTimeMillis();
        File destFile = new File(getSharedFile(newfileName));
        try (
                InputStream in = new FileInputStream(sourceFile);
                OutputStream out = new FileOutputStream(destFile)
        ) {
            progressInfor.setBytesTransferred(0);
            progressInfor.setTotalBytes(sourceFile.length());
            progressInfor.setStatus(ProgressInfo.ProgressStatus.SHARING);
            progressInfor.setProgressPercentage(0);
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                if (progressInfor.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED)) {
                    out.close();
                    destFile.delete();
                    logInfo("File copy cancelled by user: " + sourceFile.getName());
                    return false;
                }
                out.write(buffer, 0, bytesRead);
                progressInfor.addBytesTransferred(bytesRead);
                int progress = (int) ((progressInfor.getBytesTransferred() * 70) / progressInfor.getTotalBytes());
                progressInfor.setProgressPercentage(progress);

            }
            System.out.println("Total time: " + (System.currentTimeMillis() - start) + " ms");
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static int getFileCount(String fileName) {
        String name = fileName;
        String extension = "";

        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex != -1) {
            name = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex); // gồm cả dấu chấm
        }

        Pattern pattern = Pattern.compile("^" + Pattern.quote(name) + "( \\(\\d+\\))?" + Pattern.quote(extension) + "$");

        File dir = new File(AppPaths.getAppDataDirectory() + File.separator + "shared_files");
        File[] files = dir.listFiles();

        int count = 0;
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && pattern.matcher(file.getName()).matches()) {
                    count++;
                }
            }
        }
        return count;
    }


    public static String incrementFileName(String fileName) {
        String name = fileName;
        String extension = "";

        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex != -1) {
            name = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex);
        }

        int count = AppPaths.getFileCount(fileName);
        System.out.println("Current file count for '" + fileName + "': " + count);
        fileName = name + " (" + (count + 1) + ")" + extension;
        return fileName;
    }

    public static boolean removeSharedFile(String fileName) {
        File file = new File(getSharedFile(fileName));
        if (file.exists()) {
            return file.delete();
        }
        return false;
    }

    public static boolean saveUsername(String username) {
        Path configPath = Paths.get(getAppDataDirectory(), "config.json");
        try (BufferedWriter writer = Files.newBufferedWriter(configPath)) {
            writer.write("{\"username\": \"" + username + "\"}");
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static String loadUsername() {
        Path configPath = Paths.get(getAppDataDirectory(), "config.json");
        if (Files.exists(configPath)) {
            try (BufferedReader reader = Files.newBufferedReader(configPath)) {
                String line = reader.readLine();
                if (line != null && line.contains("\"username\":")) {
                    String username = line.split(":")[1].trim().replace("\"", "").replace("}", "");
                    return username;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }
}
