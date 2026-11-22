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
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/**
 * Primary JavaFX application entrypoint launching the split-screen login and main StockPro shell
 * with bounded desktop window sizing, native OS title-bar chrome, taskbar branding, and clean shutdown.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class StockProApp extends Application {
    private static final Logger LOG = AppLogger.get(StockProApp.class);
    private Scene mainScene;
    private Stage primaryStage;

    /**
     * Creates the StockPro JavaFX application instance.
     */
    public StockProApp() {
    }

    /**
     * Initializes exception handling, builds the stage inside the primary display's usable bounds,
     * boots services asynchronously, and displays the Login view.
     *
     * @param stage primary JavaFX window stage
     */
    @Override
    public void start(Stage stage) {
        GlobalExceptionHandler.install();
        LOG.info("Starting StockPro desktop application...");
        this.primaryStage = stage;

        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        double width = Math.min(1360, Math.max(980, bounds.getWidth() - 36));
        double height = Math.min(820, Math.max(600, bounds.getHeight() - 52));

        StackPane loadingRoot = new StackPane(new Label("Starting StockPro..."));
        loadingRoot.getStyleClass().add("app-canvas");
        mainScene = new Scene(loadingRoot, width, height);
        applyActiveTheme();

        stage.initStyle(StageStyle.DECORATED);
        stage.setTitle("StockPro — Inventory Management System");
        stage.getIcons().add(createAppIcon());
        stage.setMinWidth(Math.min(960, bounds.getWidth() - 40));
        stage.setMinHeight(Math.min(580, bounds.getHeight() - 60));
        stage.setScene(mainScene);
        stage.setOnCloseRequest(e -> shutdownAndExit());
        stage.show();

        stage.setX(bounds.getMinX() + Math.max(0, (bounds.getWidth() - stage.getWidth()) / 2.0));
        stage.setY(bounds.getMinY() + Math.max(4, (bounds.getHeight() - stage.getHeight()) / 2.0));

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
                user -> showMainShell(services),
                this::shutdownAndExit
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
                this::applyActiveTheme,
                this::shutdownAndExit
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
     * Generates a crisp 64×64 vector-rendered isometric StockPro cube icon for taskbar and title bar display.
     *
     * @return FX {@link Image}
     */
    private Image createAppIcon() {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setPaint(new GradientPaint(0, 0, new Color(79, 70, 229), 64, 64, new Color(124, 58, 237)));
        g.fillRoundRect(4, 4, 56, 56, 16, 16);

        Path2D hex = new Path2D.Double();
        hex.moveTo(32, 12);
        hex.lineTo(50, 22);
        hex.lineTo(50, 42);
        hex.lineTo(32, 52);
        hex.lineTo(14, 42);
        hex.lineTo(14, 22);
        hex.closePath();
        g.setColor(new Color(255, 255, 255, 42));
        g.fill(hex);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(hex);
        g.drawLine(32, 32, 32, 52);
        g.drawLine(32, 32, 50, 22);
        g.drawLine(32, 32, 14, 22);
        g.dispose();
        return SwingFXUtils.toFXImage(img, null);
    }

    /**
     * Gracefully releases pools, closes the window, and terminates the desktop process.
     */
    public void shutdownAndExit() {
        DatabaseManager.getInstance().close();
        AppExecutor.shutdown();
        if (primaryStage != null) {
            primaryStage.hide();
        }
        Platform.exit();
        System.exit(0);
    }

    /**
     * Closes database connection pools gracefully on shutdown.
     */
    @Override
    public void stop() {
        DatabaseManager.getInstance().close();
        AppExecutor.shutdown();
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
