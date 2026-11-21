package com.stockpro.util;

import javafx.application.Platform;
import javafx.concurrent.Task;
import org.slf4j.Logger;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Shared daemon thread pool ensuring database, file export, ML, and network tasks never block the JavaFX UI thread.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public final class AppExecutor {
    private static final Logger LOG = AppLogger.get(AppExecutor.class);
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "stockpro-worker");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Prevents instantiation of utility class.
     *
     * @author Ehtisham Haider, Syed Ahmed
     */
    private AppExecutor() {
    }

    /**
     * Submits a callable task to run asynchronously in the background pool.
     *
     * @param work background work unit
     * @param <T> result type returned by the work unit
     * @return {@link Future} representing the pending computation
     * @author Ehtisham Haider, Syed Ahmed
     */
    public static <T> Future<T> submit(Callable<T> work) {
        return POOL.submit(work);
    }

    /**
     * Executes background work inside a JavaFX {@link Task} and dispatches callbacks on the FX Application Thread.
     *
     * @param work background supplier callable
     * @param onSuccess callback invoked on the JavaFX thread with the produced result
     * @param onError callback invoked on the JavaFX thread when execution throws
     * @param <T> type of value produced by the background operation
     * @author Ehtisham Haider, Syed Ahmed
     */
    public static <T> void runAsync(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(event -> {
            if (onSuccess != null) {
                onSuccess.accept(task.getValue());
            }
        });
        task.setOnFailed(event -> {
            Throwable ex = task.getException();
            LOG.error("Background task failed: {}", ex != null ? ex.getMessage() : "unknown", ex);
            if (onError != null) {
                onError.accept(ex);
            }
        });
        POOL.execute(task);
    }

    /**
     * Runs a UI update safely on the JavaFX Application Thread.
     *
     * @param action runnable modifying scene graph nodes
     * @author Ehtisham Haider, Syed Ahmed
     */
    public static void onFxThread(Runnable action) {
        if (action == null) {
            return;
        }
        try {
            if (Platform.isFxApplicationThread()) {
                action.run();
            } else {
                Platform.runLater(action);
            }
        } catch (IllegalStateException ex) {
            action.run();
        }
    }
}
