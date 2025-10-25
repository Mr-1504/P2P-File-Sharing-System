package utils;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;


import java.nio.file.Paths;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;

import javax.net.ssl.*;
import java.io.*;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static utils.Log.logError;

/**
 * Utility class for SSL/TLS configuration
 */
public class SSLUtils {
    private static final String CA_KEYSTORE = Config.CA_KEYSTORE;
    private static final String CA_ALIAS = Config.CA_ALIAS;
    private static final String ROOT_CA_ALIAS = Config.ROOT_CA_ALIAS;
    private static final String KEYSTORE_PASSWORD = Config.KEYSTORE_PASSWORD;
    private static final String KEYSTORE_NAME = Config.KEYSTORE;
    private static final String TRUSTSTORE_NAME = Config.TRUSTSTORE;
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");


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
        try (FileInputStream keyStoreFile = new FileInputStream(Paths.get(AppPaths.getCertificatePath().toString(), KEYSTORE_NAME).toString())) {
            keyStore.load(keyStoreFile, KEYSTORE_PASSWORD.toCharArray());
        }

        try (FileInputStream trustStoreFile = new FileInputStream(Paths.get(AppPaths.getCertificatePath().toString(), TRUSTSTORE_NAME).toString())) {
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

    public static String signCertificateForPeer(String csrPem) {
        try {
            // Load CSR from PEM string
            PKCS10CertificationRequest csr;
            StringReader reader = new StringReader(csrPem);
            PEMParser pemParser = new PEMParser(reader);
            Object parsedObj = pemParser.readObject();
            if (parsedObj instanceof PKCS10CertificationRequest) {
                csr = (PKCS10CertificationRequest) parsedObj;
            } else {
                throw new IllegalArgumentException("Provided string is not a valid PKCS#10 CSR");
            }
            // Load CA keystore
            File trackerKeystoreFile = new File(Paths.get(AppPaths.getCertificatePath().toString(), CA_KEYSTORE).toString());
            if (!trackerKeystoreFile.exists()) {
                logError("[TRACKER-ENROLL]: Intermediate CA keystore not found! Cannot sign certificates on " + getCurrentTime(), null);
                return "CERT_ERROR|Intermediate CA keystore not available";
            }

            KeyStore caKeyStore = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(trackerKeystoreFile)) {
                caKeyStore.load(fis, KEYSTORE_PASSWORD.toCharArray());
            }

            // Get CA private key and certificate
            PrivateKey caPrivateKey = (PrivateKey) caKeyStore.getKey(CA_ALIAS, KEYSTORE_PASSWORD.toCharArray());
            X509Certificate caCert = (X509Certificate) caKeyStore.getCertificate(CA_ALIAS);

            // Generate new certificate for Peer
            Instant now = Instant.now();
            Date validityBeginDate = Date.from(now);
            Date validityEndDate = Date.from(now.plus(365, ChronoUnit.DAYS)); // Expires in 1 year

            JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                    X500Name.getInstance(caCert.getSubjectX500Principal().getEncoded()), // Issuer is CA
                    new BigInteger(128, new SecureRandom()), // Generate random serial number
                    validityBeginDate,
                    validityEndDate,
                    csr.getSubject(), // Subject get from CSR
                    csr.getSubjectPublicKeyInfo() // Public key get from CSR
            );

            // 5. Sign the certificate with CA private key
            ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSA").build(caPrivateKey);
            X509Certificate peerCert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(certBuilder.build(contentSigner));

            // 6. Generate PEM string including Peer cert, Intermediate CA cert, and Root CA cert
            Certificate rootCaCert = caKeyStore.getCertificate(ROOT_CA_ALIAS); // Get Root CA certificate

            StringWriter stringWriter = new StringWriter();
            try (JcaPEMWriter pemWriter = new JcaPEMWriter(stringWriter)) {
                pemWriter.writeObject(peerCert);     // Peer's certificate
                pemWriter.writeObject(caCert);       // Intermediate CA certificate
                pemWriter.writeObject(rootCaCert);   // Root CA certificate
            }

            return stringWriter.toString();
        } catch (UnrecoverableKeyException e) {
            Log.logError("[TRACKER-ENROLL]: UnrecoverableKeyException - " + e.getMessage() + " on " + getCurrentTime(), e);
        } catch (FileNotFoundException e) {
            Log.logError("[TRACKER-ENROLL]: CA keystore file not found - " + e.getMessage() + " on " + getCurrentTime(), e);
        } catch (CertificateException e) {
            Log.logError("[TRACKER-ENROLL]: CertificateException - " + e.getMessage() + " on " + getCurrentTime(), e);
        } catch (IOException e) {
            Log.logError("[TRACKER-ENROLL]: IOException - " + e.getMessage() + " on " + getCurrentTime(), e);
        } catch (KeyStoreException e) {
            Log.logError("[TRACKER-ENROLL]: KeyStoreException - " + e.getMessage() + " on " + getCurrentTime(), e);
        } catch (NoSuchAlgorithmException e) {
            Log.logError("[TRACKER-ENROLL]: NoSuchAlgorithmException - " + e.getMessage() + " on " + getCurrentTime(), e);
        } catch (OperatorCreationException e) {
            Log.logError("[TRACKER-ENROLL]: OperatorCreationException - " + e.getMessage() + " on " + getCurrentTime(), e);
        } catch (IllegalArgumentException e) {
            Log.logError("[TRACKER-ENROLL]: IllegalArgumentException - " + e.getMessage() + " on " + getCurrentTime(), e);
        } catch (Exception e) {
            Log.logError("[TRACKER-ENROLL]: General Exception - " + e.getMessage() + " on " + getCurrentTime(), e);
        }
        return "CERT_ERROR|Internal error during certificate signing";
    }

    private static String getCurrentTime() {
        return LocalDateTime.now().format(formatter);
    }
}
