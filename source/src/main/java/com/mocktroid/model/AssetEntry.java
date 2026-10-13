package com.mocktroid.model;

import java.util.Objects;

/**
 * A single browsable asset, shown as a card in the grid and in detail in the
 * preview pane.
 * <p>
 * {@code sourceRef} is an opaque handle the data layer uses to locate the real
 * bytes once a loader module exists (e.g. a PAK asset UUID + owning PakReader
 * for the pak module, or a plain file path for standalone TXTR/CSMP/etc. files).
 * The UI never interprets it directly -- it just hands it to whichever preview
 * module matches {@link #type()}.
 */
public final class AssetEntry {
    private final String id;
    private final String displayName;
    private final AssetType type;
    private final String sourcePakName;
    private final Object sourceRef;
    /** A readable name worked out from other assets (see {@code NameIndex}) for
     *  entries the pak only knows by GUID; null until/unless one is found. */
    private volatile String derivedName;

    public AssetEntry(String id, String displayName, AssetType type, String sourcePakName, Object sourceRef) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.type = Objects.requireNonNull(type, "type");
        this.sourcePakName = sourcePakName;
        this.sourceRef = sourceRef;
    }

    /** The user's own name for this asset (see {@code CustomNames}); wins over everything. */
    private volatile String customName;

    public String id() { return id; }
    /** The user's custom name, else the derived name, else the pak's name or the GUID. */
    public String displayName() { return customName != null ? customName : originalName(); }
    /** What the asset is called without the user's custom name. */
    public String originalName() { return derivedName != null ? derivedName : displayName; }
    public void setCustomName(String name) { this.customName = name; }
    public boolean hasCustomName() { return customName != null; }
    /** The name the pak itself gives (STRG), or the GUID when it has none. */
    public String rawName() { return displayName; }
    public void setDerivedName(String name) { this.derivedName = name; }
    public boolean hasDerivedName() { return derivedName != null; }
    public AssetType type() { return type; }
    public String sourcePakName() { return sourcePakName; }
    public Object sourceRef() { return sourceRef; }

    @Override
    public String toString() { return displayName(); }
}
