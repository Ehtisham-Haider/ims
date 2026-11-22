package com.stockpro.service.command;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Manages dual Undo and Redo stacks using the Command Pattern for Ctrl+Z / Ctrl+Y reversals.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class CommandHistoryManager {
    private static final int DEFAULT_MAX_HISTORY = 50;

    private final Deque<InventoryCommand> undoStack = new ArrayDeque<>();
    private final Deque<InventoryCommand> redoStack = new ArrayDeque<>();
    private final int maxHistory;

    /**
     * Creates a command history manager with default stack capacity of 50 actions.
     */
    public CommandHistoryManager() {
        this(DEFAULT_MAX_HISTORY);
    }

    /**
     * Creates a command history manager with a custom stack capacity.
     *
     * @param maxHistory maximum number of undo steps retained
     */
    public CommandHistoryManager(int maxHistory) {
        this.maxHistory = Math.max(1, maxHistory);
    }

    /**
     * Executes a command immediately, pushes it onto the undo stack, and clears stale redo history.
     *
     * @param command command to execute and record
     */
    public synchronized void execute(InventoryCommand command) {
        command.execute();
        if (undoStack.size() >= maxHistory) {
            undoStack.removeLast();
        }
        undoStack.push(command);
        redoStack.clear();
    }

    /**
     * Reverts the most recently executed command and moves it onto the redo stack.
     *
     * @return description of the undone action, or empty if nothing can be undone
     */
    public synchronized Optional<String> undo() {
        if (undoStack.isEmpty()) {
            return Optional.empty();
        }
        InventoryCommand command = undoStack.pop();
        command.undo();
        redoStack.push(command);
        return Optional.of(command.getDescription());
    }

    /**
     * Re-applies the most recently undone command and moves it back onto the undo stack.
     *
     * @return description of the redone action, or empty if nothing can be redone
     */
    public synchronized Optional<String> redo() {
        if (redoStack.isEmpty()) {
            return Optional.empty();
        }
        InventoryCommand command = redoStack.pop();
        command.execute();
        undoStack.push(command);
        return Optional.of(command.getDescription());
    }

    /**
     * Indicates whether at least one command is available to undo.
     *
     * @return {@code true} if undo stack is non-empty
     */
    public synchronized boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /**
     * Indicates whether at least one command is available to redo.
     *
     * @return {@code true} if redo stack is non-empty
     */
    public synchronized boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * Clears both undo and redo stacks.
     */
    public synchronized void clear() {
        undoStack.clear();
        redoStack.clear();
    }
}
