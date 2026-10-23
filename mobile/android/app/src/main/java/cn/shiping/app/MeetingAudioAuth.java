package cn.shiping.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Mutual authentication for the loopback-only audio channel; keys never cross the socket. */
final class MeetingAudioAuth {
    static final int PORT = 39827;
    static String nonce() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    static String proof(String key, String role, String nonce) throws Exception {
        if (!key.matches("[0-9a-f]{64}") || !nonce.matches("[0-9a-f]{128}")) throw new IllegalArgumentException("Invalid activation credential");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
        byte[] bytes = mac.doFinal((role + ":" + nonce).getBytes(StandardCharsets.US_ASCII));
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    static boolean matches(String expected, String received) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), received.getBytes(StandardCharsets.US_ASCII));
    }
}
