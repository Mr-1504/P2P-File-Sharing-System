package main.java.utils;

import main.java.model.ProgressInfor;

import java.io.*;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static main.java.utils.Log.logInfo;

public class AppPaths {
    public static String APP_NAME = "P2PFileSharing";
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
            appDir.mkdirs();
        }

        return appDir.getAbsolutePath();
    }

    public static String getSharedFile(String fileName) {
        return Paths.get(getAppDataDirectory(), "shared_files", fileName).toString();
    }

    public static boolean isExistSharedFile(String fileName) {
        File file = new File(getSharedFile(fileName));
        return file.exists();
    }

    public static boolean copyFileToShare(File sourceFile, String newfileName, ProgressInfor progressInfor) {
        File destFile = new File(getSharedFile(newfileName));
        try (
                InputStream in = new FileInputStream(sourceFile);
                OutputStream out = new FileOutputStream(destFile)
        ) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                if (progressInfor.getStatus().equals(ProgressInfor.ProgressStatus.CANCELLED)) {
                    out.close();
                    destFile.delete();
                    logInfo("File copy cancelled by user: " + sourceFile.getName());
                    return false;
                }
                out.write(buffer, 0, bytesRead);
            }
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
}
