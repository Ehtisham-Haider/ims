package com.stockpro.service;

import com.stockpro.domain.MovementType;
import com.stockpro.domain.Product;
import com.stockpro.repository.ProductRepository;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Keyboard-wedge USB barcode scanner listener capturing rapid character bursts ending in {@code Enter}
 * to perform instant item lookup, stock-in restock, or sale deduction.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class UsbBarcodeScannerListener {

    /**
     * Defines how a completed hardware barcode scan is handled.
     *
     * @author Ehtisham Haider, Syed Ahmed
     */
    public enum ScanMode {
        /** Locate and highlight product details without modifying quantity. */
        LOOKUP,
        /** Increment stock by 1 unit via Restock movement. */
        STOCK_IN,
        /** Deduct 1 unit via Sale movement. */
        SALE_DEDUCT
    }

    /**
     * Outcome returned when processing a scanned barcode string.
     *
     * @param matched {@code true} if a product matched the scanned barcode or SKU
     * @param product matched product, or {@code null}
     * @param message concise result message for toast display
     * @author Ehtisham Haider, Syed Ahmed
     */
    public record ScanOutcome(boolean matched, Product product, String message) {
    }

    private static final long MAX_INTER_KEY_NANOS = 80_000_000L; // 80 ms keyboard-wedge threshold

    private final ProductRepository productRepository;
    private final StockOperationService stockOperationService;
    private final StringBuilder buffer = new StringBuilder();
    private long lastKeyTimestampNs = 0L;
    private ScanMode activeMode = ScanMode.LOOKUP;

    /**
     * Creates a USB barcode scanner listener wired to product and stock services.
     *
     * @param productRepository product repository
     * @param stockOperationService stock operation service
     */
    public UsbBarcodeScannerListener(
            ProductRepository productRepository,
            StockOperationService stockOperationService) {
        this.productRepository = productRepository;
        this.stockOperationService = stockOperationService;
    }

    /**
     * Installs an event filter onto a JavaFX {@link Scene} to capture hardware scanner bursts passively.
     *
     * @param scene host JavaFX scene
     * @param onScan callback notified with the processed {@link ScanOutcome}
     */
    public void attachToScene(Scene scene, Consumer<ScanOutcome> onScan) {
        if (scene == null) {
            return;
        }
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            long now = System.nanoTime();
            if (event.getCode() == KeyCode.ENTER) {
                Optional<String> completed = completeIfBurst(now);
                if (completed.isPresent()) {
                    ScanOutcome outcome = handleScannedCode(completed.get(), activeMode, AppSession.getInstance().getActorName());
                    if (onScan != null) {
                        onScan.accept(outcome);
                    }
                    event.consume();
                }
                return;
            }
            String text = event.getText();
            if (text != null && text.length() == 1 && !event.isControlDown() && !event.isAltDown()) {
                recordChar(text.charAt(0), now);
            }
        });
    }

    /**
     * Appends a character to the scanner burst buffer, resetting if typing gap exceeds 80ms.
     *
     * @param ch character received
     * @param timestampNanos monotonic nanosecond timestamp
     */
    public synchronized void recordChar(char ch, long timestampNanos) {
        if (lastKeyTimestampNs > 0 && (timestampNanos - lastKeyTimestampNs) > MAX_INTER_KEY_NANOS) {
            buffer.setLength(0);
        }
        buffer.append(ch);
        lastKeyTimestampNs = timestampNanos;
    }

    /**
     * Completes and drains the burst buffer on Enter if at least 4 rapid characters were buffered.
     *
     * @param timestampNanos Enter keystroke timestamp
     * @return scanned barcode string, or empty if input looks like normal slow human typing
     */
    public synchronized Optional<String> completeIfBurst(long timestampNanos) {
        String candidate = buffer.toString().trim();
        buffer.setLength(0);
        boolean withinBurst = lastKeyTimestampNs > 0 && (timestampNanos - lastKeyTimestampNs) <= MAX_INTER_KEY_NANOS * 2;
        lastKeyTimestampNs = 0L;
        if (candidate.length() >= 4 && withinBurst) {
            return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /**
     * Processes a completed barcode/SKU code according to the configured {@link ScanMode}.
     *
     * @param barcode scanned code
     * @param mode action mode ({@code LOOKUP}, {@code STOCK_IN}, or {@code SALE_DEDUCT})
     * @param actor operator display name
     * @return {@link ScanOutcome}
     */
    public ScanOutcome handleScannedCode(String barcode, ScanMode mode, String actor) {
        Optional<Product> opt = productRepository.findByBarcode(barcode);
        if (opt.isEmpty()) {
            return new ScanOutcome(false, null, "No product matched scanned code '" + barcode + "'.");
        }
        Product product = opt.get();
        if (mode == ScanMode.STOCK_IN) {
            var res = stockOperationService.recordOperation(
                    product.getId(), MovementType.RESTOCK, "1", "USB Barcode Scan-In", barcode, actor);
            Product refreshed = productRepository.findById(product.getId()).orElse(product);
            return new ScanOutcome(res.isSuccess(), refreshed, res.getMessage());
        }
        if (mode == ScanMode.SALE_DEDUCT) {
            var res = stockOperationService.recordOperation(
                    product.getId(), MovementType.SALE, "1", "USB Barcode Scan Sale", barcode, actor);
            Product refreshed = productRepository.findById(product.getId()).orElse(product);
            return new ScanOutcome(res.isSuccess(), refreshed, res.getMessage());
        }
        return new ScanOutcome(true, product, "Scanned " + product.getName() + " (" + product.getSku() + ")");
    }

    /**
     * Sets the active hardware scanner behavior mode.
     *
     * @param activeMode target {@link ScanMode}
     */
    public void setActiveMode(ScanMode activeMode) {
        if (activeMode != null) {
            this.activeMode = activeMode;
        }
    }

    /**
     * Returns the current hardware scanner mode.
     *
     * @return active {@link ScanMode}
     */
    public ScanMode getActiveMode() {
        return activeMode;
    }
}
