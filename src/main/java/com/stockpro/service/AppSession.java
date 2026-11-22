package com.stockpro.service;

import com.stockpro.domain.User;
import com.stockpro.domain.UserRole;

/**
 * Holds active session state including authenticated user, theme preference, and permission checks.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public final class AppSession {
    private static final AppSession INSTANCE = new AppSession();

    private User currentUser;
    private boolean darkMode;
    private String storeName = "StockPro Store";
    private String currencySymbol = "$";
    private double taxRatePercent = 15.0;
    private long activeBranchId = 1L;
    private String activeBranchName = "Main Flagship Store";

    /**
     * Prevents external instantiation of singleton session holder.
     */
    private AppSession() {
    }

    /**
     * Returns the singleton {@link AppSession}.
     *
     * @return shared application session
     */
    public static AppSession getInstance() {
        return INSTANCE;
    }

    /**
     * Returns the currently logged-in staff account, if any.
     *
     * @return active {@link User}, or {@code null} when signed out
     */
    public User getCurrentUser() {
        return currentUser;
    }

    /**
     * Assigns the active authenticated user.
     *
     * @param currentUser signed-in staff user
     */
    public void setCurrentUser(User currentUser) {
        this.currentUser = currentUser;
    }

    /**
     * Signs out the active user.
     */
    public void clearUser() {
        this.currentUser = null;
    }

    /**
     * Returns the active actor display name for audit logs and stock movements.
     *
     * @return active user full name, username, or {@code "System"} if unauthenticated
     */
    public String getActorName() {
        if (currentUser == null) {
            return "System";
        }
        if (currentUser.getFullName() != null && !currentUser.getFullName().isBlank()) {
            return currentUser.getFullName();
        }
        return currentUser.getUsername();
    }

    /**
     * Checks whether the current user has Owner privileges.
     *
     * @return {@code true} if current user is {@link UserRole#OWNER}
     */
    public boolean isOwner() {
        return currentUser != null && currentUser.getRole() == UserRole.OWNER;
    }

    /**
     * Checks whether the current user can create, update, or delete inventory items and orders.
     *
     * @return {@code true} for Owner and Manager accounts
     */
    public boolean canManageInventory() {
        return currentUser != null && currentUser.getRole().canManageInventory();
    }

    /**
     * Checks whether the current user may permanently delete items or approve automated proposals.
     *
     * @return {@code true} for Owner and Manager roles
     */
    public boolean canDeleteOrApprove() {
        return canManageInventory();
    }

    /**
     * Returns whether dark theme is currently active.
     *
     * @return {@code true} if dark theme is enabled
     */
    public boolean isDarkMode() {
        return darkMode;
    }

    /**
     * Sets the active UI theme mode.
     *
     * @param darkMode {@code true} for dark mode, {@code false} for light mode
     */
    public void setDarkMode(boolean darkMode) {
        this.darkMode = darkMode;
    }

    /**
     * Returns the configured store name.
     *
     * @return store name
     */
    public String getStoreName() {
        return storeName;
    }

    /**
     * Updates the configured store name.
     *
     * @param storeName store display name
     */
    public void setStoreName(String storeName) {
        if (storeName != null && !storeName.isBlank()) {
            this.storeName = storeName.trim();
        }
    }

    /**
     * Returns the configured currency symbol.
     *
     * @return currency prefix symbol
     */
    public String getCurrencySymbol() {
        return currencySymbol;
    }

    /**
     * Extracts and stores currency symbol from settings values such as {@code USD ($)} or {@code PKR (Rs)}.
     *
     * @param currencySetting raw currency label or symbol
     */
    public void setCurrencyFromSetting(String currencySetting) {
        if (currencySetting == null || currencySetting.isBlank()) {
            return;
        }
        int open = currencySetting.indexOf('(');
        int close = currencySetting.indexOf(')');
        if (open >= 0 && close > open + 1) {
            this.currencySymbol = currencySetting.substring(open + 1, close).trim();
        } else {
            this.currencySymbol = currencySetting.trim();
        }
    }

    /**
     * Formats a monetary amount using the active store currency symbol.
     *
     * @param amount numeric amount
     * @return formatted money string such as {@code $12.50}
     */
    public String formatMoney(double amount) {
        return String.format("%s%,.2f", currencySymbol, amount);
    }

    /**
     * Returns the configured tax/GST percentage.
     *
     * @return tax rate percentage (0..100)
     */
    public double getTaxRatePercent() {
        return taxRatePercent;
    }

    /**
     * Sets the store tax/GST percentage.
     *
     * @param taxRatePercent non-negative percentage
     */
    public void setTaxRatePercent(double taxRatePercent) {
        if (taxRatePercent >= 0.0 && taxRatePercent <= 100.0) {
            this.taxRatePercent = taxRatePercent;
        }
    }

    /**
     * Returns the currently selected physical store branch ID.
     *
     * @return active branch identifier
     */
    public long getActiveBranchId() {
        return activeBranchId;
    }

    /**
     * Sets the active physical store branch ID for multi-branch UI filtering.
     *
     * @param activeBranchId target branch ID
     */
    public void setActiveBranchId(long activeBranchId) {
        if (activeBranchId > 0) {
            this.activeBranchId = activeBranchId;
        }
    }

    /**
     * Returns the display name of the currently selected store branch.
     *
     * @return active branch name
     */
    public String getActiveBranchName() {
        return activeBranchName;
    }

    /**
     * Sets the display name of the currently selected store branch.
     *
     * @param activeBranchName branch display name
     */
    public void setActiveBranchName(String activeBranchName) {
        if (activeBranchName != null && !activeBranchName.isBlank()) {
            this.activeBranchName = activeBranchName.trim();
        }
    }
}
