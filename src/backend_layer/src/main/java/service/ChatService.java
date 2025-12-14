package service;

import domain.entity.*;
import domain.repository.IChatRepository;
import domain.repository.IPeerJpaRepository;
import infras.repository.ChatRepository;
import infras.repository.PeerJpaRepository;
import infras.utils.SSLUtils;
import utils.AppPaths;
import utils.Config;
import utils.CryptoUtils;
import utils.Log;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Chat service implementation.
 */
public class ChatService implements IChatService {

    private final IChatRepository chatRepository;
    private final IPeerJpaRepository peerRepository;
    private final INetworkService networkService; // For tracker communication
    private final WebSocketClientService directMessagingService; // For direct P2P messaging
    private final BlockingQueue<Runnable> messageProcessingQueue;
    private final String ownPeerId;
    private final PrivateKey ownPrivateKey; // From SSL keystore

    // Cache for public keys to avoid repeated tracker calls
    private final Map<String, PublicKey> publicKeyCache = new HashMap<>();

    public ChatService(INetworkService networkService) {
        this.networkService = networkService;
        this.chatRepository = new ChatRepository(); // In real impl, inject
        this.peerRepository = new PeerJpaRepository(); // In real impl, inject
        this.directMessagingService = new WebSocketClientService();
        this.messageProcessingQueue = new LinkedBlockingQueue<>();
        this.ownPeerId = AppPaths.loadUsername();

        // Load own private key from keystore
        this.ownPrivateKey = loadOwnPrivateKey();

        // Start message processing threads
        startMessageProcessingThreads();
    }

    @Override
    public boolean sendPrivateMessage(String conversationId, String content) {
        try {
            // Get conversation
            Conversation conversation = chatRepository.findConversationById(conversationId);
            if (conversation == null || conversation.getIsGroup() != 0) {
                Log.logError("Invalid or non-private conversation: " + conversationId, null);
                return false;
            }

            // Get public key from conversation
            String peerPublicKeyStr = conversation.getPeerPublicKey();
            if (peerPublicKeyStr == null || peerPublicKeyStr.isEmpty()) {
                Log.logError("No public key available in conversation: " + conversationId, null);
                return false;
            }

            PublicKey receiverPublicKey = CryptoUtils.loadPublicKey(peerPublicKeyStr);

            // Encrypt message
            String encryptedPayload = CryptoUtils.buildEncryptedPayload(content, receiverPublicKey);

            // Store locally (store plain text locally)
            Message message = createMessage(conversationId, ownPeerId, "text", content);
            chatRepository.saveMessage(message);

            // Update conversation last message
            updateConversationLastMessage(conversationId, content, System.currentTimeMillis());

            Peer peer = peerRepository.findByPublicKey(conversation.getPeerPublicKey());
            Peer sender = peerRepository.findPeerByIpAndPort(Config.SERVER_IP, Config.PEER_PORT);

            // Try direct P2P messaging first
            boolean directSuccess = false;
            if (peer != null) {
                directSuccess = directMessagingService.sendMessageDirect(peer.getIp(), peer.getPort(), encryptedPayload);
            }

            if (!directSuccess) {
                // Fallback to tracker delivery
                Log.logInfo("Direct messaging failed, using tracker for message delivery");
                sendToTracker(sender.getTrackerPeerId(), peer.getTrackerPeerId(), null, encryptedPayload);
            }

            return true;
        } catch (Exception e) {
            Log.logError("Error sending private message", e);
            return false;
        }
    }

    @Override
    public boolean sendGroupMessage(String groupId, String content) {
        try {
            // Get group members and their public keys
            List<GroupMember> members = chatRepository.findGroupMembersByGroupId(groupId);
            Map<String, PublicKey> memberPublicKeys = new HashMap<>();
            for (GroupMember member : members) {
                PublicKey pubKey = getOrFetchPublicKey(member.getPeerId());
                if (pubKey != null) {
                    memberPublicKeys.put(member.getPeerId(), pubKey);
                }
            }

            if (memberPublicKeys.isEmpty()) {
                Log.logError("No member public keys available for group: " + groupId, null);
                return false;
            }

            // Encrypt for each member
            Map<String, String> encryptedPayloads = CryptoUtils.buildGroupEncryptedPayloads(content, memberPublicKeys);

            // Store locally
            Conversation conversation = chatRepository.findConversationById(groupId);
            if (conversation == null) {
                Log.logError("Conversation not found: " + groupId, null);
                return false;
            }

            Message message = createMessage(groupId, ownPeerId, "text", content); // Store plain locally? Or encrypt for self
            chatRepository.saveMessage(message);

            // Send to tracker for each member (since offline)
            for (Map.Entry<String, String> entry : encryptedPayloads.entrySet()) {
                sendToTracker(entry.getKey(), groupId, groupId, entry.getValue());
            }

            return true;
        } catch (Exception e) {
            Log.logError("Error sending group message", e);
            return false;
        }
    }

