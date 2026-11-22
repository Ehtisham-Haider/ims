package com.stockpro.repository;

import com.stockpro.domain.User;
import com.stockpro.domain.UserRole;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC repository managing staff user accounts and role assignments in SQLite.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class UserRepository {
    private final DatabaseManager dbManager;

    /**
     * Creates a repository backed by the default singleton {@link DatabaseManager}.
     */
    public UserRepository() {
        this(DatabaseManager.getInstance());
    }

    /**
     * Creates a repository bound to a specific {@link DatabaseManager}.
     *
     * @param dbManager database manager instance
     */
    public UserRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    /**
     * Looks up a staff account by unique username (case-insensitive).
     *
     * @param username account username
     * @return {@link Optional} containing the matched {@link User}
     */
    public Optional<User> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT * FROM users WHERE LOWER(username) = LOWER(?) LIMIT 1";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
            return Optional.empty();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to look up user " + username, ex);
        }
    }

    /**
     * Returns all staff accounts ordered by creation order.
     *
     * @return list of registered staff users
     */
    public List<User> findAll() {
        String sql = "SELECT * FROM users ORDER BY id ASC";
        List<User> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
            return list;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list users", ex);
        }
    }

    /**
     * Inserts a new staff user account.
     *
     * @param user user entity containing a BCrypt password hash
     * @return persisted user with assigned database id
     */
    public User insert(User user) {
        LocalDateTime now = user.getCreatedAt() != null ? user.getCreatedAt() : LocalDateTime.now();
        user.setCreatedAt(now);
        String sql = """
                INSERT INTO users (username, password_hash, full_name, role, active, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, user.getUsername().trim());
            ps.setString(2, user.getPasswordHash());
            ps.setString(3, user.getFullName().trim());
            ps.setString(4, user.getRole().name());
            ps.setInt(5, user.isActive() ? 1 : 0);
            ps.setString(6, now.toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    user.setId(keys.getLong(1));
                }
            }
            return user;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create user " + user.getUsername(), ex);
        }
    }

    /**
     * Updates a user's full name, role, and active status.
     *
     * @param user modified user entity
     * @return {@code true} if the user row existed and was updated
     */
    public boolean updateProfile(User user) {
        String sql = "UPDATE users SET full_name = ?, role = ?, active = ? WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, user.getFullName().trim());
            ps.setString(2, user.getRole().name());
            ps.setInt(3, user.isActive() ? 1 : 0);
            ps.setLong(4, user.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to update user id " + user.getId(), ex);
        }
    }

    /**
     * Updates a user's hashed password.
     *
     * @param userId target user id
     * @param newPasswordHash new BCrypt password hash
     * @return {@code true} if updated
     */
    public boolean updatePasswordHash(long userId, String newPasswordHash) {
        String sql = "UPDATE users SET password_hash = ? WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newPasswordHash);
            ps.setLong(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to update password for user id " + userId, ex);
        }
    }

    /**
     * Maps a SQLite user row to a {@link User} domain object.
     *
     * @param rs active result set
     * @return hydrated {@link User}
     * @throws SQLException if reading any column fails
     */
    private User mapRow(ResultSet rs) throws SQLException {
        User u = new User();
        u.setId(rs.getLong("id"));
        u.setUsername(rs.getString("username"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setFullName(rs.getString("full_name"));
        u.setRole(UserRole.fromString(rs.getString("role")));
        u.setActive(rs.getInt("active") == 1);
        String created = rs.getString("created_at");
        if (created != null && !created.isBlank()) {
            u.setCreatedAt(LocalDateTime.parse(created));
        }
        return u;
    }
}
