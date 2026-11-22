package com.stockpro.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Defines a unit-of-measure (UoM) conversion ratio mapping purchase, storage, or selling units
 * into base sellable pieces using exact {@link BigDecimal} arithmetic.
 *
 * @param id database row identifier
 * @param productId target product ID
 * @param unitName unit label (e.g., {@code Box}, {@code Pack}, {@code Piece})
 * @param factorToBase exact multiplier converting one unit into base pieces
 * @param unitRole operational role ({@code PURCHASE}, {@code STORAGE}, or {@code SELLING})
 * @param updatedAt last modification timestamp
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public record UomConversion(
        long id,
        long productId,
        String unitName,
        BigDecimal factorToBase,
        String unitRole,
        LocalDateTime updatedAt) {
}
