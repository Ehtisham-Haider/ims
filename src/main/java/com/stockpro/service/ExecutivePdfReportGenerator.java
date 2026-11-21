package com.stockpro.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.stockpro.ai.AiAssistantService;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockMovement;
import com.stockpro.repository.ProductRepository;

import java.awt.Color;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the print-ready A4 branded Executive Business Report PDF matching
 * {@code Screens and popups/Generated Report.png} with KPI boxes, category shares, top products,
 * inventory health score, recent activities, AI insights, and manager signature footer.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class ExecutivePdfReportGenerator {
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' hh:mm a");

    private final ProductRepository productRepository;
    private final DashboardMetricsService metricsService;
    private final FinanceService financeService;
    private final AiAssistantService aiAssistantService;

    /**
     * Creates an executive PDF report generator wired to live analytics and AI insight services.
     *
     * @param productRepository product repository
     * @param metricsService dashboard metrics aggregator
     * @param financeService financial engine
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
     * Generates the multi-section branded A4 Business Report PDF to the specified file path.
     *
     * @param periodLabel date filter description (e.g. {@code "Last 30 Days"})
     * @param outputPath target PDF file path
     * @return written PDF {@link Path}
     */
    public Path generateBusinessReport(String periodLabel, Path outputPath) {
        try {
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }
            AppSession session = AppSession.getInstance();
            DashboardMetricsService.DashboardSnapshot snap = metricsService.loadSnapshot();
            FinanceService.FinanceSummary fin = financeService.computeSummary(60, session.getTaxRatePercent());
            List<Product> products = productRepository.findAll(false);
            String briefing = aiAssistantService.generateMorningBriefing();

            try (FileOutputStream fos = new FileOutputStream(outputPath.toFile());
                 Document doc = new Document(PageSize.A4, 30, 30, 30, 30)) {
                PdfWriter.getInstance(doc, fos);
                doc.open();

                Font brandFont = new Font(Font.HELVETICA, 20, Font.BOLD, new Color(79, 70, 229));
                Font titleFont = new Font(Font.HELVETICA, 13, Font.BOLD, new Color(15, 23, 42));
                Font mutedFont = new Font(Font.HELVETICA, 9.5f, Font.NORMAL, new Color(100, 116, 139));
                Font secHeadFont = new Font(Font.HELVETICA, 11, Font.BOLD, Color.WHITE);
                Font cellFont = new Font(Font.HELVETICA, 9.5f, Font.NORMAL, new Color(15, 23, 42));
                Font boldCellFont = new Font(Font.HELVETICA, 10, Font.BOLD, new Color(15, 23, 42));

                // 1. Report Masthead
                PdfPTable masthead = new PdfPTable(2);
                masthead.setWidthPercentage(100);
                masthead.setWidths(new float[]{2.2f, 1.2f});

                PdfPCell leftHead = new PdfPCell();
                leftHead.setBorder(0);
                leftHead.addElement(new Paragraph("StockPro — " + session.getStoreName(), brandFont));
                leftHead.addElement(new Paragraph("Inventory Management System • Business Report", mutedFont));

                PdfPCell rightHead = new PdfPCell();
                rightHead.setBackgroundColor(new Color(241, 245, 249));
                rightHead.setPadding(8);
                rightHead.setBorderColor(new Color(203, 213, 225));
                rightHead.addElement(new Paragraph("Report Period: " + (periodLabel != null ? periodLabel : "Last 30 Days"), boldCellFont));
                rightHead.addElement(new Paragraph("Generated: " + LocalDateTime.now().format(TS_FMT), mutedFont));

                masthead.addCell(leftHead);
                masthead.addCell(rightHead);
                doc.add(masthead);
                doc.add(new Paragraph(" "));

                // 2. Four Pastel KPI Boxes
                PdfPTable kpiTable = new PdfPTable(4);
                kpiTable.setWidthPercentage(100);
                kpiTable.setSpacingAfter(12);
                kpiTable.addCell(kpiBox("Total SKUs", String.valueOf(snap.totalProducts()), new Color(239, 246, 255), new Color(79, 70, 229)));
                kpiTable.addCell(kpiBox("Inventory Value", session.formatMoney(fin.totalCostValue()), new Color(236, 253, 245), new Color(16, 185, 129)));
                kpiTable.addCell(kpiBox("Est. Profit", session.formatMoney(fin.totalPotentialProfit()), new Color(255, 251, 235), new Color(217, 119, 6)));
                kpiTable.addCell(kpiBox("Low / Out Items", (snap.lowStockCount() + snap.outOfStockCount()) + " Alerts", new Color(254, 242, 242), new Color(220, 38, 38)));
                doc.add(kpiTable);

                // 3. Top Products & Category Breakdown
                addSectionBanner(doc, "Top Inventory & Category Breakdown", secHeadFont);
                PdfPTable prodTable = new PdfPTable(5);
                prodTable.setWidthPercentage(100);
                prodTable.setWidths(new float[]{1.1f, 2.5f, 1.4f, 0.9f, 1.3f});
                prodTable.setSpacingAfter(12);
                for (String col : new String[]{"SKU", "Product", "Category", "Qty", "Retail Value"}) {
                    PdfPCell c = new PdfPCell(new Phrase(col, boldCellFont));
                    c.setBackgroundColor(new Color(248, 250, 252));
                    c.setPadding(6);
                    prodTable.addCell(c);
                }
                products.stream()
                        .sorted(Comparator.comparingDouble(Product::getRetailValuation).reversed())
                        .limit(8)
                        .forEach(p -> {
                            prodTable.addCell(new PdfPCell(new Phrase(p.getSku(), cellFont)));
                            prodTable.addCell(new PdfPCell(new Phrase(p.getName(), cellFont)));
                            prodTable.addCell(new PdfPCell(new Phrase(p.getCategory(), cellFont)));
                            prodTable.addCell(new PdfPCell(new Phrase(String.valueOf(p.getQuantity()), cellFont)));
                            prodTable.addCell(new PdfPCell(new Phrase(session.formatMoney(p.getRetailValuation()), cellFont)));
                        });
                doc.add(prodTable);

                // 4. Inventory Health + Category Shares
                int healthPct = (int) Math.round(snap.inStockPercent());
                Map<String, Double> catValues = new LinkedHashMap<>();
                for (Product p : products) {
                    catValues.merge(p.getCategory() != null ? p.getCategory() : "General", p.getRetailValuation(), Double::sum);
                }
                String catLine = catValues.entrySet().stream()
                        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                        .limit(4)
                        .map(e -> e.getKey() + ": " + session.formatMoney(e.getValue()))
                        .reduce((a, b) -> a + "   |   " + b)
                        .orElse("No active categories");

                addSectionBanner(doc, "Inventory Health & Category Distribution", secHeadFont);
                Paragraph healthBody = new Paragraph(
                        String.format("Stock Health Score: %d%% Healthy  •  In Stock: %d  •  Low Stock: %d  •  Out of Stock: %d  •  Expiring (7d): %d\nTop Categories: %s",
                                healthPct, snap.inStockCount(), snap.lowStockCount(), snap.outOfStockCount(), snap.expiringSoonCount(), catLine),
                        cellFont);
                healthBody.setSpacingAfter(10);
                doc.add(healthBody);

                // 5. Recent Activities
                addSectionBanner(doc, "Recent Stock Activities", secHeadFont);
                PdfPTable actTable = new PdfPTable(4);
                actTable.setWidthPercentage(100);
                actTable.setSpacingAfter(12);
                for (String col : new String[]{"Action", "Product", "Delta", "Staff"}) {
                    PdfPCell c = new PdfPCell(new Phrase(col, boldCellFont));
                    c.setBackgroundColor(new Color(248, 250, 252));
                    c.setPadding(6);
                    actTable.addCell(c);
                }
                if (snap.recentMovements().isEmpty()) {
                    PdfPCell none = new PdfPCell(new Phrase("No stock movements recorded yet.", mutedFont));
                    none.setColspan(4);
                    none.setPadding(6);
                    actTable.addCell(none);
                } else {
                    for (StockMovement m : snap.recentMovements()) {
                        actTable.addCell(new PdfPCell(new Phrase(m.getMovementType().getDisplayName(), cellFont)));
                        actTable.addCell(new PdfPCell(new Phrase(m.getProductName(), cellFont)));
                        actTable.addCell(new PdfPCell(new Phrase((m.getQuantityDelta() >= 0 ? "+" : "") + m.getQuantityDelta(), cellFont)));
                        actTable.addCell(new PdfPCell(new Phrase(m.getPerformedBy(), cellFont)));
                    }
                }
                doc.add(actTable);

                // 6. AI Insights Box
                addSectionBanner(doc, "AI Insights & Executive Briefing", secHeadFont);
                PdfPTable aiBox = new PdfPTable(1);
                aiBox.setWidthPercentage(100);
                aiBox.setSpacingAfter(16);
                PdfPCell aiCell = new PdfPCell(new Phrase(briefing, cellFont));
                aiCell.setBackgroundColor(new Color(238, 242, 255));
                aiCell.setPadding(10);
                aiCell.setBorderColor(new Color(199, 210, 254));
                aiBox.addCell(aiCell);
                doc.add(aiBox);

                // 7. Footer with Local SQLite badge & Store Manager Signature
                PdfPTable footer = new PdfPTable(2);
                footer.setWidthPercentage(100);
                PdfPCell leftFoot = new PdfPCell(new Phrase("StockPro • Built for a Smarter Store • Database: Local SQLite", mutedFont));
                leftFoot.setBorder(0);
                PdfPCell rightFoot = new PdfPCell(new Phrase("__________________________\nStore Manager (" + session.getActorName() + ")", titleFont));
                rightFoot.setBorder(0);
                rightFoot.setHorizontalAlignment(Element.ALIGN_RIGHT);
                footer.addCell(leftFoot);
                footer.addCell(rightFoot);
                doc.add(footer);
            }
            return outputPath;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate executive PDF report", ex);
        }
    }

    /**
     * Builds a pastel KPI summary cell for the PDF report header strip.
     *
     * @param title KPI label
     * @param value formatted metric value
     * @param bg soft pastel background
     * @param accent accent text color
     * @return configured {@link PdfPCell}
     */
    private PdfPCell kpiBox(String title, String value, Color bg, Color accent) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(bg);
        cell.setPadding(10);
        cell.setBorderColor(new Color(226, 232, 240));
        Font tFont = new Font(Font.HELVETICA, 8.5f, Font.BOLD, new Color(71, 85, 105));
        Font vFont = new Font(Font.HELVETICA, 13, Font.BOLD, accent);
        cell.addElement(new Paragraph(title, tFont));
        cell.addElement(new Paragraph(value, vFont));
        return cell;
    }

    /**
     * Appends a Midnight Navy section banner bar matching {@code Generated Report.png}.
     *
     * @param doc active PDF document
     * @param text section title
     * @param font white bold font
     */
    private void addSectionBanner(Document doc, String text, Font font) throws Exception {
        PdfPTable banner = new PdfPTable(1);
        banner.setWidthPercentage(100);
        banner.setSpacingAfter(4);
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(new Color(23, 28, 46));
        cell.setPadding(6);
        banner.addCell(cell);
        doc.add(banner);
    }
}
