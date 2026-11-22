package com.stockpro.ui.views;

import com.stockpro.domain.User;
import com.stockpro.domain.UserRole;
import com.stockpro.service.AuthService;
import com.stockpro.service.InventoryOperationResult;
import com.stockpro.ui.components.AppIcons;
import com.stockpro.ui.components.StockProLogo;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

import java.util.function.Consumer;

/**
 * Split-screen login experience styled after {@code Screens and popups/Login.png} with BCrypt authentication,
 * password reveal toggle, capability feature pills, window exit button, and instant Demo Mode access.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class LoginView {
    private final AuthService authService;
    private final Consumer<User> onLoginSuccess;
    private final Runnable onExitApp;

    /**
     * Creates a login view builder.
     *
     * @param authService authentication service validating credentials via jBCrypt
     * @param onLoginSuccess callback invoked once a user signs in
     */
    public LoginView(AuthService authService, Consumer<User> onLoginSuccess) {
        this(authService, onLoginSuccess, () -> {});
    }

    /**
     * Creates a login view builder with close/exit app control.
     *
     * @param authService authentication service validating credentials via jBCrypt
     * @param onLoginSuccess callback invoked once a user signs in
     * @param onExitApp callback exiting the desktop window
     */
    public LoginView(AuthService authService, Consumer<User> onLoginSuccess, Runnable onExitApp) {
        this.authService = authService;
        this.onLoginSuccess = onLoginSuccess;
        this.onExitApp = onExitApp != null ? onExitApp : () -> {};
    }

    /**
     * Builds the split-screen JavaFX login root node.
     *
     * @return root {@link Parent} ready to display in a JavaFX {@code Scene}
     */
    public Parent build() {
        HBox splitRoot = new HBox();
        splitRoot.setFillHeight(true);

        // Left Brand Showcase Panel
        StackPane leftPane = new StackPane();
        leftPane.getStyleClass().add("login-brand-pane");
        leftPane.setPrefWidth(480);
        HBox.setHgrow(leftPane, Priority.ALWAYS);

        VBox brandContent = new VBox(14);
        brandContent.setAlignment(Pos.CENTER);
        brandContent.setPadding(new Insets(40));

        Label brandTitle = new Label("StockPro");
        brandTitle.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 38px; -fx-font-weight: bold;");

        Label brandSub = new Label("Inventory Management System");
        brandSub.setStyle("-fx-text-fill: #E0E7FF; -fx-font-size: 16px; -fx-font-weight: 600;");

        Label tagline = new Label("Smarter Stock. Stronger Business.");
        tagline.setStyle("-fx-text-fill: #C7D2FE; -fx-font-size: 14px; -fx-padding: 6 0 12 0;");

        HBox pills = new HBox(10,
                makeGlassBadge(Feather.DATABASE, "Offline SQLite"),
                makeGlassBadge(Feather.CPU, "Offline ML & Groq AI"),
                makeGlassBadge(Feather.SHIELD, "SHA-256 Audit Seal")
        );
        pills.setAlignment(Pos.CENTER);

        brandContent.getChildren().addAll(StockProLogo.create(76), brandTitle, brandSub, tagline, pills);
        leftPane.getChildren().add(brandContent);

        // Right Sign-In Panel
        VBox rightHost = new VBox();
        rightHost.getStyleClass().add("login-form-pane");
        rightHost.setPrefWidth(480);
        HBox.setHgrow(rightHost, Priority.ALWAYS);

        HBox topStrip = new HBox();
        topStrip.setAlignment(Pos.CENTER_RIGHT);
        topStrip.setPadding(new Insets(16, 18, 0, 18));
        Button closeAppBtn = new Button("Exit");
        closeAppBtn.setGraphic(AppIcons.of(Feather.POWER, 14));
        closeAppBtn.getStyleClass().add("icon-btn-danger");
        closeAppBtn.setTooltip(new Tooltip("Close StockPro Application"));
        closeAppBtn.setOnAction(e -> onExitApp.run());
        topStrip.getChildren().add(closeAppBtn);

        VBox rightCenter = new VBox();
        rightCenter.setAlignment(Pos.CENTER);
        rightCenter.setPadding(new Insets(20, 56, 48, 56));
        VBox.setVgrow(rightCenter, Priority.ALWAYS);

        VBox formBox = new VBox(14);
        formBox.setMaxWidth(365);

        Label heading = new Label("Welcome Back");
        heading.getStyleClass().add("page-title");

        Label subHeading = new Label("Sign in to continue to your store workspace");
        subHeading.getStyleClass().add("page-subtitle");

        TextField usernameField = new TextField("admin");
        usernameField.setPromptText("Username");
        usernameField.setPrefHeight(42);
        HBox.setHgrow(usernameField, Priority.ALWAYS);

        HBox userRow = new HBox(8, AppIcons.of(Feather.USER, 16, "#64748B"), usernameField);
        userRow.setAlignment(Pos.CENTER_LEFT);

        PasswordField passwordField = new PasswordField();
        passwordField.setText("admin123");
        passwordField.setPromptText("Password");
        passwordField.setPrefHeight(42);

        TextField visiblePassField = new TextField("admin123");
        visiblePassField.setPromptText("Password");
        visiblePassField.setPrefHeight(42);
        visiblePassField.setVisible(false);
        visiblePassField.setManaged(false);
        visiblePassField.textProperty().bindBidirectional(passwordField.textProperty());

        StackPane passInputs = new StackPane(passwordField, visiblePassField);
        HBox.setHgrow(passInputs, Priority.ALWAYS);

        Button toggleEyeBtn = new Button();
        toggleEyeBtn.setGraphic(AppIcons.of(Feather.EYE, 15));
        toggleEyeBtn.getStyleClass().add("icon-btn");
        toggleEyeBtn.setTooltip(new Tooltip("Show / Hide Password"));
        toggleEyeBtn.setOnAction(e -> {
            boolean show = !visiblePassField.isVisible();
            visiblePassField.setVisible(show);
            visiblePassField.setManaged(show);
            passwordField.setVisible(!show);
            passwordField.setManaged(!show);
            toggleEyeBtn.setGraphic(AppIcons.of(show ? Feather.EYE_OFF : Feather.EYE, 15));
        });

        HBox passRow = new HBox(8, AppIcons.of(Feather.LOCK, 16, "#64748B"), passInputs, toggleEyeBtn);
        passRow.setAlignment(Pos.CENTER_LEFT);

        ComboBox<UserRole> roleCombo = new ComboBox<>();
        roleCombo.getItems().addAll(UserRole.OWNER, UserRole.MANAGER, UserRole.CASHIER);
        roleCombo.setValue(UserRole.OWNER);
        roleCombo.setMaxWidth(Double.MAX_VALUE);
        roleCombo.setPrefHeight(42);
        HBox.setHgrow(roleCombo, Priority.ALWAYS);

        HBox roleRow = new HBox(8, AppIcons.of(Feather.SHIELD, 16, "#64748B"), roleCombo);
        roleRow.setAlignment(Pos.CENTER_LEFT);

        Label feedbackLabel = new Label();
        feedbackLabel.getStyleClass().add("error-label");
        feedbackLabel.setWrapText(true);
        feedbackLabel.setVisible(false);
        feedbackLabel.setManaged(false);

        Button loginBtn = new Button("Login");
        loginBtn.getStyleClass().add("btn-primary");
        loginBtn.setMaxWidth(Double.MAX_VALUE);
        loginBtn.setPrefHeight(42);

        Button demoBtn = new Button("Try Demo Mode (1-Click Access)");
        demoBtn.setGraphic(AppIcons.of(Feather.PLAY, 14));
        demoBtn.getStyleClass().add("btn-secondary");
        demoBtn.setMaxWidth(Double.MAX_VALUE);
        demoBtn.setPrefHeight(40);

        Hyperlink forgotLink = new Hyperlink("Forgot Password?");
        forgotLink.setStyle("-fx-text-fill: #6366F1;");

        HBox linkRow = new HBox(forgotLink);
        linkRow.setAlignment(Pos.CENTER);

        Runnable submitLogin = () -> {
            InventoryOperationResult<User> res = authService.login(
                    usernameField.getText(),
                    passwordField.getText(),
                    roleCombo.getValue()
            );
            if (res.isSuccess()) {
                feedbackLabel.setVisible(false);
                feedbackLabel.setManaged(false);
                onLoginSuccess.accept(res.getData());
            } else {
                feedbackLabel.setText(res.getMessage());
                feedbackLabel.getStyleClass().setAll("error-label");
                feedbackLabel.setVisible(true);
                feedbackLabel.setManaged(true);
            }
        };

        loginBtn.setOnAction(e -> submitLogin.run());
        passwordField.setOnAction(e -> submitLogin.run());
        visiblePassField.setOnAction(e -> submitLogin.run());

        demoBtn.setOnAction(e -> {
            InventoryOperationResult<User> res = authService.loginDemoMode(roleCombo.getValue());
            if (res.isSuccess()) {
                onLoginSuccess.accept(res.getData());
            }
        });

        forgotLink.setOnAction(e -> {
            feedbackLabel.setText("Default credentials: admin/admin123 (Owner), manager/manager123 (Manager), cashier/cashier123 (Cashier)");
            feedbackLabel.getStyleClass().setAll("kpi-delta-accent");
            feedbackLabel.setVisible(true);
            feedbackLabel.setManaged(true);
        });

        Region gap = new Region();
        gap.setMinHeight(4);

        formBox.getChildren().addAll(
                heading,
                subHeading,
                gap,
                userRow,
                passRow,
                roleRow,
                feedbackLabel,
                loginBtn,
                demoBtn,
                linkRow
        );
        rightCenter.getChildren().add(formBox);
        rightHost.getChildren().addAll(topStrip, rightCenter);

        splitRoot.getChildren().addAll(leftPane, rightHost);
        return splitRoot;
    }

    private HBox makeGlassBadge(Feather feather, String labelText) {
        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-text-fill: #E0E7FF; -fx-font-size: 11.5px; -fx-font-weight: 600;");
        HBox pill = new HBox(6, AppIcons.of(feather, 13, "#E0E7FF"), lbl);
        pill.setAlignment(Pos.CENTER);
        pill.setPadding(new Insets(6, 11, 6, 11));
        pill.setStyle("-fx-background-color: rgba(255,255,255,0.14); -fx-background-radius: 999;");
        return pill;
    }
}
