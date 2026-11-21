package com.stockpro.ui.views;

import com.stockpro.domain.User;
import com.stockpro.domain.UserRole;
import com.stockpro.service.AuthService;
import com.stockpro.service.InventoryOperationResult;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * Split-screen login experience styled after {@code Screens and popups/Login.png} with BCrypt authentication
 * and instant Demo Mode access.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class LoginView {
    private final AuthService authService;
    private final Consumer<User> onLoginSuccess;

    /**
     * Creates a login view builder.
     *
     * @param authService authentication service validating credentials via jBCrypt
     * @param onLoginSuccess callback invoked once a user signs in
     */
    public LoginView(AuthService authService, Consumer<User> onLoginSuccess) {
        this.authService = authService;
        this.onLoginSuccess = onLoginSuccess;
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
        tagline.setStyle("-fx-text-fill: #C7D2FE; -fx-font-size: 14px; -fx-padding: 12 0 0 0;");

        brandContent.getChildren().addAll(StockProLogo.create(74), brandTitle, brandSub, tagline);
        leftPane.getChildren().add(brandContent);

        // Right Sign-In Panel
        VBox rightPane = new VBox(18);
        rightPane.getStyleClass().add("login-form-pane");
        rightPane.setPrefWidth(480);
        rightPane.setAlignment(Pos.CENTER);
        rightPane.setPadding(new Insets(48, 56, 48, 56));
        HBox.setHgrow(rightPane, Priority.ALWAYS);

        VBox formBox = new VBox(14);
        formBox.setMaxWidth(360);

        Label heading = new Label("Welcome Back");
        heading.getStyleClass().add("page-title");

        Label subHeading = new Label("Sign in to continue");
        subHeading.getStyleClass().add("page-subtitle");

        TextField usernameField = new TextField("admin");
        usernameField.setPromptText("Username");
        usernameField.setPrefHeight(42);

        PasswordField passwordField = new PasswordField();
        passwordField.setText("admin123");
        passwordField.setPromptText("Password");
        passwordField.setPrefHeight(42);

        ComboBox<UserRole> roleCombo = new ComboBox<>();
        roleCombo.getItems().addAll(UserRole.OWNER, UserRole.MANAGER, UserRole.CASHIER);
        roleCombo.setValue(UserRole.OWNER);
        roleCombo.setMaxWidth(Double.MAX_VALUE);
        roleCombo.setPrefHeight(42);

        Label feedbackLabel = new Label();
        feedbackLabel.getStyleClass().add("error-label");
        feedbackLabel.setWrapText(true);
        feedbackLabel.setVisible(false);
        feedbackLabel.setManaged(false);

        Button loginBtn = new Button("Login");
        loginBtn.getStyleClass().add("btn-primary");
        loginBtn.setMaxWidth(Double.MAX_VALUE);
        loginBtn.setPrefHeight(42);

        Button demoBtn = new Button("Try Demo Mode");
        demoBtn.getStyleClass().add("btn-secondary");
        demoBtn.setMaxWidth(Double.MAX_VALUE);
        demoBtn.setPrefHeight(40);

        Hyperlink forgotLink = new Hyperlink("Forgot Password?");
        forgotLink.setStyle("-fx-text-fill: #4F46E5;");

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
                feedbackLabel.setStyle("-fx-text-fill: #DC2626;");
                feedbackLabel.setVisible(true);
                feedbackLabel.setManaged(true);
            }
        };

        loginBtn.setOnAction(e -> submitLogin.run());
        passwordField.setOnAction(e -> submitLogin.run());

        demoBtn.setOnAction(e -> {
            InventoryOperationResult<User> res = authService.loginDemoMode(roleCombo.getValue());
            if (res.isSuccess()) {
                onLoginSuccess.accept(res.getData());
            }
        });

        forgotLink.setOnAction(e -> {
            feedbackLabel.setText("Built-in accounts: admin/admin123 (Owner), manager/manager123 (Manager), cashier/cashier123 (Cashier)");
            feedbackLabel.setStyle("-fx-text-fill: #4F46E5;");
            feedbackLabel.setVisible(true);
            feedbackLabel.setManaged(true);
        });

        formBox.getChildren().addAll(
                heading,
                subHeading,
                usernameField,
                passwordField,
                roleCombo,
                feedbackLabel,
                loginBtn,
                demoBtn,
                linkRow
        );
        rightPane.getChildren().add(formBox);

        splitRoot.getChildren().addAll(leftPane, rightPane);
        return splitRoot;
    }
}