    @Override
    public Conversation createPrivateConversation(String conversationName, String receiverPublicKey) {
        // Check if exists
        Conversation existing = getAllConversations().stream()
                .filter(c -> c.getIsGroup() == 0 && conversationName.equals(c.getName()))
                .findFirst().orElse(null);
        if (existing != null) {
            return existing;
        }

        Conversation conv = new Conversation();
        conv.setId(generatePrivateConversationId(ownPeerId, conversationName));
        conv.setName(conversationName);
        conv.setIsGroup(0);
        conv.setPeerPublicKey(receiverPublicKey);
        return chatRepository.saveConversation(conv);
    }

    @Override
    public Conversation createPrivateConversation(PeerInfo receiver, String receiverPublicKey) {
        String username = receiver.getUsername() != null ? receiver.getUsername() : receiver.getIp() + ":" + receiver.getPort();
        // Check if exists
        Conversation existing = getAllConversations().stream()
                .filter(c -> c.getIsGroup() == 0 && username.equals(c.getName()))
                .findFirst().orElse(null);
        if (existing != null) {
            return existing;
        }

        Conversation conv = new Conversation();
        conv.setId(generatePrivateConversationId(ownPeerId, username));
        conv.setName(username);
        conv.setIsGroup(0);
        conv.setPeerPublicKey(receiverPublicKey);
        return chatRepository.saveConversation(conv);
    }

    @Override
    public Conversation createGroupConversation(String groupName, List<String> memberIds) {
        Conversation conv = new Conversation();
        conv.setId(generateConversationId());
        conv.setName(groupName);
        conv.setIsGroup(1);
        Conversation saved = chatRepository.saveConversation(conv);

        // Add members
        for (String memberId : memberIds) {
            // Assume public keys are cached or fetch
            // TODO: fetch public keys
            GroupMember member = new GroupMember();
            member.setGroupId(saved.getId());
            member.setPeerId(memberId);
            member.setPublicKey(null); // TODO
            member.setRole("member");
            chatRepository.saveGroupMember(member);
        }
        return saved;
    }

    @Override
    public Conversation getConversationById(String conversationId) {
        return chatRepository.findConversationById(conversationId);
    }

    @Override
    public List<Conversation> getAllConversations() {
        return chatRepository.findAllConversations();
    }

    @Override
    public List<Message> getMessages(String conversationId, int limit, int offset) {
        return chatRepository.findMessagesByConversationId(conversationId, limit, offset);
    }

    @Override
    public List<Message> getOfflineMessages(String receiverId) {
        // TODO: Call tracker to get offline messages, but for now return stored
        return chatRepository.findOfflineMessages(receiverId);
    }

    @Override
    public void acknowledgeMessages(List<String> messageIds) {
        // Delete acknowledged messages from offline storage
//        chatRepository.deleteMessages(messageIds);
        // TODO: Send ACK_OFFLINE_MSGS to tracker
    }

    @Override
    public String getPublicKeyFromTracker(String peerId) {
        // Check if peerId is "ip:port" format
        if (peerId.contains(":")) {
            String[] parts = peerId.split(":");
            if (parts.length == 2) {
                try {
                    String ip = parts[0];
                    int port = Integer.parseInt(parts[1]);
                    return networkService.requestPublicKey(ip, port);
                } catch (NumberFormatException e) {
                    Log.logError("Invalid port in peerId: " + peerId, null);
                }
            }
        }
        return null;
    }

    @Override
    public boolean addGroupMember(String groupId, String memberId, String publicKey) {
        try {
            GroupMember member = new GroupMember();
            member.setGroupId(groupId);
            member.setPeerId(memberId);
            member.setPublicKey(publicKey);
            member.setRole("member");
            chatRepository.saveGroupMember(member);
            return true;
        } catch (Exception e) {
            Log.logError("Error adding group member", e);
            return false;
        }
    }

    @Override
    public List<String> getGroupMembers(String groupId) {
        List<GroupMember> members = chatRepository.findGroupMembersByGroupId(groupId);
        List<String> memberIds = new ArrayList<>();
        for (GroupMember m : members) {
            memberIds.add(m.getPeerId());
        }
        return memberIds;
    }

    private Conversation getOrCreatePrivateConversation(String receiverId, String receiverPublicKey) {
        // TODO: Implement lookup by participants
        return createPrivateConversation(receiverId, receiverPublicKey);
    }

