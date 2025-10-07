package main.java.usecases;

import main.java.domain.entities.FileInfo;
import main.java.domain.entities.ProgressInfo;
import main.java.domain.repositories.FileRepository;
import main.java.utils.AppPaths;
import main.java.utils.LogTag;

import java.io.File;
import java.util.List;
import java.util.Set;

public class ShareFileUseCase {
    private final FileRepository fileRepository;

    public ShareFileUseCase(FileRepository fileRepository) {
        this.fileRepository = fileRepository;
    }

    public String execute(String filePath, int isReplace, String fileName, String progressId) {
        try {
            File file = new File(filePath);
            ProgressInfo progressInfo = fileRepository.getProgress().get(progressId);

            boolean result = AppPaths.copyFileToShare(file, fileName, progressInfo);

            if (!result) {
                progressInfo.setStatus(ProgressInfo.ProgressStatus.FAILED);
                return LogTag.S_ERROR;
            }

            FileInfo oldFileInfo = fileRepository.getPublicSharedFiles().get(file.getName());
            if (oldFileInfo == null) {
                oldFileInfo = fileRepository.getPrivateSharedFiles().keySet().stream()
                        .filter(f -> f.getFileName().equals(file.getName()))
                        .findFirst()
                        .orElse(null);
            }
            fileRepository.shareFileAsync(file, fileName, progressId, isReplace, oldFileInfo);
            return progressId;
        } catch (Exception e) {
            return "Internal server error";
        }
    }

    public String executeShareFileToSelectivePeer
            (String filePath, int isReplace, String fileName, List<String> peersList, String progressId) {
        File file = new File(filePath);
        ProgressInfo progressInfo = fileRepository.getProgress().get(progressId);

        boolean result = AppPaths.copyFileToShare(file, fileName, progressInfo);

        if (!result) {
            progressInfo.setStatus(ProgressInfo.ProgressStatus.FAILED);
            return LogTag.S_ERROR;
        }

        FileInfo oldFileInfo = fileRepository.getPublicSharedFiles().get(file.getName());
        if (oldFileInfo == null) {
            oldFileInfo = fileRepository.getPrivateSharedFiles().keySet().stream()
                    .filter(f -> f.getFileName().equals(file.getName()))
                    .findFirst()
                    .orElse(null);
        }

        fileRepository.shareFileToPeers(file, oldFileInfo, isReplace, progressId, peersList);
        return progressId;
    }

    private String getUniqueFileName(String baseName, Set<String> existingNames) {
        if (!existingNames.contains(baseName)) {
            return baseName;
        }
        String nameWithoutExt = baseName;
        String ext = "";
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex > 0) {
            nameWithoutExt = baseName.substring(0, dotIndex);
            ext = baseName.substring(dotIndex);
        }
        int counter = 1;
        String newName;
        do {
            newName = nameWithoutExt + "(" + counter + ")" + ext;
            counter++;
        } while (existingNames.contains(newName));
        return newName;
    }
}
