package com.mocktroid.format.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.mocktroid.format.FormDescriptor;
import com.mocktroid.format.FourCC;
import com.mocktroid.format.MocktroidFormatException;
import com.mocktroid.format.compress.Compression;

/**
 * Reads a .pak file fully into memory: RFRM("PACK") -> RFRM("TOCC") ->
 * chunks ADIR (asset list) + STRG (names). Layout verified byte-for-byte
 * against a real romfs dump; see roadmap.md.
 * <p>
 * Good for one-off inspection of a single known pak. For scanning many paks
 * across a whole romfs to build a catalog, use {@link PackCatalog} instead
 * -- it reads only the header/TOC region rather than the whole file (which
 * matters when paks run tens of MB and a romfs has hundreds of them).
 */
public final class PackReader {

    private final byte[] data;
    private final List<PackAsset> assets;

    private PackReader(byte[] data, List<PackAsset> assets) {
        this.data = data;
        this.assets = assets;
    }

    public static PackReader open(Path pakFile) throws IOException {
        return open(Files.readAllBytes(pakFile));
    }

    public static PackReader open(byte[] data) {
        FormDescriptor packForm = FormDescriptor.read(data, 0);
        if (!packForm.id().equals(new FourCC("PACK"))) {
            throw new MocktroidFormatException("Expected PACK form, found \"" + packForm.id() + "\"");
        }
        FormDescriptor toccForm = FormDescriptor.read(data, packForm.bodyStart());
        if (!toccForm.id().equals(new FourCC("TOCC"))) {
            throw new MocktroidFormatException("Expected TOCC form, found \"" + toccForm.id() + "\"");
        }
        List<PackAsset> assets = PackToc.readAssets(data,
            Math.toIntExact(toccForm.bodyStart()), Math.toIntExact(toccForm.nextOffset()));
        return new PackReader(data, assets);
    }

    public List<PackAsset> assets() {
        return assets;
    }

    /** Extracts and decompresses one asset's bytes. The result is still an
     *  RFRM-wrapped inner resource (e.g. RFRM("CSMP")) -- callers that need
     *  the payload past that header parse it themselves; PackReader doesn't
     *  know about per-type inner structure. */
    public byte[] extractDecompressed(PackAsset asset) {
        int offset = Math.toIntExact(asset.offset());
        int length = Math.toIntExact(asset.size());
        return Compression.decompressAsset(data, offset, length, asset.decompressedSize());
    }
}
