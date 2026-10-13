package com.mocktroid.library;

import java.io.IOException;

import com.mocktroid.format.pack.PackCatalog;
import com.mocktroid.format.texture.Texture;
import com.mocktroid.format.texture.TxtrReader;
import com.mocktroid.model.AssetEntry;

/** Extracts and parses the TXTR behind an {@link AssetEntry}. Blocking -- call off the FX thread. */
public final class TextureLoader {
    private TextureLoader() {}

    public static Texture load(AssetEntry entry) throws IOException {
        if (entry.sourceRef() instanceof TextureSequences.SequenceRef seq) {
            return TextureSequences.asTexture(seq);
        }
        if (!(entry.sourceRef() instanceof PakAssetRef ref)) {
            throw new IOException("No source data for " + entry.displayName());
        }
        byte[] raw = PackCatalog.extractDecompressed(ref.pakFile(), ref.asset());
        return TxtrReader.read(raw, ref.asset().meta());
    }
}
