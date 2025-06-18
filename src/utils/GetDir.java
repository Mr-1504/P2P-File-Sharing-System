package utils;

import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static utils.Log.logInfo;

public class GetDir {
    public static String getDir() {
        String dir = System.getProperty("user.dir");
        if (dir.endsWith("src")) {
            dir = dir.substring(0, dir.length() - 4);
        }
        return dir;
    }

    public static String getShareDir(String fileName) {
        return GetDir.getDir() + "\\shared_files\\" + fileName;
    }

    public static boolean copyFileToShare(File sourceFile, String newfileName, AtomicBoolean isCancelled) {
        File destFile = new File(getShareDir(newfileName));
        try (
                InputStream in = new FileInputStream(sourceFile);
                OutputStream out = new FileOutputStream(destFile)
        ) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                if (isCancelled.get()) {
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

        File dir = new File(GetDir.getDir() + File.separator + "shared_files");
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
            extension = fileName.substring(dotIndex); // bao gồm dấu chấm
        }

        int count = GetDir.getFileCount(fileName);
        System.out.println("Current file count for '" + fileName + "': " + count);
        fileName = name + " (" + (count + 1) + ")" + extension;
        return fileName;
    }
}
