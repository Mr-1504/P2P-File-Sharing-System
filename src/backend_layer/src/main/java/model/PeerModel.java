package main.java.model;

import main.java.domain.entities.FileInfo;
import main.java.domain.entities.PeerInfo;
import main.java.domain.entities.ProgressInfo;
import main.java.utils.AppPaths;
import main.java.utils.Infor;
import main.java.utils.Log;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class PeerModel implements IPeerModel {
    private final int CHUNK_SIZE;
    private final PeerInfo SERVER_HOST;
    private final PeerInfo TRACKER_HOST;
    private final Selector selector;
    private final Map<String, FileInfo> mySharedFiles;
    private Set<FileInfo> sharedFileNames;
    private final ExecutorService executor;
    private final Map<String, CopyOnWriteArrayList<SocketChannel>> openChannels;
    private final Map<String, List<Future<Boolean>>> futures;
    private final Map<String, ProgressInfo> processes;
    private boolean isRunning;
    private volatile boolean cancelled;
    private final ReentrantLock fileLock;

    public PeerModel() throws IOException {
        this.CHUNK_SIZE = Infor.CHUNK_SIZE;
        this.SERVER_HOST = new PeerInfo(Infor.SERVER_IP, Infor.SERVER_PORT);
        this.TRACKER_HOST = new PeerInfo(Infor.TRACKER_IP, Infor.TRACKER_PORT);
        this.openChannels = new HashMap<>();
        this.futures = new HashMap<>();
        this.processes = new ConcurrentHashMap<>();
        this.cancelled = false;
        this.fileLock = new ReentrantLock();
        this.mySharedFiles = new HashMap<>();
        this.sharedFileNames = new HashSet<>();
        this.executor = Executors.newFixedThreadPool(8);
        this.selector = Selector.open();
        this.isRunning = true;
        Log.logInfo("Server socket initialized on " + this.SERVER_HOST.getIp() + ":" + this.SERVER_HOST.getPort());
    }

    public void initializeServerSocket() throws IOException {
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(new InetSocketAddress(this.SERVER_HOST.getIp(), this.SERVER_HOST.getPort()));
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.register(this.selector, SelectionKey.OP_ACCEPT);
        Log.logInfo("Server socket created on " + this.SERVER_HOST.getIp() + ":" + this.SERVER_HOST.getPort());
    }

    public void startServer() {
        this.executor.submit(() -> {
            Log.logInfo("Starting TCP server loop...");

            try {
                while(this.isRunning) {
                    this.selector.select();
                    Iterator<SelectionKey> selectedKeysIterator = this.selector.selectedKeys().iterator();

                    while(selectedKeysIterator.hasNext()) {
                        SelectionKey currentKey = selectedKeysIterator.next();
                        selectedKeysIterator.remove();
                        if (currentKey.isValid()) {
                            try {
                                if (currentKey.isAcceptable()) {
                                    this.acceptConnection(currentKey);
                                } else if (currentKey.isReadable()) {
                                    this.handleRead(currentKey);
                                }
                            } catch (IOException processingException) {
                                Log.logError("Error processing key: " + currentKey, processingException);
                                currentKey.cancel();
                                this.closeChannel(currentKey.channel());
                            }
                        }
                    }
                }
            } catch (IOException serverException) {
                Log.logError("TCP server error: " + serverException.getMessage(), serverException);
            } finally {
                this.shutdown();
            }

        });
    }

    private void shutdown() {
        Log.logInfo("Shutting down TCP server...");
        this.isRunning = false;

        try {
            this.selector.close();
            Log.logInfo("Selector closed.");
        } catch (IOException var3) {
            Log.logError("Error closing selector: " + var3.getMessage(), var3);
        }

        this.executor.shutdown();

        try {
            if (!this.executor.awaitTermination(5L, TimeUnit.SECONDS)) {
                this.executor.shutdownNow();
                Log.logInfo("Executor service forcefully shut down.");
            }
        } catch (InterruptedException var2) {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
            Log.logError("Executor service interrupted during shutdown: " + var2.getMessage(), var2);
        }

        Log.logInfo("TCP server shutdown complete.");
    }

    private void closeChannel(SelectableChannel channel) {
        if (channel != null && channel.isOpen()) {
            try {
                channel.close();
                Log.logInfo("Closed channel: " + channel);
            } catch (IOException closeException) {
                Log.logError("Error closing channel: " + channel, closeException);
            }
        }
    }

    public void startUDPServer() {
        this.executor.submit(() -> {
            try (DatagramSocket udpSocket = new DatagramSocket(this.SERVER_HOST.getPort())) {
                byte[] buffer = new byte[1024];
                Log.logInfo("UDP server started on " + this.SERVER_HOST.getIp() + ":" + this.SERVER_HOST.getPort());

                while(this.isRunning) {
                    DatagramPacket receivedPacket = new DatagramPacket(buffer, buffer.length);
                    udpSocket.receive(receivedPacket);
                    String receivedMessage = new String(receivedPacket.getData(), 0, receivedPacket.getLength());
                    Log.logInfo("Received UDP request: " + receivedMessage);
                    if (receivedMessage.equals("PING")) {
                        this.sendPongResponse(udpSocket, receivedPacket);
                    }
                }
            } catch (IOException udpException) {
                Log.logError("UDP server error: " + udpException.getMessage(), udpException);
            }

        });
    }

    private void sendPongResponse(DatagramSocket udpSocket, DatagramPacket receivedPacket) throws IOException {
        byte[] pongData = "PONG".getBytes();
        DatagramPacket pongPacket = new DatagramPacket(pongData, pongData.length, receivedPacket.getAddress(), receivedPacket.getPort());
        udpSocket.send(pongPacket);
        Log.logInfo("Pong response sent to " + receivedPacket.getAddress() + ":" + receivedPacket.getPort());
    }

    private void handleRead(SelectionKey clientKey) {
        SocketChannel clientChannel = (SocketChannel) clientKey.channel();

        try {
            StringBuilder requestBuilder = new StringBuilder();
            int bufferSize = 8192;
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            Log.logInfo("Handling read operation for key: " + clientKey + ", client: " + clientChannel.getRemoteAddress());
            int totalBytesRead = 0;

            while (true) {
                buffer.clear();
                int bytesRead = clientChannel.read(buffer);
                Log.logInfo("Read " + bytesRead + " bytes from " + clientChannel.getRemoteAddress());
                if (bytesRead == -1) {
                    Log.logInfo("Client closed connection");
                    break;
                }

                if (bytesRead == 0 && totalBytesRead > 0) {
                    Log.logInfo("No more data to read, processing request");
                    break;
                }

                if (bytesRead == 0) {
                    Thread.sleep(100L);
                } else {
                    totalBytesRead += bytesRead;
                    buffer.flip();
                    String chunk = new String(buffer.array(), 0, buffer.limit());
                    requestBuilder.append(chunk);
                    Log.logInfo("Current request: [" + requestBuilder + "]");
                    if (requestBuilder.toString().contains("\n")) {
                        Log.logInfo("Request complete, breaking loop");
                        break;
                    }

                    if (bytesRead == buffer.capacity()) {
                        bufferSize *= 2;
                        Log.logInfo("Increasing buffer size to " + bufferSize + " bytes");
                        buffer = ByteBuffer.allocate(bufferSize);
                    }
                }
            }

            String completeRequest = requestBuilder.toString().trim();
            Log.logInfo("Final request: [" + completeRequest + "]");
            if (!completeRequest.isEmpty()) {
                String clientIP = clientChannel.getRemoteAddress().toString().split(":")[0].replace("/", "");
                String clientIdentifier = clientIP + "|" + this.SERVER_HOST.getPort();
                if (completeRequest.startsWith("SEARCH")) {
                    String fileName = completeRequest.split("\\|")[1];
                    Log.logInfo("Processing SEARCH for file: " + fileName);
                    this.sendFileInfor(clientChannel, fileName);
                    return;
                } else if (completeRequest.startsWith("GET_CHUNK")) {
                    String[] requestParts = completeRequest.split("\\|");
                    String fileHash = requestParts[1];
                    int chunkIndex = Integer.parseInt(requestParts[2]);
                    Log.logInfo("Processing GET_CHUNK for file hash: " + fileHash + ", chunk: " + chunkIndex);
                    if (this.hasAccessToFile(clientIdentifier, fileHash)) {
                        this.sendChunk(clientChannel, fileHash, chunkIndex);
                    } else {
                        Log.logInfo("Access denied for peer " + clientIdentifier + " to file " + fileHash);
                        this.sendAccessDenied(clientChannel);
                    }

                    return;
                } else {
                    Log.logInfo("Unknown request: [" + completeRequest + "]");
                    return;
                }
            }

            Log.logInfo("Empty request received");
        } catch (InterruptedException | IOException processingException) {
            Log.logError("Error handling read: " + processingException.getMessage(), processingException);
        } finally {
            try {
                clientChannel.close();
                clientKey.cancel();
                Log.logInfo("Closed client connection: " + clientChannel.getLocalAddress() + " -> " + clientChannel.getRemoteAddress());
            } catch (IOException closeException) {
                Log.logError("Error closing client: " + closeException.getMessage(), closeException);
            }

        }

    }

    private void sendFileInfor(SocketChannel clientChannel, String fileName) {
        try {
            FileInfo fileInfo = this.mySharedFiles.get(fileName);
            String response = getResponse(fileName, fileInfo);
            ByteBuffer responseBuffer = ByteBuffer.wrap(response.getBytes());
            int totalBytesWritten = 0;

            while(responseBuffer.hasRemaining()) {
                int bytesWritten = clientChannel.write(responseBuffer);
                totalBytesWritten += bytesWritten;
                Log.logInfo("Wrote " + bytesWritten + " bytes, total: " + totalBytesWritten);
            }

            Log.logInfo("Sent file information for: " + fileName + ", response: [" + response.trim() + "]");
        } catch (IOException sendException) {
            Log.logError("Error sending file info: " + sendException.getMessage(), sendException);
        }
    }

    private static String getResponse(String var0, FileInfo var1) {
        String var2;
        if (var1 != null) {
            String var10000 = var1.getFileName();
            var2 = "FILE_INFO|" + var10000 + "|" + var1.getFileSize() + "|" + var1.getPeerInfo().getIp() + "|" + var1.getPeerInfo().getPort() + "|" + var1.getFileHash() + "\n";
        } else {
            var2 = "FILE_NOT_FOUND|" + var0 + "\n";
        }

        return var2;
    }

    private void acceptConnection(SelectionKey var1) throws IOException {
        ServerSocketChannel var2 = (ServerSocketChannel)var1.channel();
        SocketChannel var3 = var2.accept();
        Log.logInfo("Accepted connection from: " + var3.getRemoteAddress());
        var3.configureBlocking(false);
        var3.register(this.selector, 1);
    }

    public int registerWithTracker() {
        int var1 = 0;

        while(var1 < Infor.MAX_RETRIES) {
            try (Socket var2 = this.createSocket(this.TRACKER_HOST)) {
                String var10000 = this.SERVER_HOST.getIp();
                String var3 = "REGISTER|" + var10000 + "|" + this.SERVER_HOST.getPort() + "\n";
                var2.getOutputStream().write(var3.getBytes());
                Log.logInfo("Registered with tracker: " + var3);
                BufferedReader var4 = new BufferedReader(new InputStreamReader(var2.getInputStream()));
                String var5 = var4.readLine();
                Log.logInfo("check: " + var5);
                if (var5 != null) {
                    if (!var5.startsWith("SHARED_LIST")) {
                        if (var5.startsWith("REGISTERED")) {
                            return 2;
                        } else {
                            Log.logInfo("Tracker registration failed or no response: " + var5);
                            return 0;
                        }
                    } else {
                        Log.logInfo("Tracker registration successful: " + var5);
                        String[] var24 = var5.split("\\|");
                        if (var24.length < 2) {
                            Log.logInfo("Invalid response format from tracker: " + var5);
                            return -1;
                        } else if (var24.length != 3) {
                            Log.logInfo("Invalid response format from tracker: " + var5);
                            return -1;
                        } else {
                            String[] var7 = var24[2].split(",");
                            Log.logInfo("Files shared by tracker: " + Arrays.toString(var7));

                            for(String var11 : var7) {
                                String[] var12 = var11.split("'");
                                if (var12.length != 5) {
                                    Log.logInfo("Invalid file info format: " + var11);
                                } else {
                                    String var13 = var12[0];
                                    long var14 = Long.parseLong(var12[1]);
                                    String var16 = var12[2];
                                    PeerInfo var17 = new PeerInfo(var12[3], Integer.parseInt(var12[4]));
                                    this.sharedFileNames.add(new FileInfo(var13, var14, var16, var17));
                                }
                            }

                            Log.logInfo("Total files shared by tracker: " + this.sharedFileNames.size());
                            return 1;
                        }
                    }
                } else {
                    Log.logInfo("Tracker did not respond.");
                    return 0;
                }
            } catch (ConnectException var21) {
                Log.logError("Tracker connection failed: " + var21.getMessage(), var21);

                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException var18) {
                    Thread.currentThread().interrupt();
                }

                ++var1;
            } catch (IOException var22) {
                Log.logError("Error connecting to tracker: " + var22.getMessage(), var22);
                return 0;
            } catch (Exception var23) {
                Log.logError("Unexpected error during tracker registration: " + var23.getMessage(), var23);
                return 0;
            }
        }

        Log.logError("Cannot connect to tracker after multiple attempts.", null);
        return 0;
    }

    public void shareFileAsync(File var1, String var2, String var3) {
        this.executor.submit(() -> {
            String var4 = this.hashFile(var1, var3);
            FileInfo var5 = new FileInfo(var2, var1.length(), var4, this.SERVER_HOST, true);
            this.mySharedFiles.put(var1.getName(), var5);
            this.notifyTracker(var5, true);
            ProgressInfo var6 = this.processes.get(var3);
            var6.setProgressPercentage(100);
            var6.setStatus("completed");
            return Boolean.TRUE;
        });
    }

    private String hashFile(File var1, String var2) {
        try (FileInputStream var3 = new FileInputStream(var1)) {
            MessageDigest var4 = MessageDigest.getInstance("SHA-256");
            byte[] var5 = new byte[this.CHUNK_SIZE];
            long var7 = var1.length();
            long var9 = 0L;
            ProgressInfo var11 = this.processes.get(var2);
            if (Objects.equals(var11.getStatus(), "canceled")) {
                return null;
            } else {
                var11.setStatus("sharing");
                var11.setTotalBytes(var7);
                var11.setBytesTransferred(var9);

                int var6;
                while((var6 = var3.read(var5)) != -1) {
                    if (var11.getStatus().equals("canceled")) {
                        var11.setStatus("canceled");
                        return null;
                    }

                    var4.update(var5, 0, var6);
                    var9 += var6;
                    int var12 = (int)((double)var9 * (double)100.0F / (double)var7);
                    var11.setProgressPercentage(var12);
                    var11.setBytesTransferred(var9);
                }

                String var18 = this.bytesToHex(var4.digest());
                return var18;
            }
        } catch (NoSuchAlgorithmException | IOException var16) {
            this.processes.get(var2).setStatus("ERROR");
            return null;
        }
    }

    public void shareFileList() {
        Set<FileInfo> var1 = this.convertSharedFilesToFileBase();

        try {
            try (Socket var2 = new Socket(this.TRACKER_HOST.getIp(), this.TRACKER_HOST.getPort())) {
                StringBuilder var3 = new StringBuilder("SHARED_LIST|");
                var3.append(var1.size()).append("|");

                for(FileInfo var5 : var1) {
                    var3.append(var5.getFileName()).append("'").append(var5.getFileSize()).append("'").append(var5.getFileHash()).append(",");
                }

                if (var3.charAt(var3.length() - 1) == ',') {
                    var3.deleteCharAt(var3.length() - 1);
                }

                var3.append("|").append(this.SERVER_HOST.getIp()).append("|").append(this.SERVER_HOST.getPort()).append("\n");
                String var9 = var3.toString();
                var2.getOutputStream().write(var9.getBytes());
                Log.logInfo("Shared file list with tracker: " + var9);
            }

        } catch (IOException var8) {
            throw new RuntimeException(var8);
        }
    }

    private Set<FileInfo> convertSharedFilesToFileBase() {
        HashSet<FileInfo> var1 = new HashSet();

        for(Map.Entry<String, FileInfo> var3 : this.mySharedFiles.entrySet()) {
            FileInfo var4 = var3.getValue();
            var1.add(var4);
        }

        return var1;
    }

    private int notifyTracker(FileInfo var1, boolean var2) {
        try {
            byte var5;
            try (Socket var3 = new Socket(this.TRACKER_HOST.getIp(), this.TRACKER_HOST.getPort())) {
                String var4 = (var2 ? "SHARE" : "UNSHARED_FILE") + "|" + var1.getFileName() + "|" + var1.getFileSize() + "|" + var1.getFileHash() + "|" + this.SERVER_HOST.getIp() + "|" + this.SERVER_HOST.getPort();
                var3.getOutputStream().write(var4.getBytes());
                String var10000 = var1.getFileName();
                Log.logInfo("Notified tracker about shared file: " + var10000 + ", message: " + var4);
                var5 = 1;
            }

            return var5;
        } catch (IOException var8) {
            Log.logError("Error notifying tracker about shared file: " + var1.getFileName(), var8);
            return 0;
        }
    }

    private String bytesToHex(byte[] var1) {
        StringBuilder var2 = new StringBuilder();

        for(byte var6 : var1) {
            var2.append(String.format("%02x", var6));
        }

        return var2.toString();
    }

    public void downloadFile(FileInfo var1, File var2, List<PeerInfo> var3, String var4) {
        this.executor.submit(() -> this.processDownload(var1, var2, var4, var3));
    }

    private Integer processDownload(FileInfo var1, File var2, String var3, List<PeerInfo> var4) {
        this.futures.put(var3, new ArrayList());
        this.openChannels.put(var3, new CopyOnWriteArrayList());
        AtomicInteger var5 = new AtomicInteger(0);
        ProgressInfo var6 = this.processes.get(var3);
        if (Objects.equals(var6.getStatus(), "canceled")) {
            return 7;
        } else {
            var6.setStatus("downloading");
            var6.setTotalBytes(var1.getFileSize());
            var6.setBytesTransferred(0L);

            try (RandomAccessFile var7 = new RandomAccessFile(var2, "rw")) {
                var7.setLength(var1.getFileSize());
                ConcurrentHashMap var8 = new ConcurrentHashMap();
                int var9 = (int)Math.ceil((double)var1.getFileSize() / (double)this.CHUNK_SIZE);
                this.initializeHashMap(var8, var9);
                int var10 = this.downloadAllChunks(var1, var4, var3, var5, var7, var8);
                if (var10 == 7) {
                    this.cancelDownload(var2.getPath());
                    return 7;
                } else {
                    String var11 = this.computeFileHash(var2);
                    if (var11.equals("ERROR")) {
                        return 0;
                    } else {
                        String var12 = var1.getFileHash();
                        if (!var11.equalsIgnoreCase(var12)) {
                            return 8;
                        } else {
                            var6.setStatus("completed");
                            return 1;
                        }
                    }
                }
            } catch (Exception var16) {
                this.cancelDownload(var2.getPath());
                return 0;
            }
        }
    }

    private Integer downloadAllChunks(FileInfo var1, List<PeerInfo> var2, String var3, AtomicInteger var4, RandomAccessFile var5, ConcurrentHashMap<Integer, List<PeerInfo>> var6) throws InterruptedException {
        int var7 = (int)Math.ceil((double)var1.getFileSize() / (double)this.CHUNK_SIZE);
        ArrayList<Integer> var8 = new ArrayList();
        ProgressInfo var9 = this.processes.get(var3);

        for(int var10 = 0; var10 < var7; ++var10) {
            if (var9.getStatus().equals("canceled") || Thread.currentThread().isInterrupted()) {
                return 7;
            }

            while(((ThreadPoolExecutor)this.executor).getActiveCount() >= 6) {
                Thread.sleep(50L);
                if (var9.getStatus().equals("canceled")) {
                    return 7;
                }
            }

            if (!this.downloadChunkWithRetry(var10, var2, var5, var1, var3, var4, var6)) {
                Log.logInfo("Chunk " + var10 + "Failed to download, adding to retry list");
                var8.add(var10);
            }
        }

        byte var15 = 2;

        for(int var11 = 1; var11 <= var15 && !var8.isEmpty(); ++var11) {
            Log.logInfo("Retrying failed chunks, round " + var11 + " with " + var8.size() + " chunks");
            ArrayList var12 = new ArrayList();

            for(int var14 : var8) {
                if (var9.getStatus().equals("canceled")) {
                    return 7;
                }

                if (!this.downloadChunkWithRetry(var14, var2, var5, var1, var3, var4, var6)) {
                    var12.add(var14);
                }
            }

            var8 = var12;
        }

        if (!var8.isEmpty()) {
            return 6;
        } else {
            return 1;
        }
    }

    private boolean downloadChunkWithRetry(int var1, List<PeerInfo> var2, RandomAccessFile var3, FileInfo var4, String var5, AtomicInteger var6, ConcurrentHashMap<Integer, List<PeerInfo>> var7) throws InterruptedException {
        byte var8 = 3;

        for(int var9 = 0; var9 < var8; ++var9) {
            PeerInfo var10 = this.selectAvailablePeer(var5, var2, var1, var7.getOrDefault(var1, new ArrayList()));
            if (var10 == null) {
                Log.logInfo("No available peers for chunk " + var1);
                return false;
            }

            try {
                Log.logInfo("Retrying to download chunk " + var1 + " from peer " + var10.getIp() + ":" + var10.getPort() + " (attempt " + (var9 + 1) + ")");
                var10.addTaskForDownload();
                var7.computeIfAbsent(var1, (var0) -> new ArrayList()).add(var10);
                Future var11 = this.executor.submit(() -> {
                    this.fileLock.lock();

                    Boolean result;
                    try {
                        result = this.downloadChunk(var10, var1, var3, var4, var5, var6);
                    } finally {
                        this.fileLock.unlock();
                    }

                    return result;
                });
                this.futures.get(var5).add(var11);
                if ((Boolean)var11.get()) {
                    Log.logInfo("Chunk " + var1 + " downloaded successfully from peer " + var10.getIp() + ":" + var10.getPort());
                    boolean var12 = true;
                    return var12;
                }

                Log.logInfo("Chunk " + var1 + " download failed from peer " + var10.getIp() + ":" + var10.getPort() + " (attempt " + (var9 + 1) + ")");
                Thread.sleep(50L * (long)(var9 + 1));
            } catch (Exception var16) {
                Log.logError("Error downloading chunk " + var1 + " from peer " + var10.getIp() + ":" + var10.getPort() + ": " + var16.getMessage(), var16);
                Thread.sleep(50L * (long)(var9 + 1));
            } finally {
                var10.removeTaskForDownload();
            }
        }

        Log.logInfo("Failed to download chunk " + var1 + " after " + var8 + " attempts");
        return false;
    }

    private PeerInfo selectAvailablePeer(String var1, List<PeerInfo> var2, int var3, List<PeerInfo> var4) throws InterruptedException {
        int var5 = 0;

        while(var5++ < 20) {
            if ((this.processes.get(var1)).getStatus().equals("canceled")) {
                return null;
            }

            PeerInfo var6 = var2.stream().filter(
                    (var1x) -> !var4.contains(var1x) && var1x.isAvailableForDownload())
                        .min(Comparator.comparingInt(PeerInfo::getTaskForDownload))
                        .orElse( null);
            if (var6 != null) {
                return var6;
            }

            Log.logInfo("Waiting for available peer for chunk " + var3 + " (retry " + var5 + ")");
            Thread.sleep(50L);
        }

        return null;
    }

    private void initializeHashMap(ConcurrentHashMap<Integer, List<PeerInfo>> var1, int var2) {
        for(int var3 = 0; var3 < var2; ++var3) {
            var1.putIfAbsent(var3, new ArrayList());
        }

    }

    public List<PeerInfo> getPeersWithFile(String var1) {
        try (Socket var2 = new Socket(this.TRACKER_HOST.getIp(), this.TRACKER_HOST.getPort())) {
            String var3 = "GET_PEERS|" + var1 + "\n";
            var2.getOutputStream().write(var3.getBytes());
            Log.logInfo("Requesting peers with file hash: " + var1 + ", message: " + var3);
            BufferedReader var4 = new BufferedReader(new InputStreamReader(var2.getInputStream()));
            String var5 = var4.readLine();
            if (var5 == null || var5.isEmpty()) {
                Log.logInfo("No response from tracker for file hash: " + var1);
                return Collections.emptyList();
            } else {
                String[] var6 = var5.split("\\|");
                if (var6.length != 3 || !var6[0].equals("GET_PEERS")) {
                    Log.logInfo("Invalid response format from tracker: " + var5);
                    return Collections.emptyList();
                } else {
                    int var7 = Integer.parseInt(var6[1]);
                    if (var7 == 0) {
                        Log.logInfo("No peers found for file hash: " + var1);
                        return Collections.emptyList();
                    } else {
                        String[] var8 = var6[2].split(",");
                        ArrayList var9 = new ArrayList();

                        for(String var13 : var8) {
                            String[] var14 = var13.split("'");
                            if (var14.length != 2) {
                                Log.logInfo("Invalid peer info format: " + var13);
                            } else {
                                String var15 = var14[0];
                                int var16 = Integer.parseInt(var14[1]);
                                var9.add(new PeerInfo(var15, var16));
                            }
                        }

                        if (var9.isEmpty()) {
                            Log.logInfo("No valid peers found for file hash: " + var1);
                            return Collections.emptyList();
                        } else if (var7 != var9.size()) {
                            Log.logInfo("Peer count mismatch: expected " + var7 + ", found " + var9.size());
                            return Collections.emptyList();
                        } else {
                            Log.logInfo("Received response from tracker: " + var5);
                            return var9;
                        }
                    }
                }
            }
        } catch (IOException var19) {
            Log.logError("Error getting peers with file hash: " + var1, var19);
            return Collections.emptyList();
        }
    }

    public boolean shareFileToPeers(File var1, String var2, List<String> var3) {
        String var4 = this.hashFile(var1, var2);

        try (Socket var5 = new Socket(this.TRACKER_HOST.getIp(), this.TRACKER_HOST.getPort())) {
            StringBuilder var6 = new StringBuilder("SHARE_TO_PEERS|");
            var6.append(var4).append("|");
            var6.append(var3.size()).append("|");

            for(String var8 : var3) {
                var6.append(var8).append("|");
            }

            if (var6.charAt(var6.length() - 1) == '|') {
                var6.deleteCharAt(var6.length() - 1);
            }

            var6.append("\n");
            String var14 = var6.toString();
            var5.getOutputStream().write(var14.getBytes());
            String var10000 = var1.getName();
            Log.logInfo("Sharing file " + var10000 + " (hash: " + var4 + ") to specific peers: " + var3 + ", message: " + var14);
            BufferedReader var15 = new BufferedReader(new InputStreamReader(var5.getInputStream()));
            String var9 = var15.readLine();
            Log.logInfo("Response from tracker: " + var9);
            return var9 != null && var9.contains("thành công");
        } catch (IOException var13) {
            Log.logError("Error sharing file to peers: " + var1.getName(), var13);
            return false;
        }
    }

    public List<String> getSelectivePeers(String var1) {
        try (Socket var2 = new Socket(this.TRACKER_HOST.getIp(), this.TRACKER_HOST.getPort())) {
            String var3 = "GET_SHARED_PEERS|" + var1 + "\n";
            var2.getOutputStream().write(var3.getBytes());
            Log.logInfo("Requesting selective peers for file hash: " + var1 + ", message: " + var3);
            BufferedReader var4 = new BufferedReader(new InputStreamReader(var2.getInputStream()));
            String var5 = var4.readLine();
            if (var5 == null || var5.isEmpty()) {
                Log.logInfo("No response from tracker for selective peers of file hash: " + var1);
                return Collections.emptyList();
            } else {
                String[] var6 = var5.split("\\|");
                if (var6.length != 3 || !var6[0].equals("GET_SHARED_PEERS")) {
                    Log.logInfo("Invalid response format from tracker: " + var5);
                    return Collections.emptyList();
                } else {
                    int var7 = Integer.parseInt(var6[1]);
                    if (var7 == 0) {
                        Log.logInfo("No selective peers found for file hash: " + var1);
                        return Collections.emptyList();
                    } else {
                        String[] var8 = var6[2].split(",");
                        ArrayList var9 = new ArrayList();

                        Collections.addAll(var9, var8);

                        if (var9.isEmpty()) {
                            Log.logInfo("No valid selective peers found for file hash: " + var1);
                            return Collections.emptyList();
                        } else if (var7 != var9.size()) {
                            Log.logInfo("Selective peer count mismatch: expected " + var7 + ", found " + var9.size());
                            return Collections.emptyList();
                        } else {
                            Log.logInfo("Received selective peers from tracker: " + var5);
                            return var9;
                        }
                    }
                }
            }
        } catch (IOException var16) {
            Log.logError("Error getting selective peers for file hash: " + var1, var16);
            return Collections.emptyList();
        }
    }

    public List<String> getKnownPeers() {
        try (Socket var1 = new Socket(this.TRACKER_HOST.getIp(), this.TRACKER_HOST.getPort())) {
            String var2 = "GET_KNOWN_PEERS\n";
            var1.getOutputStream().write(var2.getBytes());
            Log.logInfo("Requesting known peers from tracker, message: " + var2);
            BufferedReader var3 = new BufferedReader(new InputStreamReader(var1.getInputStream()));
            String var4 = var3.readLine();
            if (var4 == null || var4.isEmpty()) {
                Log.logInfo("No response from tracker for known peers");
                return Collections.emptyList();
            } else {
                String[] var5 = var4.split("\\|");
                if (var5.length != 3 || !var5[0].equals("GET_KNOWN_PEERS")) {
                    Log.logInfo("Invalid response format from tracker: " + var4);
                    return Collections.emptyList();
                } else {
                    int var6 = Integer.parseInt(var5[1]);
                    if (var6 == 0) {
                        Log.logInfo("No known peers found");
                        return Collections.emptyList();
                    } else {
                        String[] var7 = var5[2].split(",");
                        ArrayList var8 = new ArrayList();

                        Collections.addAll(var8, var7);

                        if (var8.isEmpty()) {
                            Log.logInfo("No valid known peers found");
                            return Collections.emptyList();
                        } else if (var6 != var8.size()) {
                            Log.logInfo("Known peer count mismatch: expected " + var6 + ", found " + var8.size());
                            return Collections.emptyList();
                        } else {
                            Log.logInfo("Received known peers from tracker: " + var4);
                            return var8;
                        }
                    }
                }
            }
        } catch (IOException var15) {
            Log.logError("Error getting known peers from tracker", var15);
            return Collections.emptyList();
        }
    }

    private String computeFileHash(File var1) {
        try (FileInputStream var2 = new FileInputStream(var1)) {
            MessageDigest var3 = MessageDigest.getInstance("SHA-256");
            byte[] var4 = new byte[this.CHUNK_SIZE];

            int var5;
            while((var5 = var2.read(var4)) != -1) {
                var3.update(var4, 0, var5);
            }

            return this.bytesToHex(var3.digest());
        } catch (NoSuchAlgorithmException | IOException var9) {
            Log.logError("Error computing hash for file: " + var1.getAbsolutePath(), var9);
            return "ERROR";
        }
    }

    private void cancelDownload(String var1) {
        Log.logInfo("Download process cancelled for file: " + var1);
        File var2 = new File(var1);
        Path var3 = var2.toPath();
        if (Files.exists(var3) && var2.delete()) {
            Log.logInfo("Deleted file: " + var2.getAbsolutePath());
        } else {
            Log.logInfo("Cannot delete file: " + var2.getAbsolutePath() + ". It may not exist or is not writable.");
        }

        this.cancelled = false;
    }

    private boolean downloadChunk(PeerInfo var1, int var2, RandomAccessFile var3, FileInfo var4, String var5, AtomicInteger var6) {
        byte var7 = 3;
        ProgressInfo var8 = this.processes.get(var5);
        if (var8.getStatus().equals("canceled")) {
            return false;
        } else {
            for(int var9 = 1; var9 <= var7; ++var9) {
                if (var8.getStatus().equals("canceled") || Thread.currentThread().isInterrupted()) {
                    Log.logInfo("Process cancelled by user while downloading chunk " + var2 + " from peer " + var1);
                    return false;
                }

                SocketChannel var10 = null;

                try {
                    var10 = SocketChannel.open(new InetSocketAddress(var1.getIp(), var1.getPort()));
                    var10.socket().setSoTimeout(Infor.SOCKET_TIMEOUT_MS);
                    (this.openChannels.get(var5)).add(var10);
                    String var10000 = var4.getFileHash();
                    String var11 = "GET_CHUNK|" + var10000 + "|" + var2 + "\n";
                    ByteBuffer var12 = ByteBuffer.wrap(var11.getBytes());

                    while(var12.hasRemaining()) {
                        var10.write(var12);
                    }

                    ByteBuffer var48 = ByteBuffer.allocate(4);
                    int var14 = 0;

                    int var17;
                    for(long var15 = System.currentTimeMillis(); var14 < 4 && System.currentTimeMillis() - var15 < 5000L; var14 += var17) {
                        if (this.cancelled) {
                            Log.logInfo("Process cancelled by user while reading index chunk from peer " + var1);
                            return false;
                        }

                        var17 = var10.read(var48);
                        if (var17 == -1) {
                            break;
                        }
                    }

                    if (var14 < 4) {
                        var10000 = String.valueOf(var1);
                        Log.logInfo("Don't receive enough bytes for index chunk from peer " + var10000 + " (attempt: " + var9 + ")");
                    } else {
                        var48.flip();
                        var17 = var48.getInt();
                        if (var17 != var2) {
                            Log.logInfo("Received chunk index " + var17 + " does not match requested index " + var2 + " from peer " + var1 + " (attempt " + var9 + ")");
                        } else {
                            ByteBuffer var18 = ByteBuffer.allocate(this.CHUNK_SIZE);
                            ByteArrayOutputStream var19 = new ByteArrayOutputStream();
                            long var49 = System.currentTimeMillis();

                            while(System.currentTimeMillis() - var49 < 5000L) {
                                if (this.cancelled) {
                                    Log.logInfo("Process cancelled by user while reading chunk data from peer " + var1);
                                    boolean var52 = false;
                                    return var52;
                                }

                                int var20 = var10.read(var18);
                                if (var20 == -1) {
                                    break;
                                }

                                if (var20 == 0) {
                                    Thread.sleep(100L);
                                } else {
                                    var18.flip();
                                    var19.write(var18.array(), 0, var20);
                                    var18.clear();
                                }
                            }

                            byte[] var53 = var19.toByteArray();
                            if (var53.length != 0) {
                                synchronized(var3) {
                                    var3.seek((long)var2 * (long)this.CHUNK_SIZE);
                                    var3.write(var53);
                                }

                                long var21 = (var4.getFileSize() + (long)this.CHUNK_SIZE - 1L) / (long)this.CHUNK_SIZE;
                                long var23 = var6.incrementAndGet();
                                int var25 = (int)((double)var23 * (double)100.0F / (double)var21);
                                var8.addBytesTransferred(var53.length);
                                var8.setProgressPercentage(var25);
                                Log.logInfo("Successfully downloaded chunk " + var2 + " from peer " + var1 + " (attempt " + var9 + ")");
                                boolean var26 = true;
                                return var26;
                            }

                            Log.logInfo("Received empty chunk data from peer " + var1 + " for chunk index " + var2 + " (attempt " + var9 + ")");
                        }
                    }
                } catch (InterruptedException | IOException var46) {
                    Log.logError("Error downloading chunk " + var2 + " from peer " + var1 + " (attempt " + var9 + "): " + var46.getMessage(), var46);

                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException var45) {
                        Thread.currentThread().interrupt();
                        boolean var13 = false;
                        return var13;
                    }
                } finally {
                    if (var10 != null) {
                        this.openChannels.get(var5).remove(var10);

                        try {
                            var10.close();
                        } catch (IOException var43) {
                            Log.logError("Error closing channel: " + var10, var43);
                        }
                    }

                }
            }

            Log.logInfo("Failed to download chunk " + var2 + " from peer " + var1 + " after " + var7 + " attempts.");
            return false;
        }
    }

    private void sendChunk(SocketChannel var1, String var2, int var3) {
        FileInfo var4 = null;

        for(FileInfo var6 : this.mySharedFiles.values()) {
            if (var6.getFileHash().equals(var2)) {
                var4 = var6;
                break;
            }
        }

        if (var4 == null) {
            Log.logInfo("File not found in shared files: " + var2);
        } else {
            String var10000 = AppPaths.getAppDataDirectory();
            String var16 = var10000 + "\\shared_files\\" + var4.getFileName();
            File var17 = new File(var16);
            if (var17.exists() && var17.canRead()) {
                try (RandomAccessFile var7 = new RandomAccessFile(var17, "r")) {
                    byte[] var8 = new byte[this.CHUNK_SIZE];
                    var7.seek((long)var3 * (long)this.CHUNK_SIZE);
                    int var9 = var7.read(var8);
                    if (var9 <= 0) {
                        Log.logInfo("No bytes read for chunk " + var3 + " of file " + var4.getFileName() + ". File may be empty or chunk index is out of bounds.");
                    } else {
                        ByteBuffer var10 = ByteBuffer.allocate(var9 + 4);
                        var10.putInt(var3);
                        var10.put(var8, 0, var9);
                        var10.flip();

                        int var11;
                        int var12;
                        for(var11 = 0; var10.hasRemaining(); var11 += var12) {
                            var12 = var1.write(var10);
                        }

                        Log.logInfo("Chunk " + var3 + " of file " + var4.getFileName() + " for " + var2 + " sent successfully.");
                        Log.logInfo("Chunk index sent: " + var3);
                        Log.logInfo("Total bytes written: " + var11);
                        if (var11 < var9 + 4) {
                            Log.logInfo("Warning: Not all bytes were sent for chunk " + var3 + " of file " + var4.getFileName() + ". Expected: " + (var9 + 4) + ", Sent: " + var11);
                        } else {
                            Log.logInfo("All bytes sent successfully for chunk " + var3 + " of file " + var4.getFileName());
                        }
                    }
                } catch (IOException var15) {
                    Log.logError("Error sending chunk " + var3 + " of file " + var4.getFileName() + ": " + var15.getMessage(), var15);
                }

            } else {
                Log.logInfo("File does not exist or cannot be read: " + var16);
            }
        }
    }

    private boolean hasAccessToFile(String var1, String var2) {
        for(FileInfo var4 : this.mySharedFiles.values()) {
            if (var4.getFileHash().equals(var2)) {
                List var5 = this.getSelectivePeers(var2);
                if (var5.isEmpty()) {
                    return true;
                }

                return var5.contains(var1);
            }
        }

        return false;
    }

    private void sendAccessDenied(SocketChannel var1) {
        try {
            String var2 = "ACCESS_DENIED\n";
            ByteBuffer var3 = ByteBuffer.wrap(var2.getBytes());

            while(var3.hasRemaining()) {
                var1.write(var3);
            }

            Log.logInfo("Access denied response sent to client");
        } catch (IOException var4) {
            Log.logError("Error sending access denied response: " + var4.getMessage(), var4);
        }

    }

    public void loadSharedFiles() {
        String var1 = AppPaths.getAppDataDirectory() + "\\shared_files\\";
        File var2 = new File(var1);
        if (var2.exists() && var2.isDirectory()) {
            File[] var3 = var2.listFiles();
            if (var3 != null && var3.length != 0) {
                Log.logInfo("List of shared files in directory: " + var2.getAbsolutePath());

                for(File var7 : var3) {
                    if (var7.isFile()) {
                        String var8 = var7.getName();
                        long var9 = var7.length();
                        Log.logInfo(" - " + var8 + " (" + var9 + " bytes)");
                        String var11 = this.computeFileHash(var7);
                        if (var11.equals("ERROR")) {
                            Log.logInfo("Error computing hash for file: " + var8);
                        } else {
                            this.mySharedFiles.put(var8, new FileInfo(var8, var9, var11, this.SERVER_HOST, true));
                        }
                    }
                }

            } else {
                Log.logInfo("No shared files found in directory: " + var2.getAbsolutePath());
            }
        } else {
            Log.logInfo("Shared directory does not exist or is not a directory: " + var1);
        }
    }

    public int refreshSharedFileNames() {
        try (Socket var1 = new Socket()) {
            var1.connect(new InetSocketAddress(this.TRACKER_HOST.getIp(), this.TRACKER_HOST.getPort()));
            PrintWriter var2 = new PrintWriter(var1.getOutputStream(), true);
            String var10000 = this.SERVER_HOST.getIp();
            String var3 = "REFRESH|" + var10000 + "|" + this.SERVER_HOST.getPort() + "\n";
            var2.println(var3);
            BufferedReader var4 = new BufferedReader(new InputStreamReader(var1.getInputStream()));
            String var5 = var4.readLine();
            if (var5 != null && var5.startsWith("REFRESHED")) {
                String[] var22 = var5.split("\\|");
                if (var22.length != 3) {
                    Log.logInfo("Invalid response format from tracker: " + var5);
                    return -1;
                } else {
                    int var7 = Integer.parseInt(var22[1]);
                    String[] var8 = var22[2].split(",");
                    if (var8.length != var7) {
                        Log.logInfo("File count mismatch: expected " + var7 + ", got " + var8.length);
                        return -1;
                    } else {
                        this.sharedFileNames.clear();

                        for(String var12 : var8) {
                            String[] var13 = var12.split("'");
                            if (var13.length != 5) {
                                Log.logInfo("Invalid file info format: " + var12);
                            } else {
                                String var14 = var13[0];
                                long var15 = Long.parseLong(var13[1]);
                                String var17 = var13[2];
                                PeerInfo var18 = new PeerInfo(var13[3], Integer.parseInt(var13[4]));
                                this.sharedFileNames.add(new FileInfo(var14, var15, var17, var18));
                            }
                        }

                        Log.logInfo("Refreshed shared file names from tracker: " + this.sharedFileNames.size() + " files found.");
                        return 1;
                    }
                }
            } else {
                Log.logInfo("Invalid response from tracker: " + var5);
                return -1;
            }
        } catch (Exception var21) {
            Log.logError("Error refreshing shared file names from tracker: " + var21.getMessage(), var21);
            return 0;
        }
    }

    public Set<FileInfo> getSharedFileNames() {
        return this.sharedFileNames;
    }

    public void setSharedFileNames(Set<FileInfo> var1) {
        this.sharedFileNames = var1;
    }

    public Map<String, FileInfo> getMySharedFiles() {
        return this.mySharedFiles;
    }

    public int stopSharingFile(String var1) {
        if (!this.mySharedFiles.containsKey(var1)) {
            Log.logInfo("File not found in shared files: " + var1);
            return 2;
        } else {
            FileInfo var2 = this.mySharedFiles.get(var1);
            this.mySharedFiles.remove(var1);
            this.sharedFileNames.removeIf((var1x) -> var1x.getFileName().equals(var1));
            String var10000 = AppPaths.getAppDataDirectory();
            String var3 = var10000 + "\\shared_files\\" + var1;
            File var4 = new File(var3);
            if (var4.exists() && var4.delete()) {
                Log.logInfo("Removed shared file: " + var3);
            } else {
                Log.logInfo("Failed to remove shared file: " + var3);
            }

            Log.logInfo("Stopped sharing file: " + var1);
            return this.notifyTracker(var2, false);
        }
    }

    public void cleanupProgress(List<String> var1) {
        for(String var3 : var1) {
            this.processes.remove(var3);
            List<Future<Boolean>> var4 = this.futures.remove(var3);
            if (var4 != null) {
                for(Future<Boolean> var6 : var4) {
                    var6.cancel(true);
                }
            }

            List<SocketChannel> var10 = this.openChannels.remove(var3);
            if (var10 != null) {
                for(SocketChannel var7 : var10) {
                    try {
                        var7.close();
                    } catch (IOException var9) {
                        Log.logError("Error closing channel: " + var7, var9);
                    }
                }
            }
        }

    }

    public Map<String, ProgressInfo> getProgress() {
        return this.processes;
    }

    public void setProgress(ProgressInfo var1) {
        this.processes.put(var1.getId(), var1);
    }

    private Socket createSocket(PeerInfo var1) throws IOException {
        Socket var2 = new Socket();
        var2.setSoTimeout(Infor.SOCKET_TIMEOUT_MS);
        var2.connect(new InetSocketAddress(var1.getIp(), var1.getPort()), Infor.SOCKET_TIMEOUT_MS);
        return var2;
    }
}
