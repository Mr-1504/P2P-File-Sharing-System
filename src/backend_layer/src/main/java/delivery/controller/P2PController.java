package delivery.controller;

import domain.entity.*;
import domain.repository.IPeerJpaRepository;
import infras.repository.PeerJpaRepository;
import delivery.api.IP2PApi;
import service.*;
import utils.AppPaths;
import utils.Config;
import utils.Log;
import utils.LogTag;

import java.time.LocalDateTime;
import java.util.UUID;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P2PController serves as the main controller for the P2P file sharing application.
 * It manages initialization, user sessions, file sharing, downloading, and communication
 * with the frontend via the IP2PApi interface.
 */
public class P2PController {
    private final IFileService service;
    private final INetworkService networkService;
    private final IChatService chatService;
    private final IP2PApi api;
    private final IPeerJpaRepository peerRepository;
    private final ExecutorService executor = Executors.newFixedThreadPool(10);
    private volatile boolean isConnected = false;
    private volatile boolean isLoadSharedFiles = false;
    private volatile boolean isRetrying = false;

    // Username state management - blocks full initialization until set
    private String username = null;
    private volatile boolean isInitialized = false;

    /**
     * Constructor for P2PController.
     *
     * @param service        IFileService
     * @param networkService INetworkService
     * @param chatService    IChatService
     * @param api            IP2PApi
     */
    public P2PController(IFileService service, INetworkService networkService, IChatService chatService, IP2PApi api) {
        this.service = service;
        this.networkService = networkService;
        this.chatService = chatService;
        this.api = api;
        this.peerRepository = new PeerJpaRepository();
        this.username = AppPaths.loadUsername();
        setupApiRoutes();
    }

    /**
     * Starts the P2PController initialization process.
     * If a username is already set, it proceeds with full initialization.
     */
    public synchronized void start() {
        if (!checkUsernameExists()) {
            return;
        }
        performFullInitialization();
    }

    /**
     * Performs the full initialization tasks including
     * task initialization and tracker registration.
     */
    private void performFullInitialization() {
        taskInitialization();
        taskTrackerRegistration();
        isInitialized = true;
    }

    /**
     * Sets the username for the current session.
     * This method is synchronized to ensure thread safety.
     *
     * @param inputUsername The username to set
     * @return boolean indicating success of the operation
     */
    public synchronized boolean setUsername(String inputUsername) {
        this.username = inputUsername;

        // Save to backend
        if (!AppPaths.saveUsername(this.username)) {
            this.username = null;
            return false;
        }

        // Now start the full initialization if not already started
        if (!isInitialized) {
            performFullInitialization();
        }

        return true;
    }

    /**
     * Checks if a username already exists for the current session.
     * This method is synchronized to ensure thread safety.
     *
     * @return boolean indicating if username exists
     */
    public synchronized boolean checkUsernameExists() {
        if (this.username == null) {
            this.username = AppPaths.loadUsername();
            System.out.println("Loaded " + username);
        }
        System.out.println(username);
        return this.username != null;
    }

    /**
     * Gets the current username.
     * This method is synchronized to ensure thread safety.
     *
     * @return The current username
     */
    public synchronized String getUsername() {
        return username;
    }

    /**
     * Initializes the necessary tasks for the P2PController.
     */
    private void taskInitialization() {
        try {
            String shareDirPath = AppPaths.getAppDataDirectory() + "/shared_files/";
            File shareDir = new File(shareDirPath);
            if (!shareDir.exists()) {
                shareDir.mkdir();
            }
            networkService.initializeServerSocket();
            networkService.startServer();
            networkService.startUDPServer();
            isLoadSharedFiles = true;
            service.shareFileList();
        } catch (Exception e) {
            Log.logError("Initialization error", e);
        }
    }

