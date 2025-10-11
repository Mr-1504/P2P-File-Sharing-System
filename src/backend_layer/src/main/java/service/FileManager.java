package main.java.service;

import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.domain.entity.ProgressInfo;
import main.java.domain.repository.FileRepository;
import main.java.utils.AppPaths;
import main.java.utils.Log;

import javax.net.ssl.SSLSocket;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FileManager implements FileRepository {
    private final int CHUNK_SIZE;
    private final ConcurrentHashMap<String, FileInfo> publicSharedFiles;
    private final ConcurrentHashMap<FileInfo, Set<PeerInfo>> privateSharedFiles;
    private final Set<FileInfo> sharedFileNames;
    private final ConcurrentHashMap<String, ProgressInfo> processes;

    public FileManager(int chunkSize) {
        this.CHUNK_SIZE = chunkSize;
        this.publicSharedFiles = new ConcurrentHashMap<>();
        this.privateSharedFiles = new ConcurrentHashMap<>();
        this.sharedFileNames = new HashSet<>();
        this.processes = new ConcurrentHashMap<>();
        loadData();
    }

    public void shareFileAsync(File file, String fileName, String progressId, int isReplace, FileInfo oldFileInfo) throws Exception {
        boolean result = sharePublicFile(file, fileName, progressId, isReplace, oldFileInfo);
        if (!result) {
            throw new RuntimeException("Failed to share public file");
        }
    }

    public void shareFileList() {
        // Implementation would delegate to TrackerConnector in coordinator
        throw new UnsupportedOperationException("Use TrackerConnector for this operation");
    }

    public void downloadFile(FileInfo fileInfo, File saveFile, List<PeerInfo> peers, String progressId) {
        // Implementation would delegate to DownloadManager in coordinator
        throw new UnsupportedOperationException("Use DownloadManager for this operation");
    }

    public void resumeDownload(String progressId) {
        // Implementation would delegate to DownloadManager in coordinator
        throw new UnsupportedOperationException("Use DownloadManager for this operation");
    }

    public List<PeerInfo> getPeersWithFile(String fileHash) {
        // Implementation would delegate to TrackerConnector in coordinator
        throw new UnsupportedOperationException("Use TrackerConnector for this operation");
    }

    public boolean shareFileToPeers(File file, FileInfo oldFileInfo, int isReplace, String progressId, List<String> peerList) throws Exception {
        try {
            if (isReplace == 1 && oldFileInfo != null) {
                this.unshareFile(oldFileInfo);
                publicSharedFiles.remove(oldFileInfo.getFileName());
                privateSharedFiles.remove(oldFileInfo);
            }
            String fileHash = this.hashFile(file, progressId);
            String fileName = file.getName();
            long fileSize = file.length();
            FileInfo sharedFile = new FileInfo(fileName, fileSize, fileHash, null, true); // peerInfo will be set by coordinator

            Set<PeerInfo> peerInfos = new HashSet<>();
            for (String peer : peerList) {
                String[] parts = peer.split(":");
                if (parts.length == 2) {
                    String ip = parts[0];
                    int port = Integer.parseInt(parts[1]);
                    peerInfos.add(new PeerInfo(ip, port));
                } else {
                    Log.logInfo("Invalid peer format: " + peer);
                    return false;
                }
            }

            // Note: shareFileList would be called here in coordinator, but returns boolean result
            privateSharedFiles.put(sharedFile, new HashSet<>(peerInfos));
            Log.logInfo("Sharing file " + fileName + " (hash: " + fileHash + ") to specific peers: " + peerList);

            processes.get(progressId).setStatus(ProgressInfo.ProgressStatus.COMPLETED);
            processes.get(progressId).setProgressPercentage(100);
            return true; // Assume success, actual shareFileList call in coordinator
        } catch (Exception e) {
            Log.logError("Error in shareFileToPeers: " + e.getMessage(), e);
            if (processes.get(progressId) != null) {
                processes.get(progressId).setStatus(ProgressInfo.ProgressStatus.FAILED);
            }
            return false;
        }
    }

    public int refreshSharedFileNames() {
        // Implementation would delegate to TrackerConnector in coordinator
        throw new UnsupportedOperationException("Use TrackerConnector for this operation");
    }

    public List<String> getKnownPeers() {
        // Implementation would delegate to TrackerConnector in coordinator
        throw new UnsupportedOperationException("Use TrackerConnector for this operation");
    }

    public Set<FileInfo> getSharedFileNames() {
        return this.sharedFileNames;
    }

    public Map<String, FileInfo> getPublicSharedFiles() {
        return this.publicSharedFiles;
    }

    public Map<FileInfo, Set<PeerInfo>> getPrivateSharedFiles() {
        return this.privateSharedFiles;
    }

    public int stopSharingFile(String fileName) {
        if (!this.publicSharedFiles.containsKey(fileName)) {
            Log.logInfo("File not found in shared files: " + fileName);
            return 2;
        } else {
            FileInfo fileInfo = this.publicSharedFiles.get(fileName);
            this.publicSharedFiles.remove(fileName);
            this.sharedFileNames.removeIf((file) -> file.getFileName().equals(fileName));
            String appPath = AppPaths.getAppDataDirectory();
            String filePath = appPath + "/shared_files/" + fileName;
            File file = new File(filePath);
            if (file.exists() && file.delete()) {
                Log.logInfo("Removed shared file: " + filePath);
            } else {
                Log.logInfo("Failed to remove shared file: " + filePath);
            }

            Log.logInfo("Stopped sharing file: " + fileName);
            // Note: unshareFile call would be done in coordinator via TrackerConnector
            return 1; // Assume success
        }
    }

    public Map<String, ProgressInfo> getProgress() {
        return this.processes;
    }

    public void setProgress(ProgressInfo progressInfo) {
        this.processes.put(progressInfo.getId(), progressInfo);
    }

    public void cleanupProgress(List<String> progressIds) {
        for (String progressId : progressIds) {
            this.processes.remove(progressId);
            // Note: futures and openChannels cleanup handled by DownloadManager
        }
    }

    public boolean sharePublicFile(File file, String fileName, String progressId, int isReplace, FileInfo oldFileInfo) {
        try {
            if (isReplace == 1 && oldFileInfo != null) {
                this.unshareFile(oldFileInfo);
                publicSharedFiles.remove(oldFileInfo.getFileName());
                privateSharedFiles.remove(oldFileInfo);
            }

            String fileHash = this.hashFile(file, progressId);
            if (fileHash == null) {
                return false;
            }

            FileInfo newFileInfo = new FileInfo(fileName, file.length(), fileHash, null, true); // peerInfo set by coordinator
            this.publicSharedFiles.put(fileName, newFileInfo);
            this.sharedFileNames.add(newFileInfo);

            ProgressInfo progress = this.processes.get(progressId);
            if (progress != null) {
                synchronized (progress) {
                    progress.setProgressPercentage(100);
                    progress.setStatus(ProgressInfo.ProgressStatus.COMPLETED);
                }
            }
            return true;
        } catch (Exception e) {
            Log.logError("Error in shareFileAsync: " + e.getMessage(), e);
            ProgressInfo progress = this.processes.get(progressId);
            if (progress != null) {
                progress.setStatus(ProgressInfo.ProgressStatus.FAILED);
            }
            return false;
        }
    }

    private void unshareFile(FileInfo fileInfo) {
        // This would typically notify tracker via TrackerConnector in coordinator
        Log.logInfo("Notifying tracker about shared file: " + fileInfo.getFileName());
        // Actual network call handled by TrackerConnector
    }

    private String hashFile(File file, String progressId) {
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[this.CHUNK_SIZE];
            long fileSize = file.length();
            long readbyte = 0L;
            ProgressInfo progressInfo = this.processes.get(progressId);
            if (Objects.equals(progressInfo.getStatus(), ProgressInfo.ProgressStatus.CANCELLED)) {
                return null;
            } else {
                progressInfo.setStatus(ProgressInfo.ProgressStatus.SHARING);

                int readedByteCount;
                while ((readedByteCount = fileInputStream.read(bytes)) != -1) {
                    if (progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED)) {
                        return null;
                    }

                    messageDigest.update(bytes, 0, readedByteCount);
                    readbyte += readedByteCount;
                    int percentage = (int) ((double) readbyte * (double) 25.0F / (double) fileSize) + 70;
                    synchronized (progressInfo) {
                        progressInfo.setProgressPercentage(percentage);
                    }
                }

                return bytesToHex(messageDigest.digest());
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            this.processes.get(progressId).setStatus(ProgressInfo.ProgressStatus.FAILED);
            return null;
        }
    }

    public String computeFileHash(File file) {
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] buff = new byte[this.CHUNK_SIZE];

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

    private String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();

        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }

        return hex.toString();
    }

    public boolean hasAccessToFile(PeerInfo clientIdentify, String fileHash) {
        for (FileInfo file : this.publicSharedFiles.values()) {
            if (file.getFileHash().equals(fileHash)) {
                return true;
            }
        }
        List<PeerInfo> selectivePeers = getSelectivePeers(fileHash);
        return selectivePeers.contains(clientIdentify);
    }

    public List<PeerInfo> getSelectivePeers(String fileHash) {
        List<PeerInfo> peers = new ArrayList<>();
        for (Map.Entry<FileInfo, Set<PeerInfo>> entry : privateSharedFiles.entrySet()) {
            FileInfo fileInfo = entry.getKey();
            if (fileInfo.getFileHash().equals(fileHash)) {
                peers.addAll(entry.getValue());
                break;
            }
        }
        return peers;
    }

    public void sendChunkSSL(SSLSocket sslSocket, String fileHash, int chunkIndex) {
        FileInfo fileInfo = null;

        for (FileInfo file : this.publicSharedFiles.values()) {
            if (file.getFileHash().equals(fileHash)) {
                fileInfo = file;
                break;
            }
        }

        if (fileInfo == null) {
            Log.logInfo("File not found in shared files: " + fileHash);
        } else {
            String appPath = AppPaths.getAppDataDirectory();
            String filePath = appPath + "/shared_files/" + fileInfo.getFileName();
            File file = new File(filePath);
            if (file.exists() && file.canRead()) {
                try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                    byte[] chunkbyte = new byte[this.CHUNK_SIZE];
                    raf.seek((long) chunkIndex * (long) this.CHUNK_SIZE);
                    int byteReads = raf.read(chunkbyte);
                    if (byteReads <= 0) {
                        Log.logInfo("No bytes read for chunk " + chunkIndex + " of file " + fileInfo.getFileName() + ". File may be empty or chunk index is out of bounds.");
                    } else {
                        try (DataOutputStream dos = new DataOutputStream(sslSocket.getOutputStream())) {
                            dos.writeInt(chunkIndex);
                            dos.write(chunkbyte, 0, byteReads);
                            dos.flush();
                            Log.logInfo("Chunk " + chunkIndex + " of file " + fileInfo.getFileName() + " for " + fileHash + " sent successfully. Bytes sent: " + (byteReads + 4));
                        }
                    }
                } catch (IOException e) {
                    Log.logError("Error sending chunk " + chunkIndex + " of file " + fileInfo.getFileName() + " via SSL: " + e.getMessage(), e);
                }
            } else {
                Log.logInfo("File does not exist or cannot be read: " + filePath);
            }
        }
    }

    public FileInfo getPublicFileInfo(String fileName) {
        return this.publicSharedFiles.get(fileName);
    }

    // save persistent data
    private void saveData() {
        String dataDirPath = AppPaths.getAppDataDirectory() + File.separator + "data";
        File dataDir = new File(dataDirPath);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }

        // Save public shared files
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(dataDirPath + File.separator + "publicSharedFiles.dat"))) {
            oos.writeObject(this.publicSharedFiles);
            Log.logInfo("Public shared files saved successfully.");
        } catch (IOException e) {
            Log.logError("Error saving public shared files: " + e.getMessage(), e);
        }

        //save private shared files
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(dataDirPath + File.separator + "privateSharedFiles.dat"))) {
            oos.writeObject(this.privateSharedFiles);
            Log.logInfo("Private shared files saved successfully.");
        } catch (IOException e) {
            Log.logError("Error saving private shared files: " + e.getMessage(), e);
        }
    }

    private void loadData() {
        String dataDirPath = AppPaths.getAppDataDirectory() + File.separator + "data";
        File dataDir = new File(dataDirPath);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
            return;
        }

        // Load public shared files
        File publicFile = new File(dataDirPath + File.separator + "publicSharedFiles.dat");
        if (publicFile.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(publicFile))) {
                this.publicSharedFiles.putAll((ConcurrentHashMap<String, FileInfo>) ois.readObject());
                Log.logInfo("Public shared files loaded successfully. " + this.publicSharedFiles.toString() + " files found.");
            } catch (IOException | ClassNotFoundException e) {
                Log.logError("Error loading public shared files: " + e.getMessage(), e);
            }
        }

        // Load private shared files
        File privateFile = new File(dataDirPath + File.separator + "privateSharedFiles.dat");
        if (privateFile.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(privateFile))) {
                this.privateSharedFiles.putAll((ConcurrentHashMap<FileInfo, Set<PeerInfo>>) ois.readObject());
                Log.logInfo("Private shared files loaded successfully. " + this.privateSharedFiles.toString() + " files found.");
            } catch (IOException | ClassNotFoundException e) {
                Log.logError("Error loading private shared files: " + e.getMessage(), e);
            }
        }
    }
}
