package com.stockpro;

import com.stockpro.repository.DatabaseManager;
import com.stockpro.service.AppSession;
import com.stockpro.service.ServiceContext;
import com.stockpro.ui.views.LoginView;
import com.stockpro.ui.views.MainShellView;
import com.stockpro.util.AppExecutor;
import com.stockpro.util.AppLogger;
import com.stockpro.util.GlobalExceptionHandler;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.slf4j.Logger;

/**
 * Primary JavaFX application entrypoint launching the split-screen login and main StockPro shell.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class StockProApp extends Application {
    private static final Logger LOG = AppLogger.get(StockProApp.class);
    private Scene mainScene;

    /**
     * Creates the StockPro JavaFX application instance.
     */
    public StockProApp() {
    }

    /**
     * Initializes exception handling, builds the stage, boots services asynchronously, and displays the Login view.
     *
     * @param stage primary JavaFX window stage
     */
    @Override
    public void start(Stage stage) {
        GlobalExceptionHandler.install();
        LOG.info("Starting StockPro desktop application...");

        StackPane loadingRoot = new StackPane(new Label("Starting StockPro..."));
        loadingRoot.getStyleClass().add("app-canvas");
        mainScene = new Scene(loadingRoot, 1280, 800);
        applyActiveTheme();

        stage.setTitle("StockPro — Inventory Management System");
        stage.setMinWidth(1040);
        stage.setMinHeight(680);
        stage.setScene(mainScene);
        stage.show();

        AppExecutor.runAsync(
                ServiceContext::getInstance,
                this::showLoginScreen,
                ex -> LOG.error("Failed to boot StockPro services", ex)
        );
    }

    /**
     * Switches the primary scene root to the split-screen login screen.
     *
     * @param services initialized application service context
     */
    private void showLoginScreen(ServiceContext services) {
        applyActiveTheme();
        LoginView loginView = new LoginView(
                services.getAuthService(),
                user -> showMainShell(services)
        );
        Parent root = loginView.build();
        mainScene.setRoot(root);
    }

    /**
     * Switches the primary scene root to the main application shell after successful authentication.
     *
     * @param services initialized application service context
     */
    private void showMainShell(ServiceContext services) {
        MainShellView shell = new MainShellView(
                services,
                () -> showLoginScreen(services),
                this::applyActiveTheme
        );
        Parent root = shell.build();
        mainScene.setRoot(root);
        shell.bindShortcuts(mainScene);
        applyActiveTheme();
    }

    /**
     * Applies either {@code light-theme.css} or {@code dark-theme.css} immediately without restart.
     */
    private void applyActiveTheme() {
        if (mainScene == null) {
            return;
        }
        mainScene.getStylesheets().clear();
        String path = AppSession.getInstance().isDarkMode()
                ? "/css/dark-theme.css"
                : "/css/light-theme.css";
        var url = getClass().getResource(path);
        if (url != null) {
            mainScene.getStylesheets().add(url.toExternalForm());
        }
    }

    /**
     * Closes database connection pools gracefully on shutdown.
     */
    @Override
    public void stop() {
        DatabaseManager.getInstance().close();
    }

    /**
     * Launches the JavaFX runtime.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        launch(args);
    }
}
