package main.java.infras;

import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.domain.entity.ProgressInfo;
import main.java.domain.repository.*;
import main.java.infras.subrepo.*;
import main.java.infras.utils.FileUtils;
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


public class PeerRepository implements IPeerRepository, AutoCloseable {
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
    private final String username;

    // Sub-models
    private final IFileDownloadRepository fileDownloadModel;
    private final IFileShareRepository fileShareModel;
    private final INetworkRepository networkModel;
    private final IPeerDiscoveryRepository peerDiscoveryModel;

    public PeerRepository() {
        this.openChannels = new ConcurrentHashMap<>();
        this.futures = new ConcurrentHashMap<>();
        this.processes = new ConcurrentHashMap<>();
        this.fileLock = new ReentrantLock();
        this.publicSharedFiles = new ConcurrentHashMap<>();
        this.privateSharedFiles = new ConcurrentHashMap<>();
        this.sharedFileNames = new HashSet<>();
        this.executor = Executors.newFixedThreadPool(8);
        this.isRunning = true;
        this.username = AppPaths.loadUsername();
        // Instantiate sub-models
        this.fileDownloadModel = new FileDownloadRepository(this);
        this.fileShareModel = new FileShareRepository(this);
        this.networkModel = new NetworkRepository(this);
        this.peerDiscoveryModel = new PeerDiscoveryRepository(this);


        if (!SSLUtils.initializeSSLCertificates()) {
            Log.logError("Failed to initialize SSL certificates! SSL is mandatory for secure communication.", null);
            throw new RuntimeException("SSL certificate initialization failed");
        }

        Log.logInfo("Server socket initialized on " + Config.SERVER_IP + ":" + Config.PEER_PORT);
        startTimeoutMonitor();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> FileUtils.saveData(this.publicSharedFiles, this.privateSharedFiles)) );
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
    public boolean sharePrivateFile(File file, FileInfo oldFileInfo, int isReplace, String progressId, List<String> peerList) {
        return fileShareModel.sharePrivateFile(file, oldFileInfo, isReplace, progressId, peerList);
    }

    @Override
    public int refreshFiles() {
        return fileShareModel.refreshFiles();
    }

    @Override
    public Set<FileInfo> getFiles() {
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

    @Override
    public void initializeServerSocket(String username) throws Exception {
        networkModel.initializeServerSocket(username);
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
    public List<String> queryOnlinePeerList() {
        return peerDiscoveryModel.queryOnlinePeerList();
    }

    @Override
    public List<PeerInfo> getPeersWithFile(String fileHash) {
        return peerDiscoveryModel.getPeersWithFile(fileHash);
    }

    @Override
    public List<PeerInfo> getSelectivePeers(String fileHash) {
        return peerDiscoveryModel.getSelectivePeers(fileHash);
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public Map<String, ProgressInfo> getProcesses() {
        return Collections.unmodifiableMap(processes);
    }

    public ReentrantLock getFileLock() {
        return fileLock;
    }

    public Map<String, List<Future<Boolean>>> getFutures() {
        return Collections.unmodifiableMap(futures);
    }

    public Map<String, CopyOnWriteArrayList<SSLSocket>> getOpenChannels() {
        return Collections.unmodifiableMap(openChannels);
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
        return networkModel.processSSLRequest(socketChannel, request);
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
            byte[] bytes = new byte[Config.CHUNK_SIZE];
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

    @Override
    public void close() {
        for (Future<Boolean> future : futures.values().stream().flatMap(List::stream).toList()) {
            future.cancel(true);
        }
        this.isRunning = false;
        this.executor.shutdown();
        FileUtils.saveData(publicSharedFiles, privateSharedFiles);
    }
}
