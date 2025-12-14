package utils;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Base64;
import com.google.gson.Gson;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for hybrid encryption: AES-GCM + RSA.
 */
public class CryptoUtils {

    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final int AES_KEY_SIZE = 256;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String RSA_ALGORITHM = "RSA/ECB/PKCS1Padding";

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * Generate AES key and IV for symmetric encryption.
     */
    public static Map<String, byte[]> generateAESKeyAndIV() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(AES_KEY_SIZE);
            SecretKey secretKey = keyGen.generateKey();

            SecureRandom random = new SecureRandom();
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);

            Map<String, byte[]> result = new HashMap<>();
            result.put("key", secretKey.getEncoded());
            result.put("iv", iv);
            return result;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("AES key generation failed", e);
        }
    }

    /**
     * Encrypt content using AES-GCM.
     */
    public static byte[] encryptAES(byte[] content, byte[] keyBytes, byte[] iv) {
        try {
            SecretKey key = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);
            return cipher.doFinal(content);
        } catch (Exception e) {
            throw new RuntimeException("AES encryption failed", e);
        }
    }

    /**
     * Decrypt content using AES-GCM.
     */
    public static byte[] decryptAES(byte[] encryptedContent, byte[] keyBytes, byte[] iv) {
        try {
            SecretKey key = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);
            return cipher.doFinal(encryptedContent);
        } catch (Exception e) {
            throw new RuntimeException("AES decryption failed", e);
        }
    }

    /**
     * Encrypt data using RSA public key.
     */
    public static byte[] encryptRSA(byte[] data, PublicKey publicKey) {
        try {
            Cipher cipher = Cipher.getInstance(RSA_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("RSA encryption failed", e);
        }
    }

    /**
     * Decrypt data using RSA private key.
     */
    public static byte[] decryptRSA(byte[] encryptedData, PrivateKey privateKey) {
        try {
            Cipher cipher = Cipher.getInstance(RSA_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            return cipher.doFinal(encryptedData);
        } catch (Exception e) {
            throw new RuntimeException("RSA decryption failed", e);
        }
    }

    /**
     * Build encrypted payload: {k, iv, d} as JSON, then base64 encode.
     */
    public static String buildEncryptedPayload(String content, PublicKey recipientPublicKey) {
        try {
            // Generate AES key and IV
            Map<String, byte[]> aesData = generateAESKeyAndIV();
            byte[] aesKey = aesData.get("key");
            byte[] iv = aesData.get("iv");

            // Encrypt content with AES
            byte[] contentBytes = content.getBytes("UTF-8");
            byte[] encryptedContent = encryptAES(contentBytes, aesKey, iv);

            // Encrypt AES key with RSA
            byte[] encryptedKey = encryptRSA(aesKey, recipientPublicKey);

            // Build payload
            Map<String, String> payload = new HashMap<>();
            payload.put("k", Base64.toBase64String(encryptedKey));
            payload.put("iv", Base64.toBase64String(iv));
            payload.put("d", Base64.toBase64String(encryptedContent));

            Gson gson = new Gson();
            String jsonPayload = gson.toJson(payload);

            return Base64.toBase64String(jsonPayload.getBytes("UTF-8"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to build encrypted payload", e);
        }
    }

    /**
     * Decrypt payload: decode base64 JSON {k,iv,d}, decrypt AES key with RSA, decrypt content with AES.
     */
    public static String decryptPayload(String base64Payload, PrivateKey privateKey) {
        try {
            byte[] jsonBytes = Base64.decode(base64Payload);
            String jsonPayload = new String(jsonBytes, "UTF-8");

            Gson gson = new Gson();
            Map<String, String> payload = gson.fromJson(jsonPayload, Map.class);

            byte[] encryptedKey = Base64.decode(payload.get("k"));
            byte[] iv = Base64.decode(payload.get("iv"));
            byte[] encryptedContent = Base64.decode(payload.get("d"));

            // Decrypt AES key with RSA
            byte[] aesKey = decryptRSA(encryptedKey, privateKey);

            // Decrypt content with AES
            byte[] contentBytes = decryptAES(encryptedContent, aesKey, iv);

            return new String(contentBytes, "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt payload", e);
        }
    }

    /**
     * For groups: build payload per member, return map peerId -> encryptedPayload
     */
    public static Map<String, String> buildGroupEncryptedPayloads(String content, Map<String, PublicKey> memberPublicKeys) {
        Map<String, String> payloads = new HashMap<>();
        for (Map.Entry<String, PublicKey> entry : memberPublicKeys.entrySet()) {
            String peerId = entry.getKey();
            PublicKey publicKey = entry.getValue();
            String payload = buildEncryptedPayload(content, publicKey);
            payloads.put(peerId, payload);
        }
        return payloads;
    }

    /**
     * Load public key from PEM string or DER hex string.
     * If starts with "-----BEGIN", treat as PEM (Base64).
     * Otherwise, treat as hex string representing DER bytes.
     */
    public static PublicKey loadPublicKey(String keyString) {
        try {
            byte[] publicKeyBytes;
            if (keyString.startsWith("-----BEGIN")) {
                // PEM format: remove header/footer, decode Base64
                String base64Data = keyString
                    .replaceAll("-----BEGIN PUBLIC KEY-----", "")
                    .replaceAll("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
                publicKeyBytes = Base64.decode(base64Data);
            } else {
                // Assume hex string: convert to bytes
                publicKeyBytes = hexStringToByteArray(keyString);
            }

            X509EncodedKeySpec spec = new X509EncodedKeySpec(publicKeyBytes);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return factory.generatePublic(spec);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load public key from: " + keyString.substring(0, Math.min(50, keyString.length())), e);
        }
    }

    /**
     * Convert hex string to byte array.
     */
    private static byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Load private key from PEM string (for own key, if needed for testing).
     */
    public static PrivateKey loadPrivateKey(String pemPrivateKey) {
        try {
            byte[] privateKeyBytes = Base64.decode(pemPrivateKey);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(privateKeyBytes);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return factory.generatePrivate(spec);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load private key", e);
        }
    }
}
