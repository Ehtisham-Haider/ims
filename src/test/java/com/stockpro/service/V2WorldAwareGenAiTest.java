package com.stockpro.service;

import com.stockpro.ai.GroqApiClient;
import com.stockpro.ai.WorldAwareGenAiService;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.WorldAwareModels.HolidayDemandBoost;
import com.stockpro.domain.WorldAwareModels.LocalTrendMatch;
import com.stockpro.domain.WorldAwareModels.ProductCopySeo;
import com.stockpro.domain.WorldAwareModels.WeatherStockAlert;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.SettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic offline integration tests for Branch 08:
 * Open-Meteo weather stocking rules, Nager.Date &amp; cultural festival planner,
 * Google Trends RSS matcher, product SEO copywriting, monthly narrative,
 * clearance promos, and in-app help guide.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
class V2WorldAwareGenAiTest {

    @TempDir
    Path tempDir;

    @Test
    void openMeteoNagerDateAndGoogleTrendsParseLiveMocksAndFallBackCleanlyOffline() {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch08-world.db"));
        try {
            ProductRepository productRepo = new ProductRepository(db);
            DashboardMetricsService metricsService = new DashboardMetricsService(db, productRepo);
            FinanceService financeService = new FinanceService(db, productRepo);
            GroqApiClient groqNoKey = new GroqApiClient(new SettingsRepository(db));

            Product icedTea = new Product("Zero Sugar Sparkling Cola", 40, StockStatus.IN_STOCK);
            icedTea.setSku("WA-COLA");
            icedTea.setCategory("Drinks");
            icedTea.setTags("beverage, chilled");
            icedTea.setBuyPrice(0.6);
            icedTea.setSellPrice(1.5);
            productRepo.insert(icedTea);

            String mockHttp = null;
            WorldAwareGenAiService mockLive = new WorldAwareGenAiService(
                    productRepo, groqNoKey, metricsService, financeService, url -> {
                if (url.contains("open-meteo.com")) {
                    return "{\"daily\":{\"temperature_2m_max\":[35.0,37.2,36.0],\"precipitation_sum\":[0.0,0.0,1.0]}}";
                }
                if (url.contains("date.nager.at")) {
                    return "[{\"date\":\"2026-12-25\",\"name\":\"Quaid Day\"}]";
                }
                if (url.contains("trends.google.com")) {
                    return "<rss><channel><item><title>Sparkling Cola</title></item></channel></rss>";
                }
                return mockHttp;
            });

            WeatherStockAlert alert = mockLive.analyzeWeatherStocking(31.52, 74.35);
            assertEquals("OPEN_METEO_LIVE", alert.source());
            assertTrue(alert.maxTempC() >= 37.0);
            assertEquals(1.35, alert.suggestedMultiplier(), 0.01);
            assertTrue(alert.matchedSkus().contains("Zero Sugar Sparkling Cola"));

            List<HolidayDemandBoost> holidays = mockLive.forecastUpcomingHolidays("PK", LocalDate.of(2026, 12, 10));
            assertEquals("NAGER_DATE_LIVE", holidays.get(0).source());
            assertEquals(15L, holidays.get(0).daysAway());

            List<LocalTrendMatch> trends = mockLive.spotLocalTrends("PK");
            assertEquals("GOOGLE_TRENDS_RSS", trends.get(0).source());
            assertTrue(trends.get(0).matchingProductNames().contains("Zero Sugar Sparkling Cola"));

            // Offline fallback check
            WorldAwareGenAiService offline = new WorldAwareGenAiService(
                    productRepo, groqNoKey, metricsService, financeService, u -> null);
            assertEquals("OFFLINE_SEASONAL_FALLBACK", offline.analyzeWeatherStocking(31.52, 74.35).source());
            assertFalse(offline.forecastUpcomingHolidays("PK", LocalDate.now()).isEmpty());
            assertFalse(offline.spotLocalTrends("PK").isEmpty());
        } finally {
            db.close();
        }
    }

    @Test
    void genAiCopywriterExecutiveNarrativeClearancePromoAndHelpGuideWorkDeterministically() {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch08-genai.db"));
        try {
            ProductRepository productRepo = new ProductRepository(db);
            DashboardMetricsService metricsService = new DashboardMetricsService(db, productRepo);
            FinanceService financeService = new FinanceService(db, productRepo);
            GroqApiClient groqNoKey = new GroqApiClient(new SettingsRepository(db));

            Product coffee = new Product("Colombian Arabica Beans", 5, StockStatus.LOW_STOCK);
            coffee.setSku("WA-COFFEE");
            coffee.setCategory("Drinks");
            coffee.setBuyPrice(6.5);
            coffee.setSellPrice(14.0);
            productRepo.insert(coffee);

            WorldAwareGenAiService gen = new WorldAwareGenAiService(
                    productRepo, groqNoKey, metricsService, financeService, u -> null);

            ProductCopySeo seo = gen.generateProductDescriptionAndSeo(coffee.getName(), coffee.getCategory(), coffee.getSellPrice());
            assertTrue(seo.description().contains("Colombian Arabica Beans"));
            assertTrue(seo.seoTags().contains("drinks"));

            String narrative = gen.writeMonthlyExecutiveNarrative("Last 30 Days");
            assertTrue(narrative.contains("Executive Summary"));

            String promo = gen.generateClearancePromoCopy("WhatsApp", coffee, 25);
            assertTrue(promo.contains("25% OFF"));
            assertTrue(promo.contains("10.50"));

            String help = gen.answerInAppHelpQuestion("How do I use the 2D warehouse map?");
            assertTrue(help.contains("Dijkstra"));
        } finally {
            db.close();
        }
    }
}
