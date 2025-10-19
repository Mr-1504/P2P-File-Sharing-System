package main.java.domain.repository;

import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface IFileShareRepository {
    void sharePublicFile(File file, String fileName, String progressId, int isReplace, FileInfo oldFileInfo);

    boolean shareFileList(List<FileInfo> publicFiles, Map<FileInfo, Set<PeerInfo>> privateFiles);

    void sharePrivateFile(File file, FileInfo oldFileInfo, int isReplace, String progressId, List<PeerInfo> peerList);

    int refreshFiles();

    Set<FileInfo> getFiles();

    void setSharedFileNames(Set<FileInfo> sharedFileNames);

    Map<String, FileInfo> getPublicSharedFiles();

    Map<FileInfo, Set<PeerInfo>> getPrivateSharedFiles();

    int stopSharingFile(String fileName);

    List<FileInfo> getPublicFiles();
}
