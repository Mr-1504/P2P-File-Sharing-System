package main.java.domain.adapter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import main.java.domain.entity.PeerInfo;

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
        return new PeerInfo(parts[0], Integer.parseInt(parts[1]), parts[2]);
    }
}
