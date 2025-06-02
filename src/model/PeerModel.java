package model;

import ultis.Infor;
import ultis.RequestInfor;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class PeerModel {
    private final int CHUNK_SIZE = 1024 * 1024;
    private final PeerInfor SERVER_HOST = new PeerInfor(Infor.SERVER_IP, Infor.SERVER_PORT);
    private final PeerInfor TRACKER_HOST = new PeerInfor(Infor.TRACKER_IP, Infor.TRACKER_PORT);
    private Selector selector;
    private ServerSocketChannel serverSocket;
    private Map<String, FileInfor> sharedFiles;
    private Set<String> knownPeers;
    private ExecutorService executor;

    public PeerModel() throws IOException {
        sharedFiles = new java.util.HashMap<>();
        knownPeers = new java.util.HashSet<>();
        executor = java.util.concurrent.Executors.newFixedThreadPool(10);

        this.selector = Selector.open();
        this.serverSocket = ServerSocketChannel.open();
        this.serverSocket.bind(new InetSocketAddress(SERVER_HOST.getPeerPort()));
        this.serverSocket.configureBlocking(false);
        this.serverSocket.register(selector, SelectionKey.OP_ACCEPT);
    }

    public void startServer() {
        new Thread(() -> {
            try {
                while (true) {
                    selector.select();
                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();

                        if (!key.isValid()) continue;

                        try {
                            if (key.isAcceptable()) {
                                acceptConnection(key);
                            } else if (key.isReadable()) {
                                handleRead(key);
                            }
                        } catch (IOException ex) {
                            key.cancel();
                            key.channel().close();
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();


    }

    private void handleRead(SelectionKey key) {
        try {
            SocketChannel client = (SocketChannel) key.channel();
            ByteBuffer buffer = ByteBuffer.allocate(1024);
            StringBuilder requestBuilder = new StringBuilder();

            while (true) {
                int bytesRead = client.read(buffer);
                if (bytesRead == -1) break; // End of stream

                buffer.flip();
                requestBuilder.append(new String(buffer.array(), 0, buffer.limit()));
                buffer.clear();
            }

            String request = requestBuilder.toString();
            if (request.startsWith(RequestInfor.SEARCH)) {
                String fileName = request.split(":")[1];
                sendFileInfor(client, fileName);
                System.out.println("Handling search request for: " + fileName);
            } else if (request.startsWith(RequestInfor.GET_CHUNK)) {
                String[] parts = request.split(":");
                String fileName = parts[1];
                int chunkIndex = Integer.parseInt(parts[2]);
                sendChunk(client, fileName, chunkIndex);
                System.out.println("Handling download request for: " + fileName);
            } else {
                System.out.println("Unknown request: " + request);
            }

            System.out.println("Handling read operation for key: " + key);
        } catch (IOException e) {
            e.printStackTrace();
            key.cancel();
        }
    }

    private void sendFileInfor(SocketChannel client, String fileName) {
        FileInfor fileInfor = sharedFiles.get(fileName);

        String response = fileInfor != null
                ? RequestInfor.FILE_INFO + ":" + fileInfor.getFileName() + ":" + fileInfor.getFileSize()
                + ":" + fileInfor.getPeerInfor().getPeerIp() + ":" + fileInfor.getPeerInfor().getPeerPort() +
                ":" + String.join(",", fileInfor.getChunkHashes())
                : RequestInfor.FILE_NOT_FOUND + ":" + fileName;
        try {
            ByteBuffer buffer = ByteBuffer.wrap(response.getBytes());
            client.write(buffer);
            System.out.println("Sent file information for: " + fileName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendChunk(SocketChannel client, String fileName, int chunkIndex) {
        FileInfor fileInfor = sharedFiles.get(fileName);
        if (fileInfor != null) {
            File file = new File(fileName);

            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                byte[] chunk = new byte[CHUNK_SIZE];
                raf.seek(chunkIndex * CHUNK_SIZE);
                int bytesRead = raf.read(chunk);
                if (bytesRead > 0) {
                    ByteBuffer buffer = ByteBuffer.allocate(bytesRead + 4);
                    buffer.putInt(chunkIndex); // Send chunk index first
                    buffer.put(chunk, 0, bytesRead);
                    buffer.flip();
                    client.write(buffer);
                    System.out.println("Sent chunk " + chunkIndex + " of file " + fileName);
                } else {
                    System.out.println("No more data to send for chunk " + chunkIndex);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void acceptConnection(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ);
    }

    public void discoverPeers() {
        executor.submit(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                String message = RequestInfor.DISCOVER + ":" + SERVER_HOST.getPeerIp() + ":" + SERVER_HOST.getPeerPort();

                byte[] buffer = message.getBytes();
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length,
                        InetAddress.getByName("255.255.255.255"), TRACKER_HOST.getPeerPort());
                socket.send(packet);

                byte[] receiveBuffer = new byte[1024];
                DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                socket.setSoTimeout(5000);
                try {
                    socket.receive(receivePacket);
                    String response = new String(receivePacket.getData(), 0, receivePacket.getLength());
                    System.out.println("Received response: " + response);
                    String[] parts = response.split(":");
                    if (parts.length == 3) {
                        String peerIp = parts[1];
                        int peerPort = Integer.parseInt(parts[2]);
                        knownPeers.add(peerIp + ":" + peerPort);
                        System.out.println("Discovered peer: " + peerIp + ":" + peerPort);
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("No response from tracker.");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    public void registerWithTracker() {
        try (Socket socket = new Socket(TRACKER_HOST.getPeerIp(), TRACKER_HOST.getPeerPort())) {
            String message = RequestInfor.REGISTER + ":" + SERVER_HOST.getPeerIp() + ":" + SERVER_HOST.getPeerPort();
            socket.getOutputStream().write(message.getBytes());
            System.out.println("Registered with tracker: " + message);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void shareFile(String filePath) {
        File file = new File(filePath);
        List<String> chunkHashes = new ArrayList<>();

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] chunk = new byte[CHUNK_SIZE];
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            int bytesRead;

            while ((bytesRead = raf.read(chunk)) != -1) {
                md.update(chunk, 0, bytesRead);
                String chunkHash = bytesToHex(md.digest());
                chunkHashes.add(chunkHash);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        sharedFiles.put(file.getName(), new FileInfor(file.getName(), file.length(), chunkHashes, SERVER_HOST));
        notifyTracker(file.getName());
    }

    private void notifyTracker(String fileName) {
        try (Socket socket = new Socket(TRACKER_HOST.getPeerIp(), TRACKER_HOST.getPeerPort())) {
            String message = RequestInfor.SHARE + ":" + fileName + ":" + SERVER_HOST.getPeerIp() + ":" + SERVER_HOST.getPeerPort();
            socket.getOutputStream().write(message.getBytes());
            System.out.println("Notified tracker about shared file: " + fileName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String bytesToHex(byte[] digest) {
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public void downloadFile(String fileName, String savePath) {
        executor.submit(() -> {
            try {
                List<String> peers = queryTracker(fileName);
                FileInfor fileInfor = getFileInforFromPeers(peers.get(0), fileName);

            if (fileInfor == null) {
                System.out.println("File not found on any peer.");
                return;
            }

            File file = new File(savePath);


            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                List<Future<?>> futures = new ArrayList<>();



                raf.setLength(fileInfor.getFileSize());
                for (int i = 0; i < fileInfor.getChunkHashes().size(); i++) {
                    final  int chunkIndex = i;
                    futures.add(executor.submit(() -> downloadChunk(peers, fileName, chunkIndex, raf, fileInfor.getChunkHashes().get(chunkIndex)))) ;
                }

                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (Exception e) {
                        System.out.println("Error downloading chunk: " + e.getMessage());
                    }
                }
                System.out.println("Download completed for file: " + fileName);
            }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void downloadChunk(List<String> peers, String fileName, int chunkIndex, RandomAccessFile raf, String expectedHash) {
        for (String peer : peers) {
            try {
                String[] peerInfor = peer.split(":");
                try (SocketChannel channel = SocketChannel.open(
                        new InetSocketAddress(peerInfor[0], Integer.parseInt(peerInfor[1])))) {
                    ByteBuffer buffer = ByteBuffer.wrap((RequestInfor.GET_CHUNK + ":" + fileName + ":" + chunkIndex).getBytes());
                    channel.write(buffer);
                    buffer.clear();

                    // Read the chunk index first
                    channel.read(buffer);
                    buffer.flip();
                    int receivedChunkIndex = buffer.getInt();

                    if (receivedChunkIndex != chunkIndex) {
                        System.out.println("Received chunk index " + receivedChunkIndex + ", expected " + chunkIndex);
                        continue;
                    }

                    // Read the actual chunk data
                    buffer.clear();
                    int bytesRead = channel.read(buffer);
                    if (bytesRead > 0) {
                        byte[] chunkData = new byte[bytesRead];
                        buffer.flip();
                        buffer.get(chunkData);

                        // Verify the hash
                        MessageDigest md = MessageDigest.getInstance("SHA-256");
                        md.update(chunkData);
                        String chunkHash = bytesToHex(md.digest());

                        if (!chunkHash.equals(expectedHash)) {
                            System.out.println("Hash mismatch for chunk " + chunkIndex + " from peer " + peer);
                            continue;
                        }
                        synchronized (raf) {
                            // Write the chunk to the file
                            raf.seek(chunkIndex * CHUNK_SIZE);
                            raf.write(chunkData);
                        }
                        System.out.println("Downloaded chunk " + chunkIndex + " from peer " + peer);
                        break;
                    }
                }
            } catch (IOException | NoSuchAlgorithmException e) {
                e.printStackTrace();
            }
        }
        System.out.println("Failed to download chunk " + chunkIndex + " from all peers.");
    }

    public FileInfor getFileInforFromPeers(String peer, String fileName) {
        System.out.println(peer);
        String[] peerInfor = peer.split(":");
        System.out.println("Searching for file " + fileName + " on peer: " + peerInfor[0] + ":" + peerInfor[1]);
        try (SocketChannel channel = SocketChannel.open(
                new InetSocketAddress(peerInfor[0], Integer.parseInt(peerInfor[1])))) {
            ByteBuffer buffer = ByteBuffer.wrap((RequestInfor.SEARCH + ":" + fileName).getBytes());
            channel.write(buffer);
            buffer.clear();
            channel.read(buffer);
            buffer.flip();

            String response = new String(buffer.array(), 0, buffer.limit());

            if (response.startsWith(RequestInfor.FILE_INFO)) {
                String[] parts = response.split(":");
                String name = parts[1];
                long size = Long.parseLong(parts[2]);
                PeerInfor peerInfo = new PeerInfor(parts[3], Integer.parseInt(parts[4]));
                List<String> chunkHashes = Arrays.asList(parts[5].split(","));
                return new FileInfor(name, size, chunkHashes, peerInfo);
            } else {
                System.out.println("File not found on peer: " + peer);
                return null;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return null;

        }
    }

    public List<String> queryTracker(String fileName) throws IOException {
        List<String> peers = new ArrayList<>();
        try (Socket socket = new Socket(TRACKER_HOST.getPeerIp(), TRACKER_HOST.getPeerPort())) {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.println(RequestInfor.QUERY + ":" + fileName);
            String response = in.readLine();
            peers.addAll(Arrays.asList(response.split(",")));
        }
        return peers;
    }
}