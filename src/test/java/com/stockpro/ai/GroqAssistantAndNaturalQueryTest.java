package com.stockpro.ai;

import com.stockpro.domain.InventoryFilterCriteria;
import com.stockpro.domain.InventoryFilterCriteria.StatusFilterChip;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.Supplier;
import com.stockpro.ml.OfflineMlEngine;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.SettingsRepository;
import com.stockpro.repository.SupplierRepository;
import com.stockpro.service.InventoryEventBus;
import com.stockpro.service.InventoryService;
import com.stockpro.service.command.CommandHistoryManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Groq client graceful offline fallback, SQLite-grounded Q&amp;A, supplier email drafting,
 * Ask-for-Any-Chart JSON parsing, plain-English explanations, and Smart Natural Search translation.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
class GroqAssistantAndNaturalQueryTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private SettingsRepository settingsRepo;
    private GroqApiClient groqClient;
    private InventoryService inventoryService;
    private AiAssistantService assistantService;

    @BeforeEach
    void setUp() {
        dbManager = DatabaseManager.createForFile(tempDir.resolve("ai-assistant-test.db"));
        ProductRepository prodRepo = new ProductRepository(dbManager);
        SupplierRepository supRepo = new SupplierRepository(dbManager);
        settingsRepo = new SettingsRepository(dbManager);
        settingsRepo.set("groq.api_key", "");

        inventoryService = new InventoryService(prodRepo, new CommandHistoryManager(), InventoryEventBus.getInstance());
        OfflineMlEngine mlEngine = new OfflineMlEngine(dbManager, prodRepo);
        groqClient = new GroqApiClient(settingsRepo);
        assistantService = new AiAssistantService(groqClient, prodRepo, supRepo, mlEngine);
    }

    @AfterEach
    void tearDown() {
        dbManager.close();
    }

    @Test
    @DisplayName("Answers store questions offline grounded in SQLite, drafts supplier email, and explains alerts")
    void offlineStoreChatBriefingEmailAndExplain() {
        inventoryService.addProductFromInputs("Milk 1L", "4", "Dairy", 1.0, 1.5, null);
        inventoryService.addProductFromInputs("Chips", "3", "Snacks", 1.2, 1.8, null);
        inventoryService.addProductFromInputs("Wireless Mouse", "80", "Electronics", 10.0, 18.0, null);

        AiAssistantService.AssistantReply reply = assistantService.answerQuestion("Which items run out this week?");
        assertFalse(reply.usedCloud());
        assertTrue(reply.message().contains("Milk 1L") || reply.message().contains("Chips"));

        String briefing = assistantService.generateMorningBriefing();
        assertTrue(briefing.contains("Morning Store Briefing"));

        Supplier sup = new Supplier();
        sup.setName("TechGlobal Ltd");
        sup.setDeliveryDays(2);
        String email = assistantService.draftSupplierEmail(sup);
        assertTrue(email.contains("TechGlobal Ltd"));
        assertTrue(email.contains("Subject:"));

        String explained = assistantService.explainInPlainEnglish("low stock alert");
        assertTrue(explained.contains("•"));
    }

    @Test
    @DisplayName("Parses natural chart prompts and translates 'cheap snacks running low' into filter criteria")
    void chartSpecAndNaturalSearchTranslation() {
        inventoryService.addProductFromInputs("Chips", "4", "Snacks", 1.2, 1.8, null);
        inventoryService.addProductFromInputs("Premium Truffle Box", "20", "Snacks", 10.0, 25.0, null);
        inventoryService.addProductFromInputs("Wireless Keyboard", "30", "Electronics", 15.0, 35.0, null);

        AiAssistantService.AssistantReply chartReply = assistantService.generateChartFromPrompt(
                "Show a bar chart of top 5 valuable categories");
        assertNotNull(chartReply.chartSpec());
        assertEquals("BAR", chartReply.chartSpec().chartType());
        assertFalse(chartReply.chartSpec().points().isEmpty());

        ChartSpec parsedJson = NaturalQueryEngine.tryParseChartJson(
                "```json\n{\"chartType\":\"PIE\",\"title\":\"Categories\",\"points\":[{\"label\":\"Snacks\",\"value\":100}]}\n```");
        assertNotNull(parsedJson);
        assertEquals("PIE", parsedJson.chartType());

        InventoryFilterCriteria criteria = assistantService.parseNaturalSearch("cheap snacks running low");
        assertEquals("Snacks", criteria.getCategory());
        assertEquals(StatusFilterChip.LOW_STOCK, criteria.getStatusChip());
        assertEquals(5.0, criteria.getMaxSellPrice());

        List<Product> filtered = inventoryService.filterProducts(criteria);
        assertEquals(1, filtered.size());
        assertEquals("Chips", filtered.get(0).getName());
        assertEquals(StockStatus.LOW_STOCK, filtered.get(0).getStatus());
    }

    @Test
    @DisplayName("Persists user-supplied Groq API key & model cleanly and propagates reply metadata")
    void groqUserConfigurationAndReplyMetadata() {
        assertFalse(groqClient.isApiKeyConfigured());
        assertFalse(assistantService.isCloudAiReady());

        groqClient.saveConfiguration("gsk_user_sample_123", "openai/gpt-oss-20b");
        assertTrue(groqClient.isApiKeyConfigured());
        assertTrue(assistantService.isCloudAiReady());
        assertEquals("openai/gpt-oss-20b", assistantService.getActiveModel());

        AiAssistantService.AssistantReply offlineEmailReply = assistantService.draftSupplierEmailReply(null);
        assertNotNull(offlineEmailReply.message());
        assertFalse(offlineEmailReply.message().isBlank());

        groqClient.clearApiKey();
        assertFalse(groqClient.isApiKeyConfigured());
        assertTrue(assistantService.generateMorningBriefingReply().message().contains("Morning Store Briefing"));
    }

    @Test
    @DisplayName("Auto-migrates retired Groq models, filters non-chat models, and strips reasoning tags")
    void autoMigratesRetiredModelsFiltersLiveCatalogAndStripsThinkTags() {
        groqClient.saveConfiguration("gsk_user_sample_123", "llama-3.1-8b-instant");
        assertEquals("openai/gpt-oss-20b", groqClient.resolveModel());
        assertEquals("openai/gpt-oss-20b", settingsRepo.get("groq.model", ""));

        groqClient.saveConfiguration("gsk_user_sample_123", "llama-3.3-70b-versatile");
        assertEquals("openai/gpt-oss-20b", groqClient.resolveModel());

        List<String> consoleCatalog = List.of(
                "allam-2-7b",
                "meta-llama/llama-prompt-guard-2-22m",
                "meta-llama/llama-prompt-guard-2-86m",
                "openai/gpt-oss-120b",
                "openai/gpt-oss-20b",
                "openai/gpt-oss-safeguard-20b",
                "qwen/qwen3.8-27b",
                "whisper-large-v3",
                "whisper-large-v3-turbo",
                "canopylabs/orpheus-arabic-saudi",
                "canopylabs/orpheus-v1-english"
        );
        assertEquals(
                List.of("openai/gpt-oss-20b", "openai/gpt-oss-120b", "qwen/qwen3.8-27b", "allam-2-7b"),
                GroqApiClient.filterChatModels(consoleCatalog)
        );
        assertEquals(
                "Restock Milk 1L.",
                GroqApiClient.sanitizeModelContent("<think>checking stock</think>\nRestock Milk 1L.")
        );
    }
}
