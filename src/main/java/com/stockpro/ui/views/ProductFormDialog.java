package com.stockpro.ui.views;

import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.ValidationResult;
import com.stockpro.service.ProductImageService;
import com.stockpro.service.ProductValidator;
import com.stockpro.ui.components.AppIcons;
import com.stockpro.util.AppExecutor;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.kordamp.ikonli.feather.Feather;

import java.io.File;
import java.util.Map;
import java.util.Optional;

/**
 * Add/Edit Product dialog matching {@code Screens and popups/Add Product.png} with live red inline
 * field validation, photo picker, category/tag entry, shelf location, expiry date, substitute SKU,
 * archive toggle, and compact scrolling fit on 1280×752 laptop screens.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class ProductFormDialog {
    private Product result;
    private String selectedPhotoPath;

    /**
     * Creates a new Add/Edit Product modal helper.
     */
    public ProductFormDialog() {
    }

    /**
     * Opens the modal Add/Edit product dialog and waits until the user saves or cancels.
     *
     * @param owner parent window
     * @param existing optional product being edited, or {@code null} when adding a new product
     * @param darkMode whether dark CSS should be applied
     * @return {@link Optional} containing the validated {@link Product} on Save, or empty on Cancel
     */
    public Optional<Product> showAndWait(Window owner, Product existing, boolean darkMode) {
        return showAndWait(owner, existing, darkMode, null);
    }

    /**
     * Opens the modal Add/Edit product dialog with optional Pexels photo search fallback.
     *
     * @param owner parent window
     * @param existing optional product being edited, or {@code null} when adding a new product
     * @param darkMode whether dark CSS should be applied
     * @param imageService optional image resolver supporting Pexels search fallback
     * @return {@link Optional} containing the validated {@link Product} on Save, or empty on Cancel
     */
    public Optional<Product> showAndWait(
            Window owner,
            Product existing,
            boolean darkMode,
            ProductImageService imageService) {
        this.result = null;
        this.selectedPhotoPath = existing != null ? existing.getPhotoPath() : null;

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(existing == null ? "Add New Product" : "Edit Product — " + existing.getSku());

        VBox root = new VBox(14);
        root.getStyleClass().add("card");
        root.setPadding(new Insets(20));

        Label title = new Label(existing == null ? "Add New Product" : "Edit Product");
        title.getStyleClass().add("page-title");

        HBox body = new HBox(20);
        body.setAlignment(Pos.TOP_LEFT);

        // Left Photo Card
        VBox photoBox = new VBox(8);
        photoBox.setAlignment(Pos.CENTER);
        photoBox.setPrefWidth(164);

        StackPane photoFrame = new StackPane();
        photoFrame.setMinSize(144, 144);
        photoFrame.setPrefSize(144, 144);
        photoFrame.setMaxSize(144, 144);
        photoFrame.getStyleClass().add("surface-subtle");

        Label photoGlyph = new Label();
        photoGlyph.setGraphic(AppIcons.of(Feather.PACKAGE, 42, "#6366F1"));
        ImageView preview = new ImageView();
        preview.setFitWidth(134);
        preview.setFitHeight(134);
        preview.setPreserveRatio(true);
        if (selectedPhotoPath != null && new File(selectedPhotoPath).exists()) {
            preview.setImage(new Image(new File(selectedPhotoPath).toURI().toString(), 268, 268, true, true, true));
            photoGlyph.setVisible(false);
        }
        photoFrame.getChildren().addAll(photoGlyph, preview);

        Label photoHint = new Label();
        photoHint.getStyleClass().add("muted-text");
        photoHint.setStyle("-fx-font-size: 11px;");
        photoHint.setWrapText(true);
        photoHint.setMaxWidth(156);
        photoHint.setVisible(false);
        photoHint.setManaged(false);

        Button changePhotoBtn = new Button("Change Photo");
        changePhotoBtn.setGraphic(AppIcons.of(Feather.CAMERA, 14));
        changePhotoBtn.getStyleClass().add("btn-secondary");
        changePhotoBtn.setMaxWidth(Double.MAX_VALUE);
        changePhotoBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select Product Image");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif"));
            File chosen = chooser.showOpenDialog(dialog);
            if (chosen != null) {
                selectedPhotoPath = chosen.getAbsolutePath();
                preview.setImage(new Image(chosen.toURI().toString(), 268, 268, true, true, true));
                photoGlyph.setVisible(false);
                photoHint.setVisible(false);
                photoHint.setManaged(false);
            }
        });

        Button findPhotoBtn = new Button("Find Photo (Pexels)");
        findPhotoBtn.setGraphic(AppIcons.of(Feather.SEARCH, 13));
        findPhotoBtn.getStyleClass().add("btn-secondary");
        findPhotoBtn.setMaxWidth(Double.MAX_VALUE);

        photoBox.getChildren().addAll(photoFrame, changePhotoBtn, findPhotoBtn, photoHint);

        // Right Two-Column Form Grid
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(10);
        HBox.setHgrow(grid, Priority.ALWAYS);

        TextField nameField = new TextField(existing != null ? existing.getName() : "");
        nameField.setPromptText("e.g. Wireless Headphones");
        Label nameErr = makeErrorLabel();

        TextField tagsField = new TextField(existing != null && existing.getTags() != null ? existing.getTags() : "");
        tagsField.setPromptText("e.g. Audio, Wireless");

        ComboBox<String> categoryBox = new ComboBox<>();
        categoryBox.setEditable(true);
        categoryBox.getItems().addAll("Groceries", "Dairy", "Bakery", "Snacks", "Drinks", "Electronics", "Accessories", "General");
        categoryBox.setValue(existing != null && existing.getCategory() != null ? existing.getCategory() : "General");
        categoryBox.setMaxWidth(Double.MAX_VALUE);

        findPhotoBtn.setVisible(imageService != null);
        findPhotoBtn.setManaged(imageService != null);
        findPhotoBtn.setOnAction(e -> {
            if (imageService == null) {
                return;
            }
            String qName = nameField.getText();
            String qCat = categoryBox.getEditor().getText() != null && !categoryBox.getEditor().getText().isBlank()
                    ? categoryBox.getEditor().getText().trim()
                    : categoryBox.getValue();
            findPhotoBtn.setDisable(true);
            photoHint.setText("Searching photo...");
            photoHint.setVisible(true);
            photoHint.setManaged(true);
            AppExecutor.runAsync(
                    () -> imageService.resolveImagePath(qName, qCat, true),
                    resolved -> {
                        findPhotoBtn.setDisable(false);
                        if (resolved != null && new File(resolved).exists()) {
                            selectedPhotoPath = resolved;
                            preview.setImage(new Image(new File(resolved).toURI().toString(), 268, 268, true, true, true));
                            photoGlyph.setVisible(false);
                            photoHint.setText(ProductImageService.isGeneratedPlaceholder(resolved)
                                    ? "Local category artwork ready (add Pexels key in Settings for photos)."
                                    : "Loaded Pexels product photo!");
                        } else {
                            photoHint.setText("Could not find photo.");
                        }
                    },
                    ex -> {
                        findPhotoBtn.setDisable(false);
                        photoHint.setText("Lookup failed: " + ex.getMessage());
                    }
            );
        });

        TextField shelfField = new TextField(existing != null && existing.getShelfLocation() != null ? existing.getShelfLocation() : "");
        shelfField.setPromptText("e.g. A1-01");

        TextField qtyField = new TextField(existing != null ? String.valueOf(existing.getQuantity()) : "0");
        Label qtyErr = makeErrorLabel();

        TextField thresholdField = new TextField(existing != null ? String.valueOf(existing.getLowStockThreshold()) : "10");
        Label thresholdErr = makeErrorLabel();

        TextField buyPriceField = new TextField(existing != null ? String.valueOf(existing.getBuyPrice()) : "0.00");
        Label buyErr = makeErrorLabel();

        TextField sellPriceField = new TextField(existing != null ? String.valueOf(existing.getSellPrice()) : "0.00");
        Label sellErr = makeErrorLabel();

        DatePicker expiryPicker = new DatePicker(existing != null ? existing.getExpiryDate() : null);
        expiryPicker.setPromptText("Select Date");
        expiryPicker.setMaxWidth(Double.MAX_VALUE);

        ComboBox<StockStatus> statusBox = new ComboBox<>();
        statusBox.getItems().addAll(StockStatus.IN_STOCK, StockStatus.LOW_STOCK, StockStatus.OUT_OF_STOCK);
        statusBox.setValue(existing != null ? existing.getStatus() : StockStatus.IN_STOCK);
        statusBox.setMaxWidth(Double.MAX_VALUE);

        TextField substituteField = new TextField(existing != null && existing.getSubstituteSku() != null ? existing.getSubstituteSku() : "");
        substituteField.setPromptText("Backup SKU (optional)");

        CheckBox manualOverrideCheck = new CheckBox("Manual Status Override");
        manualOverrideCheck.setSelected(existing != null && existing.isManualStatusOverride());

        CheckBox archivedCheck = new CheckBox("Discontinued / Archived");
        archivedCheck.setSelected(existing != null && existing.isArchived());

        grid.add(wrapField("Product Name *", nameField, nameErr), 0, 0);
        grid.add(wrapField("Tags", tagsField, null), 1, 0);
        grid.add(wrapField("Category", categoryBox, null), 0, 1);
        grid.add(wrapField("Shelf Location", shelfField, null), 1, 1);
        grid.add(wrapField("Quantity *", qtyField, qtyErr), 0, 2);
        grid.add(wrapField("Low Stock Limit", thresholdField, thresholdErr), 1, 2);
        grid.add(wrapField("Buy Price *", buyPriceField, buyErr), 0, 3);
        grid.add(wrapField("Sell Price *", sellPriceField, sellErr), 1, 3);
        grid.add(wrapField("Expiry Date", expiryPicker, null), 0, 4);
        grid.add(wrapField("Status", statusBox, null), 1, 4);
        grid.add(wrapField("Substitute SKU", substituteField, null), 0, 5);
        grid.add(new VBox(6, manualOverrideCheck, archivedCheck), 1, 5);

        body.getChildren().addAll(photoBox, grid);

        ScrollPane bodyScroll = new ScrollPane(body);
        bodyScroll.setFitToWidth(true);
        VBox.setVgrow(bodyScroll, Priority.ALWAYS);

        Runnable runLiveCheck = () -> validateAndShowErrors(
                nameField, nameErr, qtyField, qtyErr,
                thresholdField, thresholdErr, buyPriceField, buyErr, sellPriceField, sellErr);
        nameField.textProperty().addListener((o, a, b) -> runLiveCheck.run());
        qtyField.textProperty().addListener((o, a, b) -> runLiveCheck.run());
        thresholdField.textProperty().addListener((o, a, b) -> runLiveCheck.run());
        buyPriceField.textProperty().addListener((o, a, b) -> runLiveCheck.run());
        sellPriceField.textProperty().addListener((o, a, b) -> runLiveCheck.run());

        HBox footer = new HBox(12);
        footer.setAlignment(Pos.CENTER_RIGHT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("btn-secondary");
        cancelBtn.setOnAction(e -> dialog.close());

        Button saveBtn = new Button("Save Product");
        saveBtn.setGraphic(AppIcons.of(Feather.SAVE, 14));
        saveBtn.getStyleClass().add("btn-primary");
        saveBtn.setOnAction(e -> {
            ValidationResult vr = validateAndShowErrors(
                    nameField, nameErr, qtyField, qtyErr,
                    thresholdField, thresholdErr, buyPriceField, buyErr, sellPriceField, sellErr);
            if (!vr.isValid()) {
                return;
            }
            Product out = existing != null ? Product.copyOf(existing) : new Product();
            out.setName(nameField.getText().trim());
            out.setTags(tagsField.getText() != null ? tagsField.getText().trim() : "");
            String catVal = categoryBox.getEditor().getText();
            out.setCategory(catVal != null && !catVal.isBlank() ? catVal.trim() : "General");
            out.setShelfLocation(shelfField.getText() != null ? shelfField.getText().trim() : "");
            out.setQuantity(ProductValidator.parseQuantity(qtyField.getText()));
            String thrText = thresholdField.getText();
            out.setLowStockThreshold(thrText == null || thrText.isBlank() ? 10 : ProductValidator.parseQuantity(thrText));
            out.setBuyPrice(buyPriceField.getText().isBlank() ? 0.0 : ProductValidator.parsePrice(buyPriceField.getText(), "Buy price"));
            out.setSellPrice(sellPriceField.getText().isBlank() ? 0.0 : ProductValidator.parsePrice(sellPriceField.getText(), "Sell price"));
            out.setExpiryDate(expiryPicker.getValue());
            out.setManualStatusOverride(manualOverrideCheck.isSelected());
            out.setStatus(statusBox.getValue());
            out.refreshStatusIfAutomatic();
            out.setPhotoPath(selectedPhotoPath);
            out.setSubstituteSku(substituteField.getText() != null ? substituteField.getText().trim() : "");
            out.setArchived(archivedCheck.isSelected());
            this.result = out;
            dialog.close();
        });

        footer.getChildren().addAll(spacer, cancelBtn, saveBtn);
        root.getChildren().addAll(title, bodyScroll, footer);

        Scene scene = new Scene(root, 770, 565);
        var cssUrl = getClass().getResource(darkMode ? "/css/dark-theme.css" : "/css/light-theme.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }
        dialog.setScene(scene);
        dialog.showAndWait();
        return Optional.ofNullable(result);
    }

    private ValidationResult validateAndShowErrors(
            TextField nameField, Label nameErr,
            TextField qtyField, Label qtyErr,
            TextField thresholdField, Label thresholdErr,
            TextField buyField, Label buyErr,
            TextField sellField, Label sellErr) {
        ValidationResult vr = ProductValidator.validateRawInputs(
                nameField.getText(),
                qtyField.getText(),
                thresholdField.getText(),
                buyField.getText(),
                sellField.getText()
        );
        Map<String, String> errs = vr.getFieldErrors();
        applyFieldError(nameField, nameErr, errs.get("name"));
        applyFieldError(qtyField, qtyErr, errs.get("quantity"));
        applyFieldError(thresholdField, thresholdErr, errs.get("lowStockThreshold"));
        applyFieldError(buyField, buyErr, errs.get("buyPrice"));
        applyFieldError(sellField, sellErr, errs.get("sellPrice"));
        return vr;
    }

    private void applyFieldError(TextField field, Label errorLabel, String message) {
        field.getStyleClass().remove("field-error");
        if (message != null && !message.isBlank()) {
            field.getStyleClass().add("field-error");
            errorLabel.setText(message);
            errorLabel.setVisible(true);
            errorLabel.setManaged(true);
        } else {
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
        }
    }

    private VBox wrapField(String labelText, javafx.scene.Node control, Label errorLabel) {
        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        VBox box = new VBox(4, lbl, control);
        if (errorLabel != null) {
            box.getChildren().add(errorLabel);
        }
        return box;
    }

    private Label makeErrorLabel() {
        Label l = new Label();
        l.getStyleClass().add("error-label");
        l.setVisible(false);
        l.setManaged(false);
        l.setWrapText(true);
        return l;
    }
}
