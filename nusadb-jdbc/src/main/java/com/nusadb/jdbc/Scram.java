package com.nusadb.jdbc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Client-side SCRAM-SHA-256 (RFC 5802 / RFC 7677), docs/wire-protocol.md §7.2. Uses the JDK's
 * built-in {@code javax.crypto} / {@code java.security}, with no dependencies.
 */
final class Scram {
    private static final String GS2_HEADER = "n,,";
    private static final String CHANNEL_BINDING =
            Base64.getEncoder().encodeToString(GS2_HEADER.getBytes(StandardCharsets.US_ASCII));
    private static final SecureRandom RANDOM = new SecureRandom();

    final String clientFirstBare;
    final String fullClientFirst;
    private String expectedServerSignature;

    private Scram(String bare, String full) {
        this.clientFirstBare = bare;
        this.fullClientFirst = full;
    }

    static Scram start(String user) {
        byte[] raw = new byte[18];
        RANDOM.nextBytes(raw);
        String nonce = Base64.getEncoder().encodeToString(raw);
        String bare = "n=" + user + ",r=" + nonce;
        return new Scram(bare, GS2_HEADER + bare);
    }

    /** Build the client-final message from the server-first message. */
    byte[] clientFinal(String password, String serverFirst) throws Exception {
        String combinedNonce = null;
        byte[] salt = null;
        int iterations = 0;
        for (String field : serverFirst.split(",")) {
            int i = field.indexOf('=');
            if (i <= 0) {
                continue;
            }
            String key = field.substring(0, i);
            String value = field.substring(i + 1);
            switch (key) {
                case "r":
                    combinedNonce = value;
                    break;
                case "s":
                    salt = Base64.getDecoder().decode(value);
                    break;
                case "i":
                    iterations = Integer.parseInt(value);
                    break;
                default:
                    break;
            }
        }
        if (combinedNonce == null || salt == null || iterations <= 0) {
            throw new IllegalStateException("nusadb: malformed server-first message");
        }

        byte[] salted = pbkdf2(password, salt, iterations);
        byte[] clientKey = hmac(salted, "Client Key");
        byte[] storedKey = sha256(clientKey);

        String withoutProof = "c=" + CHANNEL_BINDING + ",r=" + combinedNonce;
        String authMessage = clientFirstBare + "," + serverFirst + "," + withoutProof;
        byte[] clientSig = hmac(storedKey, authMessage);

        byte[] proof = new byte[clientKey.length];
        for (int i = 0; i < proof.length; i++) {
            proof[i] = (byte) (clientKey[i] ^ clientSig[i]);
        }
        String finalStr = withoutProof + ",p=" + Base64.getEncoder().encodeToString(proof);

        byte[] serverKey = hmac(salted, "Server Key");
        byte[] serverSig = hmac(serverKey, authMessage);
        this.expectedServerSignature = Base64.getEncoder().encodeToString(serverSig);
        return finalStr.getBytes(StandardCharsets.UTF_8);
    }

    /** Verify the server-final {@code v=<signature>} in constant time. */
    boolean verifyServerFinal(String serverFinal) {
        String got = "";
        for (String field : serverFinal.split(",")) {
            if (field.startsWith("v=")) {
                got = field.substring(2);
            }
        }
        return MessageDigest.isEqual(
                got.getBytes(StandardCharsets.UTF_8),
                expectedServerSignature.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return factory.generateSecret(spec).getEncoded();
    }

    private static byte[] hmac(byte[] key, String msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }
}
