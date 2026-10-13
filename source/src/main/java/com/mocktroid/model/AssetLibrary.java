package com.mocktroid.model;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mocktroid.library.PakAssetRef;

import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;

/**
 * Holds the currently loaded set of asset categories. Populated by the data
 * layer -- today {@link DemoData}, later the PAK/loader modules -- and consumed
 * by {@code CategoryRail} and {@code AssetGridView}.
 */
public final class AssetLibrary {
    private final ObservableMap<AssetType, AssetCategory> categories =
        FXCollections.observableMap(new java.util.LinkedHashMap<>());

    /** Every asset in the romfs by GUID -- including types with no browse tab
     *  (MATI, MTRL, ...) -- so previews can follow cross-asset references
     *  (model -> material -> texture) regardless of which pak holds them. */
    private final Map<UUID, PakAssetRef> index = new HashMap<>();

    public void index(PakAssetRef ref) {
        index.putIfAbsent(ref.asset().assetId(), ref);
    }

    /** The asset with this GUID, or null if it isn't in the scanned romfs. */
    public PakAssetRef lookup(UUID id) {
        return index.get(id);
    }

    public java.util.Collection<PakAssetRef> allAssets() {
        return index.values();
    }

    /** For each model GUID, the CHPR characters that use it (where its animation clips live). */
    private volatile Map<UUID, java.util.List<UUID>> charactersByModel = Map.of();

    public java.util.List<UUID> charactersOf(UUID model) {
        return charactersByModel.getOrDefault(model, java.util.List.of());
    }

    /** Bumped on the FX thread whenever derived names are applied, so views can re-sort. */
    private final javafx.beans.property.IntegerProperty namesRevision = new javafx.beans.property.SimpleIntegerProperty();

    public javafx.beans.property.IntegerProperty namesRevisionProperty() {
        return namesRevision;
    }

    /** Applies a {@code NameIndex} result: FX thread only. */
    public void applyDerivedNames(Map<UUID, String> names, Map<UUID, java.util.List<UUID>> characters) {
        this.charactersByModel = characters;
        for (AssetCategory category : categories.values()) {
            if (category.type() == AssetType.ALL || category.type() == AssetType.ANIMATED_TEXTURE) {
                continue;
            }
            for (AssetEntry entry : category.entries()) {
                if (entry.sourceRef() instanceof PakAssetRef ref && ref.asset().name() == null) {
                    String name = names.get(ref.asset().assetId());
                    if (name != null) {
                        entry.setDerivedName(name);
                    }
                }
            }
        }
        namesRevision.set(namesRevision.get() + 1);
    }

    /** Applies every saved custom name to the scanned entries: FX thread only. */
    public void applyCustomNames() {
        com.mocktroid.library.CustomNames names = com.mocktroid.library.CustomNames.get();
        for (AssetCategory category : categories.values()) {
            if (category.type() == AssetType.ALL || category.type() == AssetType.ANIMATED_TEXTURE) {
                continue;
            }
            for (AssetEntry entry : category.entries()) {
                entry.setCustomName(names.get(com.mocktroid.library.CustomNames.keyOf(entry)));
            }
        }
        namesRevision.set(namesRevision.get() + 1);
    }

    /** Renames an asset (every pak's copy of it); a null/blank name restores
     *  its original name. Saved to {@code CustomNames}. FX thread only. */
    public void rename(AssetEntry entry, String name) {
        com.mocktroid.library.CustomNames.get().set(com.mocktroid.library.CustomNames.keyOf(entry), name);
        applyCustomNames();
    }

    public AssetCategory category(AssetType type) {
        return categories.computeIfAbsent(type, t -> new AssetCategory(t, t.displayName));
    }

    public ObservableMap<AssetType, AssetCategory> categories() {
        return categories;
    }

    public boolean isEmpty() {
        return categories.values().stream().allMatch(c -> c.entries().isEmpty());
    }

    public int totalAssetCount() {
        // ALL and ANIMATED_TEXTURE re-list entries from the real categories.
        return categories.values().stream()
            .filter(c -> c.type() != AssetType.ALL && c.type() != AssetType.ANIMATED_TEXTURE)
            .mapToInt(c -> c.entries().size()).sum();
    }
}
