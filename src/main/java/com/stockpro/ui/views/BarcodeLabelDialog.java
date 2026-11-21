package com.stockpro.ui.views;

import com.stockpro.domain.Product;
import com.stockpro.service.AppSession;
import com.stockpro.service.BarcodeLabelService;
import com.stockpro.ui.components.ToastNotificationManager;
import com.stockpro.util.AppExecutor;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.print.PrinterJob;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.util.List;

/**
 * Barcode &amp; QR Sticker Preview dialog matching {@code Screens and popups/QR.png}
 * with live ZXing preview, native printing, and PDF sticker export.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class BarcodeLabelDialog {
    private final BarcodeLabelService labelService;
    private final ToastNotificationManager toastManager;

    /**
     * Constructs a Barcode &amp; QR label modal helper.
     *
     * @param labelService ZXing label service
     * @param toastManager toast notification manager
     */
    public BarcodeLabelDialog(BarcodeLabelService labelService, ToastNotificationManager toastManager) {
        this.labelService = labelService;
        this.toastManager = toastManager;
    }

    /**
     * Displays the live Barcode &amp; QR label card for the specified product.
     *
     * @param owner parent window
     * @param product target product
     * @param darkMode whether dark theme is active
     */
    public void show(Window owner, Product product, boolean darkMode) {
        if (product == null) {
            return;
        }
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("Barcode & QR Label — " + product.getSku());

        VBox root = new VBox(16);
        root.getStyleClass().add("card");
        root.setPadding(new Insets(24));
        root.setAlignment(Pos.CENTER);

        VBox printableCard = new VBox(12);
        printableCard.setAlignment(Pos.CENTER);
        printableCard.setPadding(new Insets(16));
        printableCard.setStyle("-fx-background-color: #FFFFFF; -fx-background-radius: 12; -fx-border-color: #E2E8F0; -fx-border-radius: 12;");

        Label nameLbl = new Label(product.getName());
        nameLbl.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #0F172A;");

        String codeText = product.getBarcode() != null && !product.getBarcode().isBlank()
                ? product.getBarcode() : product.getSku();
        ImageView barcodeView = new ImageView(labelService.toFxImage(labelService.generateCode128Png(codeText, 220, 74)));
        barcodeView.setPreserveRatio(true);
        Label skuLbl = new Label(codeText);
        skuLbl.setStyle("-fx-font-family: monospace; -fx-font-weight: bold; -fx-text-fill: #0F172A;");
        VBox leftCol = new VBox(4, barcodeView, skuLbl);
        leftCol.setAlignment(Pos.CENTER);

        ImageView qrView = new ImageView(labelService.toFxImage(
                labelService.generateQrCodePng(labelService.buildQrPayload(product), 115)));
        qrView.setPreserveRatio(true);

        HBox graphicsRow = new HBox(20, leftCol, qrView);
        graphicsRow.setAlignment(Pos.CENTER);
        printableCard.getChildren().addAll(nameLbl, graphicsRow);

        Button printBtn = new Button("Print");
        printBtn.getStyleClass().add("btn-secondary");
        printBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(printBtn, Priority.ALWAYS);
        printBtn.setOnAction(e -> {
            PrinterJob job = PrinterJob.createPrinterJob();
            if (job != null && job.showPrintDialog(dialog)) {
                if (job.printPage(printableCard)) {
                    job.endJob();
                    toastManager.showInfo("Sent barcode sticker for " + product.getSku() + " to printer.");
                }
            } else {
                toastManager.showInfo("No printer selected or print canceled.");
            }
        });

        Button downloadBtn = new Button("Download PDF");
        downloadBtn.getStyleClass().add("btn-primary");
        downloadBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(downloadBtn, Priority.ALWAYS);
        downloadBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setInitialFileName(product.getSku() + "-label.pdf");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Documents", "*.pdf"));
            File out = chooser.showSaveDialog(dialog);
            if (out != null) {
                AppExecutor.runAsync(
                        () -> labelService.exportStickerSheetPdf(List.of(product), out.toPath()),
                        path -> toastManager.showInfo("Saved barcode PDF to " + path.getFileName()),
                        ex -> toastManager.showInfo("PDF failed: " + ex.getMessage())
                );
            }
        });

        HBox actions = new HBox(12, printBtn, downloadBtn);
        actions.setAlignment(Pos.CENTER);

        root.getChildren().addAll(printableCard, actions);

        Scene scene = new Scene(root, 430, 290);
        var css = getClass().getResource(darkMode ? "/css/dark-theme.css" : "/css/light-theme.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        dialog.setScene(scene);
        dialog.showAndWait();
    }
}
