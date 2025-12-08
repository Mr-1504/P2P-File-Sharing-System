package utils;

import io.github.cdimascio.dotenv.Dotenv;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

public class HibernateUtils {
    private static final SessionFactory sessionFactory = build();
    private static SessionFactory build() {
        try {
            Dotenv dotenv = Dotenv.load();

            Configuration cfg = new Configuration();
            cfg.setProperty("hibernate.connection.driver_class", "org.postgresql.Driver");
            cfg.setProperty("hibernate.connection.url", dotenv.get("DB_URL"));
            cfg.setProperty("hibernate.connection.username", dotenv.get("DB_USER"));
            cfg.setProperty("hibernate.connection.password", dotenv.get("DB_PASS"));
            cfg.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
            cfg.setProperty("hibernate.hbm2ddl.auto", "update");
            cfg.setProperty("hibernate.show_sql", "true");
            cfg.setProperty("hibernate.format_sql", "true");

            cfg.addAnnotatedClass(dto.Peer.class);

            return cfg.buildSessionFactory();
        } catch (Exception e) {
            throw new RuntimeException("Lỗi tạo Hibernate SessionFactory", e);
        }
    }

    public static SessionFactory getSessionFactory() {
        return sessionFactory;
    }
}
