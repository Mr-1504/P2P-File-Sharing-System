package main.java.usecases;

import main.java.domain.entities.ProgressInfo;
import main.java.domain.repositories.FileRepository;

import java.io.File;

public class ShareFileUseCase {
    private final FileRepository fileRepository;

    public ShareFileUseCase(FileRepository fileRepository) {
        this.fileRepository = fileRepository;
    }

    public String execute(String filePath, int isReplace) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                return "File not found";
            }

            String fileName = file.getName();

            // Check if file is already being shared
            boolean alreadyShared = fileRepository.getSharedFileNames().stream()
                    .anyMatch(f -> f.getFileName().equals(fileName) && f.isSharedByMe());

            if (alreadyShared && isReplace == 0) {
                return "File already shared";
            }

            // If replacing, stop sharing the existing file first
            if (alreadyShared && isReplace == 1) {
                fileRepository.stopSharingFile(fileName);
            }

            String progressId = ProgressInfo.generateProgressId();
            ProgressInfo progressInfo = new ProgressInfo(progressId, ProgressInfo.ProgressStatus.STARTING, fileName);
            fileRepository.setProgress(progressInfo);

            fileRepository.shareFileAsync(file, fileName, progressId);
            return progressId;
        } catch (Exception e) {
            return "Internal server error";
        }
    }
}
