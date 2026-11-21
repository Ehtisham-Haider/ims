package com.stockpro.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.stockpro.domain.Product;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates crisp Code-128 barcodes and QR codes via ZXing and exports printable A4 sticker sheets via OpenPDF.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class BarcodeLabelService {

    /**
     * Creates a ZXing barcode &amp; QR label service.
     */
    public BarcodeLabelService() {
    }

    /**
     * Encodes a product code into a Code-128 PNG byte array.
     *
     * @param code text to encode (SKU or barcode)
     * @param width pixel width
     * @param height pixel height
     * @return PNG image bytes
     */
    public byte[] generateCode128Png(String code, int width, int height) {
        String content = code != null && !code.isBlank() ? code.trim() : "PRD-0000";
        try {
            BitMatrix matrix = new Code128Writer().encode(content, BarcodeFormat.CODE_128, width, height);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate Code-128 barcode for " + content, ex);
        }
    }

    /**
     * Encodes structured product payload into a QR code PNG byte array.
     *
     * @param payload QR payload string
     * @param size pixel width and height
     * @return PNG image bytes
     */
    public byte[] generateQrCodePng(String payload, int size) {
        String content = payload != null && !payload.isBlank() ? payload.trim() : "STOCKPRO";
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate QR code", ex);
        }
    }

    /**
     * Converts PNG bytes directly into a JavaFX {@link javafx.scene.image.Image} for live preview.
     *
     * @param pngBytes raw PNG bytes
     * @return JavaFX image
     */
    public javafx.scene.image.Image toFxImage(byte[] pngBytes) {
        return new javafx.scene.image.Image(new ByteArrayInputStream(pngBytes));
    }

    /**
     * Builds the QR label content payload embedding SKU, name, and selling price.
     *
     * @param product target product
     * @return compact QR payload text
     */
    public String buildQrPayload(Product product) {
        return String.format("SKU:%s|NAME:%s|PRICE:%.2f",
                product.getSku(),
                product.getName(),
                product.getSellPrice());
    }

    /**
     * Exports a printable A4 sticker sheet PDF containing Code-128 barcodes and QR codes for supplied items.
     *
     * @param products products to print on sticker labels
     * @param outputPdf target PDF output path
     * @return written PDF {@link Path}
     */
    public Path exportStickerSheetPdf(List<Product> products, Path outputPdf) {
        if (products == null || products.isEmpty()) {
            throw new IllegalArgumentException("At least one product is required to print barcode labels.");
        }
        try {
            if (outputPdf.getParent() != null) {
                Files.createDirectories(outputPdf.getParent());
            }
            AppSession session = AppSession.getInstance();
            try (FileOutputStream fos = new FileOutputStream(outputPdf.toFile());
                 Document doc = new Document(PageSize.A4, 24, 24, 28, 24)) {
                PdfWriter.getInstance(doc, fos);
                doc.open();

                Font titleFont = new Font(Font.HELVETICA, 14, Font.BOLD, new Color(23, 28, 46));
                Font labelFont = new Font(Font.HELVETICA, 10, Font.BOLD, new Color(15, 23, 42));
                Font skuFont = new Font(Font.COURIER, 9, Font.BOLD, new Color(71, 85, 105));

                Paragraph header = new Paragraph(session.getStoreName() + " — Barcode & QR Sticker Sheet", titleFont);
                header.setSpacingAfter(12);
                doc.add(header);

                PdfPTable grid = new PdfPTable(2);
                grid.setWidthPercentage(100);

                for (Product p : products) {
                    PdfPCell cell = new PdfPCell();
                    cell.setPadding(10);
                    cell.setBorderColor(new Color(203, 213, 225));

                    Paragraph nameP = new Paragraph(p.getName() + "  (" + session.formatMoney(p.getSellPrice()) + ")", labelFont);
                    nameP.setAlignment(Element.ALIGN_CENTER);
                    nameP.setSpacingAfter(4);
                    cell.addElement(nameP);

                    PdfPTable inner = new PdfPTable(2);
                    inner.setWidthPercentage(100);
                    inner.setWidths(new float[]{1.8f, 1.0f});

                    String barcodeText = p.getBarcode() != null && !p.getBarcode().isBlank() ? p.getBarcode() : p.getSku();
                    Image barcodeImg = Image.getInstance(generateCode128Png(barcodeText, 220, 64));
                    barcodeImg.scaleToFit(140, 44);
                    PdfPCell bCell = new PdfPCell();
                    bCell.setBorder(0);
                    bCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                    bCell.addElement(barcodeImg);
                    Paragraph codeP = new Paragraph(barcodeText, skuFont);
                    codeP.setAlignment(Element.ALIGN_CENTER);
                    bCell.addElement(codeP);

                    Image qrImg = Image.getInstance(generateQrCodePng(buildQrPayload(p), 120));
                    qrImg.scaleToFit(58, 58);
                    PdfPCell qCell = new PdfPCell(qrImg);
                    qCell.setBorder(0);
                    qCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                    qCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

                    inner.addCell(bCell);
                    inner.addCell(qCell);
                    cell.addElement(inner);
                    grid.addCell(cell);
                }

                if (products.size() % 2 != 0) {
                    PdfPCell filler = new PdfPCell(new Phrase(""));
                    filler.setBorder(0);
                    grid.addCell(filler);
                }

                doc.add(grid);
            }
            return outputPdf;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to export sticker sheet PDF", ex);
        }
    }
}
