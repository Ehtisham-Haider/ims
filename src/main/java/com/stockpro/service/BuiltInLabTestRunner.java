package com.stockpro.service;

import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.service.command.CommandHistoryManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Executes all 8 mandatory {@code 30B.docx} Open-Ended Lab verification checks live against
 * an isolated sandbox SQLite database for Settings viva demonstrations.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class BuiltInLabTestRunner {

    /**
     * Result of a single mandatory lab test case.
     *
     * @param caseNumber 1-based test case index (1..8)
     * @param testCaseName mandatory test title from {@code 30B.docx}
     * @param expectedResult expected outcome specification
     * @param actualResult observed actual outcome
     * @param passed {@code true} if the case passed
     * @author Ehtisham Haider, Syed Ahmed
     */
    public record LabCaseResult(
            int caseNumber,
            String testCaseName,
            String expectedResult,
            String actualResult,
            boolean passed
    ) {
    }

    /**
     * Creates a built-in lab test runner.
     */
    public BuiltInLabTestRunner() {
    }

    /**
     * Runs all 8 mandatory lab test cases inside an isolated temporary SQLite database and cleans up afterward.
     *
     * @return ordered list of 8 {@link LabCaseResult} entries
     */
    public List<LabCaseResult> runAllMandatoryLabChecks() {
        List<LabCaseResult> results = new ArrayList<>();
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("stockpro-lab-qa-");
            Path dbPath = tempDir.resolve("lab-qa.db");
            try (DatabaseManager sandboxDb = DatabaseManager.createForFile(dbPath)) {
                ProductRepository repo = new ProductRepository(sandboxDb);
                InventoryService svc = new InventoryService(repo, new CommandHistoryManager(), InventoryEventBus.getInstance());

                // 5. View an empty inventory first
                List<Product> emptyList = svc.getAllProducts();
                String emptyMsg = svc.viewInventorySummaryMessage();
                boolean c5 = emptyList.isEmpty() && emptyMsg.toLowerCase().contains("empty");
                results.add(new LabCaseResult(5, "View an empty inventory", "Appropriate message shown",
                        emptyMsg, c5));

                // 1. Add a valid item
                InventoryOperationResult<Product> addRes = svc.addProductFromInputs(
                        "Rice 5kg", "40", "Groceries", 2.50, 3.50, null);
                boolean c1 = addRes.isSuccess() && addRes.getData() != null && "PRD-1001".equals(addRes.getData().getSku());
                results.add(new LabCaseResult(1, "Add a valid item", "Item is added",
                        addRes.getMessage(), c1));

                // 2. Add an item with an empty name
                InventoryOperationResult<Product> emptyNameRes = svc.addProductFromInputs(
                        "   ", "10", "General", 1.0, 2.0, null);
                boolean c2 = !emptyNameRes.isSuccess() && emptyNameRes.getMessage().toLowerCase().contains("name");
                results.add(new LabCaseResult(2, "Add an item with an empty name", "Validation message shown",
                        emptyNameRes.getMessage(), c2));

                // 3. Add an item with a negative or non-numeric quantity
                InventoryOperationResult<Product> negRes = svc.addProductFromInputs(
                        "Milk 1L", "-5", "Dairy", 1.0, 1.5, null);
                InventoryOperationResult<Product> alphaRes = svc.addProductFromInputs(
                        "Milk 1L", "abc", "Dairy", 1.0, 1.5, null);
                boolean c3 = !negRes.isSuccess() && !alphaRes.isSuccess();
                results.add(new LabCaseResult(3, "Add an item with a negative or non-numeric quantity",
                        "Error message shown", negRes.getMessage() + " / " + alphaRes.getMessage(), c3));

                // 4. View all items
                svc.addProductFromInputs("Milk 1L", "5", "Dairy", 1.0, 1.5, null);
                List<Product> all = svc.getAllProducts();
                boolean c4 = all.size() == 2;
                results.add(new LabCaseResult(4, "View all items", "All items displayed",
                        "Displayed " + all.size() + " item(s)", c4));

                // 6. Update an existing item
                Product target = Product.copyOf(addRes.getData());
                target.setQuantity(3);
                target.setName("Rice 5kg Premium");
                InventoryOperationResult<Product> updRes = svc.updateProduct(target);
                Product reloaded = svc.findById(target.getId()).orElseThrow();
                boolean c6 = updRes.isSuccess() && reloaded.getQuantity() == 3
                        && reloaded.getStatus() == StockStatus.LOW_STOCK;
                results.add(new LabCaseResult(6, "Update an existing item", "Details updated",
                        "Updated to qty=3 (" + reloaded.getStatus().getDisplayName() + ")", c6));

                // 7. Update a non-existing item
                Product ghost = new Product("Non-Existing", 10, StockStatus.IN_STOCK);
                ghost.setId(999999L);
                InventoryOperationResult<Product> missingUpd = svc.updateProduct(ghost);
                boolean c7 = !missingUpd.isSuccess() && missingUpd.getMessage().toLowerCase().contains("not found");
                results.add(new LabCaseResult(7, "Update a non-existing item", "Handled gracefully",
                        missingUpd.getMessage(), c7));

                // 8. Delete an existing item
                InventoryOperationResult<Product> delRes = svc.deleteProduct(target.getId());
                boolean c8 = delRes.isSuccess() && svc.findById(target.getId()).isEmpty();
                results.add(new LabCaseResult(8, "Delete an existing item", "Item removed",
                        delRes.getMessage(), c8));
            }
        } catch (Exception ex) {
            results.add(new LabCaseResult(0, "Lab Runner Sandbox Error", "Zero exceptions", ex.getMessage(), false));
        } finally {
            if (tempDir != null) {
                try (var walk = Files.walk(tempDir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }
        results.sort(Comparator.comparingInt(LabCaseResult::caseNumber));
        return results;
    }
}
