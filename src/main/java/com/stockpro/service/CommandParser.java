package com.stockpro.service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses natural Spotlight {@code Ctrl+K} commands into structured actions for quick keyboard execution.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public final class CommandParser {
    private static final Pattern ADD_PATTERN = Pattern.compile("^add\\s+(\\d+)\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    /**
     * Enumerates supported Spotlight command bar action types.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
     */
    public enum ActionType {
        /** Quickly add a new product or quantity via command text. */
        QUICK_ADD,
        /** Switch between Light and Dark UI themes. */
        TOGGLE_THEME,
        /** Navigate to a named application screen. */
        NAVIGATE,
        /** Trigger executive PDF report generation. */
        EXPORT_PDF,
        /** Search catalog products by text query. */
        SEARCH_PRODUCTS
    }

    /**
     * Immutable representation of a parsed Spotlight command.
     *
     * @param actionType resolved action category
     * @param targetScreen navigation destination key when {@link ActionType#NAVIGATE}
     * @param quantity parsed unit count when {@link ActionType#QUICK_ADD}
     * @param argument product name or search query
     * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
     */
    public record ParsedCommand(
            ActionType actionType,
            String targetScreen,
            int quantity,
            String argument
    ) {
    }

    /**
     * Prevents instantiation of stateless parser utility.
     */
    private CommandParser() {
    }

    /**
     * Parses a user-entered command bar string into a {@link ParsedCommand}.
     *
     * @param rawInput text entered in the Spotlight bar
     * @return structured {@link ParsedCommand}
     */
    public static ParsedCommand parse(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return new ParsedCommand(ActionType.SEARCH_PRODUCTS, null, 0, "");
        }
        String trimmed = rawInput.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        Matcher addMatcher = ADD_PATTERN.matcher(trimmed);
        if (addMatcher.matches()) {
            int qty = Integer.parseInt(addMatcher.group(1));
            String itemName = addMatcher.group(2).trim();
            return new ParsedCommand(ActionType.QUICK_ADD, null, qty, itemName);
        }

        if (lower.equals("toggle theme") || lower.equals("dark mode") || lower.equals("light mode")
                || lower.equals("switch theme")) {
            return new ParsedCommand(ActionType.TOGGLE_THEME, null, 0, lower);
        }

        if (lower.equals("export pdf") || lower.equals("export report") || lower.equals("pdf")) {
            return new ParsedCommand(ActionType.EXPORT_PDF, null, 0, "");
        }

        String navTarget = resolveNavigationTarget(lower);
        if (navTarget != null) {
            return new ParsedCommand(ActionType.NAVIGATE, navTarget, 0, trimmed);
        }

        return new ParsedCommand(ActionType.SEARCH_PRODUCTS, null, 0, trimmed);
    }

    /**
     * Matches screen jump phrases such as {@code go inventory} or {@code settings}.
     *
     * @param lower lowercased command text
     * @return canonical screen key, or {@code null} if not a navigation command
     */
    private static String resolveNavigationTarget(String lower) {
        String cleaned = lower.replaceFirst("^(go to|goto|go|open|show)\\s+", "").trim();
        return switch (cleaned) {
            case "dashboard", "home" -> "DASHBOARD";
            case "inventory", "products", "catalog" -> "INVENTORY";
            case "stock", "stock operations", "operations", "audit" -> "STOCK_OPERATIONS";
            case "suppliers", "orders", "suppliers & orders", "po" -> "SUPPLIERS_ORDERS";
            case "analytics", "reports", "analytics & reports" -> "ANALYTICS_REPORTS";
            case "ai", "ai assistant", "assistant", "chat", "approvals" -> "AI_ASSISTANT";
            case "settings", "preferences", "backup" -> "SETTINGS";
            default -> null;
        };
    }
}
