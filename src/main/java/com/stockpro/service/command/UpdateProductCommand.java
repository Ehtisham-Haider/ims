package com.stockpro.service.command;

import com.stockpro.domain.Product;
import com.stockpro.repository.ProductRepository;

/**
 * Reversible command that updates an existing product while retaining a before-snapshot for Undo.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class UpdateProductCommand implements InventoryCommand {
    private final ProductRepository repository;
    private final Product beforeSnapshot;
    private final Product afterSnapshot;

    /**
     * Creates an update-product command.
     *
     * @param repository product persistence repository
     * @param beforeSnapshot original state prior to edit
     * @param afterSnapshot modified state to persist
     */
    public UpdateProductCommand(ProductRepository repository, Product beforeSnapshot, Product afterSnapshot) {
        this.repository = repository;
        this.beforeSnapshot = Product.copyOf(beforeSnapshot);
        this.afterSnapshot = Product.copyOf(afterSnapshot);
    }

    @Override
    public void execute() {
        repository.update(Product.copyOf(afterSnapshot));
    }

    @Override
    public void undo() {
        repository.update(Product.copyOf(beforeSnapshot));
    }

    @Override
    public String getDescription() {
        return "Updated " + afterSnapshot.getName();
    }
}
