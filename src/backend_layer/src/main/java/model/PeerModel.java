package main.java.model;

import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.domain.entity.ProgressInfo;
import main.java.model.submodel.*;
import main.java.utils.AppPaths;
import main.java.utils.Config;
import main.java.utils.Log;
import main.java.utils.SSLUtils;

import javax.net.ssl.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;


public class PeerModel implements IPeerModel {
    private final int CHUNK_SIZE;
    private final PeerInfo SERVER_HOST;
    private final PeerInfo TRACKER_HOST;
    private Selector selector;
    private SSLContext sslContext;
    private ConcurrentHashMap<SocketChannel, SSLEngine> sslEngineMap;
    private ConcurrentHashMap<SocketChannel, ByteBuffer> pendingData;
    private ConcurrentHashMap<SocketChannel, Map<String, Object>> channelAttachments;
    private ConcurrentHashMap<String, FileInfo> publicSharedFiles;
    private ConcurrentHashMap<FileInfo, Set<PeerInfo>> privateSharedFiles;
    private Set<FileInfo> sharedFileNames;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SSLSocket>> openChannels;
    private final ConcurrentHashMap<String, List<Future<Boolean>>> futures;
    private final ConcurrentHashMap<String, ProgressInfo> processes;
    private boolean isRunning;
    private final ReentrantLock fileLock;

    // Sub-models
    private final IFileDownloadModel fileDownloadModel;
    private final IFileShareModel fileShareModel;
    private final INetworkModel networkModel;
    private final IPeerDiscoveryModel peerDiscoveryModel;

