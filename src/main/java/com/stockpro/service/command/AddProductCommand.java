package com.stockpro.service.command;

import com.stockpro.domain.Product;
import com.stockpro.repository.ProductRepository;

/**
 * Reversible command that adds a product to the inventory repository.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class AddProductCommand implements InventoryCommand {
    private final ProductRepository repository;
    private final Product productToInsert;
    private Product insertedProduct;

    /**
     * Creates an add-product command.
     *
     * @param repository product persistence repository
     * @param productToInsert product payload to insert
     */
    public AddProductCommand(ProductRepository repository, Product productToInsert) {
        this.repository = repository;
        this.productToInsert = Product.copyOf(productToInsert);
    }

    @Override
    public void execute() {
        if (insertedProduct == null) {
            insertedProduct = Product.copyOf(repository.insert(Product.copyOf(productToInsert)));
        } else {
            insertedProduct = Product.copyOf(repository.restore(Product.copyOf(insertedProduct)));
        }
    }

    @Override
    public void undo() {
        if (insertedProduct != null && insertedProduct.getId() > 0) {
            repository.deleteById(insertedProduct.getId());
        }
    }

    @Override
    public String getDescription() {
        return "Added " + (insertedProduct != null ? insertedProduct.getName() : productToInsert.getName());
    }

    /**
     * Returns a safe snapshot of the inserted product after {@link #execute()} completes.
     *
     * @return persisted product snapshot with assigned id and SKU
     */
    public Product getInsertedProduct() {
        return insertedProduct != null ? Product.copyOf(insertedProduct) : null;
    }
}
