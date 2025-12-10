package repository;

import dto.OfflineMessage;
import org.hibernate.Session;
import org.hibernate.Transaction;
import utils.HibernateUtils;

import java.util.List;
import java.util.UUID;

public class OfflineMessageRepositoryImpl implements OfflineMessageRepository {
    @Override
    public OfflineMessage save(OfflineMessage message) {
        Transaction transaction = null;
        try (Session session = HibernateUtils.getSessionFactory().openSession()) {
            transaction = session.beginTransaction();
            session.save(message);
            transaction.commit();
            return message;
        } catch (Exception e) {
            if (transaction != null) {
                transaction.rollback();
            }
            utils.Log.logError("Error saving OfflineMessage entity", e);
            return null;
        }
    }

    @Override
    public List<OfflineMessage> findByReceiverId(UUID receiverId) {
        try (Session session = HibernateUtils.getSessionFactory().openSession()) {
            return session.createQuery("from OfflineMessage where receiverId = :receiverId order by createdAt", OfflineMessage.class)
                    .setParameter("receiverId", receiverId)
                    .getResultList();
        } catch (Exception e) {
            utils.Log.logError("Error finding OfflineMessage entities by receiver ID", e);
            return null;
        }
    }

    @Override
    public void deleteByIds(List<Long> messageIds) {
        Transaction transaction = null;
        try (Session session = HibernateUtils.getSessionFactory().openSession()) {
            transaction = session.beginTransaction();
            session.createQuery("delete from OfflineMessage where id in :ids")
                    .setParameter("ids", messageIds)
                    .executeUpdate();
            transaction.commit();
        } catch (Exception e) {
            if (transaction != null) {
                transaction.rollback();
            }
            utils.Log.logError("Error deleting OfflineMessage entities by IDs", e);
            throw e; // Re-throw so caller knows deletion failed
        }
    }
}
