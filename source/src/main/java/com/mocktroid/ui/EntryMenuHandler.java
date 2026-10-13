package com.mocktroid.ui;

import com.mocktroid.model.AssetEntry;

import javafx.scene.Node;

/** Right-click on an asset row/card: {@code node} is what was clicked, at screen ({@code x}, {@code y}). */
@FunctionalInterface
public interface EntryMenuHandler {
    void show(AssetEntry entry, Node node, double x, double y);
}
