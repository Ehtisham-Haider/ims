package com.stockpro.service;

import com.stockpro.domain.MovementType;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockMovement;
import com.stockpro.domain.StockStatus;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.StockMovementRepository;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates structured stock mutations (Restock, Sale, Damaged, Returned, Lost, Physical Count),
 * live change previews, mandatory reason validation, damaged stock quarantine, FEFO expiry lists,
 * and SHA-256 audit trail sealing.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class StockOperationService {
    private final ProductRepository productRepository;
    private final StockMovementRepository movementRepository;
    private final AuditService auditService;
    private final InventoryEventBus eventBus;
    private MultiBranchTransferRmaService branchService;

    /**
     * Attaches the Version 2 multi-branch service so restocks automatically alert waiting backorders.
     *
     * @param branchService multi-branch &amp; backorder coordinator
     */
    public void setBranchService(MultiBranchTransferRmaService branchService) {
        this.branchService = branchService;
    }

    /**
     * Preview snapshot calculated prior to confirming a stock mutation.
     *
     * @param currentQty sellable quantity before adjustment
     * @param newQty resulting sellable quantity after adjustment
     * @param delta signed change in sellable stock
     * @param newStatus resulting automatic {@link StockStatus}
     * @param newDamagedQty resulting quarantined damaged count
     * @param valid {@code true} if the resulting quantities are legally non-negative
     * @param note human-readable preview summary
     * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
     */
    public record StockPreview(
            int currentQty,
            int newQty,
            int delta,
            StockStatus newStatus,
            int newDamagedQty,
            boolean valid,
            String note
    ) {
    }

    /**
     * Creates a stock operation service using explicit dependencies.
     *
     * @param productRepository product persistence repository
     * @param movementRepository movement ledger repository
     * @param auditService tamper-evident audit logger
     * @param eventBus shared UI refresh event bus
     */
    public StockOperationService(
            ProductRepository productRepository,
            StockMovementRepository movementRepository,
            AuditService auditService,
            InventoryEventBus eventBus) {
        this.productRepository = productRepository;
        this.movementRepository = movementRepository;
        this.auditService = auditService;
        this.eventBus = eventBus;
    }

    /**
     * Computes a non-mutating live preview showing {@code Current Stock -> New Stock (New Status)}.
     *
     * @param product target product
     * @param type movement operation type
     * @param inputQuantity quantity delta (or physical shelf count for {@link MovementType#PHYSICAL_COUNT})
     * @return calculated {@link StockPreview}
     */
    public StockPreview previewOperation(Product product, MovementType type, int inputQuantity) {
        if (product == null || type == null) {
            return new StockPreview(0, 0, 0, StockStatus.OUT_OF_STOCK, 0, false, "Select a product.");
        }
        int current = product.getQuantity();
        int damaged = product.getDamagedQty();

        if (inputQuantity < 0) {
            return new StockPreview(current, current, 0, product.getStatus(), damaged, false,
                    "Quantity cannot be negative.");
        }

        int newQty;
        int newDamaged = damaged;
        int delta;
        switch (type) {
            case RESTOCK, RETURNED -> {
                if (inputQuantity == 0) {
                    return new StockPreview(current, current, 0, product.getStatus(), damaged, false,
                            "Enter a positive quantity greater than zero.");
                }
                newQty = current + inputQuantity;
                delta = inputQuantity;
            }
            case SALE, LOST -> {
                if (inputQuantity == 0) {
                    return new StockPreview(current, current, 0, product.getStatus(), damaged, false,
                            "Enter a positive quantity greater than zero.");
                }
                newQty = current - inputQuantity;
                delta = -inputQuantity;
            }
            case DAMAGED -> {
                if (inputQuantity == 0) {
                    return new StockPreview(current, current, 0, product.getStatus(), damaged, false,
                            "Enter damaged unit count greater than zero.");
                }
                newQty = current - inputQuantity;
                delta = -inputQuantity;
                newDamaged = damaged + inputQuantity;
            }
            case PHYSICAL_COUNT -> {
                newQty = inputQuantity;
                delta = inputQuantity - current;
            }
            default -> {
                newQty = current;
                delta = 0;
            }
        }

        if (newQty < 0) {
            return new StockPreview(current, newQty, delta, StockStatus.OUT_OF_STOCK, newDamaged, false,
                    "Insufficient sellable stock (" + current + " available).");
        }

        Product simulated = Product.copyOf(product);
        simulated.setQuantity(newQty);
        simulated.setManualStatusOverride(false);
        StockStatus resultingStatus = simulated.computeAutomaticStatus();

        String summary = String.format("Current: %d  ->  New: %d (%s)%s",
                current, newQty, resultingStatus.getDisplayName(),
                type == MovementType.DAMAGED ? " | Quarantined Damaged: " + newDamaged :
                        (type == MovementType.PHYSICAL_COUNT ? " | Discrepancy: " + (delta >= 0 ? "+" : "") + delta : ""));
        return new StockPreview(current, newQty, delta, resultingStatus, newDamaged, true, summary);
    }

    /**
     * Validates reason, computes stock delta, updates product inventory, logs immutable movement,
     * and seals a SHA-256 audit entry.
     *
     * @param productId target product ID
     * @param type movement operation type
     * @param rawQuantity raw quantity or physical shelf count string
     * @param reason mandatory reason string
     * @param notes optional explanatory notes
     * @param actor active operator name
     * @return operation result containing the persisted {@link StockMovement}
     */
    public InventoryOperationResult<StockMovement> recordOperation(
            long productId,
            MovementType type,
            String rawQuantity,
            String reason,
            String notes,
            String actor) {
        if (reason == null || reason.trim().isEmpty()) {
            return InventoryOperationResult.fail("A reason is mandatory for every stock adjustment.");
        }

        int parsedQty;
        try {
            parsedQty = ProductValidator.parseQuantity(rawQuantity);
        } catch (IllegalArgumentException ex) {
            return InventoryOperationResult.fail(ex.getMessage());
        }

        Optional<Product> productOpt = productRepository.findById(productId);
        if (productOpt.isEmpty()) {
            return InventoryOperationResult.fail("Selected product does not exist.");
        }

        Product product = Product.copyOf(productOpt.get());
        StockPreview preview = previewOperation(product, type, parsedQty);
        if (!preview.valid()) {
            return InventoryOperationResult.fail(preview.note());
        }

        product.setQuantity(preview.newQty());
        product.setDamagedQty(preview.newDamagedQty());
        product.setManualStatusOverride(false);
        product.refreshStatusIfAutomatic();
        productRepository.update(product);

        StockMovement movement = new StockMovement();
        movement.setProductId(product.getId());
        movement.setProductSku(product.getSku());
        movement.setProductName(product.getName());
        movement.setMovementType(type);
        movement.setQuantityDelta(preview.delta());
        movement.setPreviousQty(preview.currentQty());
        movement.setNewQty(preview.newQty());
        movement.setReason(reason.trim());
        movement.setNotes(notes != null ? notes.trim() : "");
        movement.setPerformedBy(actor != null && !actor.isBlank() ? actor : "System");
        StockMovement saved = movementRepository.insert(movement);

        auditService.record(
                movement.getPerformedBy(),
                "STOCK_" + type.name(),
                product.getSku(),
                String.format("%s (%+d): %d -> %d | Reason: %s",
                        product.getName(), preview.delta(), preview.currentQty(), preview.newQty(), movement.getReason())
        );

        if (branchService != null && preview.delta() > 0 && preview.newQty() > 0) {
            branchService.notifyWaitingCustomersOnRestock(product.getId());
        }
        eventBus.publish("STOCK_OPERATION_" + type.name());
        return InventoryOperationResult.ok(saved,
                type.getDisplayName() + " recorded for " + product.getName() + " (New stock: " + preview.newQty() + ").");
    }

    /**
     * Returns recent stock movements ordered newest first.
     *
     * @param limit max entries
     * @return list of recent movements
     */
    public List<StockMovement> getRecentMovements(int limit) {
        return movementRepository.findRecent(limit);
    }

    /**
     * Returns active products with an expiry date within the requested day window,
     * sorted First-Expired, First-Out (FEFO) so urgent batches appear first.
     *
     * @param withinDays day horizon (e.g., 7 or 30)
     * @return FEFO-sorted list of expiring or already expired products
     */
    public List<Product> getFefoExpiringProducts(int withinDays) {
        LocalDate cutoff = LocalDate.now().plusDays(withinDays);
        return productRepository.findAll(false).stream()
                .filter(p -> p.getExpiryDate() != null && !p.getExpiryDate().isAfter(cutoff))
                .sorted(Comparator.comparing(Product::getExpiryDate))
                .toList();
    }

    /**
     * Returns products that currently hold quarantined damaged units.
     *
     * @return list of products with {@code damagedQty > 0}
     */
    public List<Product> getDamagedGoods() {
        return productRepository.findAll(false).stream()
                .filter(p -> p.getDamagedQty() > 0)
                .sorted(Comparator.comparingInt(Product::getDamagedQty).reversed())
                .toList();
    }
}
