package utils;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

public class Log {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static synchronized void logToFile(String level, String message, Exception e) {
        // Lấy thông tin vị trí gọi log (class, method, line)
        StackTraceElement caller = Thread.currentThread().getStackTrace()[3];
        String callerInfo = String.format("(%s:%d#%s)",
                caller.getFileName(), caller.getLineNumber(), caller.getMethodName());

        String time = LocalDateTime.now().format(DATE_FORMAT);
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(time).append("] [").append(level).append("] ")
          .append(callerInfo).append(" ").append(message).append(System.lineSeparator());

        if (e != null) {
            sb.append("[EXCEPTION] ").append(e.toString()).append(System.lineSeparator());
            for (StackTraceElement el : e.getStackTrace()) {
                sb.append("\tat ").append(el.toString()).append(System.lineSeparator());
            }
        }

        Path logFile = AppPaths.getLogFilePath();
        try {
            // Ghi log
            Files.createDirectories(logFile.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(logFile,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(sb.toString());
            }

            // Tự động dọn log cũ hơn 7 ngày
            cleanOldLogs(logFile.getParent(), 7);

        } catch (IOException io) {
            System.err.println("[ERROR] Failed to write log: " + io.getMessage());
        }
    }

    private static void cleanOldLogs(Path logDir, int daysToKeep) {
        if (!Files.exists(logDir)) return;

        try (Stream<Path> files = Files.list(logDir)) {
            files.filter(Files::isRegularFile)
                 .filter(f -> f.getFileName().toString().endsWith(".log"))
                 .forEach(f -> {
                     try {
                         FileTime lastModified = Files.getLastModifiedTime(f);
                         long ageDays = Duration.between(
                                 lastModified.toInstant(), Instant.now()).toDays();
                         if (ageDays > daysToKeep) {
                             Files.deleteIfExists(f);
                             System.out.println("[LOG CLEANUP] Deleted old log: " + f.getFileName());
                         }
                     } catch (Exception ex) {
                         System.err.println("[LOG CLEANUP] Failed to delete " + f.getFileName() + ": " + ex.getMessage());
                     }
                 });
        } catch (IOException e) {
            System.err.println("[LOG CLEANUP] Error scanning log folder: " + e.getMessage());
        }
    }

    public static void logInfo(String message) {
        StackTraceElement caller = Thread.currentThread().getStackTrace()[2];
        String callerInfo = String.format("(%s:%d#%s)",
                caller.getFileName(), caller.getLineNumber(), caller.getMethodName());

        System.out.println("[INFO] " + callerInfo + " " + message);
        logToFile("INFO", message, null);
    }

    public static void logError(String message, Exception e) {
        StackTraceElement caller = Thread.currentThread().getStackTrace()[2];
        String callerInfo = String.format("(%s:%d#%s)",
                caller.getFileName(), caller.getLineNumber(), caller.getMethodName());

        System.err.println("[ERROR] " + callerInfo + " " + message);
        logToFile("ERROR", message, e);
    }
}
