package com.stockpro.service;

import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.ValidationResult;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Provides Apache POI Excel ({@code .xlsx}) and CSV full-catalog export plus a dry-run validated
 * spreadsheet import wizard blocking malformed rows before committing to SQLite.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class SpreadsheetImportExportService {
    private static final String[] HEADERS = {
            "SKU", "Name", "Category", "Quantity", "LowStockThreshold",
            "BuyPrice", "SellPrice", "ShelfLocation", "ExpiryDate", "Status"
    };

    /**
     * Dry-run validation preview returned before committing spreadsheet imports into SQLite.
     *
     * @param totalRows total data rows scanned
     * @param validProducts validated {@link Product} entities ready to insert
     * @param rowErrors row-by-row diagnostic errors
     * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
     */
    public record DryRunImportReport(
            int totalRows,
            List<Product> validProducts,
            List<String> rowErrors
    ) {
        /**
         * Returns count of rows passing validation.
         *
         * @return valid row count
         */
        public int validCount() {
            return validProducts.size();
        }

        /**
         * Returns count of rows rejected by validation.
         *
         * @return error row count
         */
        public int errorCount() {
            return rowErrors.size();
        }
    }

    private final InventoryService inventoryService;

    /**
     * Creates a spreadsheet import/export service wired to {@link InventoryService}.
     *
     * @param inventoryService inventory service
     */
    public SpreadsheetImportExportService(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * Exports the supplied products into a formatted {@code .xlsx} workbook using Apache POI.
     *
     * @param products catalog items to export
     * @param outputPath target {@code .xlsx} path
     * @return written {@link Path}
     */
    public Path exportToExcel(List<Product> products, Path outputPath) {
        try (Workbook workbook = new XSSFWorkbook()) {
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }
            Sheet sheet = workbook.createSheet("Inventory");

            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font headerFont = workbook.createFont();
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            Row headerRow = sheet.createRow(0);
            for (int c = 0; c < HEADERS.length; c++) {
                Cell cell = headerRow.createCell(c);
                cell.setCellValue(HEADERS[c]);
                cell.setCellStyle(headerStyle);
            }

            int r = 1;
            for (Product p : products) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(p.getSku() != null ? p.getSku() : "");
                row.createCell(1).setCellValue(p.getName() != null ? p.getName() : "");
                row.createCell(2).setCellValue(p.getCategory() != null ? p.getCategory() : "General");
                row.createCell(3).setCellValue(p.getQuantity());
                row.createCell(4).setCellValue(p.getLowStockThreshold());
                row.createCell(5).setCellValue(p.getBuyPrice());
                row.createCell(6).setCellValue(p.getSellPrice());
                row.createCell(7).setCellValue(p.getShelfLocation() != null ? p.getShelfLocation() : "");
                row.createCell(8).setCellValue(p.getExpiryDate() != null ? p.getExpiryDate().toString() : "");
                row.createCell(9).setCellValue(p.getStatus().getDisplayName());
            }

            for (int c = 0; c < HEADERS.length; c++) {
                sheet.autoSizeColumn(c);
            }

            try (FileOutputStream fos = new FileOutputStream(outputPath.toFile())) {
                workbook.write(fos);
            }
            return outputPath;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to export Excel workbook", ex);
        }
    }

    /**
     * Exports products into a standard UTF-8 CSV spreadsheet.
     *
     * @param products catalog items
     * @param outputPath destination {@code .csv} path
     * @return written {@link Path}
     */
    public Path exportToCsv(List<Product> products, Path outputPath) {
        try {
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }
            StringBuilder sb = new StringBuilder(String.join(",", HEADERS)).append("\n");
            for (Product p : products) {
                sb.append(String.format("%s,\"%s\",\"%s\",%d,%d,%.2f,%.2f,\"%s\",%s,%s\n",
                        safeCsv(p.getSku()),
                        safeCsv(p.getName()),
                        safeCsv(p.getCategory()),
                        p.getQuantity(),
                        p.getLowStockThreshold(),
                        p.getBuyPrice(),
                        p.getSellPrice(),
                        safeCsv(p.getShelfLocation()),
                        p.getExpiryDate() != null ? p.getExpiryDate().toString() : "",
                        p.getStatus().getDisplayName()));
            }
            Files.writeString(outputPath, sb.toString());
            return outputPath;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to export CSV", ex);
        }
    }

    /**
     * Parses and validates an {@code .xlsx} or {@code .csv} spreadsheet in dry-run mode without modifying SQLite.
     *
     * @param inputPath spreadsheet path
     * @return {@link DryRunImportReport} listing valid entities and per-row errors
     */
    public DryRunImportReport dryRunImport(Path inputPath) {
        if (inputPath == null || !Files.exists(inputPath)) {
            return new DryRunImportReport(0, List.of(), List.of("Selected file does not exist."));
        }
        String fileName = inputPath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".csv")) {
            return parseCsvDryRun(inputPath);
        }
        return parseExcelDryRun(inputPath);
    }

    /**
     * Commits all validated products from a {@link DryRunImportReport} into SQLite.
     *
     * @param report validated dry-run report
     * @return number of products inserted
     */
    public int commitImport(DryRunImportReport report) {
        if (report == null || report.validProducts().isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (Product p : report.validProducts()) {
            p.setSku(null); // Auto-generate fresh collision-free SKUs unless importing raw fields
            if (inventoryService.addProduct(p).isSuccess()) {
                inserted++;
            }
        }
        return inserted;
    }

    /**
     * Parses an Excel {@code .xlsx} sheet row-by-row for dry-run validation.
     *
     * @param excelPath Excel file path
     * @return {@link DryRunImportReport}
     */
    private DryRunImportReport parseExcelDryRun(Path excelPath) {
        List<Product> valid = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int total = 0;

        try (FileInputStream fis = new FileInputStream(excelPath.toFile());
             Workbook wb = WorkbookFactory.create(fis)) {
            Sheet sheet = wb.getSheetAt(0);
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                total++;
                validateAndCollectRow(
                        r + 1,
                        cellString(row.getCell(1)),
                        cellString(row.getCell(2)),
                        cellString(row.getCell(3)),
                        cellString(row.getCell(4)),
                        cellString(row.getCell(5)),
                        cellString(row.getCell(6)),
                        cellString(row.getCell(7)),
                        cellString(row.getCell(8)),
                        valid,
                        errors
                );
            }
        } catch (Exception ex) {
            errors.add("Unable to read Excel file: " + ex.getMessage());
        }
        return new DryRunImportReport(total, valid, errors);
    }

    /**
     * Parses a CSV file row-by-row for dry-run validation.
     *
     * @param csvPath CSV file path
     * @return {@link DryRunImportReport}
     */
    private DryRunImportReport parseCsvDryRun(Path csvPath) {
        List<Product> valid = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int total = 0;

        try {
            List<String> lines = Files.readAllLines(csvPath);
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) {
                    continue;
                }
                total++;
                String[] cols = splitCsvLine(line);
                if (cols.length < 4) {
                    errors.add("Row " + (i + 1) + ": Expected at least 4 columns (SKU,Name,Category,Quantity).");
                    continue;
                }
                validateAndCollectRow(
                        i + 1,
                        cols[1],
                        cols[2],
                        cols[3],
                        cols.length > 4 ? cols[4] : "10",
                        cols.length > 5 ? cols[5] : "0",
                        cols.length > 6 ? cols[6] : "0",
                        cols.length > 7 ? cols[7] : "",
                        cols.length > 8 ? cols[8] : "",
                        valid,
                        errors
                );
            }
        } catch (IOException ex) {
            errors.add("Unable to read CSV file: " + ex.getMessage());
        }
        return new DryRunImportReport(total, valid, errors);
    }

    /**
     * Validates a single parsed spreadsheet row and appends either a {@link Product} or a row error.
     *
     * @param rowNumber 1-based spreadsheet row number
     * @param name product name
     * @param category product category
     * @param qtyStr raw quantity string
     * @param thrStr raw threshold string
     * @param buyStr raw buy price string
     * @param sellStr raw sell price string
     * @param shelf shelf location
     * @param expiryStr optional YYYY-MM-DD expiry date
     * @param valid output list of valid products
     * @param errors output list of row error messages
     */
    private void validateAndCollectRow(
            int rowNumber,
            String name,
            String category,
            String qtyStr,
            String thrStr,
            String buyStr,
            String sellStr,
            String shelf,
            String expiryStr,
            List<Product> valid,
            List<String> errors) {
        ValidationResult vr = ProductValidator.validateRawInputs(name, qtyStr, thrStr, buyStr, sellStr);
        if (!vr.isValid()) {
            errors.add("Row " + rowNumber + " (" + name + "): " + vr.getSummaryMessage().replace("\n", "; "));
            return;
        }
        Product p = new Product();
        p.setName(name.trim());
        p.setCategory(category != null && !category.isBlank() ? category.trim() : "General");
        p.setQuantity(ProductValidator.parseQuantity(qtyStr));
        p.setLowStockThreshold(thrStr == null || thrStr.isBlank() ? 10 : ProductValidator.parseQuantity(thrStr));
        p.setBuyPrice(buyStr == null || buyStr.isBlank() ? 0.0 : ProductValidator.parsePrice(buyStr, "BuyPrice"));
        p.setSellPrice(sellStr == null || sellStr.isBlank() ? 0.0 : ProductValidator.parsePrice(sellStr, "SellPrice"));
        p.setShelfLocation(shelf != null ? shelf.trim() : "");
        if (expiryStr != null && !expiryStr.isBlank()) {
            try {
                p.setExpiryDate(LocalDate.parse(expiryStr.trim()));
            } catch (Exception ex) {
                errors.add("Row " + rowNumber + ": Invalid date format '" + expiryStr + "' (use YYYY-MM-DD).");
                return;
            }
        }
        p.setStatus(StockStatus.IN_STOCK);
        p.refreshStatusIfAutomatic();
        valid.add(p);
    }

    /**
     * Reads cell contents consistently as a trimmed string.
     *
     * @param cell Apache POI cell
     * @return normalized cell value
     */
    private String cellString(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                if (d == Math.rint(d)) {
                    yield String.valueOf((long) d);
                }
                yield String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    /**
     * Splits a single CSV row honoring double-quoted segments.
     *
     * @param line raw CSV line
     * @return stripped column strings
     */
    private String[] splitCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                tokens.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        tokens.add(current.toString().trim());
        return tokens.toArray(new String[0]);
    }

    /**
     * Escapes quotes inside CSV text values.
     *
     * @param value raw field value
     * @return escaped CSV string
     */
    private String safeCsv(String value) {
        return value != null ? value.replace("\"", "\"\"") : "";
    }
}
