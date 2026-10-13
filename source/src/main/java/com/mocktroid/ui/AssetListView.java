package com.mocktroid.ui;

import com.mocktroid.audio.SoundManager;
import com.mocktroid.model.AssetCategory;
import com.mocktroid.model.AssetEntry;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;

/**
 * List-mode asset browser: one thin row per asset, name/GUID only.
 * <p>
 * Backed by {@link ListView} rather than manually building a row Node per
 * entry -- some categories run into the thousands of assets (CSMP alone is
 * ~9,500 across the full romfs), and ListView only ever builds/lays out the
 * handful of cells actually on screen, recycling them as the user scrolls.
 * Building every row eagerly (the original approach) is what caused the app
 * to hang for a long time on a real romfs scan.
 */
public final class AssetListView extends ListView<AssetEntry> {

    private final ObjectProperty<AssetEntry> selectedEntry = new SimpleObjectProperty<>();
    private java.util.function.BiConsumer<AssetEntry, javafx.scene.Node> onActivate = (e, n) -> {};

    /** Double-click on a row; receives the row so callers can give feedback on it. */
    public void setOnActivate(java.util.function.BiConsumer<AssetEntry, javafx.scene.Node> onActivate) {
        this.onActivate = onActivate;
    }

    private EntryMenuHandler onMenu = (e, n, x, y) -> {};

    public void setOnMenu(EntryMenuHandler onMenu) {
        this.onMenu = onMenu;
    }

    public AssetListView() {
        getStyleClass().add("asset-list-view");
        setItems(FXCollections.observableArrayList());
        setCellFactory(lv -> new Row());
        setFocusTraversable(false);
    }

    public void showCategory(AssetCategory category) {
        ObservableList<AssetEntry> items = FXCollections.observableArrayList();
        if (category != null) {
            items.addAll(category.entries());
        }
        setItems(items);
    }

    public ObjectProperty<AssetEntry> selectedEntryProperty() {
        return selectedEntry;
    }

    /** Selects an entry programmatically (e.g. next/previous navigation from
     *  the preview pane), same code path as clicking its row. */
    public void select(AssetEntry entry) {
        selectedEntry.set(entry);
    }

    private final class Row extends ListCell<AssetEntry> {
        private final Label label = new Label();
        private final AngledPanel panel = new AngledPanel(8);

        Row() {
            getStyleClass().add("asset-list-cell");
            label.getStyleClass().add("asset-list-row-label");
            HBox content = new HBox(label);
            content.setPadding(new Insets(8, 14, 8, 14));
            panel.getStyleClass().add("asset-list-row");
            panel.setMaxWidth(Double.MAX_VALUE);
            panel.getChildren().add(content);

            panel.setOnMouseEntered(e -> SoundManager.get().play(SoundManager.Cue.HOVER));
            // Select on press, not click -- see AssetGridView: a click is lost if
            // anything relayouts the row between press and release.
            panel.setOnMousePressed(e -> {
                if (getItem() == null || e.getButton() != javafx.scene.input.MouseButton.PRIMARY) {
                    return;
                }
                if (e.getClickCount() == 2) {
                    onActivate.accept(getItem(), panel);
                    return;
                }
                if (getItem().equals(selectedEntry.get())) {
                    return;
                }
                SoundManager.get().play(SoundManager.Cue.SELECT);
                selectedEntry.set(getItem());
            });
            panel.setOnContextMenuRequested(e -> {
                if (getItem() != null) {
                    onMenu.show(getItem(), panel, e.getScreenX(), e.getScreenY());
                    e.consume();
                }
            });
            // Each (recycled) row restyles itself when the selection changes,
            // rather than refresh()-ing the whole list.
            selectedEntry.addListener((obs, old, val) -> applySelectedStyle());
        }

        private void applySelectedStyle() {
            panel.getStyleClass().remove("selected");
            if (getItem() != null && getItem().equals(selectedEntry.get())) {
                panel.getStyleClass().add("selected");
            }
        }

        @Override
        protected void updateItem(AssetEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
                return;
            }
            label.setText(entry.displayName());
            applySelectedStyle();
            setGraphic(panel);
        }
    }
}
