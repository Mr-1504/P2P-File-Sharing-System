package model;

import utils.Infor;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import static utils.Log.*;

public class SSLChannelWrapper {
    private final SocketChannel channel;
    private final SSLEngine engine;
    private final Selector selector;
    private ByteBuffer appInBuffer;
    private ByteBuffer appOutBuffer;
    private ByteBuffer netInBuffer;
    private ByteBuffer netOutBuffer;
    private StringBuilder requestBuilder;
    private ByteArrayOutputStream chunkData;
    private SelectionKey key;
    private boolean handshakeCompleted;
    private boolean closing;
    private long handshakeStartTime;
    private int noDataCount;
    private static final long HANDSHAKE_TIMEOUT_MS = 10000; // 10 giây
    private static final int MAX_NO_DATA_ATTEMPTS = 5; // Số lần đọc 0 byte

    public SSLChannelWrapper(SocketChannel channel, SSLEngine engine, Selector selector) throws IOException {
        this.channel = channel;
        this.engine = engine;
        this.selector = selector;
        this.appInBuffer = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize());
        this.appOutBuffer = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize());
        this.netInBuffer = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
        this.netOutBuffer = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
        this.requestBuilder = new StringBuilder();
        this.chunkData = new ByteArrayOutputStream();
        this.key = channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, this);
        this.handshakeCompleted = false;
        this.closing = false;
        this.handshakeStartTime = System.currentTimeMillis();
        this.noDataCount = 0;
        logInfo("Khởi tạo SSLChannelWrapper cho channel: " + getRemoteAddressSafe());
        handleHandshake();
    }

    private String getRemoteAddressSafe() {
        try {
            return channel.getRemoteAddress().toString();
        } catch (IOException e) {
            return "unknown";
        }
    }

    private void handleHandshake() throws IOException {
        if (handshakeCompleted || closing) {
            logInfo("Bắt tay đã hoàn tất hoặc channel đang đóng: " + getRemoteAddressSafe());
            return;
        }

        if (System.currentTimeMillis() - handshakeStartTime > HANDSHAKE_TIMEOUT_MS) {
            logError("Timeout bắt tay SSL/TLS sau " + HANDSHAKE_TIMEOUT_MS + "ms: " + getRemoteAddressSafe(), null);
            close();
            throw new IOException("Timeout bắt tay SSL/TLS");
        }

        logInfo("Bắt đầu bắt tay SSL/TLS, trạng thái: " + engine.getHandshakeStatus() + ", channel: " + getRemoteAddressSafe());
        int maxAttempts = 10;
        int attempt = 0;

        while (engine.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.FINISHED &&
                engine.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING &&
                attempt++ < maxAttempts) {
            SSLEngineResult.HandshakeStatus status = engine.getHandshakeStatus();
            logInfo("Trạng thái bắt tay, lần " + attempt + ": " + status);

            switch (status) {
                case NEED_TASK:
                    runDelegatedTasks();
                    break;
                case NEED_WRAP:
                    netOutBuffer.clear();
                    SSLEngineResult result = engine.wrap(appOutBuffer, netOutBuffer);
                    logInfo("NEED_WRAP result: " + result);
                    if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        netOutBuffer = ByteBuffer.allocate(netOutBuffer.capacity() * 2);
                        logInfo("Tăng kích thước netOutBuffer: " + netOutBuffer.capacity());
                        continue;
                    }
                    if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                        close();
                        throw new IOException("SSLEngine đóng trong NEED_WRAP");
                    }
                    netOutBuffer.flip();
                    if (netOutBuffer.hasRemaining()) {
                        channel.write(netOutBuffer);
                        key.interestOpsOr(SelectionKey.OP_WRITE);
                        logInfo("Gửi dữ liệu NEED_WRAP, chờ sự kiện ghi: " + getRemoteAddressSafe());
                        return;
                    }
                    break;
                case NEED_UNWRAP:
                    netInBuffer.clear();
                    int bytesRead = channel.read(netInBuffer);
                    logInfo("NEED_UNWRAP, bytes read: " + bytesRead);
                    if (bytesRead == -1) {
                        logError("Kênh đóng bất ngờ trong bắt tay: " + getRemoteAddressSafe(), null);
                        close();
                        throw new IOException("Kênh đóng trong NEED_UNWRAP");
                    }
                    if (bytesRead == 0) {
                        noDataCount++;
                        if (noDataCount >= MAX_NO_DATA_ATTEMPTS) {
                            logError("Không nhận dữ liệu sau " + MAX_NO_DATA_ATTEMPTS + " lần thử: " + getRemoteAddressSafe(), null);
                            close();
                            throw new IOException("Peer không phản hồi trong NEED_UNWRAP");
                        }
                        key.interestOpsOr(SelectionKey.OP_READ);
                        logInfo("Chờ dữ liệu đến sau NEED_UNWRAP (lần " + noDataCount + "/" + MAX_NO_DATA_ATTEMPTS + ")");
                        return;
                    }
                    noDataCount = 0;
                    netInBuffer.flip();
                    appInBuffer.clear();
                    result = engine.unwrap(netInBuffer, appInBuffer);
                    logInfo("NEED_UNWRAP result: " + result);
                    switch (result.getStatus()) {
                        case BUFFER_UNDERFLOW:
                            key.interestOpsOr(SelectionKey.OP_READ);
                            logInfo("BUFFER_UNDERFLOW, chờ thêm dữ liệu");
                            return;
                        case BUFFER_OVERFLOW:
                            appInBuffer = ByteBuffer.allocate(appInBuffer.capacity() * 2);
                            logInfo("Tăng kích thước appInBuffer: " + appInBuffer.capacity());
                            continue;
                        case CLOSED:
                            close();
                            throw new IOException("SSLEngine đóng trong NEED_UNWRAP");
                        case OK:
                            break;
                    }
                    break;
                default:
                    logError("Trạng thái bắt tay không mong đợi: " + status + ", channel: " + getRemoteAddressSafe(), null);
                    close();
                    throw new IOException("Trạng thái bắt tay không hợp lệ: " + status);
            }
        }

        if (attempt >= maxAttempts) {
            logError("Quá số lần thử bắt tay: " + maxAttempts + ", channel: " + getRemoteAddressSafe(), null);
            close();
            throw new IOException("Bắt tay SSL/TLS thất bại sau " + maxAttempts + " lần thử");
        }

        handshakeCompleted = true;
        key.interestOps(SelectionKey.OP_READ);
        logInfo("Bắt tay SSL/TLS hoàn tất cho channel: " + getRemoteAddressSafe());
    }

    private void runDelegatedTasks() {
        Runnable task;
        while ((task = engine.getDelegatedTask()) != null) {
            logInfo("Chạy delegated task");
            task.run();
        }
    }

    public String read() throws IOException {
        if (closing) {
            logInfo("Channel đang đóng, không đọc: " + getRemoteAddressSafe());
            return null;
        }
        if (!handshakeCompleted) {
            handleHandshake();
            if (!handshakeCompleted || closing) {
                logInfo("Bắt tay chưa hoàn tất hoặc channel đóng: " + getRemoteAddressSafe());
                return null;
            }
        }

        netInBuffer.clear();
        int bytesRead = channel.read(netInBuffer);
        logInfo("Đọc dữ liệu, bytes read: " + bytesRead + ", channel: " + getRemoteAddressSafe());
        if (bytesRead == -1) {
            logInfo("Kênh đóng bởi client: " + getRemoteAddressSafe());
            close();
            return null;
        }
        if (bytesRead == 0) {
            key.interestOpsOr(SelectionKey.OP_READ);
            logInfo("Không có dữ liệu, chờ sự kiện đọc: " + getRemoteAddressSafe());
            return null;
        }

        netInBuffer.flip();
        while (netInBuffer.hasRemaining()) {
            appInBuffer.clear();
            SSLEngineResult result = engine.unwrap(netInBuffer, appInBuffer);
            logInfo("unwrap result: " + result);
            switch (result.getStatus()) {
                case BUFFER_UNDERFLOW:
                    key.interestOpsOr(SelectionKey.OP_READ);
                    logInfo("BUFFER_UNDERFLOW, chờ thêm dữ liệu: " + getRemoteAddressSafe());
                    return null;
                case BUFFER_OVERFLOW:
                    appInBuffer = ByteBuffer.allocate(appInBuffer.capacity() * 2);
                    logInfo("Tăng kích thước appInBuffer: " + appInBuffer.capacity());
                    continue;
                case CLOSED:
                    logInfo("SSLEngine đóng: " + getRemoteAddressSafe());
                    close();
                    return null;
                case OK:
                    break;
            }
            appInBuffer.flip();
            if (appInBuffer.hasRemaining()) {
                String chunk = new String(appInBuffer.array(), 0, appInBuffer.limit());
                requestBuilder.append(chunk);
                if (requestBuilder.toString().contains("\n")) {
                    String request = requestBuilder.toString().trim();
                    requestBuilder.setLength(0);
                    logInfo("Nhận yêu cầu: " + request + ", channel: " + getRemoteAddressSafe());
                    return request;
                }
            }
        }
        logInfo("Dữ liệu chưa đủ để tạo yêu cầu hoàn chỉnh: " + getRemoteAddressSafe());
        key.interestOpsOr(SelectionKey.OP_READ);
        return null;
    }

    public byte[] readChunk() throws IOException {
        if (closing) {
            logInfo("Channel đang đóng, không đọc chunk: " + getRemoteAddressSafe());
            return null;
        }
        if (!handshakeCompleted) {
            handleHandshake();
            if (!handshakeCompleted || closing) {
                logInfo("Bắt tay chưa hoàn tất hoặc channel đóng: " + getRemoteAddressSafe());
                return null;
            }
        }

        netInBuffer.clear();
        int bytesRead = channel.read(netInBuffer);
        logInfo("Đọc chunk, bytes read: " + bytesRead + ", channel: " + getRemoteAddressSafe());
        if (bytesRead == -1) {
            logInfo("Kênh đóng bởi client: " + getRemoteAddressSafe());
            close();
            return null;
        }
        if (bytesRead == 0) {
            key.interestOpsOr(SelectionKey.OP_READ);
            logInfo("Không có dữ liệu chunk, chờ sự kiện đọc: " + getRemoteAddressSafe());
            return null;
        }

        netInBuffer.flip();
        while (netInBuffer.hasRemaining()) {
            appInBuffer.clear();
            SSLEngineResult result = engine.unwrap(netInBuffer, appInBuffer);
            logInfo("unwrap chunk result: " + result);
            switch (result.getStatus()) {
                case BUFFER_UNDERFLOW:
                    key.interestOpsOr(SelectionKey.OP_READ);
                    logInfo("BUFFER_UNDERFLOW, chờ thêm dữ liệu chunk: " + getRemoteAddressSafe());
                    return null;
                case BUFFER_OVERFLOW:
                    appInBuffer = ByteBuffer.allocate(appInBuffer.capacity() * 2);
                    logInfo("Tăng kích thước appInBuffer: " + appInBuffer.capacity());
                    continue;
                case CLOSED:
                    logInfo("SSLEngine đóng: " + getRemoteAddressSafe());
                    close();
                    return null;
                case OK:
                    break;
            }
            appInBuffer.flip();
            if (appInBuffer.hasRemaining()) {
                chunkData.write(appInBuffer.array(), 0, appInBuffer.limit());
                if (chunkData.size() >= Infor.CHUNK_SIZE + 4) {
                    byte[] data = chunkData.toByteArray();
                    chunkData.reset();
                    logInfo("Nhận chunk, kích thước: " + data.length + ", channel: " + getRemoteAddressSafe());
                    return data;
                }
            }
        }
        logInfo("Dữ liệu chunk chưa đủ: " + getRemoteAddressSafe());
        key.interestOpsOr(SelectionKey.OP_READ);
        return null;
    }

    public void write(String data) {
        if (closing) {
            logInfo("Channel đang đóng, không ghi: " + getRemoteAddressSafe());
            return;
        }
        appOutBuffer.clear();
        appOutBuffer.put(data.getBytes());
        appOutBuffer.flip();
        key.interestOpsOr(SelectionKey.OP_WRITE);
        logInfo("Lên lịch ghi: " + data.trim() + ", channel: " + getRemoteAddressSafe());
    }

    public void writeChunk(int chunkIndex, byte[] chunkData) {
        if (closing) {
            logInfo("Channel đang đóng, không ghi chunk: " + getRemoteAddressSafe());
            return;
        }
        appOutBuffer.clear();
        appOutBuffer.putInt(chunkIndex);
        appOutBuffer.put(chunkData);
        appOutBuffer.flip();
        key.interestOpsOr(SelectionKey.OP_WRITE);
        logInfo("Lên lịch ghi chunk, index: " + chunkIndex + ", kích thước: " + chunkData.length + ", channel: " + getRemoteAddressSafe());
    }

    public boolean flush() throws IOException {
        if (closing) {
            logInfo("Channel đang đóng, không flush: " + getRemoteAddressSafe());
            return false;
        }
        if (!handshakeCompleted) {
            handleHandshake();
            if (!handshakeCompleted || closing) {
                logInfo("Bắt tay chưa hoàn tất hoặc channel đóng: " + getRemoteAddressSafe());
                return false;
            }
        }
        if (!appOutBuffer.hasRemaining()) {
            key.interestOpsAnd(~SelectionKey.OP_WRITE);
            logInfo("Không có dữ liệu để flush: " + getRemoteAddressSafe());
            return true;
        }

        netOutBuffer.clear();
        SSLEngineResult result = engine.wrap(appOutBuffer, netOutBuffer);
        logInfo("wrap result: " + result);
        switch (result.getStatus()) {
            case BUFFER_OVERFLOW:
                netOutBuffer = ByteBuffer.allocate(netOutBuffer.capacity() * 2);
                logInfo("Tăng kích thước netOutBuffer: " + netOutBuffer.capacity());
                return false;
            case CLOSED:
                logInfo("SSLEngine closing: " + getRemoteAddressSafe());
                close();
                return false;
            case BUFFER_UNDERFLOW:
            case OK:
                break;
        }

        netOutBuffer.flip();
        int bytesWritten = 0;
        while (netOutBuffer.hasRemaining()) {
            bytesWritten += channel.write(netOutBuffer);
        }
        logInfo("Đã ghi " + bytesWritten + " byte qua channel: " + getRemoteAddressSafe());
        if (!appOutBuffer.hasRemaining()) {
            key.interestOpsAnd(~SelectionKey.OP_WRITE);
            logInfo("Flush hoàn tất: " + getRemoteAddressSafe());
            return true;
        }
        key.interestOpsOr(SelectionKey.OP_WRITE);
        logInfo("Còn dữ liệu cần flush, chờ sự kiện ghi tiếp: " + getRemoteAddressSafe());
        return false;
    }

    public void close() {
        if (closing) {
            logInfo("Channel đã đóng trước đó: " + getRemoteAddressSafe());
            return;
        }
        closing = true;
        try {
            logInfo("Bắt đầu đóng channel: " + getRemoteAddressSafe());
            engine.closeOutbound();
            netOutBuffer.clear();
            SSLEngineResult result = engine.wrap(appOutBuffer, netOutBuffer);
            while (result.getStatus() != SSLEngineResult.Status.CLOSED && result.getStatus() != SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                netOutBuffer.flip();
                while (netOutBuffer.hasRemaining()) {
                    channel.write(netOutBuffer);
                }
                netOutBuffer.clear();
                result = engine.wrap(appOutBuffer, netOutBuffer);
            }
            channel.close();
        } catch (IOException ex) {
            logError("Lỗi khi đóng channel: " + ex.getMessage() + ", channel: " + getRemoteAddressSafe(), ex);
        } finally {
            if (key != null && key.isValid()) {
                key.cancel();
            }
            key = null;
            logInfo("Channel closed, key cancelled: " + getRemoteAddressSafe());
        }
    }

    public SocketChannel getChannel() {
        return channel;
    }

    public SelectionKey getKey() {
        return key;
    }
}