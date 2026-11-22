package com.stockpro.service;

import com.stockpro.domain.Product;
import com.stockpro.domain.User;
import com.stockpro.dsa.CatalogSearchEngine;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.SettingsRepository;
import com.stockpro.repository.UserRepository;
import com.stockpro.service.command.CommandHistoryManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies product query normalization, Pexels JSON parsing, offline category artwork generation,
 * demo catalog image population, and official author account seeding/migration.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
class ProductImageServiceTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private ProductRepository productRepo;
    private SettingsRepository settingsRepo;
    private ProductImageService imageService;

    @BeforeEach
    void setUp() {
        dbManager = DatabaseManager.createForFile(tempDir.resolve("image-test.db"));
        productRepo = new ProductRepository(dbManager);
        settingsRepo = new SettingsRepository(dbManager);
        settingsRepo.set(ProductImageService.SETTINGS_KEY_PEXELS_API, "");
        imageService = new ProductImageService(settingsRepo, productRepo, tempDir.resolve("image-cache"));
    }

    @AfterEach
    void tearDown() {
        dbManager.close();
    }

    @Test
    @DisplayName("Normalizes demo and custom queries consistently and persists Pexels API key safely")
    void queryNormalizationSlugAndKeyPersistence() {
        assertEquals("milk bottle", ProductImageService.buildSearchQuery("Fresh Milk 1L #17", "Dairy"));
        assertEquals(
                ProductImageService.buildSearchQuery("Fresh Milk 1L #17", "Dairy"),
                ProductImageService.buildSearchQuery("Organic Milk 1L #117", "Dairy")
        );
        assertEquals("wireless gaming headset", ProductImageService.buildSearchQuery("Wireless Gaming Headset #2", "Electronics"));
        assertEquals("laundry detergent bottle", ProductImageService.buildSearchQuery("Lavender Laundry Detergent Gel", "Household"));
        assertEquals("usb-c-cable-adapter", ProductImageService.toSlug("USB-C Cable / Adapter!"));

        int slot = ProductImageService.variantSlot("Royal Basmati Rice 5kg");
        assertTrue(slot >= 0 && slot < ProductImageService.getMaxVariantsPerQuery());
        assertEquals(slot, ProductImageService.variantSlot("Royal Basmati Rice 5kg #42"));

        assertFalse(imageService.isApiKeyConfigured());
        imageService.saveApiKey("pexels_sample_key_001");
        assertTrue(imageService.isApiKeyConfigured());
        assertEquals("pexels_sample_key_001", imageService.resolveApiKey());

        imageService.clearApiKey();
        assertFalse(imageService.isApiKeyConfigured());
    }

    @Test
    @DisplayName("Extracts multi-photo Pexels URLs, creates v2 icon artwork, and upgrades legacy v1 placeholders")
    void pexelsJsonExtractionAndOfflinePngGeneration() throws Exception {
        String sampleJson = """
                {
                  "photos": [
                    {
                      "id": 100,
                      "src": {
                        "medium": "https://images.pexels.com/photos/100/pexels-photo-100.jpeg?auto=compress",
                        "small": "https://images.pexels.com/photos/100/small.jpeg"
                      }
                    },
                    {
                      "id": 101,
                      "src": {
                        "medium": "https://images.pexels.com/photos/101/pexels-photo-101.jpeg?auto=compress"
                      }
                    }
                  ]
                }
                """;
        assertEquals(
                "https://images.pexels.com/photos/100/pexels-photo-100.jpeg?auto=compress",
                ProductImageService.extractPhotoUrlFromPexelsJson(sampleJson)
        );
        List<String> multi = ProductImageService.extractPhotoUrlsFromPexelsJson(sampleJson, 5);
        assertEquals(List.of(
                "https://images.pexels.com/photos/100/pexels-photo-100.jpeg?auto=compress",
                "https://images.pexels.com/photos/101/pexels-photo-101.jpeg?auto=compress"), multi);
        assertEquals(1, ProductImageService.extractPhotoUrlsFromPexelsJson(sampleJson, 1).size());
        assertNull(ProductImageService.extractPhotoUrlFromPexelsJson("{\"photos\":[]}"));

        String path = imageService.resolveImagePath("Wheat Bread #5", "Bakery", false);
        assertNotNull(path);
        assertTrue(Files.exists(Path.of(path)));
        assertTrue(ProductImageService.isGeneratedPlaceholder(path));
        assertFalse(ProductImageService.isLegacyGeneratedPlaceholder(path));
        assertTrue(path.replace('\\', '/').contains("/generated/v2/"));
        assertFalse(imageService.needsImageResolution(path));

        Path legacyFile = tempDir.resolve("image-cache").resolve("generated").resolve("wheat-bread.png");
        Files.createDirectories(legacyFile.getParent());
        Files.writeString(legacyFile, "legacy-stub");
        assertTrue(ProductImageService.isLegacyGeneratedPlaceholder(legacyFile.toString()));
        assertTrue(imageService.needsImageResolution(legacyFile.toString()));

        imageService.saveApiKey("pexels_sample_key_001");
        assertTrue(imageService.needsImageResolution(path));
    }

    @Test
    @DisplayName("Starter and demo loaders produce diverse consecutive products, accurate categories, and distinct v2 images")
    void assignsImagesToStarterAndBatchDemoProducts() {
        CatalogSearchEngine searchEngine = new CatalogSearchEngine(productRepo, InventoryEventBus.getInstance());
        try {
            InventoryService inv = new InventoryService(productRepo, new CommandHistoryManager(), InventoryEventBus.getInstance());
            inv.setProductImageService(imageService);

            int starter = inv.loadStarterDemoItems();
            assertEquals(6, starter);
            for (Product p : inv.getAllProducts()) {
                assertNotNull(p.getPhotoPath(), "Starter item should have photoPath: " + p.getName());
                assertTrue(Files.exists(Path.of(p.getPhotoPath())), "Starter image should exist: " + p.getPhotoPath());
                assertTrue(p.getPhotoPath().replace('\\', '/').contains("/generated/v2/"));
            }

            DemoDataService demoService = new DemoDataService(
                    dbManager, searchEngine, InventoryEventBus.getInstance(), imageService);
            int templateCount = DemoDataService.getTemplateCount();
            assertTrue(templateCount >= 24);
            int demoCount = demoService.generateRealisticDemoStore(templateCount);
            assertEquals(templateCount, demoCount);

            List<Product> seeded = productRepo.findAll(false).stream()
                    .sorted(Comparator.comparing(Product::getSku))
                    .toList();
            assertEquals(templateCount, seeded.size());

            Set<String> distinctNames = new HashSet<>();
            Set<String> distinctPhotos = new HashSet<>();
            Set<String> firstEightCategories = new HashSet<>();
            for (int i = 0; i < seeded.size(); i++) {
                Product p = seeded.get(i);
                assertNotNull(p.getPhotoPath(), "Demo item should have photoPath: " + p.getName());
                assertTrue(Files.exists(Path.of(p.getPhotoPath())), "Demo image should exist: " + p.getPhotoPath());
                assertTrue(p.getPhotoPath().replace('\\', '/').contains("/generated/v2/"));
                distinctNames.add(p.getName());
                distinctPhotos.add(p.getPhotoPath());
                if (i < 8) {
                    firstEightCategories.add(p.getCategory());
                }
                if (p.getName().contains("Basmati Rice 5kg")) {
                    assertEquals("Groceries", p.getCategory());
                } else if (p.getName().contains("Whole Milk 1L")) {
                    assertEquals("Dairy", p.getCategory());
                } else if (p.getName().contains("Bluetooth Speaker")) {
                    assertEquals("Electronics", p.getCategory());
                }
            }
            assertEquals(templateCount, distinctNames.size(), "All " + templateCount + " consecutive demo items should have distinct names");
            assertEquals(templateCount, distinctPhotos.size(), "All " + templateCount + " consecutive demo items should have distinct image paths");
            assertTrue(firstEightCategories.size() >= 6, "First 8 rows should span at least 6 categories");
        } finally {
            searchEngine.close();
        }
    }

    @Test
    @DisplayName("Seeds and migrates official author accounts without Syed Ahmed")
    void seedsAndMigratesOfficialAuthorAccounts() throws Exception {
        UserRepository userRepo = new UserRepository(dbManager);
        User owner = userRepo.findByUsername("admin").orElseThrow();
        User manager = userRepo.findByUsername("manager").orElseThrow();
        assertEquals("Syed Ehtisham Haider Bukhari", owner.getFullName());
        assertEquals("Faheen Ahmad Khan", manager.getFullName());

        Path dbPath = dbManager.getDatabaseFilePath();
        try (var conn = dbManager.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE users SET full_name = 'Syed Ahmed' WHERE username = 'admin'");
            stmt.executeUpdate("UPDATE users SET full_name = 'Ehtisham Haider' WHERE username = 'manager'");
        }
        dbManager.close();

        try (DatabaseManager reopened = DatabaseManager.createForFile(dbPath)) {
            UserRepository reopenedUsers = new UserRepository(reopened);
            assertEquals("Syed Ehtisham Haider Bukhari", reopenedUsers.findByUsername("admin").orElseThrow().getFullName());
            assertEquals("Faheen Ahmad Khan", reopenedUsers.findByUsername("manager").orElseThrow().getFullName());
        }
    }
}
