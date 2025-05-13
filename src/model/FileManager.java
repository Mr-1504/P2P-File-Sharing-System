package model;

import java.io.*;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class FileManager {
    public static final String SHARED_DIR = "shared_files/";
    public static final String DOWNLOADED_DIR = "downloaded_files/";
    public static final int CHUNK_SIZE = 1024 * 1024; // 1MB

    public FileManager() {
        new File(SHARED_DIR).mkdirs();
        new File(DOWNLOADED_DIR).mkdirs();
    }

    public List<String> getFileList() {
        File dir = new File(SHARED_DIR);
        return Arrays.stream(dir.listFiles())
                .filter(File::isFile)
                .map(File::getName)
                .collect(Collectors.toList());
    }

    public List<FileInfor> getFileList(String clientIp, int clientPort) {
        File dir = new File(SHARED_DIR);
        return Arrays.stream(dir.listFiles())
                .filter(File::isFile)
                .map(file ->  new FileInfor(file.getName(), new PeerInfor(clientIp, clientPort)))
                .collect(Collectors.toList());
    }

    public List<String> searchFiles(String filename) {
        return getFileList().stream()
                .filter(name -> name.toLowerCase().contains(filename.toLowerCase()))
                .collect(Collectors.toList());
    }

    public byte[] getChunk(String filename, int chunkIndex) throws IOException, NoSuchAlgorithmException {
        File file = new File(SHARED_DIR + filename);
        if (!file.exists()) return null;

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            fis.skip((long) chunkIndex * CHUNK_SIZE);
            int bytesRead = fis.read(buffer);
            return bytesRead > 0 ? Arrays.copyOf(buffer, bytesRead) : null;
        }
    }

    public String getChunkHash(byte[] chunk) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(chunk);
        return bytesToHex(digest.digest());
    }

    public void saveFile(String filename, List<byte[]> chunks) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(DOWNLOADED_DIR + filename)) {
            for (byte[] chunk : chunks) {
                fos.write(chunk);
            }
        }
    }

    public boolean verifyFile(String filePath, String expectedHash) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(filePath)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        return bytesToHex(digest.digest()).equals(expectedHash);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}