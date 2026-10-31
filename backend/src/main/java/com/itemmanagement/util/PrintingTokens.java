package com.itemmanagement.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

public final class PrintingTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] PAIRING_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private PrintingTokens() {
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static String newDeviceToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String newPairingCode() {
        char[] code = new char[8];
        for (int i = 0; i < code.length; i++) {
            code[i] = PAIRING_ALPHABET[RANDOM.nextInt(PAIRING_ALPHABET.length)];
        }
        return new String(code);
    }

    public static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    public static String normalizeDeviceToken(String token) {
        return token == null ? "" : token.trim();
    }
}
