package com.stockpro.service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Lightweight Observer bus notifying UI controllers and search indices whenever inventory data changes.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public final class InventoryEventBus {
    private static final InventoryEventBus INSTANCE = new InventoryEventBus();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Prevents external instantiation of singleton event bus.
     */
    private InventoryEventBus() {
    }

    /**
     * Returns the shared {@link InventoryEventBus} instance.
     *
     * @return singleton event bus
     */
    public static InventoryEventBus getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a subscriber to receive change event topics.
     *
     * @param listener consumer receiving event descriptions
     */
    public void subscribe(Consumer<String> listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a previously registered subscriber.
     *
     * @param listener listener reference to detach
     */
    public void unsubscribe(Consumer<String> listener) {
        listeners.remove(listener);
    }

    /**
     * Broadcasts an inventory change event to all subscribers.
     *
     * @param topic event name such as {@code PRODUCT_ADDED} or {@code STOCK_UPDATED}
     */
    public void publish(String topic) {
        for (Consumer<String> listener : listeners) {
            listener.accept(topic);
        }
    }
}
