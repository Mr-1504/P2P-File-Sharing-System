package main.java.infras.utils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.domain.entity.ProgressInfo;
import main.java.utils.AppPaths;
import main.java.utils.Config;
import main.java.utils.Log;

import java.io.*;
import java.lang.reflect.Type;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class FileUtils {
    public static void saveData(Map<String, FileInfo> publicSharedFiles, Map<FileInfo, Set<PeerInfo>> privateSharedFiles) {
        String dataDirPath = AppPaths.getAppDataDirectory() + File.separator + "data";
        File dataDir = new File(dataDirPath);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }

        Gson gson = new Gson();
        try (FileWriter writer = new FileWriter(dataDirPath + File.separator + "publicSharedFiles.json")) {
            gson.toJson(publicSharedFiles, writer);
            Log.logInfo("Public shared files saved successfully.");
        } catch (IOException e) {
            Log.logError("Error saving public shared files: " + e.getMessage(), e);
        }

        try (FileWriter writer = new FileWriter(dataDirPath + File.separator + "privateSharedFiles.json")) {
            gson.toJson(privateSharedFiles, writer);
            Log.logInfo("Private shared files saved successfully.");
        } catch (IOException e) {
            Log.logError("Error saving private shared files: " + e.getMessage(), e);
        }
    }

    public static void loadData(String username,
                                Map<String, FileInfo> publicSharedFiles,
                                Map<FileInfo, Set<PeerInfo>> privateSharedFiles) {
        String dataDirPath = AppPaths.getAppDataDirectory() + File.separator + "data";
        File dataDir = new File(dataDirPath);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
            return;
        }

        Gson gson = new Gson();
        Type publicType = new TypeToken<Map<String, FileInfo>>() {
        }.getType();
        File publicFile = new File(dataDirPath, "publicSharedFiles.json");
        if (publicFile.exists()) {
            try (FileReader reader = new FileReader(publicFile)) {
                Map<String, FileInfo> loaded = gson.fromJson(reader, publicType);
                for (FileInfo fileInfo : loaded.values()) {
                    fileInfo.getPeerInfo().setUsername(username);
                }
                publicSharedFiles.clear();
                publicSharedFiles.putAll(loaded);
                Log.logInfo("Public shared files loaded successfully. " + publicSharedFiles.size() + " files found.");
            } catch (IOException e) {
                Log.logError("Error loading public shared files: " + e.getMessage(), e);
            }
        }

        Type privateType = new TypeToken<Map<FileInfo, Set<PeerInfo>>>() {
        }.getType();
        File privateFile = new File(dataDirPath, "privateSharedFiles.json");
        if (privateFile.exists()) {
            try (FileReader reader = new FileReader(privateFile)) {
                Map<FileInfo, Set<PeerInfo>> loaded = gson.fromJson(reader, privateType);
                for (FileInfo fileInfo : loaded.keySet()) {
                    fileInfo.getPeerInfo().setUsername(username);
                }
                privateSharedFiles.clear();
                privateSharedFiles.putAll(loaded);
                Log.logInfo("Private shared files loaded successfully. " + privateSharedFiles.size() + " files found.");
            } catch (IOException e) {
                Log.logError("Error loading private shared files: " + e.getMessage(), e);
            }
        }
    }

    public static String hashFile(File file, ProgressInfo progress) {
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[Config.CHUNK_SIZE];
            long fileSize = file.length();
            long readbyte = 0L;
            if (Objects.equals(progress.getStatus(), ProgressInfo.ProgressStatus.CANCELLED)) {
                return null;
            } else {
                progress.setStatus(ProgressInfo.ProgressStatus.SHARING);

                int readedByteCount;
                while ((readedByteCount = fileInputStream.read(bytes)) != -1) {
                    if (progress.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED)) {
                        return null;
                    }

                    messageDigest.update(bytes, 0, readedByteCount);
                    readbyte += readedByteCount;
                    int percentage = (int) ((double) readbyte * 25.0 / fileSize) + 70;
                    synchronized (progress) {
                        progress.setProgressPercentage(percentage);
                    }
                }

                return bytesToHex(messageDigest.digest());
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            if (progress != null) {
                progress.setStatus(ProgressInfo.ProgressStatus.FAILED);
            }
            return null;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    public static String computeFileHash(File file) {
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] buff = new byte[Config.CHUNK_SIZE];

            int byteRead;
            while ((byteRead = fileInputStream.read(buff)) != -1) {
                messageDigest.update(buff, 0, byteRead);
            }

            return bytesToHex(messageDigest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            Log.logError("Error computing hash for file: " + file.getAbsolutePath(), e);
            return "ERROR";
        }
    }
}
