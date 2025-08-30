package main.java.model;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

public interface IPeerModel {
    void cancelAction();

    void initializeServerSocket() throws IOException;

    void startServer();

    void startUDPServer();

    int registerWithTracker();

    Future<Boolean> shareFileAsync(File file, String fileName);

    void shareFileList();

    Future<Integer> downloadFile(FileInfor fileInfor, String savePath, List<PeerInfor> peers);

    List<PeerInfor> getPeersWithFile(String fileHash);

    void loadSharedFiles();

    int refreshSharedFileNames();

    Set<FileInfor> getSharedFileNames();

    void setSharedFileNames(Set<FileInfor> sharedFileNames);

    Map<String, FileInfor> getMySharedFiles();

    int stopSharingFile(String fileName);
}
