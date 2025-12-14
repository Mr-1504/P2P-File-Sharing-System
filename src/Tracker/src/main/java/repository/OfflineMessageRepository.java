package repository;

import dto.OfflineMessage;

import java.util.List;
import java.util.UUID;

public interface OfflineMessageRepository {
    OfflineMessage save(OfflineMessage message);
    List<OfflineMessage> findByReceiverId(UUID receiverId);
    void deleteByIds(List<Long> messageIds);
}
