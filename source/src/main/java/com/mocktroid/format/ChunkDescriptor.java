package com.mocktroid.format;

/**
 * Generic chunk header inside an RFRM form's TOCC (ADIR/META/STRG) -- 24
 * bytes plus {@code skip} bytes of padding, little-endian. Verified
 * byte-for-byte against a real romfs pak dump.
 * <pre>
 *   id     FourCC
 *   size   u64
 *   unk    u32
 *   skip   u64   padding bytes between this header and the chunk body
 * </pre>
 */
public record ChunkDescriptor(FourCC id, long size, long unk, long skip,
                               int bodyStart, int bodyLen, int nextOffset) {

    public static final int HEADER_SIZE = 24;

    public static ChunkDescriptor read(byte[] data, int offset) {
        FourCC id = LE.fourCC(data, offset);
        long size = LE.u64(data, offset + 4);
        long unk = LE.u32(data, offset + 12);
        long skip = LE.u64(data, offset + 16);
        int bodyStart = Math.toIntExact(offset + HEADER_SIZE + skip);
        int bodyLen = Math.toIntExact(size);
        return new ChunkDescriptor(id, size, unk, skip, bodyStart, bodyLen, bodyStart + bodyLen);
    }
}
