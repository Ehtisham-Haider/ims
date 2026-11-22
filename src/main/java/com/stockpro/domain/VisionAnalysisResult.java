package com.stockpro.domain;

import java.util.List;

/**
 * Result produced by the Vision Studio across ZXing barcode decoding, perceptual duplicate matching,
 * and 6 computer-vision inspection modes with automatic cloud/offline fallback.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 * @param mode vision analysis mode executed
 * @param headline concise primary finding title
 * @param estimatedItemCount detected object or SKU count inside the frame
 * @param confidencePercent estimated confidence score ({@code 0..100})
 * @param detectedBarcode decoded ZXing 1D/2D barcode string, if present
 * @param detectedText extracted OCR / label / note text snippet
 * @param extractedLines structured line items parsed from receipts or notes
 * @param anomalyFlagged {@code true} when shelf gap, box damage, or duplicate photo risk is detected
 * @param usedCloudVision {@code true} when Groq Vision API enriched the result
 * @param summary detailed action guidance for inventory staff
 */
public record VisionAnalysisResult(
        VisionMode mode,
        String headline,
        int estimatedItemCount,
        double confidencePercent,
        String detectedBarcode,
        String detectedText,
        List<String> extractedLines,
        boolean anomalyFlagged,
        boolean usedCloudVision,
        String summary
) {
    /**
     * Supported multi-mode workflows in Camera Vision Studio.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     */
    public enum VisionMode {
        /** Identifies packaged retail items and decodes EAN/UPC/QR labels. */
        PRODUCT_RECOGNIZER("Product Recognizer"),
        /** Counts visible shelf units using vertical contrast segmentation. */
        SHELF_COUNTER("Shelf Counter"),
        /** Detects empty shelf voids exceeding continuity thresholds. */
        EMPTY_SHELF_GAP_DETECTOR("Empty Shelf Gap Detector"),
        /** Flags crushed corners, dark moisture staining, and box deformation. */
        DAMAGED_BOX_DETECTOR("Damaged Box Detector"),
        /** Extracts itemized invoice/bill quantities and monetary totals. */
        RECEIPT_BILL_SCANNER("Receipt / Bill Scanner"),
        /** Parses handwritten storeroom count notes into SKU adjustments. */
        HANDWRITTEN_NOTE_READER("Handwritten Note Reader");

        private final String displayName;

        /**
         * Creates a Vision Studio mode enum option.
         *
         * @param displayName UI tab title
         */
        VisionMode(String displayName) {
            this.displayName = displayName;
        }

        /**
         * Returns the human-readable UI tab title.
         *
         * @return UI display name
         */
        public String getDisplayName() {
            return displayName;
        }
    }
}
