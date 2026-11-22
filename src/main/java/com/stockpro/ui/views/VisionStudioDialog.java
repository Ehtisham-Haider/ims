package com.stockpro.ui.views;

import com.stockpro.domain.VisionAnalysisResult;
import com.stockpro.domain.VisionAnalysisResult.VisionMode;
import com.stockpro.service.AppSession;
import com.stockpro.service.OpenFoodFactsClient;
import com.stockpro.service.ServiceContext;
import com.stockpro.service.VisionStudioService;
import com.stockpro.ui.components.AppIcons;
import com.stockpro.ui.components.ToastNotificationManager;
import com.stockpro.util.AppExecutor;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Interactive AI Vision Studio dialog providing 6 optical inspection tabs
 * (Product Recognizer, Shelf Counter, Empty Shelf Gap, Damaged Box, Receipt
 * Scanner,
 * Handwritten Note Reader), ZXing barcode decoding, Open Food Facts lookup,
 * Drag-and-Drop intake, and pHash catalog duplicate checking.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class VisionStudioDialog {

    private final ServiceContext services;
    private final VisionStudioService visionService;
    private final ToastNotificationManager toastManager;

    private VisionMode activeMode = VisionMode.PRODUCT_RECOGNIZER;
    private Path selectedImagePath;
    private ImageView previewImageView;
    private Label dropPromptLabel;
    private Label headlineLabel;
    private Label detailLabel;
    private ListView<String> extractedLinesView;
    private TextField barcodeInput;

    /**
     * Constructs an interactive Vision Studio dialog controller.
     *
     * @param services     shared application service context
     * @param toastManager toast notification feedback helper
     */
    public VisionStudioDialog(ServiceContext services, ToastNotificationManager toastManager) {
        this.services = services;
        this.visionService = services.getVisionStudioService();
        this.toastManager = toastManager;
    }

    /**
     * Displays the modal Vision Studio window optionally pre-loaded with an image
     * file and mode.
     *
     * @param owner       parent window
     * @param initialFile optional pre-dropped image path (or {@code null})
     * @param initialMode initial inspection tab (or {@code null})
     */
    public void show(Window owner, Path initialFile, VisionMode initialMode) {
        if (initialMode != null) {
            this.activeMode = initialMode;
        }
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setTitle("StockPro AI Vision Studio — Optical & Barcode Scanner");

        VBox root = new VBox(14);
        root.getStyleClass().add("card");
        root.setPadding(new Insets(20));

        HBox hdr = new HBox(10);
        hdr.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("AI Vision Studio (6 Modes • ZXing • OpenFoodFacts • pHash)");
        title.getStyleClass().add("section-title");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button sampleBarcodeBtn = new Button("Generate Sample ZXing Frame");
        sampleBarcodeBtn.setGraphic(AppIcons.of(Feather.CAMERA, 14));
        sampleBarcodeBtn.getStyleClass().add("btn-secondary");
        sampleBarcodeBtn.setOnAction(e -> synthesizeSampleBarcodeFrame());
        hdr.getChildren().addAll(AppIcons.of(Feather.APERTURE, 18, "#4F46E5"), title, sp, sampleBarcodeBtn);

        // 6-Mode Toggle Strip
        FlowPane modeTabs = new FlowPane(8, 8);
        ToggleGroup modeGroup = new ToggleGroup();
        for (VisionMode mode : VisionMode.values()) {
            ToggleButton tb = new ToggleButton(mode.getDisplayName());
            tb.setToggleGroup(modeGroup);
            tb.setSelected(mode == activeMode);
            tb.getStyleClass().add("btn-secondary");
            tb.setOnAction(e -> {
                activeMode = mode;
                if (selectedImagePath != null) {
                    runAnalysisAsync();
                }
            });
            modeTabs.getChildren().add(tb);
        }

        // Split: Left Drag-Drop / Preview Canvas | Right Analysis Findings & Barcode
        // Lookup
        HBox body = new HBox(16);
        VBox.setVgrow(body, Priority.ALWAYS);

        StackPane dropZone = new StackPane();
        dropZone.setMinSize(320, 270);
        dropZone.setPrefSize(340, 280);
        dropZone.setStyle(
                "-fx-border-color: #6366F1; -fx-border-style: segments(6, 4); -fx-border-width: 2; -fx-border-radius: 12; -fx-background-radius: 12;");

        previewImageView = new ImageView();
        previewImageView.setFitWidth(310);
        previewImageView.setFitHeight(250);
        previewImageView.setPreserveRatio(true);

        dropPromptLabel = new Label("Drag & Drop Product / Shelf / Receipt Photo Here\nor Click 'Choose Photo' Below");
        dropPromptLabel.setWrapText(true);
        dropPromptLabel.setAlignment(Pos.CENTER);
        dropPromptLabel.getStyleClass().add("muted-text");

        dropZone.getChildren().addAll(previewImageView, dropPromptLabel);
        dropZone.setOnDragOver(ev -> {
            if (ev.getDragboard().hasFiles()) {
                ev.acceptTransferModes(TransferMode.COPY);
            }
            ev.consume();
        });
        dropZone.setOnDragDropped(ev -> {
            List<File> files = ev.getDragboard().getFiles();
            boolean handled = false;
            if (files != null && !files.isEmpty()) {
                for (File f : files) {
                    if (visionService.isSupportedImageFile(f)) {
                        loadImageAndAnalyze(f.toPath());
                        handled = true;
                        break;
                    }
                }
            }
            ev.setDropCompleted(handled);
            ev.consume();
        });

        VBox leftPane = new VBox(10);
        Button uploadBtn = new Button("Choose Image File...");
        uploadBtn.setGraphic(AppIcons.of(Feather.UPLOAD, 14));
        uploadBtn.getStyleClass().add("btn-secondary");
        uploadBtn.setMaxWidth(Double.MAX_VALUE);
        uploadBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.getExtensionFilters()
                    .add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.bmp"));
            File chosen = chooser.showOpenDialog(stage);
            if (chosen != null) {
                loadImageAndAnalyze(chosen.toPath());
            }
        });
        leftPane.getChildren().addAll(dropZone, uploadBtn);

        VBox rightPane = new VBox(10);
        HBox.setHgrow(rightPane, Priority.ALWAYS);

        headlineLabel = new Label("Waiting for photo or barcode...");
        headlineLabel.getStyleClass().add("section-title");
        headlineLabel.setWrapText(true);

        detailLabel = new Label(
                "Drop an image or test a retail barcode (e.g. 5449000000996 Coca-Cola, 3017620422003 Nutella).");
        detailLabel.getStyleClass().add("muted-text");
        detailLabel.setWrapText(true);

        HBox offRow = new HBox(8);
        barcodeInput = new TextField("5449000000996");
        barcodeInput.setPromptText("EAN-13 / UPC / SKU");
        HBox.setHgrow(barcodeInput, Priority.ALWAYS);
        Button offBtn = new Button("OpenFoodFacts Lookup");
        offBtn.getStyleClass().add("btn-primary");
        offBtn.setOnAction(e -> lookupBarcodeAsync());
        offRow.getChildren().addAll(barcodeInput, offBtn);

        extractedLinesView = new ListView<>();
        extractedLinesView.setPrefHeight(160);

        Button analyzeBtn = new Button("Re-Run Vision Inspection");
        analyzeBtn.setGraphic(AppIcons.of(Feather.CPU, 14, "#FFFFFF"));
        analyzeBtn.getStyleClass().add("btn-primary");
        analyzeBtn.setOnAction(e -> runAnalysisAsync());

        Button closeBtn = new Button("Close");
        closeBtn.getStyleClass().add("btn-secondary");
        closeBtn.setOnAction(e -> stage.close());

        HBox footer = new HBox(10, analyzeBtn, closeBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);

        rightPane.getChildren().addAll(
                headlineLabel,
                detailLabel,
                new Label("Live Barcode Enrichment:"),
                offRow,
                new Label("Extracted Items & Telemetry:"),
                extractedLinesView,
                footer);

        body.getChildren().addAll(leftPane, rightPane);
        root.getChildren().addAll(hdr, modeTabs, body);

        Scene sc = new Scene(root, 860, 560);
        var css = getClass()
                .getResource(AppSession.getInstance().isDarkMode() ? "/css/dark-theme.css" : "/css/light-theme.css");
        if (css != null) {
            sc.getStylesheets().add(css.toExternalForm());
        }
        stage.setScene(sc);

        if (initialFile != null) {
            loadImageAndAnalyze(initialFile);
        }
        stage.showAndWait();
    }

    private void loadImageAndAnalyze(Path path) {
        this.selectedImagePath = path;
        dropPromptLabel.setVisible(false);
        previewImageView.setImage(new Image(path.toUri().toString(), 310, 250, true, true, true));
        runAnalysisAsync();
    }

    private void runAnalysisAsync() {
        if (selectedImagePath == null) {
            toastManager.showInfo("Drop or choose an image frame first.");
            return;
        }
        Path target = selectedImagePath;
        VisionMode mode = activeMode;
        AppExecutor.runAsync(
                () -> visionService.analyzeImageFile(target, mode),
                this::renderResult,
                ex -> toastManager.showInfo("Vision analysis failed: " + ex.getMessage()));
    }

    private void synthesizeSampleBarcodeFrame() {
        AppExecutor.runAsync(
                () -> {
                    byte[] png = services.getBarcodeLabelService().generateCode128Png("PRD-1001", 320, 110);
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
                    VisionAnalysisResult res = visionService.analyzeBufferedImage(
                            img, "sample-camera-frame.png", "", activeMode);
                    return new SampleFrameResult(png, res);
                },
                frame -> {
                    dropPromptLabel.setVisible(false);
                    previewImageView.setImage(new Image(new ByteArrayInputStream(frame.png())));
                    renderResult(frame.result());
                },
                ex -> toastManager.showInfo("Sample frame failed: " + ex.getMessage()));
    }

    private void lookupBarcodeAsync() {
        String code = barcodeInput.getText() != null ? barcodeInput.getText().trim() : "";
        if (code.isBlank()) {
            toastManager.showInfo("Enter a barcode first.");
            return;
        }
        AppExecutor.runAsync(
                () -> visionService.lookupExternalBarcode(code),
                (OpenFoodFactsClient.BarcodeLookupResult res) -> {
                    if (res.found()) {
                        headlineLabel.setText(res.productName() + " (" + res.source() + ")");
                        detailLabel.setText("Category: " + res.category() + " | Brand: " + res.brand() + " | Barcode: "
                                + res.barcode());
                        extractedLinesView.getItems().setAll(
                                "Barcode: " + res.barcode(),
                                "Name: " + res.productName(),
                                "Category: " + res.category(),
                                "Brand: " + res.brand());
                    } else {
                        headlineLabel.setText("Barcode not found on OpenFoodFacts / Offline Cache");
                        detailLabel.setText("Queried: " + res.barcode());
                    }
                },
                ex -> toastManager.showInfo("OpenFoodFacts lookup failed: " + ex.getMessage()));
    }

    private void renderResult(VisionAnalysisResult res) {
        headlineLabel.setText(String.format(Locale.US, "%s (%.0f%% confidence%s)",
                res.headline(), res.confidencePercent(), res.anomalyFlagged() ? " • ALERT" : ""));
        detailLabel.setText(res.summary());
        if (res.detectedBarcode() != null && !res.detectedBarcode().isBlank()) {
            barcodeInput.setText(res.detectedBarcode());
        }
        if (res.extractedLines() != null && !res.extractedLines().isEmpty()) {
            extractedLinesView.getItems().setAll(res.extractedLines());
        } else {
            extractedLinesView.getItems().setAll(
                    "Mode: " + res.mode().getDisplayName(),
                    "Estimated Count: " + res.estimatedItemCount(),
                    "Decoded Barcode: " + (res.detectedBarcode().isBlank() ? "None" : res.detectedBarcode()),
                    "Anomaly Flagged: " + (res.anomalyFlagged() ? "YES" : "No"));
        }
    }

    private record SampleFrameResult(byte[] png, VisionAnalysisResult result) {
    }
}
