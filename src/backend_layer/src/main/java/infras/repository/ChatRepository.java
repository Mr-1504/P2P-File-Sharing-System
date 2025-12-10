package infras.repository;

import domain.entity.Conversation;
import domain.entity.GroupMember;
import domain.entity.Message;
import domain.repository.IChatRepository;
import org.hibernate.Session;
import org.hibernate.Transaction;
import utils.HibernateUtil;
import jakarta.persistence.criteria.*;

import java.util.List;

/**
 * Hibernate implementation of IChatRepository.
 */
public class ChatRepository implements IChatRepository {

    // Conversation operations
    @Override
    public Conversation saveConversation(Conversation conversation) {
        Transaction transaction = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            transaction = session.beginTransaction();
            session.persist(conversation);
            transaction.commit();
            return conversation;
        } catch (Exception e) {
            if (transaction != null) transaction.rollback();
            throw new RuntimeException("Error saving conversation", e);
        }
    }

    @Override
    public Conversation findConversationById(String conversationId) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            return session.get(Conversation.class, conversationId);
        } catch (Exception e) {
            throw new RuntimeException("Error finding conversation by ID", e);
        }
    }

    @Override
    public List<Conversation> findAllConversations() {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            CriteriaBuilder cb = session.getCriteriaBuilder();
            CriteriaQuery<Conversation> cq = cb.createQuery(Conversation.class);
            Root<Conversation> root = cq.from(Conversation.class);
            cq.select(root);
            return session.createQuery(cq).getResultList();
        } catch (Exception e) {
            throw new RuntimeException("Error finding all conversations", e);
        }
    }

    @Override
    public void updateConversation(Conversation conversation) {
        Transaction transaction = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            transaction = session.beginTransaction();
            session.merge(conversation);
            transaction.commit();
        } catch (Exception e) {
            if (transaction != null) transaction.rollback();
            throw new RuntimeException("Error updating conversation", e);
        }
    }

    // Message operations
    @Override
    public Message saveMessage(Message message) {
        Transaction transaction = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            transaction = session.beginTransaction();
            session.persist(message);
            transaction.commit();
            return message;
        } catch (Exception e) {
            if (transaction != null) transaction.rollback();
            throw new RuntimeException("Error saving message", e);
        }
    }

    @Override
    public List<Message> findMessagesByConversationId(String conversationId, int limit, int offset) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            CriteriaBuilder cb = session.getCriteriaBuilder();
            CriteriaQuery<Message> cq = cb.createQuery(Message.class);
            Root<Message> root = cq.from(Message.class);
            Predicate condition = cb.equal(root.get("conversationId"), conversationId);
            cq.where(condition);
            cq.orderBy(cb.desc(root.get("createdAt")));
            return session.createQuery(cq)
                    .setFirstResult(offset)
                    .setMaxResults(limit)
                    .getResultList();
        } catch (Exception e) {
            throw new RuntimeException("Error finding messages by conversation ID", e);
        }
    }

    @Override
    public List<Message> findOfflineMessages(String receiverId) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            CriteriaBuilder cb = session.getCriteriaBuilder();
            CriteriaQuery<Message> cq = cb.createQuery(Message.class);
            Root<Message> root = cq.from(Message.class);
            Predicate condition = cb.equal(root.get("senderId"), receiverId);
            cq.where(condition);
            cq.orderBy(cb.asc(root.get("createdAt")));
            return session.createQuery(cq).getResultList();
        } catch (Exception e) {
            throw new RuntimeException("Error finding offline messages", e);
        }
    }

    @Override
    public void deleteMessages(List<String> messageIds) {
        Transaction transaction = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            transaction = session.beginTransaction();
            CriteriaBuilder cb = session.getCriteriaBuilder();
            CriteriaDelete<Message> cd = cb.createCriteriaDelete(Message.class);
            Root<Message> root = cd.from(Message.class);
            Predicate condition = root.get("id").in(messageIds);
            cd.where(condition);
            session.createQuery(cd).executeUpdate();
            transaction.commit();
        } catch (Exception e) {
            if (transaction != null) transaction.rollback();
            throw new RuntimeException("Error deleting messages", e);
        }
    }

    // Group operations
    @Override
    public GroupMember saveGroupMember(GroupMember member) {
        Transaction transaction = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            transaction = session.beginTransaction();
            session.persist(member);
            transaction.commit();
            return member;
        } catch (Exception e) {
            if (transaction != null) transaction.rollback();
            throw new RuntimeException("Error saving group member", e);
        }
    }

    @Override
    public List<GroupMember> findGroupMembersByGroupId(String groupId) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            CriteriaBuilder cb = session.getCriteriaBuilder();
            CriteriaQuery<GroupMember> cq = cb.createQuery(GroupMember.class);
            Root<GroupMember> root = cq.from(GroupMember.class);
            Predicate condition = cb.equal(root.get("groupId"), groupId);
            cq.where(condition);
            return session.createQuery(cq).getResultList();
        } catch (Exception e) {
            throw new RuntimeException("Error finding group members by group ID", e);
        }
    }

    @Override
    public void updateGroupMember(GroupMember member) {
        Transaction transaction = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            transaction = session.beginTransaction();
            session.merge(member);
            transaction.commit();
        } catch (Exception e) {
            if (transaction != null) transaction.rollback();
            throw new RuntimeException("Error updating group member", e);
        }
    }
}
