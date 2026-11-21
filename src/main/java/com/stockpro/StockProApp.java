package com.stockpro;

import com.stockpro.repository.DatabaseManager;
import com.stockpro.util.AppExecutor;
import com.stockpro.util.AppLogger;
import com.stockpro.util.GlobalExceptionHandler;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;

/**
 * Primary JavaFX application entrypoint for StockPro Inventory Management System.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class StockProApp extends Application {
    private static final Logger LOG = AppLogger.get(StockProApp.class);
    private boolean darkTheme = false;

    /**
     * Creates the StockPro JavaFX application instance.
     *
     * @author Ehtisham Haider, Syed Ahmed
     */
    public StockProApp() {
    }

    /**
     * Starts the primary JavaFX stage, initializes local persistence off the UI thread, and applies theme stylesheets.
     *
     * @param primaryStage primary desktop window stage
     * @author Ehtisham Haider, Syed Ahmed
     */
    @Override
    public void start(Stage primaryStage) {
        GlobalExceptionHandler.install();
        LOG.info("Launching StockPro JavaFX application...");

        VBox root = new VBox(16);
        root.setPadding(new Insets(32));
        root.setAlignment(Pos.CENTER_LEFT);
        root.getStyleClass().add("app-canvas");

        Label title = new Label("StockPro Inventory Management System");
        title.getStyleClass().add("page-title");

        Label statusLabel = new Label("Initializing local SQLite database in offline mode...");
        statusLabel.getStyleClass().add("page-subtitle");

        Button themeToggle = new Button("Switch to Dark Mode");
        themeToggle.getStyleClass().add("btn-secondary");

        HBox actions = new HBox(12, themeToggle);

        VBox card = new VBox(12, title, statusLabel, actions);
        card.getStyleClass().add("card");
        root.getChildren().add(card);

        Scene scene = new Scene(root, 1080, 720);
        applyTheme(scene);

        themeToggle.setOnAction(event -> {
            darkTheme = !darkTheme;
            applyTheme(scene);
            themeToggle.setText(darkTheme ? "Switch to Light Mode" : "Switch to Dark Mode");
        });

        primaryStage.setTitle("StockPro — Inventory Management System");
        primaryStage.setMinWidth(960);
        primaryStage.setMinHeight(640);
        primaryStage.setScene(scene);
        primaryStage.show();

        AppExecutor.runAsync(
                () -> {
                    DatabaseManager db = DatabaseManager.getInstance();
                    return db.getJdbcUrl();
                },
                jdbcUrl -> statusLabel.setText("Offline SQLite ready: " + jdbcUrl),
                ex -> statusLabel.setText("Database initialization error: " + ex.getMessage())
        );
    }

    /**
     * Applies either the light or dark design-system stylesheet to the supplied scene.
     *
     * @param scene target JavaFX scene
     * @author Ehtisham Haider, Syed Ahmed
     */
    private void applyTheme(Scene scene) {
        scene.getStylesheets().clear();
        String path = darkTheme ? "/css/dark-theme.css" : "/css/light-theme.css";
        var url = getClass().getResource(path);
        if (url != null) {
            scene.getStylesheets().add(url.toExternalForm());
        }
    }

    /**
     * Closes database pools gracefully when the JavaFX lifecycle terminates.
     *
     * @author Ehtisham Haider, Syed Ahmed
     */
    @Override
    public void stop() {
        DatabaseManager.getInstance().close();
    }

    /**
     * Launches the JavaFX runtime.
     *
     * @param args command-line arguments
     * @author Ehtisham Haider, Syed Ahmed
     */
    public static void main(String[] args) {
        launch(args);
    }
}
