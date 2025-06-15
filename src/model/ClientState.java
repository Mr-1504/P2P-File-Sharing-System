package model;

import javax.net.ssl.SSLEngine;
import java.nio.ByteBuffer;

public class ClientState {
    StringBuilder request = new StringBuilder();
    ByteBuffer readBuffer = ByteBuffer.allocate(1024);
    ByteBuffer writeBuffer = null;
    String clientAddress;
    SSLEngine sslEngine;

    ClientState(String clientAddress, SSLEngine sslEngine) {
        this.clientAddress = clientAddress;
        this.sslEngine = sslEngine;
    }
}
