package com.stockpro.service;

import com.stockpro.repository.DatabaseManager;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java 64-bit Perceptual Image Hashing ({@code dHash / pHash}) service detecting visually duplicate
 * or near-duplicate product photos persisted inside SQLite {@code image_phashes}.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class PerceptualImageHasher {

    /** Maximum Hamming bit difference considered a near-duplicate photo match. */
    public static final int DEFAULT_DUPLICATE_THRESHOLD = 8;

    private final DatabaseManager dbManager;

    /**
     * Candidate catalog duplicate match annotated with Hamming distance.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     * @param productId matching catalog product database id
     * @param phashHex stored 16-character hex fingerprint
     * @param imagePath stored photo file path
     * @param hammingDistance bit distance between candidate and catalog photo ({@code 0..64})
     */
    public record DuplicateImageMatch(
            long productId,
            String phashHex,
            String imagePath,
            int hammingDistance
    ) {
    }

    /**
     * Constructs a perceptual image hashing service backed by SQLite {@code image_phashes}.
     *
     * @param dbManager SQLite connection manager
     */
    public PerceptualImageHasher(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    /**
     * Computes a 64-bit difference hash ({@code 9x8} luminance gradient comparison)
     * formatted as a 16-character lowercase hexadecimal string.
     *
     * @param image input image
     * @return 16-character lowercase hex perceptual hash
     */
    public String computeHexHash(BufferedImage image) {
        if (image == null) {
            return "0000000000000000";
        }
        BufferedImage scaled = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, 9, 8, null);
        } finally {
            g.dispose();
        }

        long hash = 0L;
        int bit = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int left = scaled.getRaster().getSample(x, y, 0);
                int right = scaled.getRaster().getSample(x + 1, y, 0);
                if (left > right) {
                    hash |= (1L << bit);
                }
                bit++;
            }
        }
        return String.format("%016x", hash);
    }

    /**
     * Computes bitwise Hamming distance between two 64-bit hex hashes.
     *
     * @param hexA first 16-char hex hash
     * @param hexB second 16-char hex hash
     * @return number of differing bits ({@code 0..64})
     */
    public int hammingDistance(String hexA, String hexB) {
        if (hexA == null || hexB == null || hexA.length() < 16 || hexB.length() < 16) {
            return 64;
        }
        try {
            long a = Long.parseUnsignedLong(hexA.trim(), 16);
            long b = Long.parseUnsignedLong(hexB.trim(), 16);
            return Long.bitCount(a ^ b);
        } catch (NumberFormatException ex) {
            return 64;
        }
    }

    /**
     * Registers or updates the perceptual fingerprint for a product photo in SQLite.
     *
     * @param productId product primary key
     * @param image image bitmap to fingerprint
     * @param imagePath persisted file path
     * @return computed 16-character hex fingerprint
     */
    public String registerProductImage(long productId, BufferedImage image, String imagePath) {
        String hex = computeHexHash(image);
        String now = LocalDateTime.now().toString();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR REPLACE INTO image_phashes (product_id, phash_hex, image_path, updated_at) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, productId);
            ps.setString(2, hex);
            ps.setString(3, imagePath != null ? imagePath : "");
            ps.setString(4, now);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to persist perceptual image hash.", ex);
        }
        return hex;
    }

    /**
     * Searches catalog {@code image_phashes} for visually duplicate photos within {@code maxDistance} bits.
     *
     * @param candidate candidate image to check
     * @param maxDistance inclusive Hamming threshold (defaults to {@link #DEFAULT_DUPLICATE_THRESHOLD} if negative)
     * @return matching catalog entries sorted by closest distance first
     */
    public List<DuplicateImageMatch> findSimilarImages(BufferedImage candidate, int maxDistance) {
        int cutoff = maxDistance < 0 ? DEFAULT_DUPLICATE_THRESHOLD : maxDistance;
        String candidateHex = computeHexHash(candidate);
        List<DuplicateImageMatch> matches = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT product_id, phash_hex, image_path FROM image_phashes")) {
            while (rs.next()) {
                String storedHex = rs.getString("phash_hex");
                int dist = hammingDistance(candidateHex, storedHex);
                if (dist <= cutoff) {
                    matches.add(new DuplicateImageMatch(
                            rs.getLong("product_id"),
                            storedHex,
                            rs.getString("image_path"),
                            dist
                    ));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to query perceptual image hashes.", ex);
        }
        matches.sort((a, b) -> Integer.compare(a.hammingDistance(), b.hammingDistance()));
        return matches;
    }
}
