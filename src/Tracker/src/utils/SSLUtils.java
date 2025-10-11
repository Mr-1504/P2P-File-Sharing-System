package src.utils;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * Utility class for SSL/TLS configuration
 */
public class SSLUtils {

    public static final int SSL_TRACKER_PORT = Infor.TRACKER_PORT + RequestInfor.SSL_PORT_OFFSET; // 6001
    public static final String KEYSTORE_PASSWORD = "p2ppassword";
    public static final String KEYSTORE_PATH = "D:\\code\\P2P-File-Sharing-System\\certificates\\tracker-keystore.jks";
    public static final String TRUSTSTORE_PATH = "D:\\code\\P2P-File-Sharing-System\\certificates\\tracker-truststore.jks";

    static {
        // Thêm Bouncy Castle provider một lần duy nhất
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * Configure SSL context for the Tracker server
     */
    public static SSLContext createSSLContext() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("JKS");
        KeyStore trustStore = KeyStore.getInstance("JKS");

        // Load keystore and truststore
        try (FileInputStream keyStoreFile = new FileInputStream(KEYSTORE_PATH)) {
            keyStore.load(keyStoreFile, KEYSTORE_PASSWORD.toCharArray());
        }

        try (FileInputStream trustStoreFile = new FileInputStream(TRUSTSTORE_PATH)) {
            trustStore.load(trustStoreFile, KEYSTORE_PASSWORD.toCharArray());
        }

        // Create key managers
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, KEYSTORE_PASSWORD.toCharArray());

        // Create trust managers
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        // Create SSL context
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), new SecureRandom());

        return sslContext;
    }

    /**
     * Create SSL server socket factory
     */
    public static SSLServerSocketFactory createSSLServerSocketFactory() throws Exception {
        return createSSLContext().getServerSocketFactory();
    }

    /**
     * Create SSL socket factory for clients
     */
    public static SSLSocketFactory createSSLSocketFactory() throws Exception {
        return createSSLContext().getSocketFactory();
    }

    /**
     * Configure SSL parameters to require client authentication
     */
    public static SSLParameters createSSLParameters() {
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setNeedClientAuth(true);
        return sslParameters;
    }
}
