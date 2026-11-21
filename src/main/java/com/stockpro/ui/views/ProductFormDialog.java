package com.stockpro.ui.views;

import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.ValidationResult;
import com.stockpro.service.ProductValidator;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
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

import java.io.File;
import java.util.Map;
import java.util.Optional;

/**
 * Add/Edit Product dialog matching {@code Screens and popups/Add Product.png} with live red inline
 * field validation, photo picker, category/tag entry, shelf location, expiry date, substitute SKU, and archive toggle.
 *
 * @author Ehtisham Haider, Syed Ahmed
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
        this.result = null;
        this.selectedPhotoPath = existing != null ? existing.getPhotoPath() : null;

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(existing == null ? "Add New Product" : "Edit Product — " + existing.getSku());

        VBox root = new VBox(18);
        root.getStyleClass().add("card");
        root.setPadding(new Insets(24));

        Label title = new Label(existing == null ? "Add New Product" : "Edit Product");
        title.getStyleClass().add("page-title");

        HBox body = new HBox(22);
        body.setAlignment(Pos.TOP_LEFT);

        // Left Photo Card
        VBox photoBox = new VBox(10);
        photoBox.setAlignment(Pos.CENTER);
        photoBox.setPrefWidth(170);

        StackPane photoFrame = new StackPane();
        photoFrame.setMinSize(150, 150);
        photoFrame.setPrefSize(150, 150);
        photoFrame.setStyle("-fx-background-color: #F1F5F9; -fx-background-radius: 14; -fx-border-color: #CBD5E1; -fx-border-radius: 14;");

        Label photoGlyph = new Label("📦");
        photoGlyph.setStyle("-fx-font-size: 46px;");
        ImageView preview = new ImageView();
        preview.setFitWidth(138);
        preview.setFitHeight(138);
        preview.setPreserveRatio(true);
        if (selectedPhotoPath != null && new File(selectedPhotoPath).exists()) {
            preview.setImage(new Image(new File(selectedPhotoPath).toURI().toString()));
            photoGlyph.setVisible(false);
        }
        photoFrame.getChildren().addAll(photoGlyph, preview);

        Button changePhotoBtn = new Button("Change Photo");
        changePhotoBtn.getStyleClass().add("btn-secondary");
        changePhotoBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select Product Image");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif"));
            File chosen = chooser.showOpenDialog(dialog);
            if (chosen != null) {
                selectedPhotoPath = chosen.getAbsolutePath();
                preview.setImage(new Image(chosen.toURI().toString()));
                photoGlyph.setVisible(false);
            }
        });
        photoBox.getChildren().addAll(photoFrame, changePhotoBtn);

        // Right Two-Column Form Grid
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(12);
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

        // Live validation feedback as user types
        Runnable runLiveCheck = () -> validateAndShowErrors(
                nameField, nameErr, qtyField, qtyErr,
                thresholdField, thresholdErr, buyPriceField, buyErr, sellPriceField, sellErr);
        nameField.textProperty().addListener((o, a, b) -> runLiveCheck.run());
        qtyField.textProperty().addListener((o, a, b) -> runLiveCheck.run());
        thresholdField.textProperty().addListener((o, a, b) -> runLiveCheck.run());
        buyPriceField.textProperty().addListener((o, a, b) -> runLiveCheck.run());
        sellPriceField.textProperty().addListener((o, a, b) -> runLiveCheck.run());

        // Footer Buttons
        HBox footer = new HBox(12);
        footer.setAlignment(Pos.CENTER_RIGHT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("btn-secondary");
        cancelBtn.setOnAction(e -> dialog.close());

        Button saveBtn = new Button("Save Product");
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
        root.getChildren().addAll(title, body, footer);

        Scene scene = new Scene(root, 780, 560);
        var cssUrl = getClass().getResource(darkMode ? "/css/dark-theme.css" : "/css/light-theme.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }
        dialog.setScene(scene);
        dialog.showAndWait();
        return Optional.ofNullable(result);
    }

    /**
     * Runs {@link ProductValidator} against form fields and applies red border/error styling inline.
     *
     * @param nameField product name text field
     * @param nameErr product name error label
     * @param qtyField quantity text field
     * @param qtyErr quantity error label
     * @param thresholdField low-stock threshold field
     * @param thresholdErr threshold error label
     * @param buyField buy price field
     * @param buyErr buy price error label
     * @param sellField sell price field
     * @param sellErr sell price error label
     * @return computed {@link ValidationResult}
     */
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

    /**
     * Toggles the {@code field-error} CSS class and displays or hides the inline validation hint.
     *
     * @param field input control
     * @param errorLabel feedback label
     * @param message error string, or {@code null} when valid
     */
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

    /**
     * Wraps a labeled field and optional inline error label into a vertical form block.
     *
     * @param labelText field caption
     * @param control input widget
     * @param errorLabel optional error label
     * @return vertical container
     */
    private VBox wrapField(String labelText, javafx.scene.Node control, Label errorLabel) {
        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        VBox box = new VBox(4, lbl, control);
        if (errorLabel != null) {
            box.getChildren().add(errorLabel);
        }
        return box;
    }

    /**
     * Creates a collapsed hidden inline error label.
     *
     * @return styled error {@link Label}
     */
    private Label makeErrorLabel() {
        Label l = new Label();
        l.getStyleClass().add("error-label");
        l.setVisible(false);
        l.setManaged(false);
        l.setWrapText(true);
        return l;
    }
}
