package model;

import java.nio.ByteBuffer;

public class ClientState {
    StringBuilder request = new StringBuilder();
    ByteBuffer readBuffer = ByteBuffer.allocate(1024);
    ByteBuffer writeBuffer = null;
    String clientAddress;

    ClientState(String clientAddress) {
        this.clientAddress = clientAddress;
    }
}
