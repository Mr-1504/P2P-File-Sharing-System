package domain.adapter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import domain.entity.Peer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Adapter for serializing and deserializing Peer objects to and from JSON from tracker.
 * Maps tracker 'id' to trackerPeerId field, not to Hibernate @Id field.
 */
public class PeerAdapter extends TypeAdapter<Peer> {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void write(JsonWriter jsonWriter, Peer peer) throws IOException {
        jsonWriter.beginObject()
                  .name("id").value(peer.getTrackerPeerId())
                  .name("ip").value(peer.getIp())
                  .name("port").value(peer.getPort())
                  .name("publicKey").value(peer.getPublicKey())
                  .name("isOnline").value(peer.isOnline())
                  .name("lastSeen").value(peer.getLastSeen().format(FORMATTER))
                  .name("createdAt").value(peer.getCreatedAt().format(FORMATTER))
                  .endObject();
    }

    @Override
    public Peer read(JsonReader jsonReader) throws IOException {
        String trackerPeerId = null;
        String ip = null;
        int port = 0;
        String publicKey = null;
        boolean isOnline = false;
        LocalDateTime lastSeen = null;
        LocalDateTime createdAt = null;

        jsonReader.beginObject();
        while (jsonReader.hasNext()) {
            String name = jsonReader.nextName();
            switch (name) {
                case "id":
                    trackerPeerId = jsonReader.nextString();
                    break;
                case "ip":
                    ip = jsonReader.nextString();
                    break;
                case "port":
                    port = jsonReader.nextInt();
                    break;
                case "publicKey":
                    publicKey = jsonReader.nextString();
                    break;
                case "isOnline":
                    isOnline = jsonReader.nextBoolean();
                    break;
                case "lastSeen":
                    String lastSeenStr = jsonReader.nextString();
                    lastSeen = LocalDateTime.parse(lastSeenStr, FORMATTER);
                    break;
                case "createdAt":
                    String createdAtStr = jsonReader.nextString();
                    createdAt = LocalDateTime.parse(createdAtStr, FORMATTER);
                    break;
                default:
                    jsonReader.skipValue();
            }
        }
        jsonReader.endObject();

        // Create Peer with id and trackerPeerId set correctly
        UUID id = null;
        try {
            id = UUID.fromString(trackerPeerId);
        } catch (IllegalArgumentException e) {
            // If not UUID format, set random UUID for Hibernate
            id = UUID.randomUUID();
        }

        return new Peer(id, trackerPeerId, ip, port, publicKey, isOnline, lastSeen, createdAt);
    }
}
