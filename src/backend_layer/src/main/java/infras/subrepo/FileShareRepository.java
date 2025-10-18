package main.java.infras.subrepo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import main.java.domain.adapter.FileInfoAdapter;
import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.domain.entity.ProgressInfo;
import main.java.domain.repository.IFileShareRepository;
import main.java.domain.repository.IPeerRepository;
import main.java.infras.utils.FileUtils;
import main.java.infras.utils.SSLUtils;
import main.java.utils.*;

import javax.net.ssl.SSLSocket;
import java.io.*;
import java.lang.reflect.Type;
import java.util.*;

public class FileShareRepository implements IFileShareRepository {

    private final IPeerRepository peerModel;

    public FileShareRepository(IPeerRepository peerModel) {
        this.peerModel = peerModel;
    }

    @Override
    public void sharePublicFile(File file, String fileName, String progressId, int isReplace, FileInfo oldFileInfo) {
        try {
            if (isReplace == 1 && oldFileInfo != null) {
                this.unshareFile(oldFileInfo);
                peerModel.getPublicSharedFiles().remove(oldFileInfo.getFileName());
                peerModel.getPrivateSharedFiles().remove(oldFileInfo);
            }

            ProgressInfo progress = peerModel.getProgress().get(progressId);

            String fileHash = FileUtils.hashFile(file, progress);
            if (fileHash == null) {
                return;
            }

            FileInfo newFileInfo = new FileInfo(fileName, file.length(), fileHash, new PeerInfo(Config.SERVER_IP, Config.PEER_PORT, AppPaths.loadUsername()), true);
            List<FileInfo> fileInfos = new ArrayList<>();
            fileInfos.add(newFileInfo);
            boolean result = shareFileList(fileInfos, new HashMap<>());
            if (!result) {
                progress.setStatus(ProgressInfo.ProgressStatus.FAILED);
                peerModel.getExecutor().submit(() -> AppPaths.removeSharedFile(fileName));
                return;
            }
            this.peerModel.getPublicSharedFiles().put(fileName, newFileInfo);
            this.peerModel.getFiles().add(newFileInfo);


            synchronized (progress) {
                progress.setProgressPercentage(100);
                progress.setStatus(ProgressInfo.ProgressStatus.COMPLETED);
            }
        } catch (Exception e) {
            Log.logError("Error in shareFileAsync: " + e.getMessage(), e);
            ProgressInfo progress = this.peerModel.getProcesses().get(progressId);
            if (progress != null) {
                progress.setStatus(ProgressInfo.ProgressStatus.FAILED);
            }
        }
    }

    @Override
    public boolean shareFileList(List<FileInfo> publicFiles, Map<FileInfo, Set<PeerInfo>> privateFiles) {
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        try {
            PeerInfo sslTrackerHost = new PeerInfo(Config.TRACKER_IP, Config.TRACKER_PORT);
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
            try (SSLSocket sslSocket = SSLUtils.createSecureSocket(sslTrackerHost)) {
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
    public void sharePrivateFile(File file, FileInfo oldFileInfo, int isReplace, String progressId, List<String> peerList) {
        if (isReplace == 1 && oldFileInfo != null) {
            this.unshareFile(oldFileInfo);
            peerModel.getPublicSharedFiles().remove(oldFileInfo.getFileName(), oldFileInfo);
            peerModel.getPrivateSharedFiles().remove(oldFileInfo);
        }

        ProgressInfo progress = peerModel.getProgress().get(progressId);
        String fileHash = FileUtils.hashFile(file, progress);
        String fileName = file.getName();
        long fileSize = file.length();
        FileInfo sharedFile = new FileInfo(fileName, fileSize, fileHash, new PeerInfo(Config.SERVER_IP, Config.PEER_PORT, AppPaths.loadUsername()), true);

        Set<PeerInfo> peerInfos = new HashSet<>();
        for (String peer : peerList) {
            String[] parts = peer.split(":");
            if (parts.length == 2) {
                String ip = parts[0];
                int port = Integer.parseInt(parts[1]);
                peerInfos.add(new PeerInfo(ip, port));
            } else {
                Log.logInfo("Invalid peer format: " + peer);
                return;
            }
        }
        boolean result = shareFileList(new ArrayList<>(), Map.of(sharedFile, peerInfos));

        if (!result) {
            Log.logInfo("Failed to share file " + fileName + " to specific peers: " + peerList);
            peerModel.getExecutor().submit(() -> AppPaths.removeSharedFile(fileName));
            peerModel.getProcesses().get(progressId).setStatus(ProgressInfo.ProgressStatus.FAILED);
            return;
        }

        peerModel.getPrivateSharedFiles().put(sharedFile, new HashSet<>(peerInfos));
        Log.logInfo("Sharing file " + fileName + " (hash: " + fileHash + ") to specific peers: " + peerList);

        peerModel.getProcesses().get(progressId).setStatus(ProgressInfo.ProgressStatus.COMPLETED);
        peerModel.getProcesses().get(progressId).setProgressPercentage(100);
    }

    @Override
    public int refreshFiles() {
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }

        PeerInfo sslTrackerHost = new PeerInfo(Config.TRACKER_IP, Config.TRACKER_PORT);
        try (SSLSocket sslSocket = SSLUtils.createSecureSocket(sslTrackerHost)) {
            Log.logInfo("Established SSL connection to tracker for refreshing shared files");

            PrintWriter printWriter = new PrintWriter(sslSocket.getOutputStream(), true);
            String ip = Config.SERVER_IP;
            String request = "REFRESH|" + ip + "|" + Config.PEER_PORT + "\n";
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
                        this.peerModel.getFiles().clear();

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
                                this.peerModel.getFiles().add(fileInfo);
                            }
                        }

                        Log.logInfo("Refreshed shared file names from tracker: " + this.peerModel.getFiles().size() + " files found.");
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
    public Set<FileInfo> getFiles() {
        return this.peerModel.getFiles();
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
            this.peerModel.getFiles().removeIf((file) -> file.getFileName().equals(fileName));
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


    private int unshareFile(FileInfo fileInfo) {
        if (!SSLUtils.isSSLSupported()) {
            Log.logError("SSL certificates not found! SSL is now mandatory for security.", null);
            throw new IllegalStateException("SSL certificates required for secure communication");
        }


        PeerInfo sslTrackerHost = new PeerInfo(Config.TRACKER_IP, Config.TRACKER_PORT);
        try (SSLSocket sslSocket = SSLUtils.createSecureSocket(sslTrackerHost)) {
            Log.logInfo("Established SSL connection to tracker for unsharing file");

            String query = "UNSHARED_FILE" + "|" + fileInfo.getFileName() + "|" + fileInfo.getFileSize() + "|" + fileInfo.getFileHash() + "|" + Config.SERVER_IP + "|" + Config.PEER_PORT;
            sslSocket.getOutputStream().write(query.getBytes());
            Log.logInfo("Notified tracker about shared file: " + fileInfo.getFileName() + " via SSL, message: " + query);

            sslSocket.close();
            return 1;

        } catch (Exception e) {
            Log.logError("SSL Error notifying tracker about shared file: " + fileInfo.getFileName(), e);
            return 0;
        }
    }

}
