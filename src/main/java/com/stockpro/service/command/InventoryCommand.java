package com.stockpro.service.command;

/**
 * Command Pattern contract representing a reversible inventory mutation for Undo/Redo history.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public interface InventoryCommand {

    /**
     * Executes the inventory mutation forward.
     */
    void execute();

    /**
     * Reverts the inventory mutation to its prior state.
     */
    void undo();

    /**
     * Returns a concise summary of the action suitable for toast banners and history tooltips.
     *
     * @return human-readable action description
     */
    String getDescription();
}
