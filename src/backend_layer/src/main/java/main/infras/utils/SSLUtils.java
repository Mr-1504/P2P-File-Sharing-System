package main.infras.utils;

import main.domain.entity.PeerInfo;
import main.utils.AppPaths;
import main.utils.Config;
import main.utils.EnvUtils;
import main.utils.Log;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import javax.net.ssl.*;
import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.util.UUID;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;

public class SSLUtils {
    public static final String TRUSTSTORE_HASH = "ffe24be633fdbddae20ae7ec5effdfa422a0a15bc4d37f5284b5860da0364171";
    public static final int SSL_TRACKER_ENROLL_PORT = Config.TRACKER_ENROLL_PORT;
    public static final int SSL_TRACKER_PORT = Config.TRACKER_PORT;
    public static final String KEYSTORE_PASSWORD = requireEnv("KEYSTORE_PASSWORD");
    public static final String TRUSTSTORE_PASSWORD = requireEnv("TRUSTSTORE_PASSWORD");
    public static final Path CERT_DIRECTORY = AppPaths.getCertificatePath();
    public static final String KEYSTORE_NAME = "peer-keystore.jks";
    public static final String TRUSTSTORE_NAME = "peer-truststore.jks";
    public static final String KEY_ALIAS = getKeyAlias();

    private static final String SERVER_IP = Config.TRACKER_IP; // Should be the Tracker's IP
    private static final int TRACKER_PORT_FOR_CSR = SSL_TRACKER_PORT;

    static {
        // Register Bouncy Castle provider once
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    }

    private static String requireEnv(String key) {
        String value = EnvUtils.getEnvString(key);
        if (value == null) {
            throw new RuntimeException("Environment variable " + key + " is not set");
        }
        return value;
    }

    private static String getKeyAlias() {
        String alias = EnvUtils.getEnvString("KEY_ALIAS");
        return alias != null ? alias : "peer-key";
    }

    public static boolean initializeSSLCertificates() {
        try {
            if (CERT_DIRECTORY == null) {
                throw new RuntimeException("Cannot get certificates dir");
            }
            File certDir = CERT_DIRECTORY.toFile();
            if (!certDir.exists()) {
                certDir.mkdirs();
            }

            File truststoreFile = Paths.get(certDir.getAbsolutePath(), TRUSTSTORE_NAME).toFile();
            if (!truststoreFile.exists()) {
                Log.logError("Truststore not found at: " + truststoreFile.getAbsolutePath(), null);
                throw new RuntimeException("Truststore not found. Please ensure the truststore is present at: " + truststoreFile.getAbsolutePath());
            } else {
                String currentHash = computeFileHash(truststoreFile);
                if (currentHash == null || !currentHash.equalsIgnoreCase(TRUSTSTORE_HASH)) {
                    Log.logError("Truststore integrity check failed. Expected hash: " + TRUSTSTORE_HASH + ", but got: " + currentHash, null);
                    throw new RuntimeException("Truststore integrity check failed. Expected hash: " + TRUSTSTORE_HASH + ", but got: " + currentHash);
                }
            }

            File keystoreFile = Paths.get(certDir.getAbsolutePath(), KEYSTORE_NAME).toFile();
            if (!keystoreFile.exists()) {
                Log.logInfo("Keystore not found. Generating new key pair and requesting certificate...");
                generateKeyPairAndRequestCertificate();
            }

            return isSSLSupported();
        } catch (Exception e) {
            Log.logError("Failed to initialize SSL certificates: " + e.getMessage(), e);
            return false;
        }
    }

    private static void generateKeyPairAndRequestCertificate() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        // Create CSR using Bouncy Castle
        String csrPem = generateCsrPem(keyPair);
        Log.logInfo("Generated CSR for peer certificate request.");

        // Send CSR to Tracker
        String signedCertPem = sendCsrToTracker(csrPem);
        if (signedCertPem == null || signedCertPem.isEmpty()) {
            throw new Exception("Failed to receive signed certificate from tracker");
        }
        Log.logInfo("Received signed certificate from tracker.");

        // Load the received certificate and CA certificate (if any)
        Certificate[] chain = loadCertificatesFromPem(signedCertPem);

        // Create and save the Keystore
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        keyStore.setKeyEntry(KEY_ALIAS, keyPair.getPrivate(), KEYSTORE_PASSWORD.toCharArray(), chain);

        File keystoreFile = Paths.get(CERT_DIRECTORY.toFile().getAbsolutePath(), KEYSTORE_NAME).toFile();
        try (FileOutputStream fos = new FileOutputStream(keystoreFile)) {
            keyStore.store(fos, KEYSTORE_PASSWORD.toCharArray());
            Log.logInfo("Keystore saved successfully to: " + keystoreFile.getAbsolutePath());
        }

        // Truststore is assumed to be pre-bundled with CA certificates
        Log.logInfo("Truststore should be pre-bundled with CA certificates.");

