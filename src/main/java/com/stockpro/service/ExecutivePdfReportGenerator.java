package com.stockpro.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;
import com.stockpro.ai.AiAssistantService;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockMovement;
import com.stockpro.repository.ProductRepository;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a print-ready single-page A4 branded Executive Business Report PDF
 * matching
 * {@code Screens and popups/Generated Report.png} with curved header/footer
 * accents, pastel KPI
 * sparkline cards, high-DPI trend &amp; category charts, Inventory Health
 * gauge, Top Products table,
 * Recent Activities status pills, AI Insights callout card, and Store Manager
 * signature.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class ExecutivePdfReportGenerator {
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' hh:mm a");
    private static final DateTimeFormatter MOVE_FMT = DateTimeFormatter.ofPattern("MMM dd, hh:mm a");
    private static final DateTimeFormatter PERIOD_DATE_FMT = DateTimeFormatter.ofPattern("MMM d");

    private static final Color NAVY_HEADER = new Color(23, 28, 46);
    private static final Color INDIGO = new Color(79, 70, 229);
    private static final Color PURPLE = new Color(124, 58, 237);
    private static final Color EMERALD = new Color(16, 185, 129);
    private static final Color AMBER = new Color(245, 158, 11);
    private static final Color ROSE = new Color(239, 68, 68);
    private static final Color SKY = new Color(14, 165, 233);
    private static final Color SLATE_900 = new Color(15, 23, 42);
    private static final Color SLATE_600 = new Color(71, 85, 105);
    private static final Color SLATE_400 = new Color(148, 163, 184);
    private static final Color BORDER_LIGHT = new Color(226, 232, 240);

    private final ProductRepository productRepository;
    private final DashboardMetricsService metricsService;
    private final FinanceService financeService;
    private final AiAssistantService aiAssistantService;
    private com.stockpro.ai.WorldAwareGenAiService worldAwareGenAiService;

    /**
     * Creates an executive PDF report generator wired to live analytics and AI
     * insight services.
     *
     * @param productRepository  product repository
     * @param metricsService     dashboard metrics aggregator
     * @param financeService     financial engine
     * @param aiAssistantService AI briefing generator
     */
    public ExecutivePdfReportGenerator(
            ProductRepository productRepository,
            DashboardMetricsService metricsService,
            FinanceService financeService,
            AiAssistantService aiAssistantService) {
        this.productRepository = productRepository;
        this.metricsService = metricsService;
        this.financeService = financeService;
        this.aiAssistantService = aiAssistantService;
    }

    /**
     * Attaches the optional World-Aware GenAI executive narrative generator.
     *
     * @param worldAwareGenAiService world-aware GenAI service instance
     */
    public void setWorldAwareGenAiService(com.stockpro.ai.WorldAwareGenAiService worldAwareGenAiService) {
        this.worldAwareGenAiService = worldAwareGenAiService;
    }

    /**
     * Generates the single-page branded A4 Business Report PDF matching the
     * reference design.
     *
     * @param periodLabel date filter description (e.g. {@code "Last 7 Days"},
     *                    {@code "Last 30 Days"}, {@code "All Time"})
     * @param outputPath  target PDF file path
     * @return written PDF {@link Path}
     */
    public Path generateBusinessReport(String periodLabel, Path outputPath) {
        try {
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }
            AppSession session = AppSession.getInstance();
            int periodDays = resolvePeriodDays(periodLabel);
            DashboardMetricsService.DashboardSnapshot snap = metricsService.loadSnapshot(periodDays);
            FinanceService.FinanceSummary fin = financeService.computeSummary(60, session.getTaxRatePercent());
            List<Product> products = productRepository.findAll(false);
            String rawBriefing = aiAssistantService.generateMorningBriefing();
            if (worldAwareGenAiService != null) {
                rawBriefing = rawBriefing + "\n" + worldAwareGenAiService.writeMonthlyExecutiveNarrative(periodLabel);
            }

            try (FileOutputStream fos = new FileOutputStream(outputPath.toFile());
                    Document doc = new Document(PageSize.A4, 0, 0, 0, 0)) {
                PdfWriter writer = PdfWriter.getInstance(doc, fos);
                doc.open();

                PdfContentByte cb = writer.getDirectContent();
                BaseFont bfReg = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
                BaseFont bfBold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
                float pageW = PageSize.A4.getWidth(); // 595 pt
                float pageH = PageSize.A4.getHeight(); // 842 pt
                float margin = 24f;
                float contentW = pageW - (margin * 2f); // 547 pt

                // 0. Subtle soft-gray page backdrop & top/bottom purple wave ribbons
                cb.setColorFill(new Color(248, 250, 253));
                cb.rectangle(0, 0, pageW, pageH);
                cb.fill();
                drawWaveRibbons(cb, pageW, pageH);

                // 1. Masthead Row (y: 774 .. 826)
                drawMasthead(cb, bfReg, bfBold, session, periodLabel, periodDays, margin, pageW);

                // 2. Top 4 Pastel KPI Sparkline Cards (y: 690 .. 760)
                int totalUnits = products.stream().mapToInt(Product::getQuantity).sum();
                drawKpiStrip(cb, bfReg, bfBold, session, snap, fin, totalUnits, margin, contentW);

                // 3. Charts Row (y: 506 .. 678)
                float chartRowY = 506f;
                float chartRowH = 172f;
                float gap = 12f;
                float leftChartW = 328f;
                float rightChartW = contentW - leftChartW - gap;

                drawTrendCard(cb, bfReg, bfBold, margin, chartRowY, leftChartW, chartRowH, snap, session);
                drawCategoryCard(cb, bfReg, bfBold, margin + leftChartW + gap, chartRowY, rightChartW, chartRowH,
                        products);

                // 4. Top Selling Products + Inventory Health Row (y: 312 .. 494)
                float midRowY = 312f;
                float midRowH = 182f;
                float topProdW = 344f;
                float healthW = contentW - topProdW - gap;

                drawTopProductsCard(cb, bfReg, bfBold, margin, midRowY, topProdW, midRowH, products, session);
                drawInventoryHealthCard(cb, bfReg, bfBold, margin + topProdW + gap, midRowY, healthW, midRowH, snap);

                // 5. Recent Activities + AI Insights Row (y: 112 .. 300)
                float botRowY = 112f;
                float botRowH = 188f;
                float actW = 292f;
                float aiW = contentW - actW - gap;

                drawRecentActivitiesCard(cb, bfReg, bfBold, margin, botRowY, actW, botRowH, snap);
                drawAiInsightsCard(cb, bfReg, bfBold, margin + actW + gap, botRowY, aiW, botRowH, rawBriefing, snap,
                        products);

                // 6. Footer & Manager Signature (y: 34 .. 96)
                drawFooter(cb, bfReg, bfBold, session, margin, pageW);
            }
            return outputPath;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate executive PDF report", ex);
        }
    }

    private int resolvePeriodDays(String periodLabel) {
        if (periodLabel == null) {
            return 30;
        }
        String lower = periodLabel.toLowerCase();
        if (lower.contains("7")) {
            return 7;
        }
        if (lower.contains("all")) {
            return 0;
        }
        return 30;
    }

    private void drawWaveRibbons(PdfContentByte cb, float w, float h) {
        // Subtle top soft violet accent arc under masthead
        cb.saveState();
        cb.setColorFill(new Color(224, 231, 255));
        cb.moveTo(0, h - 76);
        cb.curveTo(w * 0.30f, h - 90, w * 0.72f, h - 68, w, h - 78);
        cb.lineTo(w, h - 70);
        cb.curveTo(w * 0.72f, h - 62, w * 0.30f, h - 82, 0, h - 71);
        cb.closePathFillStroke();

        // Bottom signature wave matching Generated Report.png
        cb.setColorFill(new Color(199, 210, 254));
        cb.moveTo(0, 0);
        cb.lineTo(0, 32);
        cb.curveTo(w * 0.25f, 12, w * 0.70f, 38, w, 18);
        cb.lineTo(w, 0);
        cb.closePath();
        cb.fill();

        cb.setColorFill(new Color(99, 102, 241));
        cb.moveTo(0, 0);
        cb.lineTo(0, 22);
        cb.curveTo(w * 0.32f, 4, w * 0.76f, 28, w, 11);
        cb.lineTo(w, 0);
        cb.closePath();
        cb.fill();
        cb.restoreState();
    }

    private void drawMasthead(
            PdfContentByte cb, BaseFont bfReg, BaseFont bfBold,
            AppSession session, String periodLabel, int periodDays, float margin, float pageW) throws Exception {
        // White top bar surface
        cb.setColorFill(Color.WHITE);
        cb.rectangle(0, 770, pageW, 72);
        cb.fill();

        // Crisp 3D vector cube logo
        Image logoImg = toPdfImage(renderCubeLogo(110));
        logoImg.setAbsolutePosition(margin, 779);
        logoImg.scaleAbsolute(38, 38);
        cb.addImage(logoImg);

        cb.beginText();
        cb.setFontAndSize(bfBold, 17);
        cb.setColorFill(NAVY_HEADER);
        cb.showTextAligned(Element.ALIGN_LEFT, "StockPro", margin + 46, 800, 0);
        cb.setFontAndSize(bfReg, 7.5f);
        cb.setColorFill(SLATE_600);
        cb.showTextAligned(Element.ALIGN_LEFT, "Smart Inventory. Smarter Business.", margin + 46, 787, 0);

        // Vertical divider
        cb.setColorStroke(BORDER_LIGHT);
        cb.setLineWidth(1.2f);
        cb.endText();
        cb.moveTo(margin + 188, 781);
        cb.lineTo(margin + 188, 819);
        cb.stroke();

        cb.beginText();
        cb.setFontAndSize(bfBold, 12.5f);
        cb.setColorFill(SLATE_900);
        cb.showTextAligned(Element.ALIGN_LEFT, truncate(session.getStoreName() + " • Inventory System", 34),
                margin + 200, 801, 0);
        cb.setFontAndSize(bfReg, 10.5f);
        cb.setColorFill(SLATE_600);
        cb.showTextAligned(Element.ALIGN_LEFT, "Executive Business Report", margin + 200, 786, 0);
        cb.endText();

        // Right Period Box
        float pBoxW = 148f;
        float pBoxX = pageW - margin - pBoxW;
        drawRoundedBox(cb, pBoxX, 780, pBoxW, 40, 8, new Color(241, 245, 254), new Color(219, 228, 252));

        LocalDate today = LocalDate.now();
        String rangeText = periodDays <= 0
                ? "All Time History"
                : today.minusDays(Math.max(1, periodDays - 1)).format(PERIOD_DATE_FMT) + " – "
                        + today.format(PERIOD_DATE_FMT) + ", " + today.getYear();
        cb.beginText();
        cb.setFontAndSize(bfReg, 7.8f);
        cb.setColorFill(SLATE_600);
        cb.showTextAligned(Element.ALIGN_LEFT, "Report Period (" + (periodLabel != null ? periodLabel : "30d") + ")",
                pBoxX + 12, 805, 0);
        cb.setFontAndSize(bfBold, 9.2f);
        cb.setColorFill(NAVY_HEADER);
        cb.showTextAligned(Element.ALIGN_LEFT, rangeText, pBoxX + 12, 790, 0);
        cb.endText();
    }

    private void drawKpiStrip(
            PdfContentByte cb, BaseFont bfReg, BaseFont bfBold,
            AppSession session, DashboardMetricsService.DashboardSnapshot snap,
            FinanceService.FinanceSummary fin, int totalUnits,
            float margin, float contentW) throws Exception {
        float y = 690f;
        float h = 66f;
        float gap = 10f;
        float w = (contentW - gap * 3f) / 4f;

        String[] titles = { "Total Stock Units", "Inventory Value", "Est. Total Profit", "Active / Alert SKUs" };
        String[] values = {
                String.format("%,d", totalUnits),
                formatCompactMoney(session, fin.totalCostValue()),
                formatCompactMoney(session, fin.totalPotentialProfit()),
                snap.totalProducts() + " (" + (snap.lowStockCount() + snap.outOfStockCount()) + " alerts)"
        };
        String[] subNotes = {
                snap.inStockCount() + " healthy items",
                String.format("Retail: %s", formatCompactMoney(session, fin.totalRetailValue())),
                String.format("%.1f%% avg margin", fin.averageMarginPercent()),
                snap.expiringSoonCount() + " expiring <= 7d"
        };
        Color[] bgTints = {
                new Color(239, 244, 255),
                new Color(234, 250, 243),
                new Color(255, 248, 232),
                new Color(254, 239, 241)
        };
        Color[] borders = {
                new Color(212, 224, 252),
                new Color(198, 238, 217),
                new Color(253, 230, 179),
                new Color(252, 207, 211)
        };
        Color[] accents = { INDIGO, EMERALD, AMBER, ROSE };

        for (int i = 0; i < 4; i++) {
            float x = margin + i * (w + gap);
            drawRoundedBox(cb, x, y, w, h, 9, bgTints[i], borders[i]);

            // Subtle translucent sparkline wave at bottom of KPI card
            cb.saveState();
            cb.setColorStroke(accents[i]);
            cb.setLineWidth(1.1f);
            cb.moveTo(x + 8, y + 6);
            cb.curveTo(x + w * 0.35f, y + 14, x + w * 0.65f, y + 4, x + w - 8, y + 12);
            cb.stroke();
            cb.restoreState();

            // Rounded icon circle badge
            drawRoundedBox(cb, x + 10, y + 26, 26, 26, 7, accents[i], accents[i]);
            cb.beginText();
            cb.setFontAndSize(bfBold, 11);
            cb.setColorFill(Color.WHITE);
            String sym = switch (i) {
                case 0 -> "#";
                case 1 -> "$";
                case 2 -> "%";
                default -> "!";
            };
            cb.showTextAligned(Element.ALIGN_CENTER, sym, x + 23, y + 35, 0);

            // Title & Value
            cb.setFontAndSize(bfReg, 8.2f);
            cb.setColorFill(SLATE_600);
            cb.showTextAligned(Element.ALIGN_LEFT, titles[i], x + 43, y + 45, 0);
            cb.setFontAndSize(bfBold, 13.0f);
            cb.setColorFill(NAVY_HEADER);
            cb.showTextAligned(Element.ALIGN_LEFT, truncate(values[i], 14), x + 43, y + 29, 0);

            cb.setFontAndSize(bfBold, 7.6f);
            cb.setColorFill(accents[i].darker());
            cb.showTextAligned(Element.ALIGN_LEFT, truncate(subNotes[i], 22), x + 12, y + 14, 0);
            cb.endText();
        }
    }

    private void drawTrendCard(
            PdfContentByte cb, BaseFont bfReg, BaseFont bfBold,
            float x, float y, float w, float h,
            DashboardMetricsService.DashboardSnapshot snap, AppSession session) throws Exception {
        drawSectionCardShell(cb, bfBold, x, y, w, h, "Sales & Profit Trend", "Sales   Profit", true);
        BufferedImage chartImg = renderTrendChartImage(snap.trendPoints(), session, (int) (w * 3),
                (int) ((h - 30) * 3));
        Image img = toPdfImage(chartImg);
        img.setAbsolutePosition(x + 8, y + 6);
        img.scaleAbsolute(w - 16, h - 34);
        cb.addImage(img);
    }

    private void drawCategoryCard(
            PdfContentByte cb, BaseFont bfReg, BaseFont bfBold,
            float x, float y, float w, float h, List<Product> products) throws Exception {
        drawSectionCardShell(cb, bfBold, x, y, w, h, "Inventory by Category", null, false);
        BufferedImage donutImg = renderCategoryDonutImage(products, (int) ((w - 12) * 3), (int) ((h - 32) * 3));
        Image img = toPdfImage(donutImg);
        img.setAbsolutePosition(x + 6, y + 6);
        img.scaleAbsolute(w - 12, h - 34);
        cb.addImage(img);
    }

    private void drawTopProductsCard(
            PdfContentByte cb, BaseFont bfReg, BaseFont bfBold,
            float x, float y, float w, float h, List<Product> products, AppSession session) throws Exception {
        drawSectionCardShell(cb, bfBold, x, y, w, h, "Top Products by Valuation", null, false);

        float headerY = y + h - 42;
        cb.beginText();
        cb.setFontAndSize(bfBold, 8.2f);
        cb.setColorFill(SLATE_600);
        cb.showTextAligned(Element.ALIGN_LEFT, "#", x + 12, headerY, 0);
        cb.showTextAligned(Element.ALIGN_LEFT, "Product", x + 32, headerY, 0);
        cb.showTextAligned(Element.ALIGN_LEFT, "Category", x + 178, headerY, 0);
        cb.showTextAligned(Element.ALIGN_RIGHT, "Qty", x + 266, headerY, 0);
        cb.showTextAligned(Element.ALIGN_RIGHT, "Retail Value", x + w - 14, headerY, 0);
        cb.endText();

        cb.setColorStroke(BORDER_LIGHT);
        cb.setLineWidth(0.7f);
        cb.moveTo(x + 10, headerY - 5);
        cb.lineTo(x + w - 10, headerY - 5);
        cb.stroke();

        List<Product> top = products.stream()
                .sorted(Comparator.comparingDouble(Product::getRetailValuation).reversed())
                .limit(5)
                .toList();

        if (top.isEmpty()) {
            cb.beginText();
            cb.setFontAndSize(bfReg, 9.5f);
            cb.setColorFill(SLATE_400);
            cb.showTextAligned(Element.ALIGN_CENTER, "No products in inventory yet", x + w / 2f, y + h / 2f - 10, 0);
            cb.endText();
            return;
        }

        Color[] rowAccents = { INDIGO, EMERALD, SKY, AMBER, PURPLE };
        float rowY = headerY - 24;
        for (int i = 0; i < top.size(); i++) {
            Product p = top.get(i);
            drawRoundedBox(cb, x + 30, rowY - 3, 14, 14, 4, new Color(238, 242, 255),
                    rowAccents[i % rowAccents.length]);

            cb.beginText();
            cb.setFontAndSize(bfBold, 8.5f);
            cb.setColorFill(SLATE_600);
            cb.showTextAligned(Element.ALIGN_LEFT, String.valueOf(i + 1), x + 12, rowY + 1, 0);

            cb.setFontAndSize(bfBold, 8.5f);
            cb.setColorFill(SLATE_900);
            cb.showTextAligned(Element.ALIGN_LEFT, truncate(p.getName(), 24), x + 50, rowY + 1, 0);

            cb.setFontAndSize(bfReg, 8.2f);
            cb.setColorFill(SLATE_600);
            cb.showTextAligned(Element.ALIGN_LEFT, truncate(p.getCategory(), 14), x + 178, rowY + 1, 0);
            cb.showTextAligned(Element.ALIGN_RIGHT, String.valueOf(p.getQuantity()), x + 266, rowY + 1, 0);

            cb.setFontAndSize(bfBold, 8.5f);
            cb.setColorFill(NAVY_HEADER);
            cb.showTextAligned(Element.ALIGN_RIGHT, formatCompactMoney(session, p.getRetailValuation()), x + w - 14,
                    rowY + 1, 0);
            cb.endText();

            if (i < top.size() - 1) {
                cb.setColorStroke(new Color(241, 245, 249));
                cb.setLineWidth(0.6f);
                cb.moveTo(x + 10, rowY - 9);
                cb.lineTo(x + w - 10, rowY - 9);
                cb.stroke();
            }
            rowY -= 25f;
        }
    }

    private void drawInventoryHealthCard(
            PdfContentByte cb, BaseFont bfReg, BaseFont bfBold,
            float x, float y, float w, float h, DashboardMetricsService.DashboardSnapshot snap) throws Exception {
        drawSectionCardShell(cb, bfBold, x, y, w, h, "Inventory Health", null, false);
        int pct = (int) Math.round(snap.inStockPercent());
        BufferedImage gaugeImg = renderHealthGaugeImage(pct, (int) ((w - 20) * 3), 250);
        Image img = toPdfImage(gaugeImg);
        img.setAbsolutePosition(x + 10, y + 66);
        img.scaleAbsolute(w - 20, 84);
        cb.addImage(img);

        String[] labels = { "Low Stock Items:", "Out of Stock Items:", "Expiring Soon (7d):" };
        int[] counts = { snap.lowStockCount(), snap.outOfStockCount(), snap.expiringSoonCount() };
        Color[] dots = { EMERALD, AMBER, INDIGO };
        float ly = y + 48;
        for (int i = 0; i < 3; i++) {
            cb.setColorFill(dots[i]);
            cb.circle(x + 20, ly + 3, 4);
            cb.fill();
            cb.beginText();
            cb.setFontAndSize(bfReg, 8.3f);
            cb.setColorFill(SLATE_600);
            cb.showTextAligned(Element.ALIGN_LEFT, labels[i], x + 30, ly, 0);
            cb.setFontAndSize(bfBold, 8.5f);
            cb.setColorFill(NAVY_HEADER);
            cb.showTextAligned(Element.ALIGN_RIGHT, String.valueOf(counts[i]), x + w - 16, ly, 0);
            cb.endText();
            ly -= 16;
        }
    }

    private void drawRecentActivitiesCard(
            PdfContentByte cb, BaseFont bfReg, BaseFont bfBold,
            float x, float y, float w, float h, DashboardMetricsService.DashboardSnapshot snap) throws Exception {
        drawSectionCardShell(cb, bfBold, x, y, w, h, "Recent Activities", null, false);

        float headerY = y + h - 42;
        cb.beginText();
        cb.setFontAndSize(bfBold, 8.0f);
        cb.setColorFill(SLATE_600);
        cb.showTextAligned(Element.ALIGN_LEFT, "Time", x + 10, headerY, 0);
        cb.showTextAligned(Element.ALIGN_LEFT, "Action", x + 94, headerY, 0);
        cb.showTextAligned(Element.ALIGN_LEFT, "Product", x + 150, headerY, 0);
        cb.showTextAligned(Element.ALIGN_RIGHT, "By", x + w - 10, headerY, 0);
        cb.endText();

        List<StockMovement> moves = snap.recentMovements();
        if (moves.isEmpty()) {
            cb.beginText();
            cb.setFontAndSize(bfReg, 9);
            cb.setColorFill(SLATE_400);
            cb.showTextAligned(Element.ALIGN_CENTER, "No stock movements recorded yet", x + w / 2f, y + h / 2f - 10, 0);
            cb.endText();
            return;
        }

        float rowY = headerY - 23;
        for (int i = 0; i < Math.min(5, moves.size()); i++) {
            StockMovement m = moves.get(i);
            String actName = m.getMovementType().getDisplayName();
            Color pillBg = switch (m.getMovementType()) {
                case RESTOCK, RETURNED -> new Color(220, 252, 231);
                case SALE -> new Color(219, 234, 254);
                default -> new Color(254, 226, 226);
            };
            Color pillFg = switch (m.getMovementType()) {
                case RESTOCK, RETURNED -> new Color(21, 128, 61);
                case SALE -> new Color(29, 78, 216);
                default -> new Color(185, 28, 28);
            };
            drawRoundedBox(cb, x + 92, rowY - 3, 48, 13, 6, pillBg, pillBg);

            cb.beginText();
            cb.setFontAndSize(bfReg, 7.6f);
            cb.setColorFill(SLATE_600);
            String ts = m.getCreatedAt() != null ? m.getCreatedAt().format(MOVE_FMT) : "—";
            cb.showTextAligned(Element.ALIGN_LEFT, ts, x + 10, rowY + 1, 0);

            cb.setFontAndSize(bfBold, 7.2f);
            cb.setColorFill(pillFg);
            cb.showTextAligned(Element.ALIGN_CENTER, truncate(actName, 8), x + 116, rowY + 1, 0);

            cb.setFontAndSize(bfBold, 8.0f);
            cb.setColorFill(SLATE_900);
            cb.showTextAligned(Element.ALIGN_LEFT, truncate(m.getProductName(), 16), x + 150, rowY + 1, 0);

            cb.setFontAndSize(bfReg, 7.8f);
            cb.setColorFill(SLATE_600);
            cb.showTextAligned(Element.ALIGN_RIGHT, truncate(m.getPerformedBy(), 10), x + w - 10, rowY + 1, 0);
            cb.endText();
            rowY -= 24f;
        }
    }

    private void drawAiInsightsCard(
            PdfContentByte cb, BaseFont bfReg, BaseFont bfBold,
            float x, float y, float w, float h,
            String rawBriefing, DashboardMetricsService.DashboardSnapshot snap, List<Product> products)
            throws Exception {
        drawSectionCardShell(cb, bfBold, x, y, w, h, "AI Insights & Recommendations", null, false);

        // Soft lavender inner background
        drawRoundedBox(cb, x + 2, y + 2, w - 4, h - 26, 8, new Color(245, 243, 255), new Color(245, 243, 255));

        // Left cute vector AI bot illustration
        Image botImg = toPdfImage(renderBotBadge(160));
        botImg.setAbsolutePosition(x + 12, y + 66);
        botImg.scaleAbsolute(52, 52);
        cb.addImage(botImg);

        // Build crisp 3 bullets from briefing or store metrics
        List<String> bullets = new ArrayList<>();
        if (rawBriefing != null) {
            for (String line : rawBriefing.split("\\R")) {
                String clean = line.replaceAll("^[•\\-*\\d.)\\s]+", "").trim();
                if (!clean.isBlank() && !clean.toLowerCase().startsWith("morning store briefing")) {
                    bullets.add(clean);
                }
            }
        }
        if (bullets.isEmpty()) {
            bullets.add(snap.totalProducts() + " active SKUs tracked across local SQLite catalog.");
            bullets.add((snap.lowStockCount() + snap.outOfStockCount()) + " SKU(s) currently require attention.");
        }

        float by = y + h - 46;
        for (int i = 0; i < Math.min(3, bullets.size()); i++) {
            cb.setColorFill(INDIGO);
            cb.circle(x + 76, by + 3, 5);
            cb.fill();
            cb.beginText();
            cb.setFontAndSize(bfBold, 6.5f);
            cb.setColorFill(Color.WHITE);
            cb.showTextAligned(Element.ALIGN_CENTER, "v", x + 76, by + 1, 0);
            cb.endText();

            ColumnText ct = new ColumnText(cb);
            ct.setSimpleColumn(x + 86, by - 22, x + w - 10, by + 10);
            ct.addElement(new Phrase(bullets.get(i), new Font(Font.HELVETICA, 7.8f, Font.NORMAL, SLATE_900)));
            ct.go();
            by -= 33f;
        }

        // Bottom Royal Indigo Pro Tip Banner
        drawRoundedBox(cb, x + 12, y + 12, w - 24, 30, 8, INDIGO, INDIGO);
        String topItem = products.stream()
                .max(Comparator.comparingDouble(Product::getRetailValuation))
                .map(Product::getName).orElse("top-performing items");
        ColumnText tipCt = new ColumnText(cb);
        tipCt.setSimpleColumn(x + 22, y + 12, x + w - 20, y + 40);
        tipCt.addElement(new Phrase("Pro Tip: Focus replenishment on " + truncate(topItem, 26)
                + " to maximize stock turnover & profit!",
                new Font(Font.HELVETICA, 7.6f, Font.BOLD, Color.WHITE)));
        tipCt.go();
    }

    private void drawFooter(
            PdfContentByte cb, BaseFont bfReg, BaseFont bfBold,
            AppSession session, float margin, float pageW) throws Exception {
        Image logoImg = toPdfImage(renderCubeLogo(90));
        logoImg.setAbsolutePosition(margin, 44);
        logoImg.scaleAbsolute(28, 28);
        cb.addImage(logoImg);

        cb.beginText();
        cb.setFontAndSize(bfBold, 12);
        cb.setColorFill(NAVY_HEADER);
        cb.showTextAligned(Element.ALIGN_LEFT, "StockPro", margin + 35, 58, 0);
        cb.setFontAndSize(bfReg, 7.5f);
        cb.setColorFill(SLATE_600);
        cb.showTextAligned(Element.ALIGN_LEFT, "Built for a Smarter Store", margin + 35, 47, 0);

        cb.setColorStroke(BORDER_LIGHT);
        cb.setLineWidth(1.0f);
        cb.endText();
        cb.moveTo(margin + 148, 42);
        cb.lineTo(margin + 148, 72);
        cb.stroke();

        cb.beginText();
        cb.setFontAndSize(bfReg, 8.0f);
        cb.setColorFill(SLATE_600);
        cb.showTextAligned(Element.ALIGN_LEFT, "Generated on: " + LocalDateTime.now().format(TS_FMT), margin + 162, 60,
                0);
        cb.showTextAligned(Element.ALIGN_LEFT, "Database: Local SQLite • Zero Silent AI Writes", margin + 162, 47, 0);
        cb.endText();

        // Subtle signature stroke + label
        float sigX = pageW - margin - 125;
        cb.saveState();
        cb.setColorStroke(SLATE_600);
        cb.setLineWidth(1.2f);
        cb.moveTo(sigX + 18, 60);
        cb.curveTo(sigX + 35, 76, sigX + 46, 54, sigX + 64, 65);
        cb.curveTo(sigX + 78, 72, sigX + 90, 58, sigX + 108, 63);
        cb.stroke();
        cb.moveTo(sigX, 53);
        cb.lineTo(pageW - margin, 53);
        cb.stroke();
        cb.restoreState();

        cb.beginText();
        cb.setFontAndSize(bfBold, 8.5f);
        cb.setColorFill(NAVY_HEADER);
        cb.showTextAligned(Element.ALIGN_CENTER, "Store Manager (" + truncate(session.getActorName(), 16) + ")",
                sigX + 62, 41, 0);
        cb.endText();
    }

    private void drawSectionCardShell(
            PdfContentByte cb, BaseFont bfBold,
            float x, float y, float w, float h, String title, String legendText, boolean showLegendDots) {
        drawRoundedBox(cb, x, y, w, h, 9, Color.WHITE, BORDER_LIGHT);
        // Top dark navy header bar with rounded top corners
        drawRoundedBox(cb, x, y + h - 24, w, 24, 7, NAVY_HEADER, NAVY_HEADER);
        cb.setColorFill(NAVY_HEADER);
        cb.rectangle(x, y + h - 24, w, 10);
        cb.fill();

        cb.beginText();
        cb.setFontAndSize(bfBold, 9.5f);
        cb.setColorFill(Color.WHITE);
        cb.showTextAligned(Element.ALIGN_LEFT, title, x + 12, y + h - 16, 0);
        if (legendText != null && showLegendDots) {
            cb.setFontAndSize(bfBold, 7.8f);
            cb.showTextAligned(Element.ALIGN_RIGHT, "Sales        Profit", x + w - 12, y + h - 15, 0);
        }
        cb.endText();

        if (showLegendDots) {
            cb.setColorFill(new Color(59, 130, 246));
            cb.circle(x + w - 74, y + h - 12, 3);
            cb.fill();
            cb.setColorFill(PURPLE);
            cb.circle(x + w - 38, y + h - 12, 3);
            cb.fill();
        }
    }

    private void drawRoundedBox(
            PdfContentByte cb, float x, float y, float w, float h, float r, Color fill, Color stroke) {
        cb.saveState();
        cb.setColorFill(fill);
        cb.setColorStroke(stroke);
        cb.setLineWidth(0.9f);
        cb.roundRectangle(x, y, w, h, r);
        cb.fillStroke();
        cb.restoreState();
    }

    private BufferedImage renderCubeLogo(int px) {
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, INDIGO, px, px, PURPLE));
        g.fill(new RoundRectangle2D.Double(4, 4, px - 8, px - 8, px * 0.24, px * 0.24));

        Path2D hex = new Path2D.Double();
        double cx = px / 2.0, cy = px / 2.0, rad = px * 0.32;
        for (int i = 0; i < 6; i++) {
            double ang = Math.toRadians(90 + i * 60);
            double pxX = cx + Math.cos(ang) * rad;
            double pxY = cy - Math.sin(ang) * rad;
            if (i == 0)
                hex.moveTo(pxX, pxY);
            else
                hex.lineTo(pxX, pxY);
        }
        hex.closePath();
        g.setColor(new Color(255, 255, 255, 45));
        g.fill(hex);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(px * 0.045f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(hex);
        g.drawLine((int) cx, (int) cy, (int) cx, (int) (cy + rad));
        g.drawLine((int) cx, (int) cy, (int) (cx + Math.cos(Math.toRadians(30)) * rad),
                (int) (cy - Math.sin(Math.toRadians(30)) * rad));
        g.drawLine((int) cx, (int) cy, (int) (cx - Math.cos(Math.toRadians(30)) * rad),
                (int) (cy - Math.sin(Math.toRadians(30)) * rad));
        g.dispose();
        return img;
    }

    private BufferedImage renderBotBadge(int px) {
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(224, 231, 255), px, px, new Color(199, 210, 254)));
        g.fillOval(6, 6, px - 12, px - 12);

        g.setColor(INDIGO);
        g.fillRoundRect(px / 4, px / 4, px / 2, (int) (px * 0.44), 26, 26);
        g.setColor(NAVY_HEADER);
        g.fillRoundRect(px / 4 + 10, px / 4 + 12, px / 2 - 20, px / 4, 14, 14);
        g.setColor(new Color(56, 189, 248));
        g.fillOval(px / 2 - 22, px / 4 + 22, 14, 14);
        g.fillOval(px / 2 + 8, px / 4 + 22, 14, 14);
        g.setColor(INDIGO);
        g.setStroke(new BasicStroke(6, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(px / 2, px / 4, px / 2, px / 7);
        g.fillOval(px / 2 - 7, px / 7 - 7, 14, 14);
        g.dispose();
        return img;
    }

    private BufferedImage renderTrendChartImage(
            List<DashboardMetricsService.TrendPoint> points, AppSession session, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);

        int lp = 110, rp = 40, tp = 38, bp = 56;
        int pw = Math.max(40, w - lp - rp);
        int ph = Math.max(40, h - tp - bp);

        double maxV = points.stream()
                .mapToDouble(p -> Math.max(p.salesRevenue(), p.profitAmount()))
                .max().orElse(100.0);
        maxV = maxV <= 0 ? 100.0 : maxV * 1.18;

        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 22));
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i <= 3; i++) {
            double frac = i / 3.0;
            int y = (int) (tp + ph - frac * ph);
            g.setColor(new Color(226, 232, 240));
            g.setStroke(new BasicStroke(2f));
            g.drawLine(lp, y, lp + pw, y);
            String t = formatShortTick(session.getCurrencySymbol(), maxV * frac);
            g.setColor(SLATE_600);
            g.drawString(t, lp - fm.stringWidth(t) - 14, y + 7);
        }

        int n = Math.max(1, points.size());
        double[] xs = new double[n];
        double[] ysSales = new double[n];
        double[] ysProfit = new double[n];
        for (int i = 0; i < points.size(); i++) {
            xs[i] = n == 1 ? lp + pw / 2.0 : lp + (i * (double) pw) / (n - 1);
            ysSales[i] = tp + ph - (Math.max(0, points.get(i).salesRevenue()) / maxV) * ph;
            ysProfit[i] = tp + ph - (Math.max(0, points.get(i).profitAmount()) / maxV) * ph;

            String lbl = points.get(i).dayLabel();
            g.setColor(SLATE_600);
            g.drawString(lbl, (int) (xs[i] - fm.stringWidth(lbl) / 2.0), tp + ph + 32);
        }

        if (!points.isEmpty()) {
            drawSmoothSeries(g, xs, ysSales, tp + ph, new Color(59, 130, 246), 5f, 65);
            drawSmoothSeries(g, xs, ysProfit, tp + ph, PURPLE, 4.2f, 40);

            int last = points.size() - 1;
            drawCalloutPill(g, xs[last], ysSales[last] - 34,
                    formatCompactMoney(session, points.get(last).salesRevenue()), new Color(37, 99, 235));
            drawCalloutPill(g, xs[last], ysProfit[last] + 24,
                    formatCompactMoney(session, points.get(last).profitAmount()), PURPLE);
        }
        g.dispose();
        return img;
    }

    private void drawSmoothSeries(Graphics2D g, double[] xs, double[] ys, int baselineY, Color color, float strokeW,
            int alphaTop) {
        int n = xs.length;
        Path2D area = new Path2D.Double();
        area.moveTo(xs[0], baselineY);
        area.lineTo(xs[0], ys[0]);
        for (int i = 0; i < n - 1; i++) {
            double cx = (xs[i] + xs[i + 1]) / 2.0;
            area.curveTo(cx, ys[i], cx, ys[i + 1], xs[i + 1], ys[i + 1]);
        }
        area.lineTo(xs[n - 1], baselineY);
        area.closePath();
        g.setPaint(new GradientPaint(0, 30, new Color(color.getRed(), color.getGreen(), color.getBlue(), alphaTop),
                0, baselineY, new Color(color.getRed(), color.getGreen(), color.getBlue(), 4)));
        g.fill(area);

        Path2D line = new Path2D.Double();
        line.moveTo(xs[0], ys[0]);
        for (int i = 0; i < n - 1; i++) {
            double cx = (xs[i] + xs[i + 1]) / 2.0;
            line.curveTo(cx, ys[i], cx, ys[i + 1], xs[i + 1], ys[i + 1]);
        }
        g.setColor(color);
        g.setStroke(new BasicStroke(strokeW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(line);

        for (int i = 0; i < n; i++) {
            g.setColor(Color.WHITE);
            g.fillOval((int) xs[i] - 7, (int) ys[i] - 7, 14, 14);
            g.setColor(color);
            g.setStroke(new BasicStroke(3.5f));
            g.drawOval((int) xs[i] - 7, (int) ys[i] - 7, 14, 14);
        }
    }

    private void drawCalloutPill(Graphics2D g, double cx, double cy, String txt, Color bg) {
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 21));
        FontMetrics fm = g.getFontMetrics();
        int pw = fm.stringWidth(txt) + 26;
        int ph = 32;
        int rx = (int) Math.max(10, cx - pw / 2.0);
        int ry = (int) Math.max(4, cy - ph / 2.0);
        g.setColor(bg);
        g.fillRoundRect(rx, ry, pw, ph, 14, 14);
        g.setColor(Color.WHITE);
        g.drawString(txt, rx + 13, ry + 23);
    }

    private BufferedImage renderCategoryDonutImage(List<Product> products, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);

        Map<String, Double> catValues = new LinkedHashMap<>();
        double total = 0.0;
        for (Product p : products) {
            String cat = p.getCategory() != null && !p.getCategory().isBlank() ? p.getCategory() : "General";
            double val = Math.max(1.0, p.getRetailValuation());
            catValues.merge(cat, val, Double::sum);
            total += val;
        }
        List<Map.Entry<String, Double>> top = catValues.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(4)
                .toList();

        Color[] palette = { INDIGO, EMERALD, AMBER, SKY };
        double size = Math.min(w * 0.45, h * 0.78);
        double dx = 28;
        double dy = (h - size) / 2.0;

        g.setStroke(new BasicStroke((float) (size * 0.21), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.setColor(BORDER_LIGHT);
        g.draw(new Arc2D.Double(dx, dy, size, size, 0, 360, Arc2D.OPEN));

        double angle = 90;
        for (int i = 0; i < top.size(); i++) {
            double frac = total > 0 ? top.get(i).getValue() / total : 0.0;
            double sweep = -360.0 * frac;
            g.setColor(palette[i % palette.length]);
            g.draw(new Arc2D.Double(dx, dy, size, size, angle, sweep + 2, Arc2D.OPEN));
            angle += sweep;
        }

        String centerPct = top.isEmpty() || total <= 0 ? "0%"
                : String.format("%.0f%%", (top.get(0).getValue() / total) * 100);
        String centerName = top.isEmpty() ? "Empty" : truncate(top.get(0).getKey(), 11);
        g.setColor(NAVY_HEADER);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 34));
        FontMetrics fmBig = g.getFontMetrics();
        g.drawString(centerPct, (int) (dx + size / 2 - fmBig.stringWidth(centerPct) / 2.0), (int) (dy + size / 2 + 4));
        g.setColor(SLATE_600);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 20));
        FontMetrics fmSm = g.getFontMetrics();
        g.drawString(centerName, (int) (dx + size / 2 - fmSm.stringWidth(centerName) / 2.0),
                (int) (dy + size / 2 + 28));

        // Right legend
        int lx = (int) (dx + size + 36);
        int ly = (int) (h * 0.28);
        for (int i = 0; i < top.size(); i++) {
            double frac = total > 0 ? top.get(i).getValue() / total : 0.0;
            g.setColor(palette[i % palette.length]);
            g.fillOval(lx, ly - 15, 16, 16);
            g.setColor(SLATE_900);
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 22));
            g.drawString(truncate(top.get(i).getKey(), 11), lx + 26, ly);
            g.setColor(NAVY_HEADER);
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 22));
            g.drawString(String.format("%.0f%%", frac * 100), w - 68, ly);
            ly += 48;
        }
        g.dispose();
        return img;
    }

    private BufferedImage renderHealthGaugeImage(int pct, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);

        double size = Math.min(w, h) * 0.78;
        double dx = (w - size) / 2.0;
        double dy = (h - size) / 2.0;
        g.setStroke(new BasicStroke((float) (size * 0.16), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(226, 232, 240));
        g.draw(new Arc2D.Double(dx, dy, size, size, 0, 360, Arc2D.OPEN));
        g.setColor(pct >= 70 ? EMERALD : (pct >= 40 ? AMBER : ROSE));
        g.draw(new Arc2D.Double(dx, dy, size, size, 90, -360.0 * Math.max(0, Math.min(100, pct)) / 100.0, Arc2D.OPEN));

        String big = pct + "%";
        g.setColor(NAVY_HEADER);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 44));
        FontMetrics fm1 = g.getFontMetrics();
        g.drawString(big, (int) (w / 2.0 - fm1.stringWidth(big) / 2.0), (int) (h / 2.0 + 4));

        String sub = "Healthy";
        g.setColor(SLATE_600);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 22));
        FontMetrics fm2 = g.getFontMetrics();
        g.drawString(sub, (int) (w / 2.0 - fm2.stringWidth(sub) / 2.0), (int) (h / 2.0 + 30));
        g.dispose();
        return img;
    }

    private Image toPdfImage(BufferedImage buffered) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(buffered, "png", baos);
        return Image.getInstance(baos.toByteArray());
    }

    private String formatCompactMoney(AppSession session, double amount) {
        if (Math.abs(amount) >= 1_000_000) {
            return String.format("%s%.2fM", session.getCurrencySymbol(), amount / 1_000_000.0);
        }
        return session.formatMoney(amount);
    }

    private String formatShortTick(String sym, double v) {
        if (v >= 1000) {
            return String.format("%s%.0fK", sym, v / 1000.0);
        }
        return String.format("%s%.0f", sym, v);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) {
            return "—";
        }
        return s.length() <= maxLen ? s : s.substring(0, Math.max(1, maxLen - 1)) + "…";
    }
}
