package com.stockpro.domain;

import java.time.LocalDateTime;

/**
 * Authenticated staff account with role-based permissions.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class User {
    private long id;
    private String username;
    private String passwordHash;
    private String fullName;
    private UserRole role = UserRole.CASHIER;
    private boolean active = true;
    private LocalDateTime createdAt;

    /**
     * Creates a new active staff account instance.
     */
    public User() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Builds two-letter initials for sidebar and header avatar badges.
     *
     * @return uppercase initials derived from full name or username
     */
    public String getInitials() {
        String source = (fullName != null && !fullName.isBlank()) ? fullName.trim() : username;
        if (source == null || source.isBlank()) {
            return "SP";
        }
        String[] parts = source.split("\\s+");
        if (parts.length >= 2) {
            return ("" + parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase();
        }
        return source.substring(0, Math.min(2, source.length())).toUpperCase();
    }

    /**
     * Returns the user database identifier.
     *
     * @return user id
     */
    public long getId() { return id; }

    /**
     * Sets the user database identifier.
     *
     * @param id user id
     */
    public void setId(long id) { this.id = id; }

    /**
     * Returns the login username.
     *
     * @return login username
     */
    public String getUsername() { return username; }

    /**
     * Sets the login username.
     *
     * @param username login username
     */
    public void setUsername(String username) { this.username = username; }

    /**
     * Returns the stored BCrypt password hash.
     *
     * @return BCrypt password hash
     */
    public String getPasswordHash() { return passwordHash; }

    /**
     * Sets the stored BCrypt password hash.
     *
     * @param passwordHash BCrypt password hash
     */
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    /**
     * Returns the staff member's full name.
     *
     * @return staff member full name
     */
    public String getFullName() { return fullName; }

    /**
     * Sets the staff member's full name.
     *
     * @param fullName staff member full name
     */
    public void setFullName(String fullName) { this.fullName = fullName; }

    /**
     * Returns the assigned staff role.
     *
     * @return staff role
     */
    public UserRole getRole() { return role; }

    /**
     * Sets the assigned staff role.
     *
     * @param role staff role
     */
    public void setRole(UserRole role) { this.role = role; }

    /**
     * Indicates whether the staff account can sign in.
     *
     * @return {@code true} if login is enabled
     */
    public boolean isActive() { return active; }

    /**
     * Enables or disables login for this account.
     *
     * @param active {@code true} if login is enabled
     */
    public void setActive(boolean active) { this.active = active; }

    /**
     * Returns the account creation timestamp.
     *
     * @return account creation timestamp
     */
    public LocalDateTime getCreatedAt() { return createdAt; }

    /**
     * Sets the account creation timestamp.
     *
     * @param createdAt account creation timestamp
     */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
