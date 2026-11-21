package com.stockpro.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists and retrieves key-value store preferences from the {@code app_settings} SQLite table.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class SettingsRepository {
    private final DatabaseManager dbManager;

    /**
     * Creates a settings repository using the default singleton database manager.
     */
    public SettingsRepository() {
        this(DatabaseManager.getInstance());
    }

    /**
     * Creates a settings repository bound to a specific {@link DatabaseManager}.
     *
     * @param dbManager database manager instance
     */
    public SettingsRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    /**
     * Reads a setting value by key, returning a fallback if absent.
     *
     * @param key setting key
     * @param defaultValue fallback value if key is missing
     * @return persisted setting value or fallback
     */
    public String get(String key, String defaultValue) {
        if (key == null || key.isBlank()) {
            return defaultValue;
        }
        String sql = "SELECT setting_value FROM app_settings WHERE setting_key = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
            return defaultValue;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read setting: " + key, ex);
        }
    }

    /**
     * Inserts or updates a preference key-value pair.
     *
     * @param key setting key
     * @param value setting value
     */
    public void set(String key, String value) {
        String sql = """
                INSERT INTO app_settings (setting_key, setting_value, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(setting_key) DO UPDATE SET
                    setting_value = excluded.setting_value,
                    updated_at = excluded.updated_at
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key.trim());
            ps.setString(2, value != null ? value : "");
            ps.setString(3, LocalDateTime.now().toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to save setting: " + key, ex);
        }
    }

    /**
     * Returns all stored configuration settings as an ordered map.
     *
     * @return map of setting keys to values
     */
    public Map<String, String> getAll() {
        Map<String, String> map = new LinkedHashMap<>();
        String sql = "SELECT setting_key, setting_value FROM app_settings ORDER BY setting_key ASC";
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                map.put(rs.getString("setting_key"), rs.getString("setting_value"));
            }
            return map;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read application settings", ex);
        }
    }
}