    private Message createMessage(String conversationId, String senderId, String type, String content) {
        Message msg = new Message();
        msg.setId(generateMessageId());
        msg.setConversationId(conversationId);
        msg.setSenderId(senderId);
        msg.setMsgType(type);
        msg.setContent(content);
        msg.setStatus("sent");
        msg.setCreatedAt(System.currentTimeMillis());
        return msg;
    }

    private PublicKey getOrFetchPublicKey(String peerId) {
//        if (publicKeyCache.containsKey(peerId)) {
//            return publicKeyCache.get(peerId);
//        }

        // Try to get from local DB first
        try {
            if (peerId.contains(":")) {
                String[] parts = peerId.split(":");
                if (parts.length == 2) {
                    String ip = parts[0];
                    int port = Integer.parseInt(parts[1]);
                    Peer peer = peerRepository.findPeerByIpAndPort(ip, port);
                    if (peer != null && peer.getPublicKey() != null) {
                        PublicKey pubKey = CryptoUtils.loadPublicKey(peer.getPublicKey());
                        publicKeyCache.put(peerId, pubKey);
                        return pubKey;
                    }
                }
            }
        } catch (Exception e) {
            Log.logError("Error getting key from local DB", e);
        }

        // Fallback to tracker
        String pemKey = getPublicKeyFromTracker(peerId);
        if (pemKey != null) {
            try {
                PublicKey pubKey = CryptoUtils.loadPublicKey(pemKey);
                publicKeyCache.put(peerId, pubKey);
                return pubKey;
            } catch (Exception e) {
                Log.logError("Error loading public key from PEM", e);
            }
        }
        return null;
    }

    private PublicKey getOrFetchPublicKeyPrivate(String conversationId) {
        if (publicKeyCache.containsKey(conversationId)) {
            return publicKeyCache.get(conversationId);
        }

        Conversation conversation = chatRepository.findConversationById(conversationId);
        // Try to get from local DB first
        try {
            PublicKey pubKey = CryptoUtils.loadPublicKey(conversation.getPeerPublicKey());
            publicKeyCache.put(conversationId, pubKey);
            return pubKey;
        } catch (Exception e) {
            Log.logError("Error getting key from local DB", e);
        }

//        // Fallback to tracker
//        String pemKey = getPublicKeyFromTracker(peerId);
//        if (pemKey != null) {
//            try {
//                PublicKey pubKey = CryptoUtils.loadPublicKey(pemKey);
//                publicKeyCache.put(peerId, pubKey);
//                return pubKey;
//            } catch (Exception e) {
//                Log.logError("Error loading public key from PEM", e);
//            }
//        }
        return null;
    }

    private void sendToTracker(String senderId, String receiverId, String groupId, String encryptedPayload) {
        boolean success = networkService.sendMessageToTracker(receiverId, senderId, groupId != null ? groupId : "", encryptedPayload);
        if (!success) {
            Log.logError("Failed to send message to tracker for receiver: " + receiverId, null);
        }
    }

    private void updateConversationLastMessage(String conversationId, String content, long timestamp) {
        Conversation conv = chatRepository.findConversationById(conversationId);
        if (conv != null) {
            conv.setLastMsgContent(content);
            conv.setLastMsgTime(timestamp);
            conv.setUnreadCount(0); // Reset for self
            chatRepository.updateConversation(conv);
        }
    }

