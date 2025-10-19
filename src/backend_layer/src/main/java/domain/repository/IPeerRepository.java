package main.java.domain.repository;

import main.java.domain.entity.PeerInfo;
import main.java.domain.entity.ProgressInfo;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

public interface IPeerRepository extends IFileDownloadRepository, IFileShareRepository, INetworkRepository, IPeerDiscoveryRepository {
    ExecutorService getExecutor();

    Map<String, ProgressInfo> getProcesses();

    ReentrantLock getFileLock();

    Map<String, List<Future<Boolean>>> getFutures();

    Map<String, CopyOnWriteArrayList<SSLSocket>> getOpenChannels();

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

    void processSSLRequest(SocketChannel socketChannel, String request);

}
