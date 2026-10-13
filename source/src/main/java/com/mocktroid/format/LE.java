package com.mocktroid.format;

/**
 * Little-endian primitive readers over a plain byte[]. Every value in this
 * container format is little-endian; u32/u64 fields are widened to Java's
 * next-larger signed type (long) purely to represent the full unsigned
 * range safely -- no field observed in practice comes close to overflowing
 * a signed 63-bit value.
 */
public final class LE {
    private LE() {}

    public static int u16(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    public static long u32(byte[] d, int o) {
        return (d[o] & 0xFFL) | ((d[o + 1] & 0xFFL) << 8)
            | ((d[o + 2] & 0xFFL) << 16) | ((d[o + 3] & 0xFFL) << 24);
    }

    public static int i32(byte[] d, int o) {
        return (int) u32(d, o);
    }

    public static float f32(byte[] d, int o) {
        return Float.intBitsToFloat(i32(d, o));
    }

    public static void putI32(byte[] d, int o, int v) {
        d[o] = (byte) v;
        d[o + 1] = (byte) (v >> 8);
        d[o + 2] = (byte) (v >> 16);
        d[o + 3] = (byte) (v >> 24);
    }

    public static long u64(byte[] d, int o) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (d[o + i] & 0xFFL);
        }
        return v;
    }

    public static FourCC fourCC(byte[] d, int o) {
        return new FourCC(new byte[]{d[o], d[o + 1], d[o + 2], d[o + 3]});
    }
}
