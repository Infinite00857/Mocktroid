package com.mocktroid.model;

/**
 * Broad asset kinds Mocktroid understands. Drives which top tab an asset
 * shows under, and which preview module handles it.
 * <p>
 * CHPR (character/suit definitions) intentionally has no dedicated entry --
 * per design direction, characters are filed under MODEL rather than getting
 * their own top-level category; see {@link #fromFourCc}.
 */
public enum AssetType {
    VIDEO("Videos", "FMV0"),
    TEXTURE("Textures", "TXTR"),
    SOUND("Sounds", "CSMP"),
    MODEL("Models", "CMDL"),
    MATERIAL("Materials", "MTRL"),
    ROOM("Rooms", "ROOM"),
    UNKNOWN("Unknown", ""),
    /** Derived: texture flipbooks (2D arrays with many layers), filled by {@code AnimatedTextures}. */
    ANIMATED_TEXTURE("Animated Textures", ""),
    /** Derived: every browsable asset, whatever its type. */
    ALL("All", "");

    /** Tab / group label. */
    public final String displayName;
    /** The RFRM form FourCC this type corresponds to, e.g. "TXTR". Empty for UNKNOWN. */
    public final String fourCc;

    AssetType(String displayName, String fourCc) {
        this.displayName = displayName;
        this.fourCc = fourCc;
    }

    public static AssetType fromFourCc(String fourCc) {
        if ("CHPR".equalsIgnoreCase(fourCc)) {
            return UNKNOWN; // characters aren't browsable (their clips open from the models that use them)
        }
        if ("SMDL".equalsIgnoreCase(fourCc)) {
            return MODEL;
        }
        for (AssetType t : values()) {
            if (!t.fourCc.isEmpty() && t.fourCc.equalsIgnoreCase(fourCc)) {
                return t;
            }
        }
        return UNKNOWN;
    }
}
