package com.stockpro.service;

import com.stockpro.ai.GroqApiClient;
import com.stockpro.ai.GroqWhisperClient;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.VoiceCommandResult;
import com.stockpro.domain.VoiceCommandResult.VoiceIntent;
import com.stockpro.dsa.CatalogSearchEngine;
import com.stockpro.repository.AuditLogRepository;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.SettingsRepository;
import com.stockpro.repository.StockMovementRepository;
import com.stockpro.service.command.CommandHistoryManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic offline integration tests for Branch 06:
 * PCM/WAV audio container capture &amp; synthetic fallback, Groq Whisper multipart transcription,
 * and hands-free voice command execution ({@code Add}, {@code Sale}, {@code Restock},
 * {@code Navigate}, and {@code Voice-to-Search}).
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
class V2VoiceControlWhisperTest {

    @TempDir
    Path tempDir;

    @Test
    void wavSynthesizerBuildsValidRiffContainerAndGroqWhisperClientHandlesOnlineMockAndOfflineFallback() {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch06-whisper.db"));
        try {
            SettingsRepository settingsRepo = new SettingsRepository(db);
            VoiceAudioRecorder recorder = new VoiceAudioRecorder();
            byte[] wav = recorder.synthesizeSilentWav(300);
            assertTrue(wav.length > 44);
            assertEquals('R', (char) wav[0]);
            assertEquals('I', (char) wav[1]);
            assertEquals('F', (char) wav[2]);
            assertEquals('F', (char) wav[3]);

            // Without API key -> clean offline fallback transcript
            GroqApiClient apiNoKey = new GroqApiClient(settingsRepo);
            GroqWhisperClient offlineClient = new GroqWhisperClient(apiNoKey, (ep, key, b, body) -> "{\"text\":\"ignored\"}");
            var offlineRes = offlineClient.transcribe(wav, "open warehouse map");
            assertFalse(offlineRes.usedCloud());
            assertEquals("open warehouse map", offlineRes.text());

            // With API key + mocked multipart transport -> cloud Whisper transcription succeeds offline
            settingsRepo.set("groq.api_key", "gsk_test_dummy_key");
            GroqWhisperClient mockedCloud = new GroqWhisperClient(apiNoKey, (ep, key, b, body) -> {
                assertTrue(ep.endsWith("/audio/transcriptions"));
                assertEquals("Bearer gsk_test_dummy_key", "Bearer " + key);
                assertTrue(body.length > wav.length);
                return "{\"text\":\"Restock 8 Basmati Rice\"}";
            });
            var cloudRes = mockedCloud.transcribe(wav, "fallback");
            assertTrue(cloudRes.usedCloud());
            assertEquals("Restock 8 Basmati Rice", cloudRes.text());
        } finally {
            db.close();
        }
    }

    @Test
    void voiceCommandEngineExecutesAddSaleRestockNavigateAndVoiceToSearch() {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch06-cmds.db"));
        CatalogSearchEngine searchEngine = null;
        try {
            ProductRepository productRepo = new ProductRepository(db);
            StockMovementRepository moveRepo = new StockMovementRepository(db);
            AuditService auditService = new AuditService(new AuditLogRepository(db));
            InventoryService invService = new InventoryService(
                    productRepo, new CommandHistoryManager(), InventoryEventBus.getInstance());
            StockOperationService stockOps = new StockOperationService(
                    productRepo, moveRepo, auditService, InventoryEventBus.getInstance());
            searchEngine = new CatalogSearchEngine(productRepo, InventoryEventBus.getInstance());
            searchEngine.rebuildIndices();

            GroqWhisperClient offlineWhisper = new GroqWhisperClient(
                    new GroqApiClient(new SettingsRepository(db)), (e, k, b, d) -> null);
            VoiceCommandEngine engine = new VoiceCommandEngine(
                    invService, stockOps, searchEngine, offlineWhisper, auditService);

            // Seed item and rebuild indices
            Product seeded = new Product("Basmati Rice 5kg", 20, StockStatus.IN_STOCK);
            seeded.setSku("VC-RICE");
            seeded.setBuyPrice(4.0);
            seeded.setSellPrice(7.0);
            productRepo.insert(seeded);
            searchEngine.rebuildIndices();

            // 1. Add item
            VoiceCommandResult addRes = engine.executeTranscript("Add 15 Cold Pressed Olive Oil", false, "tester");
            assertTrue(addRes.executed());
            assertEquals(VoiceIntent.ADD_ITEM, addRes.intent());
            assertEquals(15, addRes.quantity());
            searchEngine.rebuildIndices();

            // 2. Sale
            VoiceCommandResult saleRes = engine.executeTranscript("Sell 5 Basmati Rice 5kg", false, "tester");
            assertTrue(saleRes.executed(), saleRes.message());
            assertEquals(VoiceIntent.RECORD_SALE, saleRes.intent());
            assertEquals(15, productRepo.findBySku("VC-RICE").orElseThrow().getQuantity());

            // 3. Restock
            VoiceCommandResult restockRes = engine.executeTranscript("Restock 10 Basmati Rice 5kg", false, "tester");
            assertTrue(restockRes.executed(), restockRes.message());
            assertEquals(VoiceIntent.RESTOCK, restockRes.intent());
            assertEquals(25, productRepo.findBySku("VC-RICE").orElseThrow().getQuantity());

            // 4. Navigate
            VoiceCommandResult navRes = engine.executeTranscript("Go to Warehouse Map", false, "tester");
            assertTrue(navRes.executed());
            assertEquals(VoiceIntent.NAVIGATE, navRes.intent());
            assertEquals("WAREHOUSE_MAP", navRes.targetSectionName());

            // 5. Voice-to-Search
            VoiceCommandResult searchRes = engine.executeTranscript("Find Basmati Rice", false, "tester");
            assertTrue(searchRes.executed());
            assertEquals(VoiceIntent.VOICE_SEARCH, searchRes.intent());
            assertEquals("VC-RICE", searchRes.matchedSku());
        } finally {
            if (searchEngine != null) {
                searchEngine.close();
            }
            db.close();
        }
    }
}
