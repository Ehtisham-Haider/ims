package com.stockpro.domain;

import java.time.LocalDateTime;

/**
 * Metadata record linking an attached PDF manual, warranty card, or safety specification sheet
 * to a catalog product.
 *
 * @param id database row identifier
 * @param productId target product ID
 * @param fileName original document display name
 * @param filePath local stored path under the spec-sheet vault
 * @param docType document category ({@code SPEC_SHEET}, {@code WARRANTY}, {@code SAFETY_GUIDE})
 * @param fileSizeBytes file size in bytes
 * @param addedAt attachment timestamp
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public record ProductAttachment(
        long id,
        long productId,
        String fileName,
        String filePath,
        String docType,
        long fileSizeBytes,
        LocalDateTime addedAt) {
}
