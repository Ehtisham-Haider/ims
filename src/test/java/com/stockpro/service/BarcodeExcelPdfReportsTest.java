package com.stockpro.service;

import com.stockpro.ai.AiAssistantService;
import com.stockpro.ai.GroqApiClient;
import com.stockpro.domain.Product;
import com.stockpro.ml.OfflineMlEngine;
import com.stockpro.repository.AuditLogRepository;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.SettingsRepository;
import com.stockpro.repository.StockMovementRepository;
import com.stockpro.repository.SupplierRepository;
import com.stockpro.service.command.CommandHistoryManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies USB keyboard-wedge scanner bursts, ZXing Code-128 &amp; QR generation, sticker PDF export,
 * Apache POI Excel export &amp; dry-run import validation, and OpenPDF executive business report generation.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
class BarcodeExcelPdfReportsTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private InventoryService inventoryService;
    private UsbBarcodeScannerListener scannerListener;
    private BarcodeLabelService labelService;
    private SpreadsheetImportExportService spreadsheetService;
    private ExecutivePdfReportGenerator reportGenerator;

    @BeforeEach
    void setUp() {
        dbManager = DatabaseManager.createForFile(tempDir.resolve("reports-barcode-test.db"));
        ProductRepository prodRepo = new ProductRepository(dbManager);
        AuditService auditService = new AuditService(new AuditLogRepository(dbManager));
        inventoryService = new InventoryService(prodRepo, new CommandHistoryManager(), InventoryEventBus.getInstance());
        StockOperationService stockService = new StockOperationService(
                prodRepo, new StockMovementRepository(dbManager), auditService, InventoryEventBus.getInstance());
        scannerListener = new UsbBarcodeScannerListener(prodRepo, stockService);
        labelService = new BarcodeLabelService();
        spreadsheetService = new SpreadsheetImportExportService(inventoryService);
        FinanceService financeService = new FinanceService(dbManager, prodRepo);
        OfflineMlEngine mlEngine = new OfflineMlEngine(dbManager, prodRepo);
        AiAssistantService aiService = new AiAssistantService(
                new GroqApiClient(new SettingsRepository(dbManager)), prodRepo, new SupplierRepository(dbManager), mlEngine);
        reportGenerator = new ExecutivePdfReportGenerator(
                prodRepo, new DashboardMetricsService(dbManager, prodRepo), financeService, aiService);
    }

    @AfterEach
    void tearDown() {
        dbManager.close();
    }

    @Test
    @DisplayName("USB barcode burst listener resolves SKU and supports scan-in restock and scan sale")
    void usbScannerBurstAndModes() {
        Product p = inventoryService.addProductFromInputs(
                "Wireless Mouse", "20", "Electronics", 10.0, 20.0, null).getData();

        long t = 1_000_000L;
        for (char ch : "PRD-1001".toCharArray()) {
            t += 10_000_000L; // 10 ms per scanner keystroke
            scannerListener.recordChar(ch, t);
        }
        String scanned = scannerListener.completeIfBurst(t + 10_000_000L).orElseThrow();
        assertEquals("PRD-1001", scanned);

        assertTrue(scannerListener.handleScannedCode(scanned, UsbBarcodeScannerListener.ScanMode.LOOKUP, "Ahmed").matched());
        assertTrue(scannerListener.handleScannedCode(scanned, UsbBarcodeScannerListener.ScanMode.STOCK_IN, "Ahmed").matched());
        assertEquals(21, inventoryService.findById(p.getId()).orElseThrow().getQuantity());
        assertTrue(scannerListener.handleScannedCode(scanned, UsbBarcodeScannerListener.ScanMode.SALE_DEDUCT, "Ahmed").matched());
        assertEquals(20, inventoryService.findById(p.getId()).orElseThrow().getQuantity());
    }

    @Test
    @DisplayName("Generates ZXing Code-128 & QR PNGs, printable sticker PDF, Excel roundtrip, and Executive Report PDF")
    void zxingExcelRoundtripAndExecutiveReportPdf() throws Exception {
        Product rice = inventoryService.addProductFromInputs(
                "Rice 5kg", "120", "Groceries", 2.50, 3.50, null).getData();
        Product milk = inventoryService.addProductFromInputs(
                "Milk 1L", "5", "Dairy", 1.00, 1.50, null).getData();

        byte[] code128 = labelService.generateCode128Png(rice.getSku(), 220, 70);
        byte[] qr = labelService.generateQrCodePng(labelService.buildQrPayload(rice), 140);
        assertEquals((byte) 0x89, code128[0]);
        assertTrue(code128.length > 60);
        assertEquals((byte) 0x89, qr[0]);
        assertTrue(qr.length > 100);

        Path stickerPdf = tempDir.resolve("stickers.pdf");
        labelService.exportStickerSheetPdf(List.of(rice, milk), stickerPdf);
        assertTrue(Files.exists(stickerPdf) && Files.size(stickerPdf) > 1000);

        // Excel export + dry-run import validation (including one intentionally invalid CSV row)
        Path xlsxOut = tempDir.resolve("inventory.xlsx");
        spreadsheetService.exportToExcel(List.of(rice, milk), xlsxOut);
        assertTrue(Files.exists(xlsxOut) && Files.size(xlsxOut) > 1000);

        SpreadsheetImportExportService.DryRunImportReport dryXlsx = spreadsheetService.dryRunImport(xlsxOut);
        assertEquals(2, dryXlsx.validCount());
        assertEquals(0, dryXlsx.errorCount());

        Path mixedCsv = tempDir.resolve("mixed.csv");
        Files.writeString(mixedCsv,
                "SKU,Name,Category,Quantity,LowStockThreshold,BuyPrice,SellPrice\n"
                        + "NEW-1,Green Tea,Drinks,30,5,1.5,2.5\n"
                        + "BAD-1,,Snacks,-9,5,1.0,2.0\n");
        SpreadsheetImportExportService.DryRunImportReport dryCsv = spreadsheetService.dryRunImport(mixedCsv);
        assertEquals(1, dryCsv.validCount());
        assertEquals(1, dryCsv.errorCount());
        assertEquals(1, spreadsheetService.commitImport(dryCsv));

        // Branded Executive Report PDF
        Path reportPdf = tempDir.resolve("business-report.pdf");
        reportGenerator.generateBusinessReport("Last 30 Days", reportPdf);
        assertTrue(Files.exists(reportPdf) && Files.size(reportPdf) > 1500);
    }
}