    private void startMessageProcessingThreads() {
        int numThreads = 4; // Configurable
        for (int i = 0; i < numThreads; i++) {
            Thread thread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Runnable task = messageProcessingQueue.take();
                        task.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
    }

    public void enqueueMessageForProcessing(String encryptedMessage) {
        messageProcessingQueue.offer(() -> {
            processIncomingMessage(encryptedMessage);
        });
    }

    private void processIncomingMessage(String encryptedMessage) {
        try {
            Log.logInfo("Processing incoming encrypted message");

            // 1. Decrypt using own private key
            String decryptedContent = CryptoUtils.decryptPayload(encryptedMessage, ownPrivateKey);
            Log.logInfo("Message decrypted successfully: " + decryptedContent);

            // 2. Parse message format after decryption: senderIP:port|content
            String[] parts = decryptedContent.split("\\|", 2);
            if (parts.length != 2) {
                Log.logError("Invalid message format. Expected: senderIP:port|content", null);
                return;
            }

            String senderPeerId = parts[0];
            String content = parts[1]; // decrypted message content

            Log.logInfo("Parsed message: sender=" + senderPeerId + ", content=" + content);

            // 3. Find or create conversation based on sender
            Conversation conversation = findOrCreateConversationForIncoming(senderPeerId);

            // 4. Create and save message
            Message message = createMessage(conversation.getId(), senderPeerId, "text", content);
            chatRepository.saveMessage(message);
            Log.logInfo("Message saved to DB");

            // 5. Update conversation last message
            updateConversationLastMessage(conversation.getId(), content, message.getCreatedAt());

            Log.logInfo("Incoming message processed successfully from: " + senderPeerId);

        } catch (Exception e) {
            Log.logError("Error processing incoming message", e);
        }
    }

    private Message processAndStoreIncomingMessage(String encryptedMessage) {
        try {
            // Same logic as processIncomingMessage but returns the created Message
            Log.logInfo("Processing incoming encrypted message for storage");

            // 1. Decrypt using own private key
            String decryptedContent = CryptoUtils.decryptPayload(encryptedMessage, ownPrivateKey);

            // 2. Parse message format after decryption: senderIP:port|content
            String[] parts = decryptedContent.split("\\|", 2);
            if (parts.length != 2) {
                Log.logError("Invalid message format. Expected: senderIP:port|content", null);
                return null;
            }

            String senderPeerId = parts[0]; // "192.168.1.100:8080"
            String content = parts[1]; // decrypted message content

            // 3. Find or create conversation
            Conversation conversation = findOrCreateConversationForIncoming(senderPeerId);

            // 4. Create and save message, return it
            Message message = createMessage(conversation.getId(), senderPeerId, "text", content);
            chatRepository.saveMessage(message);

            // 5. Update conversation last message
            updateConversationLastMessage(conversation.getId(), content, message.getCreatedAt());

            Log.logInfo("Incoming message processed and stored: " + message.getId());
            return message;

        } catch (Exception e) {
            Log.logError("Error processing incoming message for storage", e);
            return null;
        }
    }

    private Conversation findOrCreateConversationForIncoming(String senderPeerId) {
        // For incoming messages, assume it's a private message to us
        // Find conversation with sender
        for (Conversation conv : getAllConversations()) {
            if (conv.getIsGroup() == 0 && senderPeerId.equals(conv.getName())) {
                return conv;
            }
        }

        // Create new private conversation
        return createPrivateConversation(senderPeerId, null); // We don't have sender's public key yet
    }

    private String getOtherPeerInPrivateConvo(Conversation conv, String currentPeerId) {
        // This is a simple implementation - would need public key comparison
        // For now, return the peer from sender ID we got
        return null; // TODO: Implement proper peer lookup
    }

    private PrivateKey loadOwnPrivateKey() {
        try {
            // Load keystore (similar to SSLUtils.createSSLContext())
            KeyStore keyStore = KeyStore.getInstance("JKS");
            File keyStoreFile = Paths.get(SSLUtils.CERT_DIRECTORY.toFile().getAbsolutePath(),
                                          SSLUtils.KEYSTORE_NAME).toFile();

            try (FileInputStream fis = new FileInputStream(keyStoreFile)) {
                keyStore.load(fis, SSLUtils.KEYSTORE_PASSWORD.toCharArray());
            }

            // Get private key from keystore
            KeyStore.PrivateKeyEntry keyEntry = (KeyStore.PrivateKeyEntry)
                keyStore.getEntry(SSLUtils.KEY_ALIAS,
                                  new KeyStore.PasswordProtection(SSLUtils.KEYSTORE_PASSWORD.toCharArray()));

            return keyEntry.getPrivateKey();

        } catch (Exception e) {
            Log.logError("Failed to load own private key from keystore", e);
            return null;
        }
    }

    private String generateConversationId() {
        return UUID.randomUUID().toString();
    }

    private String generatePrivateConversationId(String own, String other) {
        List<String> participants = Arrays.asList(own, other);
        Collections.sort(participants);
        String combined = String.join("-", participants);
        return UUID.nameUUIDFromBytes(combined.getBytes()).toString();
    }

    private String generateMessageId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public boolean isPrivateConversationExists(String publicKey) {
        return getAllConversations().stream()
                .anyMatch(c -> c.getIsGroup() == 0 && publicKey.equals(c.getPeerPublicKey()));
    }

    @Override
    public void createPrivateConversationIfNotExists(String username, String publicKey) {
        if (!isPrivateConversationExists(publicKey)) {
            createPrivateConversation(username, publicKey);
        } else {
            // update public key
            Conversation existing = getAllConversations().stream()
                    .filter(c -> c.getIsGroup() == 0 && publicKey.equals(c.getPeerPublicKey()))
                    .findFirst().orElse(null);
            if (existing != null) {
                existing.setPeerPublicKey(publicKey);
                existing.setName(username);
                chatRepository.saveConversation(existing);
            }
        }
    }
}
