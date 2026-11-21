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
     *
     * @author Ehtisham Haider, Syed Ahmed
     */
    public User() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Builds two-letter initials for sidebar and header avatar badges.
     *
     * @return uppercase initials derived from full name or username
     * @author Ehtisham Haider, Syed Ahmed
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

    /** @return user id @author Ehtisham Haider, Syed Ahmed */
    public long getId() { return id; }

    /** @param id user id @author Ehtisham Haider, Syed Ahmed */
    public void setId(long id) { this.id = id; }

    /** @return login username @author Ehtisham Haider, Syed Ahmed */
    public String getUsername() { return username; }

    /** @param username login username @author Ehtisham Haider, Syed Ahmed */
    public void setUsername(String username) { this.username = username; }

    /** @return BCrypt password hash @author Ehtisham Haider, Syed Ahmed */
    public String getPasswordHash() { return passwordHash; }

    /** @param passwordHash BCrypt password hash @author Ehtisham Haider, Syed Ahmed */
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    /** @return staff member full name @author Ehtisham Haider, Syed Ahmed */
    public String getFullName() { return fullName; }

    /** @param fullName staff member full name @author Ehtisham Haider, Syed Ahmed */
    public void setFullName(String fullName) { this.fullName = fullName; }

    /** @return staff role @author Ehtisham Haider, Syed Ahmed */
    public UserRole getRole() { return role; }

    /** @param role staff role @author Ehtisham Haider, Syed Ahmed */
    public void setRole(UserRole role) { this.role = role; }

    /** @return {@code true} if login is enabled @author Ehtisham Haider, Syed Ahmed */
    public boolean isActive() { return active; }

    /** @param active {@code true} if login is enabled @author Ehtisham Haider, Syed Ahmed */
    public void setActive(boolean active) { this.active = active; }

    /** @return account creation timestamp @author Ehtisham Haider, Syed Ahmed */
    public LocalDateTime getCreatedAt() { return createdAt; }

    /** @param createdAt account creation timestamp @author Ehtisham Haider, Syed Ahmed */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
