package com.stockpro.service.command;

import com.stockpro.domain.Product;
import com.stockpro.repository.ProductRepository;

/**
 * Reversible command that deletes a product and restores its snapshot on Undo.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class DeleteProductCommand implements InventoryCommand {
    private final ProductRepository repository;
    private final Product deletedSnapshot;

    /**
     * Creates a delete-product command.
     *
     * @param repository product persistence repository
     * @param deletedSnapshot full snapshot of the product being removed
     */
    public DeleteProductCommand(ProductRepository repository, Product deletedSnapshot) {
        this.repository = repository;
        this.deletedSnapshot = Product.copyOf(deletedSnapshot);
    }

    @Override
    public void execute() {
        repository.deleteById(deletedSnapshot.getId());
    }

    @Override
    public void undo() {
        repository.restore(Product.copyOf(deletedSnapshot));
    }

    @Override
    public String getDescription() {
        return "Deleted " + deletedSnapshot.getName();
    }
}
