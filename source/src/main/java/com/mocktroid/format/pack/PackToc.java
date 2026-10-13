package com.mocktroid.format.pack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mocktroid.format.ChunkDescriptor;
import com.mocktroid.format.FourCC;
import com.mocktroid.format.LE;

/**
 * The ADIR/STRG chunk-walking logic shared between {@link PackReader}
 * (full-file, random-access mode) and {@link PackCatalog} (lightweight
 * header-only scanning mode) -- pulled out so both read exactly the same
 * way instead of risking two copies drifting apart.
 */
final class PackToc {
    private static final FourCC ADIR = new FourCC("ADIR");
    private static final FourCC STRG = new FourCC("STRG");
    private static final FourCC META = new FourCC("META");

    private PackToc() {}

    /** Walks every chunk between {@code toccBodyStart} and {@code toccEnd},
     *  returning the assembled asset list. {@code data} just needs to cover
     *  that byte range (absolute offsets) -- it doesn't need to include any
     *  asset payload bytes beyond the TOCC section. */
    static List<PackAsset> readAssets(byte[] data, int toccBodyStart, int toccEnd) {
        List<AssetDirEntry> adirEntries = List.of();
        Map<UUID, String> names = new HashMap<>();
        Map<UUID, byte[]> metas = new HashMap<>();

        int pos = toccBodyStart;
        while (pos < toccEnd) {
            ChunkDescriptor chunk = ChunkDescriptor.read(data, pos);
            if (chunk.id().equals(ADIR)) {
                adirEntries = readAdir(data, chunk);
            } else if (chunk.id().equals(STRG)) {
                readStrg(data, chunk, names);
            } else if (chunk.id().equals(META)) {
                readMeta(data, chunk, metas);
            }
            pos = chunk.nextOffset();
        }

        List<PackAsset> assets = new ArrayList<>(adirEntries.size());
        for (AssetDirEntry e : adirEntries) {
            assets.add(new PackAsset(e.assetType, e.assetId, names.get(e.assetId),
                e.offset, e.decompressedSize, e.size, metas.get(e.assetId)));
        }
        return assets;
    }

    /** META: u32 count, then {GUID assetId, u32 offset} entries; each offset
     *  (relative to the chunk body) points at a u32 length + that many bytes
     *  of type-specific metadata. Matches retrotool's MetadataTable. */
    private static void readMeta(byte[] data, ChunkDescriptor chunk, Map<UUID, byte[]> out) {
        int base = chunk.bodyStart();
        long count = LE.u32(data, base);
        for (int i = 0; i < count; i++) {
            int o = base + 4 + i * 20;
            UUID assetId = com.mocktroid.format.GuidUtil.fromBytesLe(data, o);
            int metaStart = base + Math.toIntExact(LE.u32(data, o + 16));
            int metaLen = Math.toIntExact(LE.u32(data, metaStart));
            byte[] meta = new byte[metaLen];
            System.arraycopy(data, metaStart + 4, meta, 0, metaLen);
            out.put(assetId, meta);
        }
    }

    private static List<AssetDirEntry> readAdir(byte[] data, ChunkDescriptor chunk) {
        int base = chunk.bodyStart();
        long count = LE.u32(data, base);
        List<AssetDirEntry> list = new ArrayList<>(Math.toIntExact(count));
        int entryStart = base + 4;
        final int entrySize = 60;
        for (int i = 0; i < count; i++) {
            int o = entryStart + i * entrySize;
            FourCC assetType = LE.fourCC(data, o);
            UUID assetId = com.mocktroid.format.GuidUtil.fromBytesLe(data, o + 4);
            // version (o+20, u32) and otherVersion (o+24, u32) intentionally unused for now.
            long offset = LE.u64(data, o + 28);
            long decompressedSize = LE.u64(data, o + 36);
            long size = LE.u64(data, o + 44);
            // unk1 (o+52, u64) intentionally unused.
            list.add(new AssetDirEntry(assetType, assetId, offset, decompressedSize, size));
        }
        return list;
    }

    private static void readStrg(byte[] data, ChunkDescriptor chunk, Map<UUID, String> out) {
        int base = chunk.bodyStart();
        long count = LE.u32(data, base);
        int o = base + 4;
        for (int i = 0; i < count; i++) {
            // kind (4 bytes, byte-reversed per the format's STRG quirk) intentionally unused.
            o += 4;
            UUID assetId = com.mocktroid.format.GuidUtil.fromBytesLe(data, o);
            o += 16;
            long nameLength = LE.u32(data, o);
            o += 4;
            int len = Math.toIntExact(nameLength);
            String name = new String(data, o, len, java.nio.charset.StandardCharsets.UTF_8);
            o += len;
            out.put(assetId, name);
        }
    }

    private record AssetDirEntry(FourCC assetType, UUID assetId, long offset, long decompressedSize, long size) {}
}
