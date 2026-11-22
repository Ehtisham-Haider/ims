package com.stockpro.service;

import com.stockpro.ai.GroqWhisperClient;
import com.stockpro.domain.MovementType;
import com.stockpro.domain.Product;
import com.stockpro.domain.VoiceCommandResult;
import com.stockpro.domain.VoiceCommandResult.VoiceIntent;
import com.stockpro.dsa.CatalogSearchEngine;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hands-Free Voice Command Engine combining Groq Whisper speech-to-text with local intent parsing
 * for adding products, logging sales/restocks, navigating screens, and running Voice-to-Search
 * straight into a product's {@code ProductDetailsDrawer}.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class VoiceCommandEngine {

    private static final Pattern ACTION_QTY_ITEM = Pattern.compile(
            "(?i)^(?:please\\s+)?(add|create|new|sell|sold|sale|restock|receive)\\s+(?:stock\\s+of\\s+)?(\\d+)\\s+(?:units?\\s+of\\s+)?(.+)$");
    private static final Pattern SEARCH_CMD = Pattern.compile(
            "(?i)^(?:please\\s+)?(?:search|find|inspect|open\\s+product|look\\s+up)\\s+(?:for\\s+)?(.+)$");
    private static final Pattern NAV_CMD = Pattern.compile(
            "(?i)^(?:please\\s+)?(?:go\\s+to|open|show|switch\\s+to|navigate\\s+to)\\s+(.+)$");

    private final InventoryService inventoryService;
    private final StockOperationService stockOperationService;
    private final CatalogSearchEngine searchEngine;
    private final GroqWhisperClient whisperClient;
    private final AuditService auditService;

    /**
     * Constructs the hands-free Voice Command Engine.
     *
     * @param inventoryService catalog CRUD service
     * @param stockOperationService stock movement service
     * @param searchEngine fast DS&amp;A catalog lookup engine
     * @param whisperClient Groq Whisper client
     * @param auditService tamper-evident audit logger
     */
    public VoiceCommandEngine(
            InventoryService inventoryService,
            StockOperationService stockOperationService,
            CatalogSearchEngine searchEngine,
            GroqWhisperClient whisperClient,
            AuditService auditService) {
        this.inventoryService = inventoryService;
        this.stockOperationService = stockOperationService;
        this.searchEngine = searchEngine;
        this.whisperClient = whisperClient;
        this.auditService = auditService;
    }

    /**
     * Transcribes recorded WAV audio via Groq Whisper (or offline fallback text) and executes
     * the corresponding store command.
     *
     * @param wavAudio WAV audio bytes
     * @param offlineFallbackTranscript fallback command string used when offline or key missing
     * @param actor authenticated staff username
     * @return structured {@link VoiceCommandResult}
     */
    public VoiceCommandResult transcribeAndExecute(
            byte[] wavAudio,
            String offlineFallbackTranscript,
            String actor) {
        GroqWhisperClient.WhisperTranscript transcript = whisperClient.transcribe(wavAudio, offlineFallbackTranscript);
        return executeTranscript(transcript.text(), transcript.usedCloud(), actor);
    }

    /**
     * Parses and executes a plain-text voice transcript immediately without audio encoding.
     *
     * @param transcript spoken command string
     * @param usedCloudWhisper {@code true} if transcribed via Groq Whisper
     * @param actor authenticated staff username
     * @return structured {@link VoiceCommandResult}
     */
    public VoiceCommandResult executeTranscript(String transcript, boolean usedCloudWhisper, String actor) {
        String clean = transcript == null ? "" : transcript.trim().replaceAll("[.!?]+$", "");
        if (clean.isBlank()) {
            return new VoiceCommandResult(
                    VoiceIntent.CHAT_QUERY, false, "", usedCloudWhisper, "", 0,
                    "", 0L, "", "No spoken command heard. Speak an action like 'Restock 10 Milk' or 'Open Warehouse Map'."
            );
        }

        String who = actor != null && !actor.isBlank() ? actor : AppSession.getInstance().getActorName();

        // 1. Check quantity-bearing inventory mutations ("Add 12 Olive Oil", "Sell 5 Cola", "Restock 20 Rice")
        Matcher actionMatcher = ACTION_QTY_ITEM.matcher(clean);
        if (actionMatcher.matches()) {
            String verb = actionMatcher.group(1).toLowerCase(Locale.ROOT);
            int qty = Integer.parseInt(actionMatcher.group(2));
            String itemText = actionMatcher.group(3).trim();

            if (verb.equals("add") || verb.equals("create") || verb.equals("new")) {
                return handleAddCommand(clean, usedCloudWhisper, itemText, qty, who);
            }
            if (verb.equals("sell") || verb.equals("sold") || verb.equals("sale")) {
                return handleStockMovementCommand(VoiceIntent.RECORD_SALE, MovementType.SALE,
                        clean, usedCloudWhisper, itemText, qty, who);
            }
            return handleStockMovementCommand(VoiceIntent.RESTOCK, MovementType.RESTOCK,
                    clean, usedCloudWhisper, itemText, qty, who);
        }

        // 2. Check Voice-to-Search ("Search Bluetooth Speaker", "Inspect Olive Oil")
        Matcher searchMatcher = SEARCH_CMD.matcher(clean);
        if (searchMatcher.matches()) {
            String query = searchMatcher.group(1).trim();
            return handleVoiceSearch(clean, usedCloudWhisper, query);
        }

        // 3. Check Navigation ("Open Warehouse Map", "Go to Settings", "Show Dashboard")
        Matcher navMatcher = NAV_CMD.matcher(clean);
        if (navMatcher.matches()) {
            String sectionName = resolveNavigationSectionName(navMatcher.group(1).trim());
            if (!sectionName.isBlank()) {
                return new VoiceCommandResult(
                        VoiceIntent.NAVIGATE,
                        true,
                        clean,
                        usedCloudWhisper,
                        navMatcher.group(1).trim(),
                        0,
                        sectionName,
                        0L,
                        "",
                        "Navigating to " + sectionName.replace('_', ' ') + "."
                );
            }
            // Fall through to Voice-to-Search if "open <product name>" wasn't a screen name
            return handleVoiceSearch(clean, usedCloudWhisper, navMatcher.group(1).trim());
        }

        // 4. Conversational prompt routed to AI Chat
        return new VoiceCommandResult(
                VoiceIntent.CHAT_QUERY,
                true,
                clean,
                usedCloudWhisper,
                clean,
                0,
                "",
                0L,
                "",
                "Routed voice question to AI Assistant."
        );
    }

    private VoiceCommandResult handleAddCommand(
            String clean,
            boolean usedCloudWhisper,
            String itemName,
            int qty,
            String actor) {
        InventoryOperationResult<Product> res = inventoryService.addProductFromInputs(
                itemName, String.valueOf(qty), "General", 5.0, 9.50, null);
        if (res.isSuccess() && res.getData() != null) {
            Product created = res.getData();
            auditService.record(actor, "VOICE_ADD", created.getSku(),
                    "Voice command created '" + created.getName() + "' qty=" + qty);
            return new VoiceCommandResult(
                    VoiceIntent.ADD_ITEM, true, clean, usedCloudWhisper,
                    created.getName(), qty, "", created.getId(), created.getSku(),
                    "Voice Added: " + created.getName() + " (" + created.getSku() + ", Qty " + qty + ")."
            );
        }
        return new VoiceCommandResult(
                VoiceIntent.ADD_ITEM, false, clean, usedCloudWhisper,
                itemName, qty, "", 0L, "", res.getMessage()
        );
    }

    private VoiceCommandResult handleStockMovementCommand(
            VoiceIntent intent,
            MovementType type,
            String clean,
            boolean usedCloudWhisper,
            String query,
            int qty,
            String actor) {
        List<Product> matches = findMatchingProducts(query);
        if (matches.isEmpty()) {
            return new VoiceCommandResult(
                    intent, false, clean, usedCloudWhisper, query, qty,
                    "", 0L, "", "No product matching '" + query + "' found for " + type.getDisplayName() + "."
            );
        }
        Product target = matches.get(0);
        InventoryOperationResult<?> op = stockOperationService.recordOperation(
                target.getId(),
                type,
                String.valueOf(qty),
                "Voice Command",
                "Executed hands-free: " + clean,
                actor
        );
        return new VoiceCommandResult(
                intent,
                op.isSuccess(),
                clean,
                usedCloudWhisper,
                target.getName(),
                qty,
                "",
                target.getId(),
                target.getSku(),
                op.getMessage()
        );
    }

    private VoiceCommandResult handleVoiceSearch(String clean, boolean usedCloudWhisper, String query) {
        List<Product> matches = findMatchingProducts(query);
        if (matches.isEmpty()) {
            return new VoiceCommandResult(
                    VoiceIntent.VOICE_SEARCH, false, clean, usedCloudWhisper, query, 0,
                    "", 0L, "", "No catalog matches found for spoken search '" + query + "'."
            );
        }
        Product best = matches.get(0);
        return new VoiceCommandResult(
                VoiceIntent.VOICE_SEARCH,
                true,
                clean,
                usedCloudWhisper,
                best.getName(),
                best.getQuantity(),
                "",
                best.getId(),
                best.getSku(),
                "Voice-to-Search matched: " + best.getName() + " (" + best.getSku() + ", Qty " + best.getQuantity() + ")."
        );
    }

    private List<Product> findMatchingProducts(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        CatalogSearchEngine.SearchResult fast = searchEngine.search(query, 5);
        if (!fast.results().isEmpty()) {
            return fast.results();
        }
        String lower = query.toLowerCase(Locale.ROOT);
        return inventoryService.getAllProducts().stream()
                .filter(p -> p.getName().toLowerCase(Locale.ROOT).contains(lower)
                        || p.getSku().toLowerCase(Locale.ROOT).contains(lower))
                .toList();
    }

    private String resolveNavigationSectionName(String rawTarget) {
        String s = rawTarget.toLowerCase(Locale.ROOT);
        if (s.contains("dashboard") || s.contains("home")) {
            return "DASHBOARD";
        }
        if (s.contains("warehouse") || s.contains("map") || s.contains("floorplan") || s.contains("picking")) {
            return "WAREHOUSE_MAP";
        }
        if (s.contains("stock") || s.contains("operation") || s.contains("transfer") || s.contains("rma")) {
            return "STOCK_OPERATIONS";
        }
        if (s.contains("inventory") || s.contains("catalog") || s.contains("products")) {
            return "INVENTORY";
        }
        if (s.contains("supplier") || s.contains("order") || s.contains("po") || s.contains("finance")) {
            return "SUPPLIERS_ORDERS";
        }
        if (s.contains("analytic") || s.contains("report")) {
            return "ANALYTICS_REPORTS";
        }
        if (s.contains("ai") || s.contains("assistant") || s.contains("chat")) {
            return "AI_ASSISTANT";
        }
        if (s.contains("setting")) {
            return "SETTINGS";
        }
        return "";
    }
}
