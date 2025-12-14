package infras.repository;

import domain.entity.Peer;
import domain.repository.IPeerJpaRepository;
import org.hibernate.Session;
import org.hibernate.Transaction;
import utils.HibernateUtil;
import jakarta.persistence.criteria.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Hibernate implementation of IPeerJpaRepository.
 */
public class PeerJpaRepository implements IPeerJpaRepository {

    @Override
    public Peer savePeer(Peer peer) {
        Transaction transaction = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            transaction = session.beginTransaction();
            if (peer.getId() == null) {
                session.persist(peer);
            } else {
                session.merge(peer);
            }
            transaction.commit();
            return peer;
        } catch (Exception e) {
            if (transaction != null) transaction.rollback();
            throw new RuntimeException("Error saving peer", e);
        }
    }

    @Override
    public Peer findPeerById(UUID id) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            return session.get(Peer.class, id);
        } catch (Exception e) {
            throw new RuntimeException("Error finding peer by ID", e);
        }
    }

    @Override
    public Peer findPeerByIpAndPort(String ip, int port) {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            CriteriaBuilder cb = session.getCriteriaBuilder();
            CriteriaQuery<Peer> cq = cb.createQuery(Peer.class);
            Root<Peer> root = cq.from(Peer.class);
            Predicate ipCondition = cb.equal(root.get("ip"), ip);
            Predicate portCondition = cb.equal(root.get("port"), port);
            cq.where(cb.and(ipCondition, portCondition));
            return session.createQuery(cq).uniqueResult();
        } catch (Exception e) {
            throw new RuntimeException("Error finding peer by IP and port", e);
        }
    }

    @Override
    public List<Peer> findAllPeers() {
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            CriteriaBuilder cb = session.getCriteriaBuilder();
            CriteriaQuery<Peer> cq = cb.createQuery(Peer.class);
            Root<Peer> root = cq.from(Peer.class);
            cq.select(root);
            return session.createQuery(cq).getResultList();
        } catch (Exception e) {
            throw new RuntimeException("Error finding all peers", e);
        }
    }

    @Override
    public void updatePeer(Peer peer) {
        Transaction transaction = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            transaction = session.beginTransaction();
            session.merge(peer);
            transaction.commit();
        } catch (Exception e) {
            if (transaction != null) transaction.rollback();
            throw new RuntimeException("Error updating peer", e);
        }
    }

    @Override
    public void deletePeer(UUID id) {
        Transaction transaction = null;
        try (Session session = HibernateUtil.getSessionFactory().openSession()) {
            transaction = session.beginTransaction();
            Peer peer = session.get(Peer.class, id);
            if (peer != null) {
                session.remove(peer);
            }
            transaction.commit();
        } catch (Exception e) {
            if (transaction != null) transaction.rollback();
            throw new RuntimeException("Error deleting peer", e);
        }
    }
}
