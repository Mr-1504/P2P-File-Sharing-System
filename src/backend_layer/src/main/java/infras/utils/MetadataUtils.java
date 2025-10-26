package infras.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;
import domain.entity.ChunkInfo;
import domain.entity.DownloadMetadata;
import utils.Log;
import utils.LogTag;

import java.io.*;
import java.lang.reflect.Type;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Utility class for managing download metadata persistence.
 * Handles serialization/deserialization of DownloadMetadata to/from JSON files.
 */
public class MetadataUtils {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
            .setPrettyPrinting()
            .create();

    /**
     * Save download metadata to a .meta file.
     *
     * @param metadata The metadata to save
     * @return true if saved successfully, false otherwise
     */
    public static boolean saveMetadata(DownloadMetadata metadata) {
        String metaFilePath = metadata.getMetaFilePath();
        metadata.updateLastModified();

        try (Writer writer = new FileWriter(metaFilePath)) {
            GSON.toJson(metadata, writer);
            Log.logInfo("Metadata saved to: " + metaFilePath);
            return true;
        } catch (IOException e) {
            Log.logError("Failed to save metadata to " + metaFilePath, e);
            return false;
        }
    }

    /**
     * Load download metadata from a .meta file.
     *
     * @param metaFilePath Path to the .meta file
     * @return DownloadMetadata object or null if loading fails
     */
    public static DownloadMetadata loadMetadata(String metaFilePath) {
        try (Reader reader = new FileReader(metaFilePath)) {
            DownloadMetadata metadata = GSON.fromJson(reader, DownloadMetadata.class);
            if (metadata != null) {
                Log.logInfo("Metadata loaded from: " + metaFilePath);
            }
            return metadata;
        } catch (IOException e) {
            Log.logError("Failed to load metadata from " + metaFilePath, e);
            return null;
        }
    }

    /**
     * Check if a resumable download exists for the given file path.
     *
     * @param filePath The target file path (without extensions)
     * @return true if both .meta and .part files exist
     */
    public static boolean existsResumableDownload(String filePath) {
        DownloadMetadata metadata = loadMetadata(filePath + ".part.meta");
        if (metadata == null) {
            return false;
        }

        File partFile = new File(metadata.getPartFilePath());
        return partFile.exists() && partFile.length() > 0;
    }

    /**
     * List all resumable downloads in a directory.
     *
     * @param directoryPath Directory to scan for .meta files
     * @return List of DownloadMetadata for resumable downloads
     */
    public static List<DownloadMetadata> listResumableDownloads(String directoryPath) {
        File directory = new File(directoryPath);
        if (!directory.exists() || !directory.isDirectory()) {
            Log.logError("Directory does not exist: " + directoryPath, null);
            return List.of();
        }

        return java.util.Arrays.stream(directory.listFiles((dir, name) -> name.endsWith(".part.meta")))
                .map(file -> loadMetadata(file.getAbsolutePath()))
                .filter(java.util.Objects::nonNull)
                .filter(metadata -> metadata.partFileExists())
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Delete metadata and part files for a completed download.
     *
     * @param metadata The download metadata
     * @return true if both files were deleted successfully
     */
    public static boolean cleanupMetadata(DownloadMetadata metadata) {
        boolean metaDeleted = true;
        boolean partDeleted = true;

        // Delete .meta file
        File metaFile = new File(metadata.getMetaFilePath());
        if (metaFile.exists()) {
            metaDeleted = metaFile.delete();
            if (metaDeleted) {
                Log.logInfo("Deleted metadata file: " + metadata.getMetaFilePath());
            } else {
                Log.logError("Failed to delete metadata file: " + metadata.getMetaFilePath(), null);
            }
        }

        // Delete .part file
        File partFile = new File(metadata.getPartFilePath());
        if (partFile.exists()) {
            partDeleted = partFile.delete();
            if (partDeleted) {
                Log.logInfo("Deleted part file: " + metadata.getPartFilePath());
            } else {
                Log.logError("Failed to delete part file: " + metadata.getPartFilePath(), null);
            }
        }

        return metaDeleted && partDeleted;
    }

    /**
     * Calculate checksum for a chunk of data.
     *
     * @param data The chunk data
     * @return SHA-256 checksum as hex string
     */
    public static String calculateChunkChecksum(byte[] data) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            messageDigest.update(data);
            return bytesToHex(messageDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            Log.logError("Failed to calculate chunk checksum", e);
            return null;
        }
    }

    /**
     * Convert byte array to hexadecimal string.
     *
     * @param bytes The byte array
     * @return Hexadecimal string representation
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Verify chunk data integrity using checksum.
     *
     * @param data The chunk data
     * @param expectedChecksum Expected SHA-256 checksum
     * @return true if checksum matches
     */
    public static boolean verifyChunkChecksum(byte[] data, String expectedChecksum) {
        if (expectedChecksum == null || expectedChecksum.isEmpty()) {
            return true; // No checksum to verify
        }

        String actualChecksum = calculateChunkChecksum(data);
        if (actualChecksum == null) {
            return false;
        }

        boolean matches = actualChecksum.equals(expectedChecksum);
        if (!matches) {
            Log.logInfo("Chunk checksum mismatch. Expected: " + expectedChecksum + ", Actual: " + actualChecksum);
        }

        return matches;
    }

    /**
     * Custom TypeAdapter for Instant serialization/deserialization.
     */
    private static class InstantTypeAdapter implements JsonSerializer<Instant>, JsonDeserializer<Instant> {
        private static final DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT;

        @Override
        public JsonElement serialize(Instant src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(formatter.format(src));
        }

        @Override
        public Instant deserialize(JsonElement json, Type typeOfSrc, JsonDeserializationContext context) {
            return Instant.from(formatter.parse(json.getAsString()));
        }
    }
}
