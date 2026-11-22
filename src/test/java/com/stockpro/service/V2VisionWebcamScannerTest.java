package com.stockpro.service;

import com.stockpro.ai.GroqApiClient;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.VisionAnalysisResult;
import com.stockpro.domain.VisionAnalysisResult.VisionMode;
import com.stockpro.repository.AuditLogRepository;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.SettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic offline integration tests for Branch 05:
 * ZXing camera/image barcode decoding, 6-mode Vision Studio optical analysis,
 * perceptual pHash duplicate photo detection, and Open Food Facts REST resolution + offline fallback.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
class V2VisionWebcamScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void zxingBarcodeDecoderRecognizesGeneratedCode128AndOpenFoodFactsResolvesOnlineAndOffline() throws Exception {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch05-barcode.db"));
        try {
            ProductRepository productRepo = new ProductRepository(db);
            AuditService auditService = new AuditService(new AuditLogRepository(db));
            PerceptualImageHasher hasher = new PerceptualImageHasher(db);

            Product sample = new Product("Sparkling Cola 330ml", 30, StockStatus.IN_STOCK);
            sample.setSku("PRD-8801");
            sample.setBarcode("PRD-8801");
            sample.setBuyPrice(0.6);
            sample.setSellPrice(1.5);
            productRepo.insert(sample);

            String mockOffJson = "{\"status\":1,\"product\":{\"product_name\":\"Organic Oat Milk\",\"brands\":\"Oatly\",\"categories\":\"Beverages, Dairy Alternatives\"}}";
            OpenFoodFactsClient liveMockOff = new OpenFoodFactsClient(url -> mockOffJson);
            var onlineRes = liveMockOff.lookupBarcode("7394376616501");
            assertTrue(onlineRes.found());
            assertEquals("Organic Oat Milk", onlineRes.productName());
            assertEquals("Drinks", onlineRes.category());

            OpenFoodFactsClient offlineOff = new OpenFoodFactsClient(url -> null);
            var cachedRes = offlineOff.lookupBarcode("5449000000996");
            assertTrue(cachedRes.found());
            assertEquals("Coca-Cola Original Taste 330ml", cachedRes.productName());

            VisionStudioService vision = new VisionStudioService(
                    productRepo, hasher, offlineOff, new GroqApiClient(new SettingsRepository(db)), auditService);

            byte[] png = new BarcodeLabelService().generateCode128Png("PRD-8801", 320, 100);
            BufferedImage frame = ImageIO.read(new ByteArrayInputStream(png));
            assertEquals("PRD-8801", vision.decodeBarcode(frame).orElse(""));

            VisionAnalysisResult recognized = vision.analyzeBufferedImage(
                    frame, "frame.png", "", VisionMode.PRODUCT_RECOGNIZER);
            assertEquals("PRD-8801", recognized.detectedBarcode());
            assertTrue(recognized.headline().contains("Sparkling Cola"));
        } finally {
            db.close();
        }
    }

    @Test
    void perceptualHasherDetectsNearDuplicateImagesAndVisionModesDetectGapsDamageAndReceipts() throws Exception {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch05-modes.db"));
        try {
            ProductRepository productRepo = new ProductRepository(db);
            AuditService auditService = new AuditService(new AuditLogRepository(db));
            PerceptualImageHasher hasher = new PerceptualImageHasher(db);
            VisionStudioService vision = new VisionStudioService(
                    productRepo, hasher, new OpenFoodFactsClient(u -> null),
                    new GroqApiClient(new SettingsRepository(db)), auditService);

            Product existing = new Product("Studio Headphones", 12, StockStatus.IN_STOCK);
            existing.setSku("VIS-001");
            existing = productRepo.insert(existing);

            // Create distinctive gradient test photo and register its perceptual fingerprint
            BufferedImage orig = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
            Graphics2D g1 = orig.createGraphics();
            for (int x = 0; x < 64; x++) {
                g1.setColor(new Color(x * 3, 180 - x * 2, 120));
                g1.fillRect(x, 0, 1, 64);
            }
            g1.dispose();
            hasher.registerProductImage(existing.getId(), orig, "headphones.png");

            List<PerceptualImageHasher.DuplicateImageMatch> matches = hasher.findSimilarImages(orig, 8);
            assertFalse(matches.isEmpty());
            assertEquals(existing.getId(), matches.get(0).productId());
            assertEquals(0, matches.get(0).hammingDistance());

            // Empty bright shelf void -> EMPTY_SHELF_GAP_DETECTOR anomaly
            BufferedImage blankShelf = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
            Graphics2D gBlank = blankShelf.createGraphics();
            gBlank.setColor(new Color(235, 238, 242));
            gBlank.fillRect(0, 0, 64, 64);
            gBlank.dispose();
            VisionAnalysisResult gapRes = vision.analyzeBufferedImage(
                    blankShelf, "aisle-gap.png", "", VisionMode.EMPTY_SHELF_GAP_DETECTOR);
            assertTrue(gapRes.anomalyFlagged(), "Uniform blank bright shelf should trigger gap detector");

            // Dark damaged box patch -> DAMAGED_BOX_DETECTOR anomaly
            BufferedImage bruisedBox = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
            Graphics2D gDark = bruisedBox.createGraphics();
            gDark.setColor(new Color(22, 18, 16));
            gDark.fillRect(0, 0, 64, 64);
            gDark.dispose();
            VisionAnalysisResult dmgRes = vision.analyzeBufferedImage(
                    bruisedBox, "crushed.png", "", VisionMode.DAMAGED_BOX_DETECTOR);
            assertTrue(dmgRes.anomalyFlagged(), "Dark bruised box surface should flag damage risk");

            // Receipt image + sidecar OCR text extraction
            Path receiptImg = tempDir.resolve("receipt.png");
            ImageIO.write(orig, "PNG", receiptImg.toFile());
            Files.writeString(tempDir.resolve("receipt.txt"), "Rice_5kg: 15\nOlive_Oil: 6\n");
            VisionAnalysisResult rcptRes = vision.analyzeImageFile(receiptImg, VisionMode.RECEIPT_BILL_SCANNER);
            assertEquals(2, rcptRes.extractedLines().size());
            assertTrue(rcptRes.estimatedItemCount() >= 21);
        } finally {
            db.close();
        }
    }
}
