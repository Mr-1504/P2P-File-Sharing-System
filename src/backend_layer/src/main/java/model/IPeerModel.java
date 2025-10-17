package main.java.model;

import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;
import main.java.domain.entity.ProgressInfo;
import main.java.model.submodel.IFileDownloadModel;
import main.java.model.submodel.IFileShareModel;
import main.java.model.submodel.INetworkModel;
import main.java.model.submodel.IPeerDiscoveryModel;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import java.util.concurrent.CopyOnWriteArrayList;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

public interface IPeerModel extends IFileDownloadModel, IFileShareModel, INetworkModel, IPeerDiscoveryModel {
    int getChunkSize();

    PeerInfo getServerHost();

    PeerInfo getTrackerHost();

    ExecutorService getExecutor();

    ConcurrentHashMap<String, ProgressInfo> getProcesses();

    ReentrantLock getFileLock();

    ConcurrentHashMap<String, List<Future<Boolean>>> getFutures();

    ConcurrentHashMap<String, CopyOnWriteArrayList<SSLSocket>> getOpenChannels();

    boolean isRunning();

    void setRunning(boolean running);

    Selector getSelector();

    void setSelector(Selector selector);

    SSLContext getSslContext();

    void setSslContext(SSLContext sslContext);

    ConcurrentHashMap<SocketChannel, SSLEngine> getSslEngineMap();

    void setSslEngineMap(ConcurrentHashMap<SocketChannel, SSLEngine> sslEngineMap);

    ConcurrentHashMap<SocketChannel, ByteBuffer> getPendingData();

    void setPendingData(ConcurrentHashMap<SocketChannel, ByteBuffer> pendingData);

    ConcurrentHashMap<SocketChannel, Map<String, Object>> getChannelAttachments();

    void setChannelAttachments(ConcurrentHashMap<SocketChannel, Map<String, Object>> channelAttachments);

    SSLSocket createSecureSocket(PeerInfo peerInfo) throws Exception;

    String processSSLRequest(SocketChannel socketChannel, String request);

    void loadData();

    String bytesToHex(byte[] bytes);

    String hashFile(File file, String progressId);
}
