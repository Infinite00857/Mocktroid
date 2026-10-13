package com.mocktroid.model;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * A left-rail grouping (e.g. "Textures") holding the flat list of assets of
 * that type. The list is observable so the grid view updates live as a PAK
 * loads assets in.
 */
public final class AssetCategory {
    private final AssetType type;
    private final String label;
    private final ObservableList<AssetEntry> entries = FXCollections.observableArrayList();

    public AssetCategory(AssetType type, String label) {
        this.type = type;
        this.label = label;
    }

    public AssetType type() { return type; }
    public String label() { return label; }
    public ObservableList<AssetEntry> entries() { return entries; }
}
