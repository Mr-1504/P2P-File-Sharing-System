package service;

import domain.entity.*;
import domain.repository.IChatRepository;
import infras.repository.ChatRepository;
import utils.AppPaths;
import utils.Config;
import utils.CryptoUtils;
import utils.Log;

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
    private final INetworkService networkService; // For tracker communication
    private final BlockingQueue<Runnable> messageProcessingQueue;
    private final String ownPeerId;
    private final PrivateKey ownPrivateKey; // From SSL keystore

    // Cache for public keys to avoid repeated tracker calls
    private final Map<String, PublicKey> publicKeyCache = new HashMap<>();

    public ChatService(INetworkService networkService) {
        this.networkService = networkService;
        this.chatRepository = new ChatRepository(); // In real impl, inject
        this.messageProcessingQueue = new LinkedBlockingQueue<>();
        this.ownPeerId = AppPaths.loadUsername();

        // Load own private key from keystore
        this.ownPrivateKey = loadOwnPrivateKey();

        // Start message processing threads
        startMessageProcessingThreads();
    }

    @Override
    public boolean sendPrivateMessage(String receiverId, String content) {
        try {
            // Get or fetch receiver's public key
            PublicKey receiverPublicKey = getOrFetchPublicKey(receiverId);
            if (receiverPublicKey == null) {
                Log.logError("Cannot get public key for receiver: " + receiverId, null);
                return false;
            }

            // Encrypt message
            String encryptedPayload = CryptoUtils.buildEncryptedPayload(content, receiverPublicKey);

            // Store locally
            Conversation conversation = getOrCreatePrivateConversation(receiverId, null); // We don't have key yet
            Message message = createMessage(conversation.getId(), ownPeerId, "text", encryptedPayload);
            chatRepository.saveMessage(message);

            // Try direct send via websocket (TODO: implement)
            // For now, send to tracker as offline message
            sendToTracker(receiverId, conversation.getId(), null, encryptedPayload);

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
        // For simplicity, assume one conversation per pair
        Conversation conv = new Conversation();
        conv.setId(generateConversationId());
        conv.setName(conversationName);
        conv.setIsGroup(0);
        conv.setPeerPublicKey(receiverPublicKey);
        return chatRepository.saveConversation(conv);
    }
    @Override
    public Conversation createPrivateConversation(PeerInfo receiver, String receiverPublicKey) {
        // Check if exists
        // For simplicity, assume one conversation per pair
        Conversation conv = new Conversation();
        conv.setId(generateConversationId());
        String username = receiver.getUsername() != null ? receiver.getUsername() : receiver.getIp() + ":" + receiver.getPort();
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
        chatRepository.deleteMessages(messageIds);
        // TODO: Send ACK_OFFLINE_MSGS to tracker
    }

    @Override
    public String getPublicKeyFromTracker(String peerId) {
        // TODO: Implement tracker request PUBLIC_KEY|<ip>|<port>
        // For now, return null
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
        if (publicKeyCache.containsKey(peerId)) {
            return publicKeyCache.get(peerId);
        }
        // TODO: Fetch from tracker or DB
        String pemKey = getPublicKeyFromTracker(peerId);
        if (pemKey != null) {
            PublicKey pubKey = CryptoUtils.loadPublicKey(pemKey);
            publicKeyCache.put(peerId, pubKey);
            return pubKey;
        }
        return null;
    }

    private void sendToTracker(String receiverId, String senderId, String groupId, String encryptedPayload) {
        // TODO: Implement SEND_MSG request to tracker
        // Format: SEND_MSG|Sender_ID=<uuid>|Receiver_ID=<uuid>|Group_ID=<uuid or NULL>|Payload_Base64
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
            // Decrypt using own private key
            String content = CryptoUtils.decryptPayload(encryptedMessage, ownPrivateKey);

            // TODO: Determine sender, conversation, store in DB

        } catch (Exception e) {
            Log.logError("Error processing incoming message", e);
        }
    }

    private PrivateKey loadOwnPrivateKey() {
        // TODO: Load from SSL keystore
        // For now, return null
        return null;
    }

    private String generateConversationId() {
        return UUID.randomUUID().toString();
    }

    private String generateMessageId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public boolean isPrivateConversationExists(String username) {
        return getAllConversations().stream()
                .anyMatch(c -> c.getIsGroup() == 0 && username.equals(c.getName()));
    }

    @Override
    public void createPrivateConversationIfNotExists(String username, String publicKey) {
        if (!isPrivateConversationExists(username)) {
            createPrivateConversation(username, publicKey);
        } else {
            // update public key
            Conversation existing = getAllConversations().stream()
                .filter(c -> c.getIsGroup() == 0 && username.equals(c.getName()))
                .findFirst().orElse(null);
            if (existing != null) {
                existing.setPeerPublicKey(publicKey);
                chatRepository.saveConversation(existing);
            }
        }
    }
}
