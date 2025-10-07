package main.java.frameworks_drivers.repositories;

import main.java.domain.entities.FileInfo;
import main.java.domain.entities.PeerInfo;
import main.java.domain.entities.ProgressInfo;
import main.java.domain.repositories.FileRepository;
import main.java.model.IPeerModel;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class FileRepositoryImpl implements FileRepository {
    private final IPeerModel peerModel;

    public FileRepositoryImpl(IPeerModel peerModel) {
        this.peerModel = peerModel;
    }

    @Override
    public void shareFileAsync(File file, String fileName, String progressId, int isReplace, FileInfo oldFileInfo) {
        peerModel.sharePublicFile(file, fileName, progressId, isReplace, oldFileInfo);
    }

    @Override
    public void shareFileList() {
        peerModel.shareFileList(peerModel.getPublicFiles(), peerModel.getPrivateSharedFiles());
    }

    @Override
    public void downloadFile(FileInfo fileInfo, File saveFile, List<PeerInfo> peers, String progressId) {
        FileInfo modelFileInfo = convertToModelFileInfo(fileInfo);
        List<PeerInfo> modelPeers = peers.stream()
                .map(this::convertToModelPeerInfo)
                .collect(Collectors.toList());

        peerModel.downloadFile(modelFileInfo, saveFile, modelPeers, progressId);
    }

    @Override
    public List<String> getKnownPeers() {
        return peerModel.getKnownPeers();
    }

    @Override
    public void resumeDownload(String progressId) {
        peerModel.resumeDownload(progressId);
    }

    @Override
    public List<PeerInfo> getPeersWithFile(String fileHash) {
        List<PeerInfo> modelPeers = peerModel.getPeersWithFile(fileHash);
        return modelPeers.stream()
                .map(this::convertToDomainPeerInfo)
                .collect(Collectors.toList());
    }

    @Override
    public boolean shareFileToPeers(File file, FileInfo oldFileInfo, int isReplace, String progressId, List<String> peerList) {
        return peerModel.shareFileToPeers(file, oldFileInfo, isReplace, progressId, peerList);
    }

    @Override
    public void loadSharedFiles() {
        peerModel.loadSharedFiles();
    }

    @Override
    public int refreshSharedFileNames() {
        return peerModel.refreshSharedFileNames();
    }

    @Override
    public Set<FileInfo> getSharedFileNames() {
        Set<FileInfo> modelFiles = peerModel.getSharedFileNames();
        return modelFiles.stream()
                .map(this::convertToDomainFileInfo)
                .collect(Collectors.toSet());
    }

    @Override
    public Map<String, FileInfo> getPublicSharedFiles() {
        Map<String, FileInfo> modelFiles = peerModel.getPublicSharedFiles();
        return modelFiles.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> convertToDomainFileInfo(e.getValue())
                ));
    }

    @Override
    public Map<FileInfo, Set<PeerInfo>> getPrivateSharedFiles() {
        return peerModel.getPrivateSharedFiles();
    }

    @Override
    public int stopSharingFile(String fileName) {
        return peerModel.stopSharingFile(fileName);
    }

    @Override
    public Map<String, ProgressInfo> getProgress() {
        Map<String, ProgressInfo> modelProgress = peerModel.getProgress();
        return modelProgress.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> convertToDomainProgressInfo(e.getValue())
                ));
    }

    @Override
    public void setProgress(ProgressInfo progressInfo) {
        ProgressInfo modelProgress = convertToModelProgressInfo(progressInfo);
        peerModel.setProgress(modelProgress);
    }

    @Override
    public void cleanupProgress(List<String> progressIds) {
        peerModel.cleanupProgress(progressIds);
    }

    // Conversion methods
    private FileInfo convertToDomainFileInfo(FileInfo modelFile) {
        PeerInfo peerInfo = convertToDomainPeerInfo(modelFile.getPeerInfo());
        return new FileInfo(modelFile.getFileName(), modelFile.getFileSize(),
                modelFile.getFileHash(), peerInfo, modelFile.isSharedByMe());
    }

    private FileInfo convertToModelFileInfo(FileInfo domainFile) {
        PeerInfo peerInfo = convertToModelPeerInfo(domainFile.getPeerInfo());
        return new FileInfo(domainFile.getFileName(), domainFile.getFileSize(),
                domainFile.getFileHash(), peerInfo, domainFile.isSharedByMe());
    }

    private PeerInfo convertToDomainPeerInfo(PeerInfo modelPeer) {
        return new PeerInfo(modelPeer.getIp(), modelPeer.getPort());
    }

    private PeerInfo convertToModelPeerInfo(PeerInfo domainPeer) {
        return new PeerInfo(domainPeer.getIp(), domainPeer.getPort());
    }

    private ProgressInfo convertToDomainProgressInfo(ProgressInfo modelProgress) {
        ProgressInfo domainProgress = new ProgressInfo(modelProgress.getId(), modelProgress.getStatus(),
                modelProgress.getFileName());
        domainProgress.setProgressPercentage(modelProgress.getProgressPercentage());
        domainProgress.setBytesTransferred(modelProgress.getBytesTransferred());
        domainProgress.setTotalBytes(modelProgress.getTotalBytes());
        domainProgress.setDownloadedChunks(modelProgress.getDownloadedChunks());
        domainProgress.setFileHash(modelProgress.getFileHash());
        domainProgress.setSavePath(modelProgress.getSavePath());
        domainProgress.setLastProgressUpdateTime(modelProgress.getLastProgressUpdateTime());
        domainProgress.setTimeoutThresholdMs(modelProgress.getTimeoutThresholdMs());
        return domainProgress;
    }

    private ProgressInfo convertToModelProgressInfo(ProgressInfo domainProgress) {
        ProgressInfo modelProgress = new ProgressInfo(domainProgress.getId(), domainProgress.getStatus(),
                domainProgress.getFileName());
        modelProgress.setProgressPercentage(domainProgress.getProgressPercentage());
        modelProgress.setBytesTransferred(domainProgress.getBytesTransferred());
        modelProgress.setTotalBytes(domainProgress.getTotalBytes());
        modelProgress.setDownloadedChunks(domainProgress.getDownloadedChunks());
        modelProgress.setFileHash(domainProgress.getFileHash());
        modelProgress.setSavePath(domainProgress.getSavePath());
        modelProgress.setLastProgressUpdateTime(domainProgress.getLastProgressUpdateTime());
        modelProgress.setTimeoutThresholdMs(domainProgress.getTimeoutThresholdMs());
        return modelProgress;
    }
}
