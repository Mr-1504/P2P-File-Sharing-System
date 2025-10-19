package main.java.infras.repository;

import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.domain.entity.ProgressInfo;
import main.java.domain.repository.*;
import main.java.infras.subrepo.*;
import main.java.infras.utils.FileUtils;
import main.java.utils.AppPaths;
import main.java.utils.Config;
import main.java.utils.Log;
import main.java.infras.utils.SSLUtils;

import javax.net.ssl.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;


public class PeerRepository implements IPeerRepository, AutoCloseable {
    private Selector selector;
    private SSLContext sslContext;
    private ConcurrentHashMap<SocketChannel, SSLEngine> sslEngineMap;
    private ConcurrentHashMap<SocketChannel, ByteBuffer> pendingData;
    private ConcurrentHashMap<SocketChannel, Map<String, Object>> channelAttachments;
    private final ConcurrentHashMap<String, FileInfo> publicSharedFiles;
    private final ConcurrentHashMap<FileInfo, Set<PeerInfo>> privateSharedFiles;
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
                    Thread.sleep(10000);
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
                    } else {
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

    @Override
    public void sharePublicFile(File file, String fileName, String progressId, int isReplace, FileInfo oldFileInfo) {
        fileShareModel.sharePublicFile(file, fileName, progressId, isReplace, oldFileInfo);
    }

    @Override
    public boolean shareFileList(List<FileInfo> publicFiles, Map<FileInfo, Set<PeerInfo>> privateFiles) {
        return fileShareModel.shareFileList(publicFiles, privateFiles);
    }

    @Override
    public void sharePrivateFile(File file, FileInfo oldFileInfo, int isReplace, String progressId, List<PeerInfo> peerList) {
        fileShareModel.sharePrivateFile(file, oldFileInfo, isReplace, progressId, peerList);
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
    public Set<PeerInfo> queryOnlinePeerList() {
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
        return processes;
    }

    public ReentrantLock getFileLock() {
        return fileLock;
    }

    public Map<String, List<Future<Boolean>>> getFutures() {
        return futures;
    }

    public Map<String, CopyOnWriteArrayList<SSLSocket>> getOpenChannels() {
        return openChannels;
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

    public void processSSLRequest(SocketChannel socketChannel, String request) {
        networkModel.processSSLRequest(socketChannel, request);
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
