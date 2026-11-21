package com.stockpro.ui.views;

import com.stockpro.domain.Product;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Confirmation dialog for deleting or archiving an inventory product safely.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class DeleteConfirmDialog {

    /**
     * Outcome chosen by the user in the confirmation popup.
     *
     * @author Ehtisham Haider, Syed Ahmed
     */
    public enum Choice {
        /** User cancelled without making any changes. */
        CANCEL,
        /** User chose to archive/discontinue instead of deleting. */
        ARCHIVE_INSTEAD,
        /** User confirmed permanent deletion. */
        DELETE
    }

    private Choice choice = Choice.CANCEL;

    /**
     * Creates a delete confirmation modal helper.
     */
    public DeleteConfirmDialog() {
    }

    /**
     * Displays the confirmation dialog and returns the user's chosen {@link Choice}.
     *
     * @param owner parent window
     * @param product target product
     * @param darkMode whether dark theme is active
     * @return user {@link Choice}
     */
    public Choice showAndWait(Window owner, Product product, boolean darkMode) {
        this.choice = Choice.CANCEL;

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("Confirm Product Removal");

        VBox card = new VBox(14);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(24));

        Label heading = new Label("Delete '" + product.getName() + "'?");
        heading.getStyleClass().add("section-title");
        heading.setStyle("-fx-font-size: 17px; -fx-font-weight: bold;");

        Label body = new Label("You can permanently delete SKU " + product.getSku()
                + " (reversible with Ctrl+Z) or archive it as discontinued while keeping historical records.");
        body.setWrapText(true);
        body.getStyleClass().add("muted-text");

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("btn-secondary");
        cancelBtn.setOnAction(e -> {
            choice = Choice.CANCEL;
            dialog.close();
        });

        Button archiveBtn = new Button("Archive Instead");
        archiveBtn.getStyleClass().add("btn-secondary");
        archiveBtn.setOnAction(e -> {
            choice = Choice.ARCHIVE_INSTEAD;
            dialog.close();
        });

        Button deleteBtn = new Button("Delete Item");
        deleteBtn.getStyleClass().add("btn-danger-outline");
        deleteBtn.setOnAction(e -> {
            choice = Choice.DELETE;
            dialog.close();
        });

        HBox actions = new HBox(10, cancelBtn, archiveBtn, deleteBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(archiveBtn, Priority.NEVER);

        card.getChildren().addAll(heading, body, actions);

        Scene scene = new Scene(card, 460, 190);
        var cssUrl = getClass().getResource(darkMode ? "/css/dark-theme.css" : "/css/light-theme.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }
        dialog.setScene(scene);
        dialog.showAndWait();
        return choice;
    }
}
