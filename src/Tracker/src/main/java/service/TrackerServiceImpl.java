package service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dto.OfflineMessage;
import dto.Peer;
import model.PeerInfo;
import repository.OfflineMessageRepository;
import repository.OfflineMessageRepositoryImpl;
import repository.PeerRepository;
import repository.PeerRepositoryImpl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static utils.Log.logError;

public class TrackerServiceImpl implements TrackerService{
    private final PeerRepository peerRepository = new PeerRepositoryImpl();
    private final OfflineMessageRepository offlineMessageRepository = new OfflineMessageRepositoryImpl();
    @Override
    public Peer onCsrSigned(Peer peer) throws Exception {
        // Check if public key already exists for another peer
        Peer existingByKey = peerRepository.findByPublicKey(peer.getPublicKey());
        if (existingByKey != null) {
            if (!existingByKey.getIp().equals(peer.getIp()) || existingByKey.getPort() != peer.getPort()) {
                // Public key already in use by different peer
                throw new Exception("Public key already exists for another peer");
            }
            // Same peer already has this key, return it
            return existingByKey;
        }

        // Check if peer with same ip/port exists
        Peer existingByIpPort = peerRepository.findByIpAndPort(peer.getIp(), peer.getPort());
        LocalDateTime currentTime = LocalDateTime.now();
        if (existingByIpPort != null) {
            // Update existing peer's public key
            existingByIpPort.setPublicKey(peer.getPublicKey());
            existingByIpPort.setOnline(true);
            existingByIpPort.setLastSeen(currentTime);
            return peerRepository.save(existingByIpPort);
        } else {
            // New peer
            peer.setCreatedAt(currentTime);
            peer.setLastSeen(currentTime);
            peer.setOnline(true);
            return peerRepository.save(peer);
        }
    }

    @Override
    public Peer findPeerById(java.util.UUID id) {
        return peerRepository.findById(id);
    }

    @Override
    public Peer findPeerByIpAndPort(String ip, int port) {
        return peerRepository.findByIpAndPort(ip, port);
    }

    @Override
    public OfflineMessage saveOfflineMessage(OfflineMessage message) {
        return offlineMessageRepository.save(message);
    }

    @Override
    public String getOfflineMessagesJsonForPeer(UUID receiverId) {
        try {
            List<OfflineMessage> messages = offlineMessageRepository.findByReceiverId(receiverId);
            if (messages == null || messages.isEmpty()) {
                return "[]"; // Empty array when no messages
            }

            // Create simple POJOs for JSON serialization to avoid issues with Hibernate proxies
            List<MessageDTO> messageDTOs = new java.util.ArrayList<>();
            for (OfflineMessage msg : messages) {
                MessageDTO dto = new MessageDTO();
                dto.setId(msg.getId());
                dto.setSender_id(msg.getSenderId().toString());
                dto.setGroup_id(msg.getGroupId() != null ? msg.getGroupId().toString() : null);
                dto.setEncrypted_payload(Base64.getEncoder().encodeToString(msg.getEncryptedPayload()));
                dto.setType(msg.getType());
                dto.setCreated_at(msg.getCreatedAt().toString()); // ISO format
                messageDTOs.add(dto);
            }

            Gson gson = new GsonBuilder().create();
            String json = gson.toJson(messageDTOs);
            return json;
        } catch (Exception e) {
            logError("Error retrieving offline messages for peer: " + receiverId, e);
            return null; // Signal error to caller
        }
    }

    @Override
    public void acknowledgeOfflineMessages(String messageIdsCsv) throws Exception {
        try {
            String[] idStrings = messageIdsCsv.split(",");
            List<Long> messageIds = new java.util.ArrayList<>();
            for (String idStr : idStrings) {
                idStr = idStr.trim();
                if (!idStr.isEmpty()) {
                    messageIds.add(Long.parseLong(idStr));
                }
            }

            if (!messageIds.isEmpty()) {
                offlineMessageRepository.deleteByIds(messageIds);
            }
        } catch (NumberFormatException e) {
            throw new Exception("Invalid message ID format: " + messageIdsCsv, e);
        } catch (Exception e) {
            throw new Exception("Failed to acknowledge offline messages", e);
        }
    }

    @Override
    public List<PeerInfo> getAllPeers() {
        try {
            List<Peer> peers = peerRepository.findAll();
            if (peers == null) {
                return new ArrayList<>();
            }
            List<PeerInfo> peerInfos = new ArrayList<>();
            for (Peer peer : peers) {
                peerInfos.add(new PeerInfo(peer.getIp(), peer.getPort(), null));
            }
            return peerInfos;
        } catch (Exception e) {
            logError("Error retrieving all peers", e);
            return new ArrayList<>();
        }
    }

    // Simple POJO for JSON serialization of message data
    private static class MessageDTO {
        private Long id;
        private String sender_id;
        private String group_id;
        private String encrypted_payload;
        private String type;
        private String created_at;

        // Getters and setters
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getSender_id() { return sender_id; }
        public void setSender_id(String sender_id) { this.sender_id = sender_id; }

        public String getGroup_id() { return group_id; }
        public void setGroup_id(String group_id) { this.group_id = group_id; }

        public String getEncrypted_payload() { return encrypted_payload; }
        public void setEncrypted_payload(String encrypted_payload) { this.encrypted_payload = encrypted_payload; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getCreated_at() { return created_at; }
        public void setCreated_at(String created_at) { this.created_at = created_at; }
    }
}
