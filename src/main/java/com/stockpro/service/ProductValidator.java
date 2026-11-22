package com.stockpro.service;

import com.stockpro.domain.Product;
import com.stockpro.domain.ValidationResult;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validates product attributes and raw form input strings against business and lab rules.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public final class ProductValidator {
    private static final int MAX_NAME_LENGTH = 140;
    private static final int MAX_QUANTITY = 1_000_000;

    /**
     * Prevents instantiation of stateless validator utility.
     */
    private ProductValidator() {
    }

    /**
     * Validates a populated {@link Product} domain object before persistence.
     *
     * @param product product instance to inspect
     * @return {@link ValidationResult} containing any field-level violations
     */
    public static ValidationResult validateProduct(Product product) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (product == null) {
            errors.put("product", "Product data must not be null.");
            return new ValidationResult(errors);
        }

        String name = product.getName();
        if (name == null || name.trim().isEmpty()) {
            errors.put("name", "Product name cannot be empty or blank.");
        } else if (name.trim().length() > MAX_NAME_LENGTH) {
            errors.put("name", "Product name cannot exceed " + MAX_NAME_LENGTH + " characters.");
        }

        if (product.getQuantity() < 0) {
            errors.put("quantity", "Quantity must be zero or a positive whole number.");
        } else if (product.getQuantity() > MAX_QUANTITY) {
            errors.put("quantity", "Quantity cannot exceed " + MAX_QUANTITY + " units.");
        }

        if (product.getDamagedQty() < 0) {
            errors.put("damagedQty", "Damaged quantity cannot be negative.");
        }

        if (product.getLowStockThreshold() < 0) {
            errors.put("lowStockThreshold", "Low-stock threshold cannot be negative.");
        }

        if (product.getBuyPrice() < 0.0 || Double.isNaN(product.getBuyPrice()) || Double.isInfinite(product.getBuyPrice())) {
            errors.put("buyPrice", "Buy price must be zero or greater.");
        }

        if (product.getSellPrice() < 0.0 || Double.isNaN(product.getSellPrice()) || Double.isInfinite(product.getSellPrice())) {
            errors.put("sellPrice", "Sell price must be zero or greater.");
        }

        return new ValidationResult(errors);
    }

    /**
     * Validates raw form text fields (name, quantity, threshold, buy price, sell price) before parsing.
     *
     * @param rawName raw product name text
     * @param rawQuantity raw quantity text
     * @param rawThreshold raw low-stock threshold text
     * @param rawBuyPrice raw buy price text
     * @param rawSellPrice raw sell price text
     * @return {@link ValidationResult} highlighting invalid fields with diagnostic messages
     */
    public static ValidationResult validateRawInputs(
            String rawName,
            String rawQuantity,
            String rawThreshold,
            String rawBuyPrice,
            String rawSellPrice) {
        Map<String, String> errors = new LinkedHashMap<>();

        if (rawName == null || rawName.trim().isEmpty()) {
            errors.put("name", "Product name cannot be empty.");
        } else if (rawName.trim().length() > MAX_NAME_LENGTH) {
            errors.put("name", "Product name cannot exceed " + MAX_NAME_LENGTH + " characters.");
        }

        validateQuantityString(rawQuantity, "quantity", "Quantity", errors, false);
        validateQuantityString(rawThreshold, "lowStockThreshold", "Low-stock threshold", errors, true);
        validatePriceString(rawBuyPrice, "buyPrice", "Buy price", errors, true);
        validatePriceString(rawSellPrice, "sellPrice", "Sell price", errors, true);

        return new ValidationResult(errors);
    }

    /**
     * Parses a raw integer quantity string strictly, rejecting blanks, decimals, negatives, and overflow.
     *
     * @param rawQuantity raw user input string
     * @return parsed non-negative integer quantity
     * @throws IllegalArgumentException if the input is blank, negative, decimal, non-numeric, or overflows
     */
    public static int parseQuantity(String rawQuantity) {
        Map<String, String> errors = new LinkedHashMap<>();
        int parsed = validateQuantityString(rawQuantity, "quantity", "Quantity", errors, false);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(errors.get("quantity"));
        }
        return parsed;
    }

    /**
     * Parses a non-negative decimal price string.
     *
     * @param rawPrice raw price input
     * @param fieldLabel readable field name for error messages
     * @return parsed non-negative price value
     * @throws IllegalArgumentException if the input is invalid or negative
     */
    public static double parsePrice(String rawPrice, String fieldLabel) {
        Map<String, String> errors = new LinkedHashMap<>();
        double value = validatePriceString(rawPrice, "price", fieldLabel, errors, false);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(errors.get("price"));
        }
        return value;
    }

    /**
     * Validates and parses a whole-number string into a map of field errors.
     *
     * @param raw raw string value
     * @param key field error map key
     * @param label display name used in error message
     * @param errors mutable error map
     * @param optionalDefaultZero whether blank input defaults to 10 (for threshold) or fails
     * @return parsed integer or 0 when invalid
     */
    private static int validateQuantityString(
            String raw,
            String key,
            String label,
            Map<String, String> errors,
            boolean optionalDefaultZero) {
        if (raw == null || raw.trim().isEmpty()) {
            if (optionalDefaultZero) {
                return 10;
            }
            errors.put(key, label + " is required and must be a whole number.");
            return 0;
        }
        String trimmed = raw.trim();
        if (trimmed.contains(".") || trimmed.contains(",")) {
            errors.put(key, label + " must be a whole integer without decimals.");
            return 0;
        }
        try {
            long value = Long.parseLong(trimmed);
            if (value < 0) {
                errors.put(key, label + " cannot be negative.");
                return 0;
            }
            if (value > MAX_QUANTITY) {
                errors.put(key, label + " exceeds maximum allowed limit (" + MAX_QUANTITY + ").");
                return 0;
            }
            return (int) value;
        } catch (NumberFormatException ex) {
            errors.put(key, label + " must be a valid non-negative number.");
            return 0;
        }
    }

    /**
     * Validates and parses a decimal currency amount into an error map.
     *
     * @param raw raw string input
     * @param key field error map key
     * @param label readable field name
     * @param errors mutable error map
     * @param optionalDefaultZero whether blank string defaults to 0.0
     * @return parsed price or 0.0 on error
     */
    private static double validatePriceString(
            String raw,
            String key,
            String label,
            Map<String, String> errors,
            boolean optionalDefaultZero) {
        if (raw == null || raw.trim().isEmpty()) {
            if (optionalDefaultZero) {
                return 0.0;
            }
            errors.put(key, label + " is required.");
            return 0.0;
        }
        try {
            BigDecimal bd = new BigDecimal(raw.trim());
            if (bd.compareTo(BigDecimal.ZERO) < 0) {
                errors.put(key, label + " cannot be negative.");
                return 0.0;
            }
            double val = bd.doubleValue();
            if (Double.isInfinite(val) || Double.isNaN(val) || val > 10_000_000.0) {
                errors.put(key, label + " exceeds valid range.");
                return 0.0;
            }
            return val;
        } catch (NumberFormatException ex) {
            errors.put(key, label + " must be a valid decimal number.");
            return 0.0;
        }
    }
}
