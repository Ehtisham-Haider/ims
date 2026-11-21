package com.stockpro.service;

import com.stockpro.domain.ValidationResult;

/**
 * Standardized outcome wrapper returned by inventory service operations.
 *
 * @param <T> payload type returned when the operation succeeds
 * @author Ehtisham Haider, Syed Ahmed
 */
public class InventoryOperationResult<T> {
    private final boolean success;
    private final String message;
    private final T data;
    private final ValidationResult validationResult;

    /**
     * Constructs an operation result instance.
     *
     * @param success {@code true} if the requested action succeeded
     * @param message human-readable status or error message
     * @param data optional resulting payload
     * @param validationResult optional field validation diagnostics
     */
    private InventoryOperationResult(boolean success, String message, T data, ValidationResult validationResult) {
        this.success = success;
        this.message = message;
        this.data = data;
        this.validationResult = validationResult != null ? validationResult : ValidationResult.ok();
    }

    /**
     * Creates a successful operation result carrying a payload.
     *
     * @param data resulting object
     * @param message confirmation message
     * @param <T> payload type
     * @return successful {@link InventoryOperationResult}
     */
    public static <T> InventoryOperationResult<T> ok(T data, String message) {
        return new InventoryOperationResult<>(true, message, data, ValidationResult.ok());
    }

    /**
     * Creates a failed operation result with a plain error message.
     *
     * @param message failure reason
     * @param <T> payload type
     * @return failed {@link InventoryOperationResult}
     */
    public static <T> InventoryOperationResult<T> fail(String message) {
        return new InventoryOperationResult<>(false, message, null, ValidationResult.fail("general", message));
    }

    /**
     * Creates a failed operation result backed by field-level validation errors.
     *
     * @param validationResult field validation breakdown
     * @param <T> payload type
     * @return failed {@link InventoryOperationResult}
     */
    public static <T> InventoryOperationResult<T> invalid(ValidationResult validationResult) {
        String msg = validationResult != null ? validationResult.getSummaryMessage() : "Validation failed.";
        return new InventoryOperationResult<>(false, msg, null, validationResult);
    }

    /**
     * Returns whether the operation succeeded.
     *
     * @return {@code true} on success
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Returns the summary status or error message.
     *
     * @return user-facing message
     */
    public String getMessage() {
        return message;
    }

    /**
     * Returns the optional payload produced by the operation.
     *
     * @return payload object, or {@code null}
     */
    public T getData() {
        return data;
    }

    /**
     * Returns field-level validation errors associated with the request.
     *
     * @return {@link ValidationResult} instance
     */
    public ValidationResult getValidationResult() {
        return validationResult;
    }
}
