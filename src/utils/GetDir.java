package utils;

import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

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

    public static boolean copyFileToShare(File sourceFile, AtomicBoolean isCancelled) {
        File destFile = new File(getShareDir(sourceFile.getName()));
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

}
