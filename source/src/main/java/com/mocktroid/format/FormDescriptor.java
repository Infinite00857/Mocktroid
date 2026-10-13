package com.mocktroid.format;

/**
 * The RFRM container header -- 32 bytes, little-endian, wraps every
 * top-level and nested form in this format (PACK, TOCC, TXTR, CMDL, FMV0,
 * CSMP, ...). Verified byte-for-byte against a real romfs pak dump.
 * <pre>
 *   magic          FourCC   "RFRM"
 *   size           u64      byte length of the body that follows
 *   unk            u64
 *   id             FourCC   the form's type, e.g. "PACK", "TOCC", "CSMP"
 *   readerVersion  u32
 *   writerVersion  u32
 * </pre>
 * <p>
 * {@code bodyStart}/{@code bodyLen}/{@code nextOffset} are {@code long}, not
 * {@code int} -- a top-level PACK form's body can legitimately exceed 2GB
 * (some real paks in this romfs run to 3.5+ GB), and this class only
 * describes positions/extents, it doesn't allocate anything. Callers that
 * actually need to size a byte[] truncate to int themselves, at the point
 * where that's meaningful (e.g. PackCatalog sizing a buffer for just the
 * TOCC region, which stays small even in a huge pak) -- this was a real bug
 * caught against real data: eagerly truncating here threw ArithmeticException
 * on two of 441 real paks.
 */
public record FormDescriptor(FourCC magic, long size, long unk, FourCC id,
                              long readerVersion, long writerVersion,
                              long bodyStart, long bodyLen, long nextOffset) {

    public static final int HEADER_SIZE = 32;
    private static final FourCC MAGIC = new FourCC("RFRM");

    public static FormDescriptor read(byte[] data, long offset) {
        int o = Math.toIntExact(offset);
        FourCC magic = LE.fourCC(data, o);
        if (!magic.equals(MAGIC)) {
            throw new MocktroidFormatException(
                "Expected RFRM magic at offset " + offset + ", found \"" + magic + "\"");
        }
        long size = LE.u64(data, o + 4);
        long unk = LE.u64(data, o + 12);
        FourCC id = LE.fourCC(data, o + 20);
        long readerVersion = LE.u32(data, o + 24);
        long writerVersion = LE.u32(data, o + 28);
        long bodyStart = offset + HEADER_SIZE;
        return new FormDescriptor(magic, size, unk, id, readerVersion, writerVersion,
            bodyStart, size, bodyStart + size);
    }
}
