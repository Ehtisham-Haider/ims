package com.stockpro.service;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

/**
 * Authenticated AES-256-GCM encrypted SQLite backup and verified restore service
 * using PBKDF2-HMAC-SHA256 (65,536 iterations) key derivation and zero external dependencies.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class EncryptedBackupService {

    private static final byte[] MAGIC_HEADER = "STKPRO2E".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int PBKDF2_ITERATIONS = 65_536;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int GCM_TAG_BITS = 128;

    private final BackupService backupService;
    private final AuditService auditService;

    /**
     * Constructs an encrypted backup coordinator over an existing {@link BackupService}.
     *
     * @param backupService baseline SQLite snapshot/restore engine
     * @param auditService tamper-evident audit trail logger
     */
    public EncryptedBackupService(BackupService backupService, AuditService auditService) {
        this.backupService = backupService;
        this.auditService = auditService;
    }

    /**
     * Exports a consistent SQLite snapshot and encrypts it into an authenticated {@code .enc} file
     * laid out as {@code [MAGIC(8)][SALT(16)][IV(12)][AES-GCM CIPHERTEXT + 128-BIT TAG]}.
     *
     * @param outputEncPath destination {@code .enc} file path
     * @param passphrase user encryption passphrase (minimum 6 characters)
     * @param actor authenticated username performing the export
     * @return operation result containing written {@link Path}
     */
    public InventoryOperationResult<Path> createEncryptedBackup(Path outputEncPath, char[] passphrase, String actor) {
        if (outputEncPath == null) {
            return InventoryOperationResult.fail("Destination encrypted backup path is required.");
        }
        if (passphrase == null || passphrase.length < 6) {
            return InventoryOperationResult.fail("Encryption passphrase must be at least 6 characters.");
        }

        Path tempPlain = null;
        try {
            tempPlain = Files.createTempFile("stockpro-plain-bak-", ".db");
            Files.deleteIfExists(tempPlain);
            backupService.createBackup(tempPlain);
            if (!Files.exists(tempPlain) || !backupService.validateBackupFile(tempPlain)) {
                return InventoryOperationResult.fail("Failed to produce valid intermediate SQLite snapshot.");
            }
            byte[] plaintext = Files.readAllBytes(tempPlain);

            SecureRandom rng = new SecureRandom();
            byte[] salt = new byte[SALT_BYTES];
            byte[] iv = new byte[IV_BYTES];
            rng.nextBytes(salt);
            rng.nextBytes(iv);

            SecretKey key = deriveAesKey(passphrase, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(MAGIC_HEADER);
            byte[] ciphertext = cipher.doFinal(plaintext);

            byte[] envelope = new byte[MAGIC_HEADER.length + SALT_BYTES + IV_BYTES + ciphertext.length];
            int pos = 0;
            System.arraycopy(MAGIC_HEADER, 0, envelope, pos, MAGIC_HEADER.length);
            pos += MAGIC_HEADER.length;
            System.arraycopy(salt, 0, envelope, pos, SALT_BYTES);
            pos += SALT_BYTES;
            System.arraycopy(iv, 0, envelope, pos, IV_BYTES);
            pos += IV_BYTES;
            System.arraycopy(ciphertext, 0, envelope, pos, ciphertext.length);

            if (outputEncPath.getParent() != null) {
                Files.createDirectories(outputEncPath.getParent());
            }
            Files.write(outputEncPath, envelope);

            auditService.record(
                    actor == null || actor.isBlank() ? "System" : actor,
                    "ENCRYPTED_BACKUP_CREATED",
                    outputEncPath.getFileName().toString(),
                    "AES-256-GCM backup created (" + envelope.length + " bytes)"
            );
            return InventoryOperationResult.ok(outputEncPath,
                    "Encrypted AES-256-GCM backup saved to " + outputEncPath.getFileName());
        } catch (Exception ex) {
            return InventoryOperationResult.fail("Encrypted backup failed: " + ex.getMessage());
        } finally {
            if (tempPlain != null) {
                try {
                    Files.deleteIfExists(tempPlain);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Authenticates, decrypts, validates schema integrity, and restores an {@code .enc} SQLite backup.
     * Rejects incorrect passphrases or tampered ciphertext via GCM tag verification before touching the database.
     *
     * @param inputEncPath source {@code .enc} archive path
     * @param passphrase decryption passphrase
     * @param actor authenticated username performing the restore
     * @return operation result holding count of restored active products
     */
    public InventoryOperationResult<Integer> restoreEncryptedBackup(Path inputEncPath, char[] passphrase, String actor) {
        if (inputEncPath == null || !Files.exists(inputEncPath)) {
            return InventoryOperationResult.fail("Encrypted backup file does not exist.");
        }
        if (passphrase == null || passphrase.length < 6) {
            return InventoryOperationResult.fail("Valid decryption passphrase (minimum 6 chars) is required.");
        }

        Path tempPlain = null;
        try {
            byte[] envelope = Files.readAllBytes(inputEncPath);
            int headerLen = MAGIC_HEADER.length + SALT_BYTES + IV_BYTES;
            if (envelope.length <= headerLen + 16) {
                return InventoryOperationResult.fail("Encrypted backup file is truncated or corrupt.");
            }
            byte[] magic = Arrays.copyOfRange(envelope, 0, MAGIC_HEADER.length);
            if (!Arrays.equals(MAGIC_HEADER, magic)) {
                return InventoryOperationResult.fail("Invalid encrypted backup file signature.");
            }

            int pos = MAGIC_HEADER.length;
            byte[] salt = Arrays.copyOfRange(envelope, pos, pos + SALT_BYTES);
            pos += SALT_BYTES;
            byte[] iv = Arrays.copyOfRange(envelope, pos, pos + IV_BYTES);
            pos += IV_BYTES;
            byte[] ciphertext = Arrays.copyOfRange(envelope, pos, envelope.length);

            SecretKey key = deriveAesKey(passphrase, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(MAGIC_HEADER);
            byte[] plaintext = cipher.doFinal(ciphertext);

            tempPlain = Files.createTempFile("stockpro-dec-bak-", ".db");
            Files.write(tempPlain, plaintext);
            if (!backupService.validateBackupFile(tempPlain)) {
                return InventoryOperationResult.fail("Decrypted snapshot failed SQLite integrity check.");
            }

            InventoryOperationResult<Integer> restored = backupService.restoreFromBackup(tempPlain);
            if (restored.isSuccess()) {
                auditService.record(
                        actor == null || actor.isBlank() ? "System" : actor,
                        "ENCRYPTED_BACKUP_RESTORED",
                        inputEncPath.getFileName().toString(),
                        "AES-256-GCM backup restored cleanly (" + restored.getData() + " products)"
                );
            }
            return restored;
        } catch (AEADBadTagException ex) {
            return InventoryOperationResult.fail("Incorrect passphrase or tampered encrypted backup file.");
        } catch (Exception ex) {
            return InventoryOperationResult.fail("Encrypted restore failed: " + ex.getMessage());
        } finally {
            if (tempPlain != null) {
                try {
                    Files.deleteIfExists(tempPlain);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static SecretKey deriveAesKey(char[] passphrase, byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
        byte[] raw = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(raw, "AES");
    }
}
