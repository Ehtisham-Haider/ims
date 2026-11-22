package com.stockpro.service;

import com.stockpro.domain.InventoryFilterCriteria;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.ValidationResult;
import com.stockpro.repository.ProductRepository;
import com.stockpro.service.command.AddProductCommand;
import com.stockpro.service.command.CommandHistoryManager;
import com.stockpro.service.command.DeleteProductCommand;
import com.stockpro.service.command.UpdateProductCommand;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Primary inventory service orchestrating validation, CRUD persistence, automatic/manual stock status rules,
 * empty-inventory handling, missing-item guards, and Command Pattern Undo/Redo.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class InventoryService {
    private final ProductRepository productRepository;
    private final CommandHistoryManager historyManager;
    private final InventoryEventBus eventBus;
    private ProductImageService productImageService;

    /**
     * Creates an inventory service wired to the default repository and shared event bus.
     */
    public InventoryService() {
        this(new ProductRepository(), new CommandHistoryManager(), InventoryEventBus.getInstance());
    }

    /**
     * Creates an inventory service with explicit dependencies for testing or custom wiring.
     *
     * @param productRepository product persistence repository
     * @param historyManager undo/redo command history manager
     * @param eventBus observer event bus notified on changes
     */
    public InventoryService(
            ProductRepository productRepository,
            CommandHistoryManager historyManager,
            InventoryEventBus eventBus) {
        this.productRepository = productRepository;
        this.historyManager = historyManager;
        this.eventBus = eventBus;
    }

    /**
     * Attaches an optional {@link ProductImageService} so newly added items receive local or Pexels images automatically.
     *
     * @param productImageService product image resolver service
     */
    public void setProductImageService(ProductImageService productImageService) {
        this.productImageService = productImageService;
    }

    /**
     * Validates and adds a new item using raw string inputs (commonly from JavaFX forms or quick commands).
     *
     * @param rawName raw item name string
     * @param rawQuantity raw item quantity string
     * @param category optional category label
     * @param buyPrice unit buy price
     * @param sellPrice unit sell price
     * @param manualStatus optional manual status override, or {@code null} for automatic status
     * @return operation result containing the persisted product or detailed validation errors
     */
    public InventoryOperationResult<Product> addProductFromInputs(
            String rawName,
            String rawQuantity,
            String category,
            double buyPrice,
            double sellPrice,
            StockStatus manualStatus) {
        ValidationResult rawCheck = ProductValidator.validateRawInputs(
                rawName,
                rawQuantity,
                "10",
                String.valueOf(buyPrice),
                String.valueOf(sellPrice)
        );
        if (!rawCheck.isValid()) {
            return InventoryOperationResult.invalid(rawCheck);
        }

        Product product = new Product();
        product.setName(rawName.trim());
        product.setQuantity(ProductValidator.parseQuantity(rawQuantity));
        product.setCategory(category != null && !category.isBlank() ? category.trim() : "General");
        product.setBuyPrice(buyPrice);
        product.setSellPrice(sellPrice);
        if (manualStatus != null) {
            product.setManualStatusOverride(true);
            product.setStatus(manualStatus);
        } else {
            product.setManualStatusOverride(false);
            product.refreshStatusIfAutomatic();
        }
        return addProduct(product);
    }

    /**
     * Validates and adds a populated {@link Product} to the store inventory, recording the command for Undo.
     *
     * @param product product entity to add
     * @return operation result with persisted product (including generated SKU) or validation failure
     */
    public InventoryOperationResult<Product> addProduct(Product product) {
        ValidationResult validation = ProductValidator.validateProduct(product);
        if (!validation.isValid()) {
            return InventoryOperationResult.invalid(validation);
        }

        Product sanitized = Product.copyOf(product);
        sanitized.setName(sanitized.getName().trim());
        sanitized.refreshStatusIfAutomatic();
        if ((sanitized.getPhotoPath() == null || sanitized.getPhotoPath().isBlank()) && productImageService != null) {
            sanitized.setPhotoPath(productImageService.resolveImagePath(
                    sanitized.getName(), sanitized.getCategory(), false));
        }

        AddProductCommand command = new AddProductCommand(productRepository, sanitized);
        historyManager.execute(command);
        Product saved = command.getInsertedProduct();
        eventBus.publish("PRODUCT_ADDED");
        return InventoryOperationResult.ok(saved, "Added '" + saved.getName() + "' (" + saved.getSku() + ").");
    }

    /**
     * Retrieves all active (non-archived) items in the inventory.
     *
     * @return list of active products; empty list if store contains zero items
     */
    public List<Product> getAllProducts() {
        return productRepository.findAll(false);
    }

    /**
     * Retrieves store items with optional inclusion of archived/discontinued records.
     *
     * @param includeArchived {@code true} to include archived items
     * @return list of matching products
     */
    public List<Product> getAllProducts(boolean includeArchived) {
        return productRepository.findAll(includeArchived);
    }

    /**
     * Returns an appropriate catalog status message, handling empty stores gracefully.
     *
     * @return empty-store guidance message when catalog is empty, or item count summary
     */
    public String viewInventorySummaryMessage() {
        int count = productRepository.countActive();
        if (count == 0) {
            return "Inventory is currently empty. Add your first product or load demo data.";
        }
        return "Showing " + count + " product(s) in inventory.";
    }

    /**
     * Finds a product safely by database ID.
     *
     * @param id product row ID
     * @return {@link Optional} containing the product if present
     */
    public Optional<Product> findById(long id) {
        return productRepository.findById(id);
    }

    /**
     * Finds a product safely by unique SKU code.
     *
     * @param sku SKU code
     * @return {@link Optional} containing the product if present
     */
    public Optional<Product> findBySku(String sku) {
        return productRepository.findBySku(sku);
    }

    /**
     * Updates an existing product after checking existence and validating all edited fields.
     *
     * @param updated updated product state containing a valid existing ID
     * @return operation result indicating success, validation failure, or graceful missing-item notice
     */
    public InventoryOperationResult<Product> updateProduct(Product updated) {
        if (updated == null || updated.getId() <= 0) {
            return InventoryOperationResult.fail("Item does not exist (invalid product ID).");
        }
        Optional<Product> existingOpt = productRepository.findById(updated.getId());
        if (existingOpt.isEmpty()) {
            return InventoryOperationResult.fail("Cannot update: product with ID " + updated.getId() + " was not found.");
        }

        ValidationResult validation = ProductValidator.validateProduct(updated);
        if (!validation.isValid()) {
            return InventoryOperationResult.invalid(validation);
        }

        Product sanitized = Product.copyOf(updated);
        sanitized.setName(sanitized.getName().trim());
        sanitized.refreshStatusIfAutomatic();

        UpdateProductCommand command = new UpdateProductCommand(productRepository, existingOpt.get(), sanitized);
        historyManager.execute(command);
        eventBus.publish("PRODUCT_UPDATED");
        return InventoryOperationResult.ok(Product.copyOf(sanitized), "Updated '" + sanitized.getName() + "'.");
    }

    /**
     * Applies or clears a manual stock status override on an existing product.
     *
     * @param productId target product ID
     * @param status manual status to enforce when {@code manualOverride} is {@code true}
     * @param manualOverride {@code true} to lock manual status, {@code false} to resume automatic calculation
     * @param authorized {@code true} if the current user role permits manual status overrides
     * @return operation result reflecting the updated product or missing-item/permission error
     */
    public InventoryOperationResult<Product> setStockStatusOverride(
            long productId,
            StockStatus status,
            boolean manualOverride,
            boolean authorized) {
        if (!authorized) {
            return InventoryOperationResult.fail("Permission denied: only Owner or Manager may override stock status.");
        }
        Optional<Product> existingOpt = productRepository.findById(productId);
        if (existingOpt.isEmpty()) {
            return InventoryOperationResult.fail("Cannot update status: product ID " + productId + " does not exist.");
        }

        Product updated = Product.copyOf(existingOpt.get());
        updated.setManualStatusOverride(manualOverride);
        if (manualOverride && status != null) {
            updated.setStatus(status);
        } else {
            updated.refreshStatusIfAutomatic();
        }
        return updateProduct(updated);
    }

    /**
     * Safely removes an existing item from inventory and stores a reversible snapshot for Undo.
     *
     * @param productId target product row ID
     * @return operation result indicating deletion success or graceful missing-item message
     */
    public InventoryOperationResult<Product> deleteProduct(long productId) {
        if (productId <= 0) {
            return InventoryOperationResult.fail("Cannot delete: invalid product ID.");
        }
        Optional<Product> existingOpt = productRepository.findById(productId);
        if (existingOpt.isEmpty()) {
            return InventoryOperationResult.fail("Cannot delete: product ID " + productId + " does not exist.");
        }

        Product snapshot = existingOpt.get();
        DeleteProductCommand command = new DeleteProductCommand(productRepository, snapshot);
        historyManager.execute(command);
        eventBus.publish("PRODUCT_DELETED");
        return InventoryOperationResult.ok(snapshot, "Deleted '" + snapshot.getName() + "'.");
    }

    /**
     * Archives or restores a product instead of hard-deleting it.
     *
     * @param productId target product row ID
     * @param archived {@code true} to archive as discontinued, {@code false} to unarchive
     * @return operation result with updated product or missing-item message
     */
    public InventoryOperationResult<Product> setArchived(long productId, boolean archived) {
        Optional<Product> existingOpt = productRepository.findById(productId);
        if (existingOpt.isEmpty()) {
            return InventoryOperationResult.fail("Cannot archive: product ID " + productId + " does not exist.");
        }
        Product copy = Product.copyOf(existingOpt.get());
        copy.setArchived(archived);
        return updateProduct(copy);
    }

    /**
     * Reverts the most recent reversible inventory action.
     *
     * @return operation result describing what was undone
     */
    public InventoryOperationResult<String> undoLastAction() {
        Optional<String> undone = historyManager.undo();
        if (undone.isEmpty()) {
            return InventoryOperationResult.fail("Nothing to undo.");
        }
        eventBus.publish("INVENTORY_UNDONE");
        return InventoryOperationResult.ok(undone.get(), "Undid: " + undone.get());
    }

    /**
     * Re-applies the most recently undone inventory action.
     *
     * @return operation result describing what was redone
     */
    public InventoryOperationResult<String> redoLastAction() {
        Optional<String> redone = historyManager.redo();
        if (redone.isEmpty()) {
            return InventoryOperationResult.fail("Nothing to redo.");
        }
        eventBus.publish("INVENTORY_REDONE");
        return InventoryOperationResult.ok(redone.get(), "Redid: " + redone.get());
    }

    /**
     * Returns the command history manager used for Ctrl+Z / Ctrl+Y state queries.
     *
     * @return active {@link CommandHistoryManager}
     */
    public CommandHistoryManager getHistoryManager() {
        return historyManager;
    }

    /**
     * Returns the underlying repository for batch imports, seeding, and health telemetry.
     *
     * @return {@link ProductRepository} instance
     */
    public ProductRepository getProductRepository() {
        return productRepository;
    }

    /**
     * Filters active products using status chip, category, search query, and optional price ceiling.
     *
     * @param criteria active {@link InventoryFilterCriteria}
     * @return filtered list of matching products
     */
    public List<Product> filterProducts(InventoryFilterCriteria criteria) {
        InventoryFilterCriteria active = criteria != null ? criteria : new InventoryFilterCriteria();
        LocalDate expiryHorizon = LocalDate.now().plusDays(30);
        String queryLower = active.getSearchQuery().toLowerCase(Locale.ROOT);
        String cat = active.getCategory();

        return getAllProducts().stream().filter(p -> {
            boolean matchesChip = switch (active.getStatusChip()) {
                case ALL -> true;
                case IN_STOCK -> p.getStatus() == StockStatus.IN_STOCK;
                case LOW_STOCK -> p.getStatus() == StockStatus.LOW_STOCK;
                case OUT_OF_STOCK -> p.getStatus() == StockStatus.OUT_OF_STOCK;
                case EXPIRING -> p.getExpiryDate() != null && !p.getExpiryDate().isAfter(expiryHorizon);
            };
            if (!matchesChip) {
                return false;
            }
            if (cat != null && !"All Categories".equalsIgnoreCase(cat)) {
                if (p.getCategory() == null || !p.getCategory().equalsIgnoreCase(cat)) {
                    return false;
                }
            }
            if (active.getMaxSellPrice() != null && p.getSellPrice() > active.getMaxSellPrice()) {
                return false;
            }
            if (!queryLower.isEmpty()) {
                boolean nameHit = p.getName() != null && p.getName().toLowerCase(Locale.ROOT).contains(queryLower);
                boolean skuHit = p.getSku() != null && p.getSku().toLowerCase(Locale.ROOT).contains(queryLower);
                boolean tagHit = p.getTags() != null && p.getTags().toLowerCase(Locale.ROOT).contains(queryLower);
                boolean catHit = p.getCategory() != null && p.getCategory().toLowerCase(Locale.ROOT).contains(queryLower);
                return nameHit || skuHit || tagHit || catHit;
            }
            return true;
        }).toList();
    }

    /**
     * Updates the stock status of multiple selected products in bulk.
     *
     * @param productIds collection of product IDs
     * @param newStatus target {@link StockStatus}
     * @param authorized whether the current role permits bulk status overrides
     * @return count of updated products wrapped in an {@link InventoryOperationResult}
     */
    public InventoryOperationResult<Integer> bulkUpdateStatus(
            Collection<Long> productIds, StockStatus newStatus, boolean authorized) {
        if (!authorized) {
            return InventoryOperationResult.fail("Only Owner or Manager may perform bulk status updates.");
        }
        if (productIds == null || productIds.isEmpty()) {
            return InventoryOperationResult.fail("No items selected.");
        }
        int updatedCount = 0;
        for (Long id : productIds) {
            Optional<Product> existingOpt = productRepository.findById(id);
            if (existingOpt.isPresent()) {
                Product copy = Product.copyOf(existingOpt.get());
                copy.setManualStatusOverride(true);
                copy.setStatus(newStatus);
                if (productRepository.update(copy)) {
                    updatedCount++;
                }
            }
        }
        eventBus.publish("BULK_STATUS_UPDATED");
        return InventoryOperationResult.ok(updatedCount, "Updated status to " + newStatus + " for " + updatedCount + " item(s).");
    }

    /**
     * Deletes multiple selected products in bulk.
     *
     * @param productIds collection of product IDs to delete
     * @param authorized whether current role allows bulk deletion
     * @return count of deleted products wrapped in an {@link InventoryOperationResult}
     */
    public InventoryOperationResult<Integer> bulkDelete(Collection<Long> productIds, boolean authorized) {
        if (!authorized) {
            return InventoryOperationResult.fail("Only Owner or Manager may bulk-delete items.");
        }
        if (productIds == null || productIds.isEmpty()) {
            return InventoryOperationResult.fail("No items selected.");
        }
        int deleted = 0;
        for (Long id : productIds) {
            if (productRepository.deleteById(id)) {
                deleted++;
            }
        }
        eventBus.publish("BULK_DELETED");
        return InventoryOperationResult.ok(deleted, "Removed " + deleted + " item(s) from inventory.");
    }

    /**
     * Loads 6 starter demo items matching the store mockups when testing from an empty catalog.
     *
     * @return count of newly inserted items
     */
    public int loadStarterDemoItems() {
        Object[][] samples = {
                {"Rice 5kg", "Groceries", 120, 2.50, 3.50, "A1-01", null},
                {"Milk 1L", "Dairy", 5, 1.00, 1.50, "B2-03", LocalDate.now().plusDays(4)},
                {"Bread", "Bakery", 0, 0.70, 1.00, "B1-02", LocalDate.now().plusDays(2)},
                {"Chips", "Snacks", 50, 1.20, 1.80, "C1-05", null},
                {"Cola 330ml", "Drinks", 200, 0.80, 1.20, "C2-01", null},
                {"Eggs (12)", "Dairy", 8, 2.00, 2.80, "B2-06", LocalDate.now().plusDays(6)}
        };
        int added = 0;
        for (Object[] row : samples) {
            Product p = new Product();
            p.setName((String) row[0]);
            p.setCategory((String) row[1]);
            p.setQuantity((Integer) row[2]);
            p.setBuyPrice((Double) row[3]);
            p.setSellPrice((Double) row[4]);
            p.setShelfLocation((String) row[5]);
            p.setExpiryDate((LocalDate) row[6]);
            p.setLowStockThreshold(10);
            if (addProduct(p).isSuccess()) {
                added++;
            }
        }
        return added;
    }
}