        Log.logInfo("SSL certificates initialized successfully.");
    }

    private static String generateCsrPem(KeyPair keyPair) throws Exception {
        String subjectName = "CN=peer-" + UUID.randomUUID().toString();
        X500Name subject = new X500Name(subjectName);

        JcaPKCS10CertificationRequestBuilder p10Builder = new JcaPKCS10CertificationRequestBuilder(subject, keyPair.getPublic());
        JcaContentSignerBuilder csBuilder = new JcaContentSignerBuilder("SHA256withRSA");
        ContentSigner signer = csBuilder.build(keyPair.getPrivate());
        PKCS10CertificationRequest csr = p10Builder.build(signer);

        // Convert CSR to PEM format
        StringWriter stringWriter = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(stringWriter)) {
            pemWriter.writeObject(csr);
        }
        return stringWriter.toString();
    }

    private static String sendCsrToTracker(String csrPem) throws Exception {
        // Use an SSL context with our certificates to connect to the Tracker
        // The pre-bundled truststore should contain the CA certificates
        SSLSocketFactory factory = createSSLSocketFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket(SERVER_IP, SSL_TRACKER_ENROLL_PORT)) {
            socket.setSoTimeout(15000); // 15s timeout

            // Send certificate signing request to Tracker
            String request = "CERT_REQUEST|" + csrPem;

            try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                System.out.println("Sending CSR to tracker:\n" + csrPem);

                out.println(request);
                out.println("END_OF_REQUEST");

                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.equals("END_OF_RESPONSE")) break;
                    sb.append(line).append("\n");
                }

                String response = sb.toString().trim();
                Log.logInfo("Received response from tracker.");
                Log.logInfo("Response: " + response);
                if (response != null && response.startsWith("CERT_RESPONSE|")) {
                    return response.substring("CERT_RESPONSE|".length());
                }
            }
        }
        return null;
    }

    private static Certificate[] loadCertificatesFromPem(String pem) throws Exception {
        try (StringReader reader = new StringReader(pem);
             PEMParser pemParser = new PEMParser(reader)) {

            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");

            Object parsedObj;
            List<Certificate> certList = new ArrayList<>();

            while ((parsedObj = pemParser.readObject()) != null) {
                if (parsedObj instanceof org.bouncycastle.cert.X509CertificateHolder) {
                    X509CertificateHolder holder = (X509CertificateHolder) parsedObj;
                    ByteArrayInputStream bis = new ByteArrayInputStream(holder.getEncoded());
                    certList.add(certFactory.generateCertificate(bis));
                }
            }
            return certList.toArray(new Certificate[0]);
        }
    }

    public static SSLContext createSSLContext() throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        File keyStoreFile = Paths.get(CERT_DIRECTORY.toFile().getAbsolutePath(), KEYSTORE_NAME).toFile();

        if (keyStoreFile.exists()) {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(keyStoreFile)) {
                keyStore.load(fis, KEYSTORE_PASSWORD.toCharArray());
            }
            kmf.init(keyStore, KEYSTORE_PASSWORD.toCharArray());
        } else {
            // Keystore does not exist, initialize KeyManagerFactory with null.
            // This is acceptable if the client side does not need to present its own certificate,
            // for example, when first requesting a certificate from a CA.
            kmf.init(null, null);
            Log.logInfo("Keystore not found. Initializing client with truststore only.");
        }

        KeyStore trustStore = KeyStore.getInstance("JKS");
        File trustStoreFile = Paths.get(CERT_DIRECTORY.toFile().getAbsolutePath(), TRUSTSTORE_NAME).toFile();
        try (FileInputStream fis = new FileInputStream(trustStoreFile)) {
            trustStore.load(fis, TRUSTSTORE_PASSWORD.toCharArray());
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());

        return sslContext;
    }

    public static SSLServerSocketFactory createSSLServerSocketFactory() throws Exception {
        return createSSLContext().getServerSocketFactory();
    }

    public static SSLSocketFactory createSSLSocketFactory() throws Exception {
        return createSSLContext().getSocketFactory();
    }

    public static boolean isSSLSupported() {
        if (CERT_DIRECTORY == null) {
            return false;
        }
        File key = CERT_DIRECTORY.resolve(KEYSTORE_NAME).toFile();
        File trust = CERT_DIRECTORY.resolve(TRUSTSTORE_NAME).toFile();
        return key.exists() && trust.exists();
    }

    public static SSLSocket createSecureSocket(PeerInfo peerInfo) throws Exception {
        SSLSocketFactory sslSocketFactory = SSLUtils.createSSLSocketFactory();
        SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(peerInfo.getIp(), peerInfo.getPort());
        sslSocket.setUseClientMode(true);
        sslSocket.setNeedClientAuth(true);
        sslSocket.setSoTimeout(Config.SOCKET_TIMEOUT_MS);
        sslSocket.startHandshake();
        return sslSocket;
    }

    private static String computeFileHash(File file) {
        try (InputStream fis = new FileInputStream(file)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] byteArray = new byte[1024];
            int bytesCount;
            while ((bytesCount = fis.read(byteArray)) != -1) {
                digest.update(byteArray, 0, bytesCount);
            }
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.logError("Failed to compute file hash: " + e.getMessage(), e);
            return null;
        }
    }
}
