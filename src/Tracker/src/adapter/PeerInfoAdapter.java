package src.adapter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import src.model.PeerInfo;

import java.io.IOException;

public class PeerInfoAdapter extends TypeAdapter<PeerInfo> {
    @Override
    public void write(JsonWriter jsonWriter, PeerInfo peerInfo) throws IOException {
        jsonWriter.value(peerInfo.getIp() + ":" + peerInfo.getPort() + ":" + peerInfo.getUsername());
    }

    @Override
    public PeerInfo read(JsonReader jsonReader) throws IOException {
        String value = jsonReader.nextString();
        String[] parts = value.split(":");
        if (parts.length < 3) {
            throw new IOException("Invalid PeerInfo format: expected 'ip:port:username', got '" + value + "'");
        }
        int port;
        try {
            port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid port in PeerInfo: '" + parts[1] + "'", e);
        }
        return new PeerInfo(parts[0], port, parts[2]);
    }
}
