package com.stockpro.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encapsulates field-level and summary validation results for form and service checks.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class ValidationResult {
    private final boolean valid;
    private final Map<String, String> fieldErrors;

    /**
     * Constructs a validation result from a map of field keys to error messages.
     *
     * @param fieldErrors map of invalid fields and diagnostic messages
     * @author Ehtisham Haider, Syed Ahmed
     */
    public ValidationResult(Map<String, String> fieldErrors) {
        this.fieldErrors = fieldErrors == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
        this.valid = this.fieldErrors.isEmpty();
    }

    /**
     * Returns a passing validation result with no errors.
     *
     * @return valid {@link ValidationResult}
     * @author Ehtisham Haider, Syed Ahmed
     */
    public static ValidationResult ok() {
        return new ValidationResult(Collections.emptyMap());
    }

    /**
     * Returns a failing validation result containing a single field error.
     *
     * @param field field identifier
     * @param message user-facing error description
     * @return invalid {@link ValidationResult}
     * @author Ehtisham Haider, Syed Ahmed
     */
    public static ValidationResult fail(String field, String message) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(field, message);
        return new ValidationResult(map);
    }

    /**
     * Indicates whether validation passed without errors.
     *
     * @return {@code true} when no field errors exist
     * @author Ehtisham Haider, Syed Ahmed
     */
    public boolean isValid() {
        return valid;
    }

    /**
     * Returns an unmodifiable map of field identifiers to error messages.
     *
     * @return field error map
     * @author Ehtisham Haider, Syed Ahmed
     */
    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }

    /**
     * Formats all validation errors into a single readable message.
     *
     * @return joined error summary, or empty string if valid
     * @author Ehtisham Haider, Syed Ahmed
     */
    public String getSummaryMessage() {
        if (valid) {
            return "";
        }
        return String.join("\n", fieldErrors.values());
    }
}
