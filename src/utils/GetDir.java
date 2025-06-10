package utils;

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
}
