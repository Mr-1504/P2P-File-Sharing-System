package repository;

import dto.Peer;
import org.hibernate.Session;
import org.hibernate.Transaction;
import utils.HibernateUtils;
import utils.Log;

import java.util.UUID;

public class PeerRepositoryImpl implements PeerRepository{
    @Override
    public Peer save(Peer peer) {
        Transaction transaction = null;
        try (Session session = HibernateUtils.getSessionFactory().openSession()) {
            transaction = session.beginTransaction();
            session.saveOrUpdate(peer);
            transaction.commit();
            return peer;
        } catch (Exception e) {
            if (transaction != null) {
                transaction.rollback();
            }
            Log.logError("Error saving Peer entity", e);
            return null;
        }
    }

    @Override
    public Peer findById(UUID id) {
        try (Session session = HibernateUtils.getSessionFactory().openSession()) {
            return session.get(Peer.class, id);
        } catch (Exception e) {
            Log.logError("Error finding Peer entity by ID", e);
            return null;
        }
    }

    @Override
    public Peer findByIpAndPort(String ip, int port) {
        try (Session session = HibernateUtils.getSessionFactory().openSession()) {
            return session.createQuery("from Peer where ip = :ip and port = :port", Peer.class)
                    .setParameter("ip", ip)
                    .setParameter("port", port)
                    .uniqueResult();
        } catch (Exception e) {
            Log.logError("Error finding Peer entity by IP and port", e);
            return null;
        }
    }

    @Override
    public Peer findByPublicKey(String publicKey) {
        try (Session session = HibernateUtils.getSessionFactory().openSession()) {
            return session.createQuery("from Peer where publicKey = :publicKey", Peer.class)
                    .setParameter("publicKey", publicKey)
                    .uniqueResult();
        } catch (Exception e) {
            Log.logError("Error finding Peer entity by public key", e);
            return null;
        }
    }
}
