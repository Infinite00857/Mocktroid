package com.mocktroid.format;

import java.util.UUID;

/**
 * Parses the mixed-endian "GUID" byte layout used for asset IDs throughout
 * this format (matches Rust's {@code Uuid::from_bytes_le}): the first 4
 * bytes are a little-endian u32, the next 2 a little-endian u16, the next 2
 * another little-endian u16 -- all three byte-swapped into big-endian order
 * for the canonical UUID form -- and the final 8 bytes are copied verbatim.
 */
public final class GuidUtil {
    private GuidUtil() {}

    public static UUID fromBytesLe(byte[] src, int off) {
        byte[] canonical = new byte[16];
        canonical[0] = src[off + 3];
        canonical[1] = src[off + 2];
        canonical[2] = src[off + 1];
        canonical[3] = src[off];
        canonical[4] = src[off + 5];
        canonical[5] = src[off + 4];
        canonical[6] = src[off + 7];
        canonical[7] = src[off + 6];
        System.arraycopy(src, off + 8, canonical, 8, 8);

        long msb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (canonical[i] & 0xFFL);
        }
        long lsb = 0;
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (canonical[i] & 0xFFL);
        }
        return new UUID(msb, lsb);
    }
}
