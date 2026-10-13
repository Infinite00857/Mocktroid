package com.mocktroid.format.pack;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.List;

import com.mocktroid.format.FormDescriptor;
import com.mocktroid.format.FourCC;
import com.mocktroid.format.MocktroidFormatException;
import com.mocktroid.format.compress.Compression;

/**
 * Memory-efficient pak access for scanning a whole romfs: {@link #scan}
 * reads only the PACK+TOCC header region (the asset directory/name table,
 * typically KB-scale) rather than the full file, and {@link #extractDecompressed}
 * seeks straight to one asset's byte range rather than loading the whole
 * pak. A romfs dump can be tens of GB across hundreds of paks -- {@link PackReader}'s
 * full-file read is fine for a single pak but wouldn't scale to cataloging
 * all of them.
 */
public final class PackCatalog {
    private static final FourCC PACK = new FourCC("PACK");
    private static final FourCC TOCC = new FourCC("TOCC");

    private PackCatalog() {}

    public static List<PackAsset> scan(Path pakFile) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(pakFile.toFile(), "r")) {
            byte[] outerHeaders = new byte[64]; // PACK's 32-byte RFRM header + TOCC's 32-byte RFRM header
            raf.seek(0);
            raf.readFully(outerHeaders);

            FormDescriptor packForm = FormDescriptor.read(outerHeaders, 0);
            if (!packForm.id().equals(PACK)) {
                throw new MocktroidFormatException(pakFile + ": expected PACK form, found \"" + packForm.id() + "\"");
            }
            FormDescriptor toccForm = FormDescriptor.read(outerHeaders, packForm.bodyStart());
            if (!toccForm.id().equals(TOCC)) {
                throw new MocktroidFormatException(pakFile + ": expected TOCC form, found \"" + toccForm.id() + "\"");
            }

            // TOCC itself (asset directory + name table) stays small even in a
            // multi-GB pak -- it's metadata, not payload -- so this int cast is
            // safe here even though the outer PACK form's own size (used only
            // to locate TOCC, never allocated) can legitimately exceed 2GB; two
            // real paks in this romfs run to 3.5+ GB.
            int toccEnd = Math.toIntExact(toccForm.nextOffset());
            byte[] prefix = new byte[toccEnd];
            raf.seek(0);
            raf.readFully(prefix);
            return PackToc.readAssets(prefix, Math.toIntExact(toccForm.bodyStart()), toccEnd);
        }
    }

    /** Seeks straight to {@code asset}'s byte range in {@code pakFile} and
     *  decompresses it, without reading the rest of the file. */
    /** The first {@code max} bytes of an asset, without reading the rest --
     *  only for assets stored uncompressed at the pak level (everything but
     *  CAUD/FSMC); null for compressed ones. Used to sniff headers and names. */
    public static byte[] readPrefix(Path pakFile, PackAsset asset, int max) throws IOException {
        if (asset.size() != asset.decompressedSize()) {
            return null;
        }
        try (RandomAccessFile raf = new RandomAccessFile(pakFile.toFile(), "r")) {
            byte[] out = new byte[(int) Math.min(max, asset.size())];
            raf.seek(asset.offset());
            raf.readFully(out);
            return out;
        }
    }

    public static byte[] extractDecompressed(Path pakFile, PackAsset asset) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(pakFile.toFile(), "r")) {
            int length = Math.toIntExact(asset.size());
            byte[] raw = new byte[length];
            raf.seek(asset.offset());
            raf.readFully(raw);
            return Compression.decompressAsset(raw, 0, length, asset.decompressedSize());
        }
    }
}
