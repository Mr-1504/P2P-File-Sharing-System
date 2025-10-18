package main.java.infras.utils;

import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.utils.AppPaths;
import main.java.utils.Log;

import java.io.*;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FileUtils {
    public static void saveData(Map<String, FileInfo> publicSharedFiles, Map<FileInfo, java.util.Set<PeerInfo>> privateSharedFiles) {
        String dataDirPath = AppPaths.getAppDataDirectory() + File.separator + "data";
        File dataDir = new File(dataDirPath);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }

        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(dataDirPath + File.separator + "publicSharedFiles.dat"))) {
            oos.writeObject(publicSharedFiles);
            Log.logInfo("Public shared files saved successfully.");
        } catch (IOException e) {
            Log.logError("Error saving public shared files: " + e.getMessage(), e);
        }

        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(dataDirPath + File.separator + "privateSharedFiles.dat"))) {
            oos.writeObject(privateSharedFiles);
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

        File publicFile = new File(dataDirPath, "publicSharedFiles.dat");
        if (publicFile.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(publicFile))) {
                Map<String, FileInfo> loaded = (ConcurrentHashMap<String, FileInfo>) ois.readObject();
                for (FileInfo fileInfo : loaded.values()) {
                    fileInfo.getPeerInfo().setUsername(username);
                }
                publicSharedFiles.clear();
                publicSharedFiles.putAll(loaded);
                Log.logInfo("Public shared files loaded successfully. " + publicSharedFiles.size() + " files found.");
            } catch (IOException | ClassNotFoundException | ClassCastException e) {
                Log.logError("Error loading public shared files: " + e.getMessage(), e);
            }
        }

        File privateFile = new File(dataDirPath, "privateSharedFiles.dat");
        if (privateFile.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(privateFile))) {
                Map<FileInfo, Set<PeerInfo>> loaded = (ConcurrentHashMap<FileInfo, Set<PeerInfo>>) ois.readObject();
                for (FileInfo fileInfo : loaded.keySet()) {
                    fileInfo.getPeerInfo().setUsername(username);
                }
                privateSharedFiles.clear();
                privateSharedFiles.putAll(loaded);
                Log.logInfo("Private shared files loaded successfully. " + privateSharedFiles.size() + " files found.");
            } catch (IOException | ClassNotFoundException | ClassCastException e) {
                Log.logError("Error loading private shared files: " + e.getMessage(), e);
            }
        }
    }
}
