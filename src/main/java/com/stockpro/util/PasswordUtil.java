package com.stockpro.util;

import org.mindrot.jbcrypt.BCrypt;

/**
 * Password hashing and verification helper powered by jBCrypt.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public final class PasswordUtil {

    /**
     * Prevents instantiation of utility class.
     */
    private PasswordUtil() {
    }

    /**
     * Generates a salted BCrypt hash for a plaintext password.
     *
     * @param rawPassword plaintext password to hash
     * @return salted BCrypt hash string
     */
    public static String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("Password cannot be empty.");
        }
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt(10));
    }

    /**
     * Verifies a candidate plaintext password against a stored BCrypt hash.
     *
     * @param rawPassword candidate plaintext password
     * @param storedHash stored BCrypt hash
     * @return {@code true} if the password matches the hash
     */
    public static boolean verify(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        try {
            return BCrypt.checkpw(rawPassword, storedHash);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
