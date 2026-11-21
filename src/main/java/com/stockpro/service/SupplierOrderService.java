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
import com.stockpro.domain.MovementType;
import com.stockpro.domain.PurchaseOrder;
import com.stockpro.domain.PurchaseOrderItem;
import com.stockpro.domain.Supplier;
import com.stockpro.repository.PurchaseOrderRepository;
import com.stockpro.repository.SupplierRepository;

import java.awt.Color;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Manages supplier CRUD, multi-item Purchase Order creation and fulfillment lifecycle,
 * and branded OpenPDF Purchase Order exports.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class SupplierOrderService {
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SupplierRepository supplierRepository;
    private final PurchaseOrderRepository poRepository;
    private final StockOperationService stockOperationService;
    private final AuditService auditService;
    private final InventoryEventBus eventBus;

    /**
     * Constructs a supplier and purchase order service.
     *
     * @param supplierRepository supplier repository
     * @param poRepository purchase order repository
     * @param stockOperationService stock operation service for auto-restock on PO delivery
     * @param auditService tamper-evident audit logger
     * @param eventBus shared UI refresh event bus
     */
    public SupplierOrderService(
            SupplierRepository supplierRepository,
            PurchaseOrderRepository poRepository,
            StockOperationService stockOperationService,
            AuditService auditService,
            InventoryEventBus eventBus) {
        this.supplierRepository = supplierRepository;
        this.poRepository = poRepository;
        this.stockOperationService = stockOperationService;
        this.auditService = auditService;
        this.eventBus = eventBus;
    }

    /**
     * Validates and saves a new or existing supplier.
     *
     * @param supplier supplier entity
     * @return operation result with persisted supplier
     */
    public InventoryOperationResult<Supplier> saveSupplier(Supplier supplier) {
        if (supplier == null || supplier.getName() == null || supplier.getName().trim().isEmpty()) {
            return InventoryOperationResult.fail("Supplier name cannot be empty.");
        }
        if (supplier.getDeliveryDays() < 0) {
            return InventoryOperationResult.fail("Delivery days cannot be negative.");
        }
        if (supplier.getReliabilityScore() < 0.0 || supplier.getReliabilityScore() > 100.0) {
            return InventoryOperationResult.fail("Reliability score must be between 0 and 100.");
        }

        if (supplier.getId() <= 0) {
            Supplier saved = supplierRepository.insert(supplier);
            auditService.record(AppSession.getInstance().getActorName(), "SUPPLIER_ADD", saved.getName(), "Added supplier");
            eventBus.publish("SUPPLIER_CHANGED");
            return InventoryOperationResult.ok(saved, "Added supplier '" + saved.getName() + "'.");
        } else {
            boolean ok = supplierRepository.update(supplier);
            if (!ok) {
                return InventoryOperationResult.fail("Supplier not found.");
            }
            auditService.record(AppSession.getInstance().getActorName(), "SUPPLIER_EDIT", supplier.getName(), "Updated supplier");
            eventBus.publish("SUPPLIER_CHANGED");
            return InventoryOperationResult.ok(supplier, "Updated supplier '" + supplier.getName() + "'.");
        }
    }

    /**
     * Deletes a supplier if no purchase orders depend on it.
     *
     * @param supplierId target supplier id
     * @return operation result
     */
    public InventoryOperationResult<Boolean> deleteSupplier(long supplierId) {
        Optional<Supplier> found = supplierRepository.findById(supplierId);
        if (found.isEmpty()) {
            return InventoryOperationResult.fail("Supplier not found.");
        }
        boolean deleted = supplierRepository.deleteById(supplierId);
        if (!deleted) {
            return InventoryOperationResult.fail("Cannot delete supplier linked to existing purchase orders.");
        }
        auditService.record(AppSession.getInstance().getActorName(), "SUPPLIER_DELETE", found.get().getName(), "Removed supplier");
        eventBus.publish("SUPPLIER_CHANGED");
        return InventoryOperationResult.ok(true, "Removed supplier '" + found.get().getName() + "'.");
    }

    /**
     * Lists all registered suppliers.
     *
     * @return list of suppliers
     */
    public List<Supplier> getAllSuppliers() {
        return supplierRepository.findAll();
    }

    /**
     * Lists all purchase orders newest first.
     *
     * @return list of purchase orders
     */
    public List<PurchaseOrder> getAllOrders() {
        return poRepository.findAll();
    }

    /**
     * Creates a multi-item purchase order in {@code Draft} status.
     *
     * @param supplierId supplier database id
     * @param items ordered line items
     * @return operation result containing the created {@link PurchaseOrder}
     */
    public InventoryOperationResult<PurchaseOrder> createPurchaseOrder(long supplierId, List<PurchaseOrderItem> items) {
        if (items == null || items.isEmpty()) {
            return InventoryOperationResult.fail("Add at least one product line item to create a Purchase Order.");
        }
        Optional<Supplier> supplierOpt = supplierRepository.findById(supplierId);
        if (supplierOpt.isEmpty()) {
            return InventoryOperationResult.fail("Selected supplier does not exist.");
        }
        for (PurchaseOrderItem item : items) {
            if (item.getQuantity() <= 0) {
                return InventoryOperationResult.fail("Order quantity must be positive for " + item.getProductName() + ".");
            }
        }

        Supplier supplier = supplierOpt.get();
        PurchaseOrder po = new PurchaseOrder();
        po.setSupplierId(supplier.getId());
        po.setSupplierName(supplier.getName());
        po.setItems(items);
        po.setStatus("Draft");
        po.recalculateTotal();

        PurchaseOrder saved = poRepository.insert(po);
        auditService.record(
                AppSession.getInstance().getActorName(),
                "PO_CREATE",
                saved.getPoNumber(),
                "Created PO for " + supplier.getName() + " totaling " + AppSession.getInstance().formatMoney(saved.getTotalCost())
        );
        eventBus.publish("PO_CREATED");
        return InventoryOperationResult.ok(saved, "Created Purchase Order " + saved.getPoNumber() + ".");
    }

    /**
     * Updates a purchase order status ({@code Draft}, {@code Sent}, {@code Delivered}).
     * Transitioning to {@code Delivered} automatically restocks every line item into SQLite inventory.
     *
     * @param poId purchase order id
     * @param newStatus target status
     * @param actor operator display name
     * @return operation result reflecting updated order
     */
    public InventoryOperationResult<PurchaseOrder> updateOrderStatus(long poId, String newStatus, String actor) {
        Optional<PurchaseOrder> poOpt = poRepository.findById(poId);
        if (poOpt.isEmpty()) {
            return InventoryOperationResult.fail("Purchase order not found.");
        }
        PurchaseOrder po = poOpt.get();
        String prevStatus = po.getStatus();
        if ("Delivered".equalsIgnoreCase(prevStatus)) {
            return InventoryOperationResult.fail("Purchase Order " + po.getPoNumber() + " is already delivered.");
        }

        poRepository.updateStatus(poId, newStatus);
        po.setStatus(newStatus);

        if ("Delivered".equalsIgnoreCase(newStatus)) {
            for (PurchaseOrderItem item : po.getItems()) {
                stockOperationService.recordOperation(
                        item.getProductId(),
                        MovementType.RESTOCK,
                        String.valueOf(item.getQuantity()),
                        "PO Delivery " + po.getPoNumber(),
                        "Supplier: " + po.getSupplierName(),
                        actor
                );
            }
        }

        auditService.record(actor, "PO_STATUS", po.getPoNumber(), prevStatus + " -> " + newStatus);
        eventBus.publish("PO_UPDATED");
        return InventoryOperationResult.ok(po, "Updated " + po.getPoNumber() + " to " + newStatus + ".");
    }

    /**
     * Generates a clean, branded A4 Purchase Order PDF document using OpenPDF.
     *
     * @param po purchase order to export
     * @param outputPath destination PDF file path
     * @return destination {@link Path} on success
     */
    public Path exportPurchaseOrderPdf(PurchaseOrder po, Path outputPath) {
        if (po == null) {
            throw new IllegalArgumentException("Purchase order must not be null.");
        }
        try {
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }
            AppSession session = AppSession.getInstance();
            try (FileOutputStream fos = new FileOutputStream(outputPath.toFile());
                 Document doc = new Document(PageSize.A4, 36, 36, 40, 36)) {
                PdfWriter.getInstance(doc, fos);
                doc.open();

                Font titleFont = new Font(Font.HELVETICA, 20, Font.BOLD, new Color(79, 70, 229));
                Font subFont = new Font(Font.HELVETICA, 11, Font.NORMAL, new Color(100, 116, 139));
                Font headFont = new Font(Font.HELVETICA, 11, Font.BOLD, Color.WHITE);
                Font bodyFont = new Font(Font.HELVETICA, 10, Font.NORMAL, new Color(15, 23, 42));
                Font boldFont = new Font(Font.HELVETICA, 11, Font.BOLD, new Color(15, 23, 42));

                Paragraph header = new Paragraph(session.getStoreName() + " — PURCHASE ORDER", titleFont);
                doc.add(header);
                doc.add(new Paragraph("PO Number: " + po.getPoNumber()
                        + "   |   Status: " + po.getStatus()
                        + "   |   Date: " + (po.getCreatedAt() != null ? po.getCreatedAt().format(DATE_FMT) : ""), subFont));
                doc.add(new Paragraph("Supplier: " + po.getSupplierName(), boldFont));
                doc.add(new Paragraph(" "));

                PdfPTable table = new PdfPTable(5);
                table.setWidthPercentage(100);
                table.setWidths(new float[]{1.4f, 3.2f, 1.0f, 1.4f, 1.5f});

                String[] headers = {"SKU", "Product Name", "Qty", "Unit Cost", "Line Total"};
                for (String h : headers) {
                    PdfPCell cell = new PdfPCell(new Phrase(h, headFont));
                    cell.setBackgroundColor(new Color(23, 28, 46));
                    cell.setPadding(8);
                    table.addCell(cell);
                }

                for (PurchaseOrderItem item : po.getItems()) {
                    table.addCell(new PdfPCell(new Phrase(item.getProductSku(), bodyFont)));
                    table.addCell(new PdfPCell(new Phrase(item.getProductName(), bodyFont)));
                    PdfPCell qCell = new PdfPCell(new Phrase(String.valueOf(item.getQuantity()), bodyFont));
                    qCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    table.addCell(qCell);
                    PdfPCell uCell = new PdfPCell(new Phrase(session.formatMoney(item.getUnitCost()), bodyFont));
                    uCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    table.addCell(uCell);
                    PdfPCell tCell = new PdfPCell(new Phrase(session.formatMoney(item.getLineTotal()), bodyFont));
                    tCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    table.addCell(tCell);
                }
                doc.add(table);

                doc.add(new Paragraph(" "));
                double tax = po.getTotalCost() * (session.getTaxRatePercent() / 100.0);
                double grandTotal = po.getTotalCost() + tax;
                Paragraph totals = new Paragraph(
                        String.format("Subtotal: %s\nEstimated Tax (%.1f%%): %s\nGrand Total: %s",
                                session.formatMoney(po.getTotalCost()),
                                session.getTaxRatePercent(),
                                session.formatMoney(tax),
                                session.formatMoney(grandTotal)),
                        boldFont
                );
                totals.setAlignment(Element.ALIGN_RIGHT);
                doc.add(totals);

                doc.add(new Paragraph("\n\nAuthorized Signature: ____________________________", subFont));
            }
            return outputPath;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate Purchase Order PDF", ex);
        }
    }

    /**
     * Seeds sample suppliers when directory is empty for quick testing/demo use.
     */
    public void ensureSampleSuppliersIfEmpty() {
        if (!supplierRepository.findAll().isEmpty()) {
            return;
        }
        String[][] defaults = {
                {"TechGlobal Ltd", "+92 300 1234567", "orders@techglobal.example", "2", "98.0", "Electronics,Accessories"},
                {"FreshFarm Dairy Co", "+92 301 7654321", "supply@freshfarm.example", "1", "96.5", "Dairy,Bakery"},
                {"Pak Wholesale Traders", "+92 302 1112233", "sales@paktraders.example", "3", "94.0", "Groceries,Snacks,Drinks"}
        };
        for (String[] row : defaults) {
            Supplier s = new Supplier();
            s.setName(row[0]);
            s.setPhone(row[1]);
            s.setEmail(row[2]);
            s.setDeliveryDays(Integer.parseInt(row[3]));
            s.setReliabilityScore(Double.parseDouble(row[4]));
            s.setLinkedCategories(row[5]);
            supplierRepository.insert(s);
        }
    }
}
