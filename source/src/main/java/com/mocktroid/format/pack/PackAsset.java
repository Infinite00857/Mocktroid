package com.mocktroid.format.pack;

import java.util.UUID;

import com.mocktroid.format.FourCC;

/**
 * One entry from a PACK's asset directory (ADIR), with its name filled in
 * from the string table (STRG) when one exists -- not every asset is named --
 * and its per-asset metadata blob from the META chunk when one exists.
 * <p>
 * {@code meta} is opaque at this layer; its layout is type-specific (TXTR
 * stores its GPU buffer/compression layout there -- see
 * {@code TxtrReader}). Typically ~100 bytes, so keeping it for every asset
 * costs a few MB across a full romfs, which is cheaper than re-reading TOCC
 * on every extraction.
 */
public record PackAsset(FourCC assetType, UUID assetId, String name,
                         long offset, long decompressedSize, long size, byte[] meta) {

    public String displayName() {
        return name != null ? name : assetId.toString();
    }
}
