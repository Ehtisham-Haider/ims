package com.stockpro.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.stockpro.repository.DatabaseManager;

import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQLite persistence service for per-user Inventory table column visibility preferences
 * stored cleanly as JSON inside {@code user_column_prefs}.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class UserColumnPreferenceService {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Boolean>>() { }.getType();

    private final DatabaseManager dbManager;

    /**
     * Constructs a user column preference repository backed by {@link DatabaseManager}.
     *
     * @param dbManager SQLite connection manager
     */
    public UserColumnPreferenceService(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    /**
     * Loads saved column visibility flags for a staff username merged over supplied defaults.
     *
     * @param username target username
     * @param defaultVisibility default column key to visibility map
     * @return merged ordered visibility map
     */
    public Map<String, Boolean> loadPreferences(String username, Map<String, Boolean> defaultVisibility) {
        Map<String, Boolean> merged = new LinkedHashMap<>();
        if (defaultVisibility != null) {
            merged.putAll(defaultVisibility);
        }
        String key = normalizeUser(username);
        String sql = "SELECT columns_json FROM user_column_prefs WHERE username = ? LIMIT 1";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Boolean> stored = GSON.fromJson(rs.getString("columns_json"), MAP_TYPE);
                    if (stored != null) {
                        merged.putAll(stored);
                    }
                }
            }
            return merged;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read user_column_prefs for " + key, ex);
        }
    }

    /**
     * Saves or updates a user's customized table column visibility map.
     *
     * @param username target username
     * @param visibilityMap column title to visibility boolean map
     */
    public void savePreferences(String username, Map<String, Boolean> visibilityMap) {
        String key = normalizeUser(username);
        String json = GSON.toJson(visibilityMap != null ? visibilityMap : Map.of());
        String now = LocalDateTime.now().toString();
        String sql = "INSERT INTO user_column_prefs (username, columns_json, updated_at) VALUES (?, ?, ?) "
                + "ON CONFLICT(username) DO UPDATE SET columns_json = excluded.columns_json, updated_at = excluded.updated_at";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, json);
            ps.setString(3, now);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to save user_column_prefs for " + key, ex);
        }
    }

    private static String normalizeUser(String username) {
        return (username == null || username.isBlank()) ? "default" : username.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
