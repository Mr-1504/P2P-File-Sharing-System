package domain.entity;

import java.util.Objects;
import java.io.Serializable;

/**
 * Represents information about a file shared by a peer in the network.
 * Includes file name, size, hash, peer information, and whether it is shared by the local user.
 * Extensively used for file sharing operations in a peer-to-peer network.
 */
public class FileInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    private String fileName;
    private long fileSize;
    private String fileHash;
    private PeerInfo peerInfo;
    private boolean isSharedByMe;

    /**
     * Constructor to initialize FileInfo with file details and peer information.
     *
     * @param fileName Name of the file
     * @param fileSize Size of the file in bytes
     * @param fileHash Hash of the file for integrity verification
     * @param peerInfo Information about the peer sharing the file
     */
    public FileInfo(String fileName, long fileSize, String fileHash, PeerInfo peerInfo) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.fileHash = fileHash;
        this.peerInfo = peerInfo;
    }

    /**
     * Constructor to initialize FileInfo with file details, peer information, and sharing status.
     *
     * @param fileName     Name of the file
     * @param fileSize     Size of the file in bytes
     * @param fileHash     Hash of the file for integrity verification
     * @param peerInfo     Information about the peer sharing the file
     * @param isSharedByMe Indicates if the file is shared by the local user
     */
    public FileInfo(String fileName, long fileSize, String fileHash, PeerInfo peerInfo, boolean isSharedByMe) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.fileHash = fileHash;
        this.peerInfo = peerInfo;
        this.isSharedByMe = isSharedByMe;
    }

    /**
     * Gets the name of the file.
     *
     * @return The file name
     */
    public String getFileName() {
        return fileName;
    }

    /**
     * Sets the name of the file.
     *
     * @param fileName The file name to set
     */
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    /**
     * Gets the size of the file.
     *
     * @return The file size in bytes
     */
    public long getFileSize() {
        return fileSize;
    }

    /**
     * Sets the size of the file.
     *
     * @param fileSize The file size in bytes to set
     */
    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    /**
     * Gets the hash of the file.
     *
     * @return The file hash
     */
    public String getFileHash() {
        return fileHash;
    }

    /**
     * Information about the peer sharing the file.
     *
     * @return The PeerInfo object representing the peer
     */
    public PeerInfo getPeerInfo() {
        return peerInfo;
    }

    /**
     * Checks if the file is shared by the local user.
     *
     * @return True if shared by the local user, false otherwise
     */
    public boolean isSharedByMe() {
        return isSharedByMe;
    }

    /**
     * Sets whether the file is shared by the local user.
     *
     * @param sharedByMe True if shared by the local user, false otherwise
     */
    public void setSharedByMe(boolean sharedByMe) {
        isSharedByMe = sharedByMe;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileInfo fileInfo = (FileInfo) o;
        return fileName.equals(fileInfo.fileName) &&
                peerInfo.equals(fileInfo.peerInfo) &&
                Objects.equals(fileHash, fileInfo.fileHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileName, peerInfo, fileHash);
    }

    @Override
    public String toString() {
        return fileName + "'" + fileSize + "'" + fileHash + "'" + peerInfo.getIp() + "'" + peerInfo.getPort();
    }
}
