package main.java.model.submodel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import main.java.domain.adapter.FileInfoAdapter;
import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.domain.entity.ProgressInfo;
import main.java.model.IPeerModel;
import main.java.utils.AppPaths;
import main.java.utils.Log;
import main.java.utils.LogTag;
import main.java.utils.SSLUtils;

import javax.net.ssl.SSLSocket;
import java.io.*;
import java.lang.reflect.Type;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class FileShareModelImpl implements IFileShareModel {

    private final IPeerModel peerModel;

    public FileShareModelImpl(IPeerModel peerModel) {
        this.peerModel = peerModel;
    }

    @Override
    public boolean sharePublicFile(File file, String fileName, String progressId, int isReplace, FileInfo oldFileInfo) {
        try {
            if (isReplace == 1 && oldFileInfo != null) {
                this.unshareFile(oldFileInfo);
                peerModel.getPublicSharedFiles().remove(oldFileInfo.getFileName());
                peerModel.getPrivateSharedFiles().remove(oldFileInfo);
            }

            String fileHash = this.hashFile(file, progressId);
            if (fileHash == null) {
                return false;
            }

            FileInfo newFileInfo = new FileInfo(fileName, file.length(), fileHash, this.peerModel.getServerHost(), true);
            List<FileInfo> fileInfos = new ArrayList<>();
            fileInfos.add(newFileInfo);
            ProgressInfo progress = this.peerModel.getProcesses().get(progressId);

            boolean result = shareFileList(fileInfos, new HashMap<>());
            if (!result) {
                if (progress != null) {
                    progress.setStatus(ProgressInfo.ProgressStatus.FAILED);
                }
                peerModel.getExecutor().submit(() -> AppPaths.removeSharedFile(fileName));
                return false;
            }
            this.peerModel.getPublicSharedFiles().put(fileName, newFileInfo);
            this.peerModel.getSharedFileNames().add(newFileInfo);


            if (progress != null) {
                synchronized (progress) {
                    progress.setProgressPercentage(100);
                    progress.setStatus(ProgressInfo.ProgressStatus.COMPLETED);
                }
            }
            return true;
        } catch (Exception e) {
            Log.logError("Error in shareFileAsync: " + e.getMessage(), e);
            ProgressInfo progress = this.peerModel.getProcesses().get(progressId);
            if (progress != null) {
                progress.setStatus(ProgressInfo.ProgressStatus.FAILED);
            }
            return false;
        }
    }

    @Override
    public boolean shareFileList(List<FileInfo> publicFiles, Map<FileInfo, Set<PeerInfo>> privateFiles) {
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        try {
            PeerInfo sslTrackerHost = new PeerInfo(this.peerModel.getTrackerHost().getIp(), this.peerModel.getTrackerHost().getPort());
            Log.logInfo("Established SSL connection to tracker for sharing files");
            StringBuilder messageBuilder = new StringBuilder("SHARE|");
            messageBuilder.append(publicFiles.size()).append("|")
                    .append(privateFiles.size()).append("|");

            Gson gson = new GsonBuilder().registerTypeAdapter(FileInfo.class, new FileInfoAdapter())
                    .enableComplexMapKeySerialization().create();
            Type listType = new TypeToken<List<FileInfo>>() {
            }.getType();
            String publicFileToPeersJson = gson.toJson(publicFiles, listType);
            Type mapType = new TypeToken<Map<FileInfo, Set<PeerInfo>>>() {
            }.getType();
            String privateSharedFileJson = gson.toJson(privateFiles, mapType);

            messageBuilder.append(publicFileToPeersJson).append("|")
                    .append(privateSharedFileJson).append("\n");

            String message = messageBuilder.toString();
            Log.logInfo("Sent request: " + message);
            try (SSLSocket sslSocket = peerModel.createSecureSocket(sslTrackerHost)) {
                sslSocket.getOutputStream().write(message.getBytes());
                BufferedReader reader = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));
                String response = reader.readLine();
                Log.logInfo("Shared file list with tracker, response: " + response);
                return response.startsWith(LogTag.S_SUCCESS);
            }
        } catch (Exception e) {
            Log.logError("Error sharing file list with tracker: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean shareFileToPeers(File file, FileInfo oldFileInfo, int isReplace, String progressId, List<String> peerList) {
        if (isReplace == 1 && oldFileInfo != null) {
            this.unshareFile(oldFileInfo);
            peerModel.getPublicSharedFiles().remove(oldFileInfo.getFileName(), oldFileInfo);
            peerModel.getPrivateSharedFiles().remove(oldFileInfo);
        }
        String fileHash = this.hashFile(file, progressId);
        String fileName = file.getName();
        long fileSize = file.length();
        FileInfo sharedFile = new FileInfo(fileName, fileSize, fileHash, this.peerModel.getServerHost(), true);

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
        boolean result = shareFileList(new ArrayList<>(), Map.of(sharedFile, peerInfos));

        if (!result) {
            Log.logInfo("Failed to share file " + fileName + " to specific peers: " + peerList);
            peerModel.getExecutor().submit(() -> AppPaths.removeSharedFile(fileName));
            peerModel.getProcesses().get(progressId).setStatus(ProgressInfo.ProgressStatus.FAILED);
            return false;
        }

        peerModel.getPrivateSharedFiles().put(sharedFile, new HashSet<>(peerInfos));
        Log.logInfo("Sharing file " + fileName + " (hash: " + fileHash + ") to specific peers: " + peerList);

        peerModel.getProcesses().get(progressId).setStatus(ProgressInfo.ProgressStatus.COMPLETED);
        peerModel.getProcesses().get(progressId).setProgressPercentage(100);
        return result;
    }

    @Override
    public int refreshSharedFileNames() {
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        try {
            PeerInfo sslTrackerHost = new PeerInfo(this.peerModel.getTrackerHost().getIp(), this.peerModel.getTrackerHost().getPort());
            SSLSocket sslSocket = peerModel.createSecureSocket(sslTrackerHost);
            Log.logInfo("Established SSL connection to tracker for refreshing shared files");

            PrintWriter printWriter = new PrintWriter(sslSocket.getOutputStream(), true);
            String ip = this.peerModel.getServerHost().getIp();
            String request = "REFRESH|" + ip + "|" + this.peerModel.getServerHost().getPort() + "\n";
            printWriter.println(request);
            BufferedReader buffer = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));
            String response = buffer.readLine();
            if (response != null && response.startsWith("REFRESHED")) {
                String[] parts = response.split("\\|");
                if (parts.length != 3) {
                    Log.logInfo("Invalid response format from tracker: " + response);
                    return -1;
                } else {
                    int filesCount = Integer.parseInt(parts[1]);
                    String[] fileParts = parts[2].split(",");
                    if (fileParts.length != filesCount) {
                        Log.logInfo("File count mismatch: expected " + filesCount + ", got " + fileParts.length);
                        return -1;
                    } else {
                        this.peerModel.getSharedFileNames().clear();

                        for (String file : fileParts) {
                            String[] f = file.split("'");
                            if (f.length != 6) {
                                Log.logInfo("Invalid file info format: " + file);
                            } else {
                                String fileName = f[0];
                                long fileSize = Long.parseLong(f[1]);
                                String fileHash = f[2];
                                PeerInfo peerInfo = new PeerInfo(f[3], Integer.parseInt(f[4]), f[5]);
                                FileInfo fileInfo = new FileInfo(fileName, fileSize, fileHash, peerInfo, false);
                                boolean isSharedByMe = this.peerModel.getPublicSharedFiles().containsKey(fileName) || this.peerModel.getPrivateSharedFiles().containsKey(fileInfo);
                                fileInfo.setSharedByMe(isSharedByMe);
                                this.peerModel.getSharedFileNames().add(fileInfo);
                            }
                        }

                        Log.logInfo("Refreshed shared file names from tracker: " + this.peerModel.getSharedFileNames().size() + " files found.");
                        return 1;
                    }
                }
            } else {
                Log.logInfo("Invalid response from tracker: " + response);
                return -1;
            }
        } catch (Exception e) {
            Log.logError("Error refreshing shared file names from tracker: " + e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public Set<FileInfo> getSharedFileNames() {
        return this.peerModel.getSharedFileNames();
    }

    @Override
    public void setSharedFileNames(Set<FileInfo> sharedFileNames) {
        this.peerModel.setSharedFileNames(sharedFileNames);
    }

    @Override
    public Map<String, FileInfo> getPublicSharedFiles() {
        return this.peerModel.getPublicSharedFiles();
    }

    @Override
    public Map<FileInfo, Set<PeerInfo>> getPrivateSharedFiles() {
        return this.peerModel.getPrivateSharedFiles();
    }

    @Override
    public int stopSharingFile(String fileName) {
        if (!this.peerModel.getPublicSharedFiles().containsKey(fileName)) {
            Log.logInfo("File not found in shared files: " + fileName);
            return 2;
        } else {
            FileInfo fileInfo = this.peerModel.getPublicSharedFiles().get(fileName);
            this.peerModel.getPublicSharedFiles().remove(fileName);
            this.peerModel.getSharedFileNames().removeIf((file) -> file.getFileName().equals(fileName));
            String appPath = AppPaths.getAppDataDirectory();
            String filePath = appPath + "/shared_files/" + fileName;
            File file = new File(filePath);
            if (file.exists() && file.delete()) {
                Log.logInfo("Removed shared file: " + filePath);
            } else {
                Log.logInfo("Failed to remove shared file: " + filePath);
            }

            Log.logInfo("Stopped sharing file: " + fileName);
            return this.unshareFile(fileInfo);
        }
    }

    @Override
    public List<FileInfo> getPublicFiles() {
        return new ArrayList<>(peerModel.getPublicSharedFiles().values());
    }

    private String hashFile(File file, String progressId) {
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[this.peerModel.getChunkSize()];
            long fileSize = file.length();
            long readbyte = 0L;
            ProgressInfo progressInfo = this.peerModel.getProcesses().get(progressId);
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

                return this.bytesToHex(messageDigest.digest());
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            this.peerModel.getProcesses().get(progressId).setStatus(ProgressInfo.ProgressStatus.FAILED);
            return null;
        }
    }

    private int unshareFile(FileInfo fileInfo) {
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        try {
            PeerInfo sslTrackerHost = new PeerInfo(this.peerModel.getTrackerHost().getIp(), this.peerModel.getTrackerHost().getPort());
            SSLSocket sslSocket = peerModel.createSecureSocket(sslTrackerHost);
            Log.logInfo("Established SSL connection to tracker for unsharing file");

            String query = "UNSHARED_FILE" + "|" + fileInfo.getFileName() + "|" + fileInfo.getFileSize() + "|" + fileInfo.getFileHash() + "|" + this.peerModel.getServerHost().getIp() + "|" + this.peerModel.getServerHost().getPort();
            sslSocket.getOutputStream().write(query.getBytes());
            Log.logInfo("Notified tracker about shared file: " + fileInfo.getFileName() + " via SSL, message: " + query);

            sslSocket.close();
            return 1;

        } catch (Exception e) {
            Log.logError("SSL Error notifying tracker about shared file: " + fileInfo.getFileName(), e);
            return 0;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();

        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }

        return hex.toString();
    }
}
