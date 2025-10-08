package main.java.domain.adapter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import main.java.domain.entity.FileInfo;
import main.java.domain.entity.PeerInfo;

import java.io.IOException;

public class FileInfoAdapter extends TypeAdapter<FileInfo> {
    @Override
    public void write(JsonWriter out, FileInfo value) throws IOException {
        out.value(value.getFileName() + "'" + value.getFileSize() + "'" + value.getFileHash() + "'" + value.getPeerInfo().getIp()
        + "'" + value.getPeerInfo().getPort());
    }

    @Override
    public FileInfo read(JsonReader in) throws IOException {
        String[] parts = in.nextString().split("'");
        return new FileInfo(parts[0], Long.parseLong(parts[1]), parts[2], new PeerInfo(parts[3], Integer.parseInt(parts[4])));
    }
}
