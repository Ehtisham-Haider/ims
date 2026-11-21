package com.stockpro.util;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import org.slf4j.Logger;

/**
 * Installs a global uncaught exception handler that logs failures and prevents silent UI crashes.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public final class GlobalExceptionHandler implements Thread.UncaughtExceptionHandler {
    private static final Logger LOG = AppLogger.get(GlobalExceptionHandler.class);

    /**
     * Creates the global exception handler.
     *
     * @author Ehtisham Haider, Syed Ahmed
     */
    public GlobalExceptionHandler() {
    }

    /**
     * Registers this handler as the JVM default uncaught exception handler.
     *
     * @author Ehtisham Haider, Syed Ahmed
     */
    public static void install() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(handler);
        Thread.currentThread().setUncaughtExceptionHandler(handler);
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        LOG.error("Uncaught exception on thread [{}]: {}", thread != null ? thread.getName() : "unknown",
                throwable != null ? throwable.getMessage() : "no details", throwable);
        showErrorDialogIfToolkitReady(throwable);
    }

    /**
     * Displays a non-crashing alert dialog when the JavaFX toolkit is active.
     *
     * @param throwable unhandled exception to summarize
     * @author Ehtisham Haider, Syed Ahmed
     */
    private void showErrorDialogIfToolkitReady(Throwable throwable) {
        try {
            if (Platform.isFxApplicationThread()) {
                displayAlert(throwable);
            } else {
                Platform.runLater(() -> displayAlert(throwable));
            }
        } catch (IllegalStateException ignored) {
            // JavaFX platform is not started (e.g., headless unit test execution).
        }
    }

    /**
     * Builds and shows a concise error alert on the JavaFX Application Thread.
     *
     * @param throwable unhandled exception
     * @author Ehtisham Haider, Syed Ahmed
     */
    private void displayAlert(Throwable throwable) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Unexpected Application Error");
        alert.setHeaderText("StockPro encountered an unexpected issue");
        String message = throwable != null && throwable.getMessage() != null
                ? throwable.getMessage()
                : "Please check logs/stockpro.log for diagnostic details.";
        alert.setContentText(message);
        alert.show();
    }
}
