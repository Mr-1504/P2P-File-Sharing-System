# P2P-File-Sharing-System
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Mr-1504/P2P-File-Sharing-System)

The P2P File Sharing System is a hybrid peer-to-peer file sharing application combining centralized peer discovery with distributed file transfers. Users can share files with other peers on the network, search for available files, and download files in parallel from multiple sources. The system maintains a centralized tracker for peer registry and file indexing while performing actual file transfers directly between peers.

## Features

-   **Decentralized File Transfer:** Files are transferred directly between peers without passing through a central server.
-   **Centralized Tracking:** A tracker server maintains a list of active peers and the metadata of the files they share.
-   **Multi-source Downloading:** Files are broken down into chunks, which can be downloaded in parallel from multiple peers that have the file, speeding up the download process.
-   **Modern Desktop Interface:** Electron-based desktop application with React UI components allows for easy interaction, including sharing, searching, and downloading files.
-  **Selective Sharing:** Support for both public sharing (all peers) and selective sharing (specific peers).
-   **File Discovery:** Users can search for files across the network based on keywords.
-   **Real-time Logging:** The application provides a log view to monitor system activities, connection status, and transfer progress.
-   **Dynamic File Management:** Users can add new files to share or stop sharing existing files at any time.
-   **P2P Chat:** A built-in chat feature allows peers to communicate with each other directly.
-   **Multi-language Support:** The application supports multiple languages, including Vietnamese and English.

## Architecture Overview
The system consists of three primary components that communicate via multiple protocols:

1. **Electron Frontend:** Developed using React.js and Electron, this component provides the graphical user interface (GUI) for users to interact with the application. It handles file selection, displays file lists, and shows logs and progress updates. Communication with the Java backend is done via IPC (Inter-Process Communication) and HTTP requests.

2. **Java Backend:** The backend is responsible for the core functionality of the application, including file sharing, downloading logic and communication with the tracker, P2P networking. It is built using Java HTTP server for handling requests from the Electron frontend, TCP sockets for peer-to-peer file transfers, and UDP for lightweight messaging. The backend also manages the local file system, breaking files into chunks for efficient transfer.

3. **Tracker Server:** The tracker is a central server that maintains a list of active peers and the metadata of the files they share. It facilitates peer discovery and helps peers find each other for direct file transfers via TCP and UDP.

## System Architecture

The system consists of two main components: the Peer and the Tracker.

### Peer

The Peer is the client-side application that runs on a user's machine. Each peer can act as both a client and a server:
-   **Client Role:** It communicates with the tracker to register itself, search for files, and get information about other peers. It also acts as a client when downloading file chunks from other peers.
-   **Server Role:** It listens for incoming connections from other peers to serve requests for file chunks.

### Tracker

The Tracker is a central server that coordinates the P2P network. Its primary responsibilities are:
-   **Peer Registration:** It keeps a list of all currently active peers in the network.
-   **File Indexing:** It stores metadata about the files being shared by each peer, including file name, size, and hash. It does **not** store the file content itself.
-   **Peer Discovery:** When a peer wants to download a file, it queries the tracker, which returns a list of peers that have the requested file.
-   **Health Checks:** The tracker periodically pings peers to ensure they are still active and removes unresponsive peers from its list.

### Communication Protocol

-   **Peer-Tracker:** Communication is primarily done over TCP. A peer registers with the tracker, sends its list of shared files, and queries for files. UDP is used for ping/pong keep-alive messages.
-   **Peer-Peer:** Communication for file transfers is done over TCP. A downloader connects to a seeder to request and receive specific file chunks.

## How to Run

### Prerequisites

-   Java Development Kit (JDK) 11 or newer.
-   An IDE like IntelliJ IDEA or Eclipse is recommended.
-   The required libraries (`gson`, `java-dotenv`) are included in the `lib/` directory.
-   REACT and Electron for the frontend (if applicable).

### Configuration

The application can be configured using a `.env` file in the project's root directory. If the file is not present, default values will be used.

**Example `.env` file:**
```env
# IP address of the Tracker server
TRACKER_IP=127.0.0.1

# Port for the Tracker server
TRACKER_PORT=5001

# Port for the Peer's server component (if not set, a random port will be used)
# SERVER_PORT=5000

# Size of chunks for file transfers (in bytes)
CHUNK_SIZE=2097152
```

### 1. Start the Tracker

The system requires a single tracker instance to be running.
1.  Open the `src/main/java/main/Main.java` file.
2.  Uncomment the `trackerThread` section to enable the tracker to start along with the first peer application.

```java
// src/main/java/main/Main.java

public static void main(String[] args) {
    // ...
    // Khởi động Tracker trong một luồng riêng
    Thread trackerThread = new Thread(() -> {
        try {
            new TrackerModel().startTracker();
        } catch (IOException e) {
            // ...
        }
    });
    trackerThread.setDaemon(true); // Đặt tracker thread là daemon để dừng khi đóng ứng dụng
    trackerThread.start();

    // Đợi tracker khởi động
    try {
        Thread.sleep(1000); // Chờ 1 giây để tracker sẵn sàng
    } catch (InterruptedException e) {
        e.printStackTrace();
    }
    // ...
}
```
3. Run the `Main` class. The first peer application will also launch the tracker in the background.

### 2. Start the Peer(s)

1.  Ensure the Tracker is running.
2.  Run the `main.java.main.Main` class to launch a peer client.
3.  You can run the `Main` class multiple times to simulate multiple peers in the network.

## Usage Guide

-   **Sharing a File:** Click the "Chọn file" button to select a file from your computer. The application will prepare the file and notify the tracker that you are sharing it. Your shared files will appear under the "File của tôi" view.
-   **Searching for a File:** Type a keyword into the search bar and click "Tìm kiếm". The table will display all files available on the network that match your search.
-   **Downloading a File:**
    1.  Find the file you want in the file table (either by searching or viewing all files).
    2.  Right-click the file to open the context menu and select "Tải xuống".
    3.  Choose a location on your computer to save the file.
    4.  The download progress will be displayed at the bottom of the window.
-   **Stopping File Sharing:**
    1.  Click the "File của tôi" button to view only the files you are sharing.
    2.  Right-click the file you wish to stop sharing and select "Dừng chia sẻ".
    3.  Confirm the action. The file will be removed from your share list and from the tracker's index.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
