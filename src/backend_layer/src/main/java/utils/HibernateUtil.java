package utils;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.flywaydb.core.Flyway;

import java.io.InputStream;
import java.util.Properties;

/**
 * Hibernate utility class for session factory management and Flyway migrations.
 */
public class HibernateUtil {

    private static SessionFactory sessionFactory;

    private HibernateUtil() {}

    public static synchronized SessionFactory getSessionFactory() {
        if (sessionFactory == null) {
            try {
                // Load properties from resources
                Properties properties = new Properties();
                try (InputStream input = HibernateUtil.class.getClassLoader().getResourceAsStream("application.properties")) {
                    if (input != null) {
                        properties.load(input);
                    }
                }

                // Run Flyway migrations
                String dbUrl = properties.getProperty("db.url", "jdbc:sqlite:p2p_chat.db");
                Flyway flyway = Flyway.configure()
                        .dataSource(dbUrl, null, null)
                        .locations("db/migration")
                        .baselineOnMigrate(true)
                        .mixed(true)
                        .load();
                flyway.migrate();

                // Configure Hibernate
                Configuration configuration = new Configuration();
                configuration.setProperty("hibernate.connection.driver_class", "org.sqlite.JDBC");
                configuration.setProperty("hibernate.connection.url", dbUrl);
                configuration.setProperty("hibernate.connection.username", "");
                configuration.setProperty("hibernate.connection.password", "");
                configuration.setProperty("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect");
                configuration.setProperty("hibernate.hbm2ddl.auto", "none");
                configuration.setProperty("hibernate.show_sql", "false");
                configuration.setProperty("hibernate.format_sql", "true");
                configuration.setProperty("hibernate.connection.pool_size", "5");
                configuration.setProperty("hibernate.connection.autocommit", "true");

                // Add annotated classes
                configuration.addAnnotatedClass(domain.entity.Conversation.class);
                configuration.addAnnotatedClass(domain.entity.Message.class);
                configuration.addAnnotatedClass(domain.entity.GroupMember.class);
                configuration.addAnnotatedClass(domain.entity.Peer.class);

                // Build session factory
                sessionFactory = configuration.buildSessionFactory();
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException("Error building SessionFactory", e);
            }
        }
        return sessionFactory;
    }

    public static void shutdown() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
    }
}