    public PeerModel() throws IOException {
        this.CHUNK_SIZE = Config.CHUNK_SIZE;
        this.SERVER_HOST = new PeerInfo(Config.SERVER_IP, Config.PEER_PORT, AppPaths.loadUsername());
        this.TRACKER_HOST = new PeerInfo(Config.TRACKER_IP, Config.TRACKER_PORT);
        this.openChannels = new ConcurrentHashMap<>();
        this.futures = new ConcurrentHashMap<>();
        this.processes = new ConcurrentHashMap<>();
        this.fileLock = new ReentrantLock();
        this.publicSharedFiles = new ConcurrentHashMap<>();
        this.privateSharedFiles = new ConcurrentHashMap<>();
        this.sharedFileNames = new HashSet<>();
        this.executor = Executors.newFixedThreadPool(8);
        this.isRunning = true;

        // Instantiate sub-models
        this.fileDownloadModel = new FileDownloadModelImpl(this);
        this.fileShareModel = new FileShareModelImpl(this);
        this.networkModel = new NetworkModelImpl(this);
        this.peerDiscoveryModel = new PeerDiscoveryModelImpl(this);


        if (!SSLUtils.initializeSSLCertificates()) {
            Log.logError("Failed to initialize SSL certificates! SSL is mandatory for secure communication.", null);
            throw new RuntimeException("SSL certificate initialization failed");
        }

        Log.logInfo("Server socket initialized on " + this.SERVER_HOST.getIp() + ":" + this.SERVER_HOST.getPort());
        startTimeoutMonitor();
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveData));
    }

    private void startTimeoutMonitor() {
        this.executor.submit(() -> {
            Log.logInfo("Starting timeout monitor...");
            while (this.isRunning) {
                try {
                    Thread.sleep(10000); // Check every 10 seconds
                    checkForTimeouts();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.logInfo("Timeout monitor interrupted");
                    break;
                }
            }
            Log.logInfo("Timeout monitor stopped");
        });
    }

    private void checkForTimeouts() {
        long currentTime = System.currentTimeMillis();
        Log.logInfo("Checking for timeouts at " + currentTime);
        for (Map.Entry<String, ProgressInfo> entry : this.processes.entrySet()) {
            ProgressInfo progress = entry.getValue();
            String status = progress.getStatus();

            if ((status.equals(ProgressInfo.ProgressStatus.DOWNLOADING) ||
                    status.equals(ProgressInfo.ProgressStatus.SHARING)) &&
                    progress.isTimedOut()) {

                Log.logInfo("Download timeout detected for progress ID: " + entry.getKey() +
                        ", last update: " + (currentTime - progress.getLastProgressUpdateTime()) + "ms ago");

                synchronized (progress) {
                    if (status.equals(ProgressInfo.ProgressStatus.DOWNLOADING)) {
                        progress.setStatus(ProgressInfo.ProgressStatus.TIMEOUT);
                        Log.logInfo("Download marked as timed out: " + entry.getKey());
                    } else if (status.equals(ProgressInfo.ProgressStatus.SHARING)) {
                        Log.logInfo("Sharing operation appears stalled: " + entry.getKey());
                    }
                }
            }
        }
    }

    // Delegated methods from IFileDownloadModel
    @Override
    public void downloadFile(FileInfo fileInfo, File saveFile, List<PeerInfo> peers, String progressId) {
        fileDownloadModel.downloadFile(fileInfo, saveFile, peers, progressId);
    }

    @Override
    public void resumeDownload(String progressId) {
        fileDownloadModel.resumeDownload(progressId);
    }

    @Override
    public Map<String, ProgressInfo> getProgress() {
        return fileDownloadModel.getProgress();
    }

    @Override
    public void setProgress(ProgressInfo progressInfo) {
        fileDownloadModel.setProgress(progressInfo);
    }

    @Override
    public void cleanupProgress(List<String> progressIds) {
        fileDownloadModel.cleanupProgress(progressIds);
    }

    // Delegated methods from IFileShareModel
    @Override
    public boolean sharePublicFile(File file, String fileName, String progressId, int isReplace, FileInfo oldFileInfo) {
        return fileShareModel.sharePublicFile(file, fileName, progressId, isReplace, oldFileInfo);
    }

    @Override
    public boolean shareFileList(List<FileInfo> publicFiles, Map<FileInfo, Set<PeerInfo>> privateFiles) {
        return fileShareModel.shareFileList(publicFiles, privateFiles);
    }

    @Override
    public boolean shareFileToPeers(File file, FileInfo oldFileInfo, int isReplace, String progressId, List<String> peerList) {
        return fileShareModel.shareFileToPeers(file, oldFileInfo, isReplace, progressId, peerList);
    }

    @Override
    public int refreshSharedFileNames() {
        return fileShareModel.refreshSharedFileNames();
    }

    @Override
    public Set<FileInfo> getSharedFileNames() {
        return sharedFileNames;
    }

    @Override
    public void setSharedFileNames(Set<FileInfo> sharedFileNames) {
        this.sharedFileNames = sharedFileNames;
    }

    @Override
    public Map<String, FileInfo> getPublicSharedFiles() {
        return publicSharedFiles;
    }

    @Override
    public Map<FileInfo, Set<PeerInfo>> getPrivateSharedFiles() {
        return privateSharedFiles;
    }

    @Override
    public int stopSharingFile(String fileName) {
        return fileShareModel.stopSharingFile(fileName);
    }

    @Override
    public List<FileInfo> getPublicFiles() {
        return fileShareModel.getPublicFiles();
    }

    // Delegated methods from INetworkModel
    @Override
    public void initializeServerSocket() throws Exception {
        networkModel.initializeServerSocket();
    }

    @Override
    public void startServer() {
        networkModel.startServer();
    }

    @Override
    public void startUDPServer() {
        networkModel.startUDPServer();
    }

    @Override
    public int registerWithTracker() {
        return networkModel.registerWithTracker();
    }

    // Delegated methods from IPeerDiscoveryModel
    @Override
    public List<String> getKnownPeers() {
        return peerDiscoveryModel.getKnownPeers();
    }

    @Override
    public List<PeerInfo> getPeersWithFile(String fileHash) {
        return peerDiscoveryModel.getPeersWithFile(fileHash);
    }

    @Override
    public List<PeerInfo> getSelectivePeers(String fileHash) {
        return peerDiscoveryModel.getSelectivePeers(fileHash);
    }

    // Getters for state to be used by sub-models via IPeerModel
    public int getChunkSize() {
        return CHUNK_SIZE;
    }

    public PeerInfo getServerHost() {
        return SERVER_HOST;
    }

    public PeerInfo getTrackerHost() {
        return TRACKER_HOST;
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public ConcurrentHashMap<String, ProgressInfo> getProcesses() {
        return processes;
    }

    public ReentrantLock getFileLock() {
        return fileLock;
    }

    public ConcurrentHashMap<String, List<Future<Boolean>>> getFutures() {
        return futures;
    }

    public ConcurrentHashMap<String, CopyOnWriteArrayList<SSLSocket>> getOpenChannels() {
        return openChannels;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public void setRunning(boolean running) {
        isRunning = running;
    }

    public Selector getSelector() {
        return selector;
    }

    public void setSelector(Selector selector) {
        this.selector = selector;
    }

    public SSLContext getSslContext() {
        return sslContext;
    }

    public void setSslContext(SSLContext sslContext) {
        this.sslContext = sslContext;
    }

    public ConcurrentHashMap<SocketChannel, SSLEngine> getSslEngineMap() {
        return sslEngineMap;
    }

    public void setSslEngineMap(ConcurrentHashMap<SocketChannel, SSLEngine> sslEngineMap) {
        this.sslEngineMap = sslEngineMap;
    }

    public ConcurrentHashMap<SocketChannel, ByteBuffer> getPendingData() {
        return pendingData;
    }

    public void setPendingData(ConcurrentHashMap<SocketChannel, ByteBuffer> pendingData) {
        this.pendingData = pendingData;
    }

    public ConcurrentHashMap<SocketChannel, Map<String, Object>> getChannelAttachments() {
        return channelAttachments;
    }

    public void setChannelAttachments(ConcurrentHashMap<SocketChannel, Map<String, Object>> channelAttachments) {
        this.channelAttachments = channelAttachments;
    }

    // Utility/Cross-cutting methods
    public SSLSocket createSecureSocket(PeerInfo peerInfo) throws Exception {
        SSLSocketFactory sslSocketFactory = SSLUtils.createSSLSocketFactory();
        SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(peerInfo.getIp(), peerInfo.getPort());
        sslSocket.setUseClientMode(true);
        sslSocket.setNeedClientAuth(true);
        sslSocket.setSoTimeout(Config.SOCKET_TIMEOUT_MS);
        sslSocket.startHandshake();
        return sslSocket;
    }

    public String processSSLRequest(SocketChannel socketChannel, String request) {
        try {
            String clientIP = socketChannel.getRemoteAddress().toString().split(":")[0].replace("/", "");
            PeerInfo clientIdentifier = new PeerInfo(clientIP, SERVER_HOST.getPort());

            if (request.startsWith("SEARCH")) {
                String fileName = request.split("\\|")[1];
                FileInfo fileInfo = this.publicSharedFiles.get(fileName);
                if (fileInfo != null) {
                    return "FILE_INFO|" + fileName + "|" + fileInfo.getFileSize() + "|" +
                            fileInfo.getPeerInfo().getIp() + "|" + fileInfo.getPeerInfo().getPort() + "|" +
                            fileInfo.getFileHash() + "\n";
                } else {
                    return "FILE_NOT_FOUND|" + fileName + "\n";
                }
            } else if (request.startsWith("GET_CHUNK")) {
                String[] requestParts = request.split("\\|");
                String fileHash = requestParts[1];
                int chunkIndex = Integer.parseInt(requestParts[2]);
                if (this.hasAccessToFile(clientIdentifier, fileHash)) {
                    return getChunkData(fileHash, chunkIndex);
                } else {
                    return "ACCESS_DENIED\n";
                }
            } else if (request.startsWith("CHAT_MESSAGE")) {
                String[] messageParts = request.split("\\|", 3);
                if (messageParts.length >= 3) {
                    Log.logInfo("Processing SSL chat message from " + messageParts[1] + ": " + messageParts[2]);
                    return "CHAT_RECEIVED|" + messageParts[1] + "\n";
                } else {
                    return "ERROR|Invalid chat format\n";
                }
            }
        } catch (IOException e) {
            Log.logError("Error processing request: " + e.getMessage(), e);
        }
        return "UNKNOWN_REQUEST\n";
    }

    private String getChunkData(String fileHash, int chunkIndex) {
        FileInfo fileInfo = findFileByHash(fileHash);
        if (fileInfo == null) {
            return "FILE_NOT_FOUND\n";
        }

        String appPath = AppPaths.getAppDataDirectory();
        String filePath = appPath + "/shared_files/" + fileInfo.getFileName();
        File file = new File(filePath);

        if (file.exists() && file.canRead()) {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                byte[] chunkData = new byte[this.CHUNK_SIZE];
                raf.seek((long) chunkIndex * (long) this.CHUNK_SIZE);
                int byteRead = raf.read(chunkData);

                if (byteRead > 0) {
                    byte[] actualData = byteRead == this.CHUNK_SIZE ? chunkData : Arrays.copyOf(chunkData, byteRead);
                    return "CHUNK_DATA|" + chunkIndex + "|" + Base64.getEncoder().encodeToString(actualData) + "\n";
                }
            } catch (IOException e) {
                Log.logError("Error reading chunk data: " + e.getMessage(), e);
            }
        }
        return "CHUNK_ERROR\n";
    }

    private FileInfo findFileByHash(String fileHash) {
        for (FileInfo file : this.publicSharedFiles.values()) {
            if (file.getFileHash().equals(fileHash)) {
                return file;
            }
        }
        for (FileInfo file : this.privateSharedFiles.keySet()) {
            if (file.getFileHash().equals(fileHash)) {
                return file;
            }
        }
        return null;
    }

    private boolean hasAccessToFile(PeerInfo clientIdentify, String fileHash) {
        for (FileInfo file : this.publicSharedFiles.values()) {
            if (file.getFileHash().equals(fileHash)) {
                return true;
            }
        }
        List<PeerInfo> selectivePeers = this.getSelectivePeers(fileHash);
        return selectivePeers.stream().anyMatch(p -> p.getIp().equals(clientIdentify.getIp()));
    }

    public void loadData() {
        String dataDirPath = AppPaths.getAppDataDirectory() + File.separator + "data";
        File dataDir = new File(dataDirPath);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
            return;
        }

        File publicFile = new File(dataDirPath + File.separator + "publicSharedFiles.dat");
        if (publicFile.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(publicFile))) {
                this.publicSharedFiles = (ConcurrentHashMap<String, FileInfo>) ois.readObject();
                for (FileInfo fileInfo : this.publicSharedFiles.values()) {
                    fileInfo.getPeerInfo().setUsername(this.SERVER_HOST.getUsername());
                }
                Log.logInfo("Public shared files loaded successfully. " + this.publicSharedFiles.size() + " files found.");
            } catch (IOException | ClassNotFoundException | ClassCastException e) {
                Log.logError("Error loading public shared files: " + e.getMessage(), e);
            }
        }

        File privateFile = new File(dataDirPath + File.separator + "privateSharedFiles.dat");
        if (privateFile.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(privateFile))) {
                this.privateSharedFiles = (ConcurrentHashMap<FileInfo, Set<PeerInfo>>) ois.readObject();
                for (FileInfo fileInfo : this.privateSharedFiles.keySet()) {
                    fileInfo.getPeerInfo().setUsername(this.SERVER_HOST.getUsername());
                }
                Log.logInfo("Private shared files loaded successfully. " + this.privateSharedFiles.size() + " files found.");
            } catch (IOException | ClassNotFoundException | ClassCastException e) {
                Log.logError("Error loading private shared files: " + e.getMessage(), e);
            }
        }
    }

    private void saveData() {
        String dataDirPath = AppPaths.getAppDataDirectory() + File.separator + "data";
        File dataDir = new File(dataDirPath);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }

        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(dataDirPath + File.separator + "publicSharedFiles.dat"))) {
            oos.writeObject(this.publicSharedFiles);
            Log.logInfo("Public shared files saved successfully.");
        } catch (IOException e) {
            Log.logError("Error saving public shared files: " + e.getMessage(), e);
        }

        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(dataDirPath + File.separator + "privateSharedFiles.dat"))) {
            oos.writeObject(this.privateSharedFiles);
            Log.logInfo("Private shared files saved successfully.");
        } catch (IOException e) {
            Log.logError("Error saving private shared files: " + e.getMessage(), e);
        }
    }

    public String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    public String hashFile(File file, String progressId) {
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[this.CHUNK_SIZE];
            long fileSize = file.length();
            long readbyte = 0L;
            ProgressInfo progressInfo = this.processes.get(progressId);
            if (progressInfo != null && Objects.equals(progressInfo.getStatus(), ProgressInfo.ProgressStatus.CANCELLED)) {
                return null;
            } else {
                if (progressInfo != null) {
                    progressInfo.setStatus(ProgressInfo.ProgressStatus.SHARING);
                }

                int readedByteCount;
                while ((readedByteCount = fileInputStream.read(bytes)) != -1) {
                    if (progressInfo != null && progressInfo.getStatus().equals(ProgressInfo.ProgressStatus.CANCELLED)) {
                        return null;
                    }

                    messageDigest.update(bytes, 0, readedByteCount);
                    readbyte += readedByteCount;
                    if (progressInfo != null) {
                        int percentage = (int) ((double) readbyte * 25.0 / fileSize) + 70;
                        synchronized (progressInfo) {
                            progressInfo.setProgressPercentage(percentage);
                        }
                    }
                }

                return this.bytesToHex(messageDigest.digest());
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            ProgressInfo progressInfo = this.processes.get(progressId);
            if (progressInfo != null) {
                progressInfo.setStatus(ProgressInfo.ProgressStatus.FAILED);
            }
            return null;
        }
    }
}
