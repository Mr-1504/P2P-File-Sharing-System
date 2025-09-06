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