    /**
     * Handles the registration process with the tracker server.
     * Retries registration until successful and shares the file list once connected.
     */
    private void taskTrackerRegistration() {
        try {
            registerWithTracker();
            while (!isLoadSharedFiles) {
                Thread.sleep(1000);
            }
            service.shareFileList();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Registers the peer with the tracker server.
     * Retries every 5 seconds until successful.
     */
    private void registerWithTracker() {
        while (!isConnected) {
            int result = networkService.registerWithTracker();
            switch (result) {
                case LogTag.I_SUCCESS, LogTag.I_NOT_FOUND -> isConnected = true;
                default -> {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    /**
     * Downloads a file given its FileInfo and the desired save path.
     *
     * @param fileInfo FileInfo of the file to download
     * @param savePath Path to save the downloaded file
     * @return String progress ID or error message
     */
    public String downloadFile(FileInfo fileInfo, String savePath) {
        return service.downloadFile(fileInfo, savePath);
    }

    /**
     * Removes a shared file by its name.
     *
     * @param fileName Name of the file to remove
     * @return int status code
     */
    public int removeFile(String fileName) {
        return service.stopSharingFile(fileName);
    }

    /**
     * Gets the current progress of all tasks.
     *
     * @return Map of progress IDs to ProgressInfo objects
     */
    public Map<String, ProgressInfo> getProgress() {
        return service.getProgress();
    }

    /**
     * Cleans up progress entries for the given task IDs.
     *
     * @param taskIds List of task IDs to clean up
     */
    public void cleanupProgress(List<String> taskIds) {
        service.cleanupProgress(taskIds);
    }

    /**
     * Cancels a task given its progress ID.
     *
     * @param progressId The progress ID of the task to cancel
     */
    public void cancelTask(String progressId) {
        Map<String, ProgressInfo> progressMap = service.getProgress();
        if (progressMap.containsKey(progressId)) {
            ProgressInfo progress = progressMap.get(progressId);
            progress.setStatus(ProgressInfo.ProgressStatus.CANCELLED);
            executor.submit(() -> {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                service.cleanupProgress(List.of(progressId));
            });
        }
    }

    /**
     * Resumes a task given its progress ID.
     *
     * @param progressId The progress ID of the task to resume
     */
    public void resumeTask(String progressId) {
        Map<String, ProgressInfo> progressMap = service.getProgress();
        if (progressMap.containsKey(progressId)) {
            ProgressInfo progress = progressMap.get(progressId);
            if (progress.getStatus().equals(ProgressInfo.ProgressStatus.TIMEOUT) ||
                    progress.getStatus().equals(ProgressInfo.ProgressStatus.STALLED)) {
                progress.setStatus(ProgressInfo.ProgressStatus.DOWNLOADING);
                progress.updateProgressTime();
            }
        }
    }

    /**
     * Checks for tasks that have timed out and updates their status accordingly.
     * This method is intended to be called periodically.
     */
    public void checkTimeouts() {
        Map<String, ProgressInfo> progressMap = service.getProgress();
        for (Map.Entry<String, ProgressInfo> entry : progressMap.entrySet()) {
            ProgressInfo progress = entry.getValue();
            if ((progress.getStatus().equals(ProgressInfo.ProgressStatus.DOWNLOADING) ||
                    progress.getStatus().equals(ProgressInfo.ProgressStatus.STARTING)) &&
                    progress.isTimedOut()) {
                progress.setStatus(ProgressInfo.ProgressStatus.TIMEOUT);
            }
        }
    }

    /**
     * Retries the connection to the tracker server if not already connected.
     */
    public void retryConnectToTracker() {
        if (isRetrying || username == null) {
            return;
        }
        isRetrying = true;
        taskTrackerRegistration();
    }

    /**
     * Sets up the API routes for communication with the frontend.
     */
    private void setupApiRoutes() {
        // Set up API routes for frontend communication
        api.setRouteForRefresh(() -> {
            if (!isConnected) {
                retryConnectToTracker();
                return -1; // Not connected
            }
            refreshFileList();
            return 0;
        });

        api.setRouteForSharePublicFile((filePath, isReplace, isCancelled) -> {
            if (!isConnected) {
                retryConnectToTracker();
                return LogTag.S_NOT_CONNECTION;
            }
            File file = new File(filePath);
            if (!file.exists()) {
                return LogTag.S_NOT_FOUND;
            }

            String fileName = file.getName();
            if (isReplace == 0) {
                fileName = AppPaths.incrementFileName(file.getName());
            }
            String progressId = ProgressInfo.generateProgressId();
            ProgressInfo newProgress = new ProgressInfo(progressId, ProgressInfo.ProgressStatus.STARTING, fileName, ProgressInfo.TaskType.SHARE);
            service.setProgress(newProgress);
            String finalFileName = fileName;
            executor.submit(() -> {
                service.sharePublicFile(filePath, isReplace, finalFileName, progressId);
            });

            return progressId;
        });

        api.setRouteForRemoveFile((filename) -> {
            if (!isConnected) {
                retryConnectToTracker();
                return -1;
            }
            return removeFile(filename);
        });

        api.setRouteForDownloadFile((file, savePath, isCancelled) -> {
            if (!isConnected) {
                retryConnectToTracker();
                return "Not connected";
            }
            return downloadFile(file, savePath);
        });

        api.setRouteForCheckFile(fileName -> {
            Set<FileInfo> sharedFiles = service.getFiles();
            return sharedFiles.stream().anyMatch(file -> file.getFileName().equals(fileName) && file.isSharedByMe());
        });

        api.setRouteForGetProgress(this::getProgress);

        api.setRouteForCleanupProgress((request) -> cleanupProgress(request.taskIds));

        api.setRouteForCancelTask(this::cancelTask);

        api.setRouteForResumeTask(this::resumeTask);

        api.setRouteForPauseDownload(this::pauseDownload);

        api.setRouteForResumeDownload(this::resumeDownload);

        api.setRouteForSharePrivateFile((filePath, isReplace, peerList) -> {
            if (!isConnected) {
                retryConnectToTracker();
                return LogTag.S_NOT_CONNECTION;
            }
            File file = new File(filePath);
            if (!file.exists()) {
                return LogTag.S_NOT_FOUND;
            }

            String fileName = file.getName();
            if (isReplace == 0) {
                fileName = AppPaths.incrementFileName(file.getName());
            }
            String progressId = ProgressInfo.generateProgressId();
            ProgressInfo newProgress = new ProgressInfo(progressId, ProgressInfo.ProgressStatus.STARTING, fileName, ProgressInfo.TaskType.SHARE);
            service.setProgress(newProgress);
            String finalFileName = fileName;
            executor.submit(() -> {
                service.sharePrivateFile(filePath, isReplace, finalFileName, peerList, progressId);
            });

            return progressId;
        });

        // Username management routes (highest priority - called during startup)
        api.setRouteForCheckUsername(this::checkUsernameExists);

        // Synchronous setter returns boolean indicating success
        api.setRouteForSetUsername(this::setUsername);

        api.setRouteForGetKnownPeers(this::getKnownPeers);

        api.setRouteForGetAllPeers(this::getAllPeers);

        api.setRouteForGetSharedPeers(this::getSharedPeers);

        api.setRouteForEditPermissions(this::editPermissions);

        // --- Chat API routes ---
        api.setRouteForSendPrivateMessage(chatService::sendPrivateMessage);

        api.setRouteForSendGroupMessage(chatService::sendGroupMessage);

        api.setRouteForCreatePrivateConversation((receiverId, receiverPublicKey) -> chatService.createPrivateConversation(receiverId, receiverPublicKey));

        api.setRouteForGetAllConversations(chatService::getAllConversations);

        api.setRouteForGetConversationById(chatService::getConversationById);

        api.setRouteForGetMessages(chatService::getMessages);

        api.setRouteForGetOfflineMessages(chatService::getOfflineMessages);

        api.setRouteForAcknowledgeMessages(chatService::acknowledgeMessages);

        api.setRouteForAddGroupMember(chatService::addGroupMember);

        api.setRouteForGetGroupMembers(chatService::getGroupMembers);

        // Start periodic timeout checker
        startTimeoutChecker();

        // Update API with current files
        updateApiFiles();
    }

    /**
     * Gets the list of known peers from the network service.
     * Filters out the current peer to avoid returning itself in the list.
     *
     * @return Set of PeerInfo objects representing known peers except the local one
     */
    public Set<PeerInfo> getKnownPeers() {
        Set<PeerInfo> peers = networkService.queryOnlinePeerList();
        peers.remove(new PeerInfo(Config.SERVER_IP, Config.PEER_PORT));
        return peers;
    }

    /**
     * Gets the list of all peers from the network service.
     * Filters out the current peer to avoid returning itself in the list.
     * Saves/updates peers in DB and creates private conversations using public key from Peer in DB.
     * Conversation name is set to username from PeerInfo if available, else ip:port.
     *
     * @return List of Peer objects representing all peers except the local one
     */
    public List<Peer> getAllPeers() {
        Set<PeerInfo> peerInfos = networkService.queryAllPeerInfo();
        Set<Peer> peers = networkService.queryAllPeers();
        // Remove current peer if present
        PeerInfo currentPeer = null;
        for (PeerInfo pi : peerInfos) {
            Log.logInfo("PeerInfo: " + pi.getIp() + ":" + pi.getPort() + " Username: " + pi.getUsername());
            if (pi.getIp().equals(Config.SERVER_IP) && pi.getPort() == Config.PEER_PORT) {
                currentPeer = pi;
                break;
            }
        }
        if (currentPeer != null) {
            peerInfos.remove(currentPeer);
        }

        List<Peer> result = new ArrayList<>();
        for (Peer peer : peers) {
            boolean found = false;
            for (PeerInfo peerInfo : peerInfos) {
                if (peer.getIp().equals(peerInfo.getIp()) && peer.getPort() == peerInfo.getPort()) {
                    // Create new Peer based on existing Peer from network service
                    Peer newPeer = new Peer();
                    newPeer.setId(peer.getId());
                    newPeer.setIp(peer.getIp());
                    newPeer.setPort(peer.getPort());
                    newPeer.setPublicKey(peer.getPublicKey());
                    newPeer.setOnline(true);
                    newPeer.setCreatedAt(LocalDateTime.now());
                    newPeer.setLastSeen(LocalDateTime.now());
                    peerRepository.savePeer(newPeer);
                    // Create or update conversation with name from PeerInfo and publicKey from Peer
                    String name = (peerInfo.getUsername() != null && !peerInfo.getUsername().isEmpty()) ? peerInfo.getUsername() : (peerInfo.getIp() + ":" + peerInfo.getPort());
                    if (newPeer.getPublicKey() != null && !newPeer.getPublicKey().trim().isEmpty()) {
                        chatService.createPrivateConversationIfNotExists(name, newPeer.getPublicKey());
                    }
                    result.add(newPeer);
                    found = true;
                    break;
                }
            }
            if (!found) {
                String name = peer.getIp() + ":" + peer.getPort();
                if (peer.getPublicKey() != null && !peer.getPublicKey().trim().isEmpty()) {
                    chatService.createPrivateConversationIfNotExists(name, peer.getPublicKey());
                }
                result.add(peer);
            }
        }
        return result;
    }

    public boolean editPermissions(String filename, String permission, List<PeerInfo> peersList) {
        try {
            for (FileInfo file : service.getPublicSharedFiles().values()) {
                if (file.getFileName().equals(filename)) {
                    return service.editPermission(file, permission, peersList);
                }
            }

            for (FileInfo file : service.getPrivateSharedFiles().keySet()) {
                if (file.getFileName().equals(filename)) {
                    return service.editPermission(file, permission, peersList);
                }
            }
            return false;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Refreshes the file list from the service and updates the API.
     */
    private void refreshFileList() {
        try {
            service.refreshFiles();
            updateApiFiles();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Gets the list of shared peers from the service.
     *
     * @return List of PeerInfo objects representing shared peers
     */
    private List<PeerInfo> getSharedPeers(String filename) {
        return service.getSharedPeers(filename);
    }

    /**
     * Updates the API with the current list of files from the service.
     */
    private void updateApiFiles() {
        Set<FileInfo> domainFiles = service.getFiles();
        api.setFiles(domainFiles.stream().toList());
    }

    /**
     * Pauses a download given its progress ID.
     *
     * @param progressId The progress ID of the download to pause
     * @return true if paused successfully, false otherwise
     */
    public boolean pauseDownload(String progressId) {
        return service.pauseDownload(progressId);
    }

    /**
     * Resumes a download given its progress ID.
     *
     * @param progressId The progress ID of the download to resume
     * @return true if resumed successfully, false otherwise
     */
    public boolean resumeDownload(String progressId) {
        return service.resumeDownload(progressId);
    }

    /**
     * Starts a periodic checker to monitor for task timeouts.
     */
    private void startTimeoutChecker() {
        executor.submit(() -> {
            while (true) {
                try {
                    Thread.sleep(30000); // Check every 30 seconds
                    checkTimeouts();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }
}
