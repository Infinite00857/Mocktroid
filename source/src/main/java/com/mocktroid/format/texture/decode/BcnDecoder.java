package com.mocktroid.format.texture.decode;

/**
 * BC1-BC7 block decoders, ported from Sergii Kudlai's bcdec
 * (https://github.com/iOrange/bcdec, MIT / Unlicense). The partition tables
 * and the BC6H per-mode bit layout are mechanically generated from bcdec.h
 * (see {@code tools/gen_bcn.py}) rather than hand-typed; the rest is a
 * line-by-line port.
 * <p>
 * LDR decoders write packed ARGB ints into {@code dst} starting at
 * {@code dstOff} with a row stride of {@code pitch} pixels. BC6H writes
 * linear float RGB triplets (stride {@code pitch} floats).
 */
public final class BcnDecoder {

    private BcnDecoder() {}

    private static int u16(byte[] s, int o) {
        return (s[o] & 0xFF) | ((s[o + 1] & 0xFF) << 8);
    }

    private static long u64(byte[] s, int o) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (s[o + i] & 0xFFL);
        }
        return v;
    }

    private static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ---- BC1-BC5 ----------------------------------------------------------

    /** Color block shared by BC1/2/3; alpha stays 0xFF unless BC1A mode. */
    private static void colorBlock(byte[] s, int o, int[] dst, int dstOff, int pitch, boolean onlyOpaqueMode) {
        int c0 = u16(s, o);
        int c1 = u16(s, o + 2);
        int r0 = (c0 >> 11) & 0x1F, g0 = (c0 >> 5) & 0x3F, b0 = c0 & 0x1F;
        int r1 = (c1 >> 11) & 0x1F, g1 = (c1 >> 5) & 0x3F, b1 = c1 & 0x1F;

        int[] ref = new int[4];
        ref[0] = argb(0xFF, (r0 * 527 + 23) >> 6, (g0 * 259 + 33) >> 6, (b0 * 527 + 23) >> 6);
        ref[1] = argb(0xFF, (r1 * 527 + 23) >> 6, (g1 * 259 + 33) >> 6, (b1 * 527 + 23) >> 6);
        if (c0 > c1 || onlyOpaqueMode) {
            ref[2] = argb(0xFF, ((2 * r0 + r1) * 351 + 61) >> 7, ((2 * g0 + g1) * 2763 + 1039) >> 11,
                ((2 * b0 + b1) * 351 + 61) >> 7);
            ref[3] = argb(0xFF, ((r0 + r1 * 2) * 351 + 61) >> 7, ((g0 + g1 * 2) * 2763 + 1039) >> 11,
                ((b0 + b1 * 2) * 351 + 61) >> 7);
        } else {
            ref[2] = argb(0xFF, ((r0 + r1) * 1053 + 125) >> 8, ((g0 + g1) * 4145 + 1019) >> 11,
                ((b0 + b1) * 1053 + 125) >> 8);
            ref[3] = 0;
        }

        int indices = (int) (u64(s, o) >>> 32);
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                dst[dstOff + i * pitch + j] = ref[indices & 3];
                indices >>>= 2;
            }
        }
    }

    /** BC4-style 8-value interpolated channel; writes 16 values to {@code out}. */
    private static void smoothAlphaBlock(byte[] s, int o, int[] out) {
        long block = u64(s, o);
        int[] alpha = new int[8];
        alpha[0] = (int) (block & 0xFF);
        alpha[1] = (int) ((block >>> 8) & 0xFF);
        if (alpha[0] > alpha[1]) {
            alpha[2] = (6 * alpha[0] + alpha[1]) / 7;
            alpha[3] = (5 * alpha[0] + 2 * alpha[1]) / 7;
            alpha[4] = (4 * alpha[0] + 3 * alpha[1]) / 7;
            alpha[5] = (3 * alpha[0] + 4 * alpha[1]) / 7;
            alpha[6] = (2 * alpha[0] + 5 * alpha[1]) / 7;
            alpha[7] = (alpha[0] + 6 * alpha[1]) / 7;
        } else {
            alpha[2] = (4 * alpha[0] + alpha[1]) / 5;
            alpha[3] = (3 * alpha[0] + 2 * alpha[1]) / 5;
            alpha[4] = (2 * alpha[0] + 3 * alpha[1]) / 5;
            alpha[5] = (alpha[0] + 4 * alpha[1]) / 5;
            alpha[6] = 0x00;
            alpha[7] = 0xFF;
        }
        long indices = block >>> 16;
        for (int i = 0; i < 16; i++) {
            out[i] = alpha[(int) (indices & 7)];
            indices >>>= 3;
        }
    }

    public static void bc1(byte[] s, int o, int[] dst, int dstOff, int pitch) {
        colorBlock(s, o, dst, dstOff, pitch, false);
    }

    public static void bc2(byte[] s, int o, int[] dst, int dstOff, int pitch) {
        colorBlock(s, o + 8, dst, dstOff, pitch, true);
        for (int i = 0; i < 4; i++) {
            int alpha = u16(s, o + i * 2);
            for (int j = 0; j < 4; j++) {
                int a = ((alpha >> (4 * j)) & 0x0F) * 17;
                int p = dstOff + i * pitch + j;
                dst[p] = (dst[p] & 0x00FFFFFF) | (a << 24);
            }
        }
    }

    public static void bc3(byte[] s, int o, int[] dst, int dstOff, int pitch) {
        colorBlock(s, o + 8, dst, dstOff, pitch, true);
        int[] alpha = new int[16];
        smoothAlphaBlock(s, o, alpha);
        for (int i = 0; i < 16; i++) {
            int p = dstOff + (i >> 2) * pitch + (i & 3);
            dst[p] = (dst[p] & 0x00FFFFFF) | (alpha[i] << 24);
        }
    }

    /** BC4: single channel, shown as grayscale. */
    public static void bc4(byte[] s, int o, int[] dst, int dstOff, int pitch) {
        int[] v = new int[16];
        smoothAlphaBlock(s, o, v);
        for (int i = 0; i < 16; i++) {
            dst[dstOff + (i >> 2) * pitch + (i & 3)] = argb(0xFF, v[i], v[i], v[i]);
        }
    }

    /** BC5: two channels (typically a normal map's XY), shown as R/G with B=0. */
    public static void bc5(byte[] s, int o, int[] dst, int dstOff, int pitch) {
        int[] r = new int[16];
        int[] g = new int[16];
        smoothAlphaBlock(s, o, r);
        smoothAlphaBlock(s, o + 8, g);
        for (int i = 0; i < 16; i++) {
            dst[dstOff + (i >> 2) * pitch + (i & 3)] = argb(0xFF, r[i], g[i], 0);
        }
    }

    // ---- BC6H / BC7 shared bitstream ----------------------------------------

    private static final class BitStream {
        long low;
        long high;

        BitStream(byte[] s, int o) {
            low = u64(s, o);
            high = u64(s, o + 8);
        }

        int bits(int numBits) {
            long mask = (1L << numBits) - 1;
            int bits = (int) (low & mask);
            low >>>= numBits;
            if (numBits > 0) {
                low |= (high & mask) << (64 - numBits);
            }
            high >>>= numBits;
            return bits;
        }

        /** BC6H stores a few endpoint MSB runs bit-reversed. */
        int bitsReversed(int numBits) {
            int bits = bits(numBits);
            int result = 0;
            while (numBits-- > 0) {
                result = (result << 1) | (bits & 1);
                bits >>= 1;
            }
            return result;
        }
    }

    private static final int[] WEIGHT2 = {0, 21, 43, 64};
    private static final int[] WEIGHT3 = {0, 9, 18, 27, 37, 46, 55, 64};
    private static final int[] WEIGHT4 = {0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64};

    private static int interpolate(int a, int b, int[] weights, int index) {
        return (a * (64 - weights[index]) + b * weights[index] + 32) >> 6;
    }

    // ---- BC6H ----------------------------------------------------------------

    private static final int[][] BC6H_BITS = {
        {10, 7, 11, 11, 11, 9, 8, 8, 8, 6, 10, 11, 12, 16},   // W
        {5, 6, 5, 4, 4, 5, 6, 5, 5, 6, 10, 9, 8, 4},          // dR
        {5, 6, 4, 5, 4, 5, 5, 6, 5, 6, 10, 9, 8, 4},          // dG
        {5, 6, 4, 4, 5, 5, 5, 5, 6, 6, 10, 9, 8, 4},          // dB
    };

    private static int extendSign(int val, int bits) {
        return (val << (32 - bits)) >> (32 - bits);
    }

    private static int transformInverse(int val, int a0, int bits, boolean signed) {
        val = (val + a0) & ((1 << bits) - 1);
        return signed ? extendSign(val, bits) : val;
    }

    private static int unquantize(int val, int bits, boolean signed) {
        int unq;
        if (!signed) {
            if (bits >= 15) {
                unq = val;
            } else if (val == 0) {
                unq = 0;
            } else if (val == ((1 << bits) - 1)) {
                unq = 0xFFFF;
            } else {
                unq = ((val << 16) + 0x8000) >> bits;
            }
        } else {
            if (bits >= 16) {
                unq = val;
            } else {
                boolean negative = val < 0;
                if (negative) {
                    val = -val;
                }
                if (val == 0) {
                    unq = 0;
                } else if (val >= ((1 << (bits - 1)) - 1)) {
                    unq = 0x7FFF;
                } else {
                    unq = ((val << 15) + 0x4000) >> (bits - 1);
                }
                if (negative) {
                    unq = -unq;
                }
            }
        }
        return unq;
    }

    /** Returns half-float bits. */
    private static int finishUnquantize(int val, boolean signed) {
        if (!signed) {
            return (val * 31) >> 6;
        }
        val = (val < 0) ? -(((-val) * 31) >> 5) : (val * 31) >> 5;
        int s = 0;
        if (val < 0) {
            s = 0x8000;
            val = -val;
        }
        return s | val;
    }

    /** Decodes one BC6H block to linear float RGB triplets. */
    public static void bc6h(byte[] src, int o, float[] dst, int dstOff, int pitch, boolean isSigned) {
        BitStream bs = new BitStream(src, o);
        int[] r = new int[4];
        int[] g = new int[4];
        int[] b = new int[4];

        int mode = bs.bits(2);
        if (mode > 1) {
            mode |= bs.bits(3) << 2;
        }
        int partition = 0;

        switch (mode) {
            case 0b00 -> {
                g[2] |= bs.bits(1) << 4;
                b[2] |= bs.bits(1) << 4;
                b[3] |= bs.bits(1) << 4;
                r[0] |= bs.bits(10);
                g[0] |= bs.bits(10);
                b[0] |= bs.bits(10);
                r[1] |= bs.bits(5);
                g[3] |= bs.bits(1) << 4;
                g[2] |= bs.bits(4);
                g[1] |= bs.bits(5);
                b[3] |= bs.bits(1);
                g[3] |= bs.bits(4);
                b[1] |= bs.bits(5);
                b[3] |= bs.bits(1) << 1;
                b[2] |= bs.bits(4);
                r[2] |= bs.bits(5);
                b[3] |= bs.bits(1) << 2;
                r[3] |= bs.bits(5);
                b[3] |= bs.bits(1) << 3;
                partition = bs.bits(5);
                mode = 0;
            }
            case 0b01 -> {
                g[2] |= bs.bits(1) << 5;
                g[3] |= bs.bits(1) << 4;
                g[3] |= bs.bits(1) << 5;
                r[0] |= bs.bits(7);
                b[3] |= bs.bits(1);
                b[3] |= bs.bits(1) << 1;
                b[2] |= bs.bits(1) << 4;
                g[0] |= bs.bits(7);
                b[2] |= bs.bits(1) << 5;
                b[3] |= bs.bits(1) << 2;
                g[2] |= bs.bits(1) << 4;
                b[0] |= bs.bits(7);
                b[3] |= bs.bits(1) << 3;
                b[3] |= bs.bits(1) << 5;
                b[3] |= bs.bits(1) << 4;
                r[1] |= bs.bits(6);
                g[2] |= bs.bits(4);
                g[1] |= bs.bits(6);
                g[3] |= bs.bits(4);
                b[1] |= bs.bits(6);
                b[2] |= bs.bits(4);
                r[2] |= bs.bits(6);
                r[3] |= bs.bits(6);
                partition = bs.bits(5);
                mode = 1;
            }
            case 0b00010 -> {
                r[0] |= bs.bits(10);
                g[0] |= bs.bits(10);
                b[0] |= bs.bits(10);
                r[1] |= bs.bits(5);
                r[0] |= bs.bits(1) << 10;
                g[2] |= bs.bits(4);
                g[1] |= bs.bits(4);
                g[0] |= bs.bits(1) << 10;
                b[3] |= bs.bits(1);
                g[3] |= bs.bits(4);
                b[1] |= bs.bits(4);
                b[0] |= bs.bits(1) << 10;
                b[3] |= bs.bits(1) << 1;
                b[2] |= bs.bits(4);
                r[2] |= bs.bits(5);
                b[3] |= bs.bits(1) << 2;
                r[3] |= bs.bits(5);
                b[3] |= bs.bits(1) << 3;
                partition = bs.bits(5);
                mode = 2;
            }
            case 0b00110 -> {
                r[0] |= bs.bits(10);
                g[0] |= bs.bits(10);
                b[0] |= bs.bits(10);
                r[1] |= bs.bits(4);
                r[0] |= bs.bits(1) << 10;
                g[3] |= bs.bits(1) << 4;
                g[2] |= bs.bits(4);
                g[1] |= bs.bits(5);
                g[0] |= bs.bits(1) << 10;
                g[3] |= bs.bits(4);
                b[1] |= bs.bits(4);
                b[0] |= bs.bits(1) << 10;
                b[3] |= bs.bits(1) << 1;
                b[2] |= bs.bits(4);
                r[2] |= bs.bits(4);
                b[3] |= bs.bits(1);
                b[3] |= bs.bits(1) << 2;
                r[3] |= bs.bits(4);
                g[2] |= bs.bits(1) << 4;
                b[3] |= bs.bits(1) << 3;
                partition = bs.bits(5);
                mode = 3;
            }
            case 0b01010 -> {
                r[0] |= bs.bits(10);
                g[0] |= bs.bits(10);
                b[0] |= bs.bits(10);
                r[1] |= bs.bits(4);
                r[0] |= bs.bits(1) << 10;
                b[2] |= bs.bits(1) << 4;
                g[2] |= bs.bits(4);
                g[1] |= bs.bits(4);
                g[0] |= bs.bits(1) << 10;
                b[3] |= bs.bits(1);
                g[3] |= bs.bits(4);
                b[1] |= bs.bits(5);
                b[0] |= bs.bits(1) << 10;
                b[2] |= bs.bits(4);
                r[2] |= bs.bits(4);
                b[3] |= bs.bits(1) << 1;
                b[3] |= bs.bits(1) << 2;
                r[3] |= bs.bits(4);
                b[3] |= bs.bits(1) << 4;
                b[3] |= bs.bits(1) << 3;
                partition = bs.bits(5);
                mode = 4;
            }
            case 0b01110 -> {
                r[0] |= bs.bits(9);
                b[2] |= bs.bits(1) << 4;
                g[0] |= bs.bits(9);
                g[2] |= bs.bits(1) << 4;
                b[0] |= bs.bits(9);
                b[3] |= bs.bits(1) << 4;
                r[1] |= bs.bits(5);
                g[3] |= bs.bits(1) << 4;
                g[2] |= bs.bits(4);
                g[1] |= bs.bits(5);
                b[3] |= bs.bits(1);
                g[3] |= bs.bits(4);
                b[1] |= bs.bits(5);
                b[3] |= bs.bits(1) << 1;
                b[2] |= bs.bits(4);
                r[2] |= bs.bits(5);
                b[3] |= bs.bits(1) << 2;
                r[3] |= bs.bits(5);
                b[3] |= bs.bits(1) << 3;
                partition = bs.bits(5);
                mode = 5;
            }
            case 0b10010 -> {
                r[0] |= bs.bits(8);
                g[3] |= bs.bits(1) << 4;
                b[2] |= bs.bits(1) << 4;
                g[0] |= bs.bits(8);
                b[3] |= bs.bits(1) << 2;
                g[2] |= bs.bits(1) << 4;
                b[0] |= bs.bits(8);
                b[3] |= bs.bits(1) << 3;
                b[3] |= bs.bits(1) << 4;
                r[1] |= bs.bits(6);
                g[2] |= bs.bits(4);
                g[1] |= bs.bits(5);
                b[3] |= bs.bits(1);
                g[3] |= bs.bits(4);
                b[1] |= bs.bits(5);
                b[3] |= bs.bits(1) << 1;
                b[2] |= bs.bits(4);
                r[2] |= bs.bits(6);
                r[3] |= bs.bits(6);
                partition = bs.bits(5);
                mode = 6;
            }
            case 0b10110 -> {
                r[0] |= bs.bits(8);
                b[3] |= bs.bits(1);
                b[2] |= bs.bits(1) << 4;
                g[0] |= bs.bits(8);
                g[2] |= bs.bits(1) << 5;
                g[2] |= bs.bits(1) << 4;
                b[0] |= bs.bits(8);
                g[3] |= bs.bits(1) << 5;
                b[3] |= bs.bits(1) << 4;
                r[1] |= bs.bits(5);
                g[3] |= bs.bits(1) << 4;
                g[2] |= bs.bits(4);
                g[1] |= bs.bits(6);
                g[3] |= bs.bits(4);
                b[1] |= bs.bits(5);
                b[3] |= bs.bits(1) << 1;
                b[2] |= bs.bits(4);
                r[2] |= bs.bits(5);
                b[3] |= bs.bits(1) << 2;
                r[3] |= bs.bits(5);
                b[3] |= bs.bits(1) << 3;
                partition = bs.bits(5);
                mode = 7;
            }
            case 0b11010 -> {
                r[0] |= bs.bits(8);
                b[3] |= bs.bits(1) << 1;
                b[2] |= bs.bits(1) << 4;
                g[0] |= bs.bits(8);
                b[2] |= bs.bits(1) << 5;
                g[2] |= bs.bits(1) << 4;
                b[0] |= bs.bits(8);
                b[3] |= bs.bits(1) << 5;
                b[3] |= bs.bits(1) << 4;
                r[1] |= bs.bits(5);
                g[3] |= bs.bits(1) << 4;
                g[2] |= bs.bits(4);
                g[1] |= bs.bits(5);
                b[3] |= bs.bits(1);
                g[3] |= bs.bits(4);
                b[1] |= bs.bits(6);
                b[2] |= bs.bits(4);
                r[2] |= bs.bits(5);
                b[3] |= bs.bits(1) << 2;
                r[3] |= bs.bits(5);
                b[3] |= bs.bits(1) << 3;
                partition = bs.bits(5);
                mode = 8;
            }
            case 0b11110 -> {
                r[0] |= bs.bits(6);
                g[3] |= bs.bits(1) << 4;
                b[3] |= bs.bits(1);
                b[3] |= bs.bits(1) << 1;
                b[2] |= bs.bits(1) << 4;
                g[0] |= bs.bits(6);
                g[2] |= bs.bits(1) << 5;
                b[2] |= bs.bits(1) << 5;
                b[3] |= bs.bits(1) << 2;
                g[2] |= bs.bits(1) << 4;
                b[0] |= bs.bits(6);
                g[3] |= bs.bits(1) << 5;
                b[3] |= bs.bits(1) << 3;
                b[3] |= bs.bits(1) << 5;
                b[3] |= bs.bits(1) << 4;
                r[1] |= bs.bits(6);
                g[2] |= bs.bits(4);
                g[1] |= bs.bits(6);
                g[3] |= bs.bits(4);
                b[1] |= bs.bits(6);
                b[2] |= bs.bits(4);
                r[2] |= bs.bits(6);
                r[3] |= bs.bits(6);
                partition = bs.bits(5);
                mode = 9;
            }
            case 0b00011 -> {
                r[0] |= bs.bits(10);
                g[0] |= bs.bits(10);
                b[0] |= bs.bits(10);
                r[1] |= bs.bits(10);
                g[1] |= bs.bits(10);
                b[1] |= bs.bits(10);
                mode = 10;
            }
            case 0b00111 -> {
                r[0] |= bs.bits(10);
                g[0] |= bs.bits(10);
                b[0] |= bs.bits(10);
                r[1] |= bs.bits(9);
                r[0] |= bs.bits(1) << 10;
                g[1] |= bs.bits(9);
                g[0] |= bs.bits(1) << 10;
                b[1] |= bs.bits(9);
                b[0] |= bs.bits(1) << 10;
                mode = 11;
            }
            case 0b01011 -> {
                r[0] |= bs.bits(10);
                g[0] |= bs.bits(10);
                b[0] |= bs.bits(10);
                r[1] |= bs.bits(8);
                r[0] |= bs.bitsReversed(2) << 10;
                g[1] |= bs.bits(8);
                g[0] |= bs.bitsReversed(2) << 10;
                b[1] |= bs.bits(8);
                b[0] |= bs.bitsReversed(2) << 10;
                mode = 12;
            }
            case 0b01111 -> {
                r[0] |= bs.bits(10);
                g[0] |= bs.bits(10);
                b[0] |= bs.bits(10);
                r[1] |= bs.bits(4);
                r[0] |= bs.bitsReversed(6) << 10;
                g[1] |= bs.bits(4);
                g[0] |= bs.bitsReversed(6) << 10;
                b[1] |= bs.bits(4);
                b[0] |= bs.bitsReversed(6) << 10;
                mode = 13;
            }
            default -> {
                // Reserved modes decode to black.
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        int p = dstOff + i * pitch + j * 3;
                        dst[p] = dst[p + 1] = dst[p + 2] = 0f;
                    }
                }
                return;
            }
        }

        int numPartitions = (mode >= 10) ? 0 : 1;
        int actualBits0Mode = BC6H_BITS[0][mode];
        if (isSigned) {
            r[0] = extendSign(r[0], actualBits0Mode);
            g[0] = extendSign(g[0], actualBits0Mode);
            b[0] = extendSign(b[0], actualBits0Mode);
        }

        // Mode 11 (like mode 10) stores both endpoints explicitly, no deltas.
        if ((mode != 9 && mode != 10) || isSigned) {
            for (int i = 1; i < (numPartitions + 1) * 2; i++) {
                r[i] = extendSign(r[i], BC6H_BITS[1][mode]);
                g[i] = extendSign(g[i], BC6H_BITS[2][mode]);
                b[i] = extendSign(b[i], BC6H_BITS[3][mode]);
            }
        }
        if (mode != 9 && mode != 10) {
            for (int i = 1; i < (numPartitions + 1) * 2; i++) {
                r[i] = transformInverse(r[i], r[0], actualBits0Mode, isSigned);
                g[i] = transformInverse(g[i], g[0], actualBits0Mode, isSigned);
                b[i] = transformInverse(b[i], b[0], actualBits0Mode, isSigned);
            }
        }
        for (int i = 0; i < (numPartitions + 1) * 2; i++) {
            r[i] = unquantize(r[i], actualBits0Mode, isSigned);
            g[i] = unquantize(g[i], actualBits0Mode, isSigned);
            b[i] = unquantize(b[i], actualBits0Mode, isSigned);
        }

        int[] weights = (mode >= 10) ? WEIGHT4 : WEIGHT3;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                int partitionSet = (mode >= 10) ? ((i | j) != 0 ? 0 : 128) : BC6H_PARTITIONS[partition][i * 4 + j];
                int indexBits = (mode >= 10) ? 4 : 3;
                if ((partitionSet & 0x80) != 0) {
                    indexBits--;
                }
                partitionSet &= 0x01;
                int index = bs.bits(indexBits);
                int ep = partitionSet * 2;
                int p = dstOff + i * pitch + j * 3;
                dst[p] = halfToFloat(finishUnquantize(interpolate(r[ep], r[ep + 1], weights, index), isSigned));
                dst[p + 1] = halfToFloat(finishUnquantize(interpolate(g[ep], g[ep + 1], weights, index), isSigned));
                dst[p + 2] = halfToFloat(finishUnquantize(interpolate(b[ep], b[ep + 1], weights, index), isSigned));
            }
        }
    }

    private static float halfToFloat(int half) {
        return Float.float16ToFloat((short) half);
    }

    // ---- BC7 -----------------------------------------------------------------

    private static final int[][] BC7_BITS = {
        {4, 6, 5, 7, 5, 7, 7, 5},   // RGB(A) endpoint bits
        {0, 0, 0, 0, 6, 8, 7, 5},   // separate alpha bits
    };
    private static final int MODE_HAS_P_BITS = 0b11001011;

    public static void bc7(byte[] src, int o, int[] dst, int dstOff, int pitch) {
        BitStream bs = new BitStream(src, o);

        int mode = 0;
        while (mode < 8 && bs.bits(1) == 0) {
            mode++;
        }
        if (mode >= 8) {
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    dst[dstOff + i * pitch + j] = 0;
                }
            }
            return;
        }

        int partition = 0;
        int numPartitions = 1;
        int rotation = 0;
        int indexSelectionBit = 0;
        if (mode == 0 || mode == 1 || mode == 2 || mode == 3 || mode == 7) {
            numPartitions = (mode == 0 || mode == 2) ? 3 : 2;
            partition = bs.bits(mode == 0 ? 4 : 6);
        }
        int numEndpoints = numPartitions * 2;
        if (mode == 4 || mode == 5) {
            rotation = bs.bits(2);
            if (mode == 4) {
                indexSelectionBit = bs.bits(1);
            }
        }

        int[][] endpoints = new int[6][4];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < numEndpoints; j++) {
                endpoints[j][i] = bs.bits(BC7_BITS[0][mode]);
            }
        }
        if (BC7_BITS[1][mode] > 0) {
            for (int j = 0; j < numEndpoints; j++) {
                endpoints[j][3] = bs.bits(BC7_BITS[1][mode]);
            }
        }

        if (mode == 0 || mode == 1 || mode == 3 || mode == 6 || mode == 7) {
            for (int i = 0; i < numEndpoints; i++) {
                for (int j = 0; j < 4; j++) {
                    endpoints[i][j] <<= 1;
                }
            }
            if (mode == 1) {
                int i = bs.bits(1);
                int j = bs.bits(1);
                for (int k = 0; k < 3; k++) {
                    endpoints[0][k] |= i;
                    endpoints[1][k] |= i;
                    endpoints[2][k] |= j;
                    endpoints[3][k] |= j;
                }
            } else if ((MODE_HAS_P_BITS & (1 << mode)) != 0) {
                for (int i = 0; i < numEndpoints; i++) {
                    int j = bs.bits(1);
                    for (int k = 0; k < 4; k++) {
                        endpoints[i][k] |= j;
                    }
                }
            }
        }

        for (int i = 0; i < numEndpoints; i++) {
            int j = BC7_BITS[0][mode] + ((MODE_HAS_P_BITS >> mode) & 1);
            for (int k = 0; k < 3; k++) {
                endpoints[i][k] = endpoints[i][k] << (8 - j);
                endpoints[i][k] = endpoints[i][k] | (endpoints[i][k] >> j);
            }
            j = BC7_BITS[1][mode] + ((MODE_HAS_P_BITS >> mode) & 1);
            endpoints[i][3] = endpoints[i][3] << (8 - j);
            endpoints[i][3] = endpoints[i][3] | (endpoints[i][3] >> j);
        }
        if (BC7_BITS[1][mode] == 0) {
            for (int j = 0; j < numEndpoints; j++) {
                endpoints[j][3] = 0xFF;
            }
        }

        int indexBitsBase = (mode == 0 || mode == 1) ? 3 : (mode == 6 ? 4 : 2);
        int indexBits2 = (mode == 4) ? 3 : (mode == 5 ? 2 : 0);
        int[] weights = indexBitsBase == 2 ? WEIGHT2 : (indexBitsBase == 3 ? WEIGHT3 : WEIGHT4);
        int[] weights2 = indexBits2 == 2 ? WEIGHT2 : WEIGHT3;
        int[][] table = numPartitions == 3 ? BC7_PARTITIONS_3 : BC7_PARTITIONS_2;

        int[] indices = new int[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                int partitionSet = numPartitions == 1 ? ((i | j) != 0 ? 0 : 128) : table[partition][i * 4 + j];
                int indexBits = indexBitsBase;
                if ((partitionSet & 0x80) != 0) {
                    indexBits--;
                }
                indices[i * 4 + j] = bs.bits(indexBits);
            }
        }

        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                int partitionSet = numPartitions == 1 ? ((i | j) != 0 ? 0 : 128) : table[partition][i * 4 + j];
                partitionSet &= 0x03;
                int index = indices[i * 4 + j];
                int[] e0 = endpoints[partitionSet * 2];
                int[] e1 = endpoints[partitionSet * 2 + 1];
                int r, g, b, a;
                if (indexBits2 == 0) {
                    r = interpolate(e0[0], e1[0], weights, index);
                    g = interpolate(e0[1], e1[1], weights, index);
                    b = interpolate(e0[2], e1[2], weights, index);
                    a = interpolate(e0[3], e1[3], weights, index);
                } else {
                    int index2 = bs.bits((i | j) != 0 ? indexBits2 : (indexBits2 - 1));
                    if (indexSelectionBit == 0) {
                        r = interpolate(e0[0], e1[0], weights, index);
                        g = interpolate(e0[1], e1[1], weights, index);
                        b = interpolate(e0[2], e1[2], weights, index);
                        a = interpolate(e0[3], e1[3], weights2, index2);
                    } else {
                        r = interpolate(e0[0], e1[0], weights2, index2);
                        g = interpolate(e0[1], e1[1], weights2, index2);
                        b = interpolate(e0[2], e1[2], weights2, index2);
                        a = interpolate(e0[3], e1[3], weights, index);
                    }
                }
                int t;
                switch (rotation) {
                    case 1 -> { t = a; a = r; r = t; }
                    case 2 -> { t = a; a = g; g = t; }
                    case 3 -> { t = a; a = b; b = t; }
                    default -> { }
                }
                dst[dstOff + i * pitch + j] = argb(a, r, g, b);
            }
        }
    }

// ---- generated by gen_bcn.py from bcdec.h (MIT/Unlicense) -- do not edit by hand ----
    static final int[][] BC6H_PARTITIONS = {
        {128, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 129},
        {128, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 129},
        {128, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 129},
        {128, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 129},
        {128, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 129},
        {128, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 129},
        {128, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 1, 1, 129},
        {128, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 129},
        {128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 129},
        {128, 0, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 129},
        {128, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 129},
        {128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 129},
        {128, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 129},
        {128, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 129},
        {128, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 129},
        {128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 129},
        {128, 0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 0, 1, 1, 1, 129},
        {128, 1, 129, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0},
        {128, 0, 0, 0, 0, 0, 0, 0, 129, 0, 0, 0, 1, 1, 1, 0},
        {128, 1, 129, 1, 0, 0, 1, 1, 0, 0, 0, 1, 0, 0, 0, 0},
        {128, 0, 129, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0},
        {128, 0, 0, 0, 1, 0, 0, 0, 129, 1, 0, 0, 1, 1, 1, 0},
        {128, 0, 0, 0, 0, 0, 0, 0, 129, 0, 0, 0, 1, 1, 0, 0},
        {128, 1, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 0, 129},
        {128, 0, 129, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0},
        {128, 0, 0, 0, 1, 0, 0, 0, 129, 0, 0, 0, 1, 1, 0, 0},
        {128, 1, 129, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0},
        {128, 0, 129, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 0, 0},
        {128, 0, 0, 1, 0, 1, 1, 1, 129, 1, 1, 0, 1, 0, 0, 0},
        {128, 0, 0, 0, 1, 1, 1, 1, 129, 1, 1, 1, 0, 0, 0, 0},
        {128, 1, 129, 1, 0, 0, 0, 1, 1, 0, 0, 0, 1, 1, 1, 0},
        {128, 0, 129, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 1, 0, 0},
    };
    static final int[][] BC7_PARTITIONS_2 = {
        {128, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 129},
        {128, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 129},
        {128, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 129},
        {128, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 129},
        {128, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 129},
        {128, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 129},
        {128, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 1, 1, 129},
        {128, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 129},
        {128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 129},
        {128, 0, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 129},
        {128, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 129},
        {128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 129},
        {128, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 129},
        {128, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 129},
        {128, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 129},
        {128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 129},
        {128, 0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 0, 1, 1, 1, 129},
        {128, 1, 129, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0},
        {128, 0, 0, 0, 0, 0, 0, 0, 129, 0, 0, 0, 1, 1, 1, 0},
        {128, 1, 129, 1, 0, 0, 1, 1, 0, 0, 0, 1, 0, 0, 0, 0},
        {128, 0, 129, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0},
        {128, 0, 0, 0, 1, 0, 0, 0, 129, 1, 0, 0, 1, 1, 1, 0},
        {128, 0, 0, 0, 0, 0, 0, 0, 129, 0, 0, 0, 1, 1, 0, 0},
        {128, 1, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 0, 129},
        {128, 0, 129, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0},
        {128, 0, 0, 0, 1, 0, 0, 0, 129, 0, 0, 0, 1, 1, 0, 0},
        {128, 1, 129, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0},
        {128, 0, 129, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 0, 0},
        {128, 0, 0, 1, 0, 1, 1, 1, 129, 1, 1, 0, 1, 0, 0, 0},
        {128, 0, 0, 0, 1, 1, 1, 1, 129, 1, 1, 1, 0, 0, 0, 0},
        {128, 1, 129, 1, 0, 0, 0, 1, 1, 0, 0, 0, 1, 1, 1, 0},
        {128, 0, 129, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 1, 0, 0},
        {128, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 129},
        {128, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 129},
        {128, 1, 0, 1, 1, 0, 129, 0, 0, 1, 0, 1, 1, 0, 1, 0},
        {128, 0, 1, 1, 0, 0, 1, 1, 129, 1, 0, 0, 1, 1, 0, 0},
        {128, 0, 129, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0},
        {128, 1, 0, 1, 0, 1, 0, 1, 129, 0, 1, 0, 1, 0, 1, 0},
        {128, 1, 1, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 129},
        {128, 1, 0, 1, 1, 0, 1, 0, 1, 0, 1, 0, 0, 1, 0, 129},
        {128, 1, 129, 1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 1, 1, 0},
        {128, 0, 0, 1, 0, 0, 1, 1, 129, 1, 0, 0, 1, 0, 0, 0},
        {128, 0, 129, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 1, 0, 0},
        {128, 0, 129, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, 1, 0, 0},
        {128, 1, 129, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 1, 1, 0},
        {128, 0, 1, 1, 1, 1, 0, 0, 1, 1, 0, 0, 0, 0, 1, 129},
        {128, 1, 1, 0, 0, 1, 1, 0, 1, 0, 0, 1, 1, 0, 0, 129},
        {128, 0, 0, 0, 0, 1, 129, 0, 0, 1, 1, 0, 0, 0, 0, 0},
        {128, 1, 0, 0, 1, 1, 129, 0, 0, 1, 0, 0, 0, 0, 0, 0},
        {128, 0, 129, 0, 0, 1, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0},
        {128, 0, 0, 0, 0, 0, 129, 0, 0, 1, 1, 1, 0, 0, 1, 0},
        {128, 0, 0, 0, 0, 1, 0, 0, 129, 1, 1, 0, 0, 1, 0, 0},
        {128, 1, 1, 0, 1, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 129},
        {128, 0, 1, 1, 0, 1, 1, 0, 1, 1, 0, 0, 1, 0, 0, 129},
        {128, 1, 129, 0, 0, 0, 1, 1, 1, 0, 0, 1, 1, 1, 0, 0},
        {128, 0, 129, 1, 1, 0, 0, 1, 1, 1, 0, 0, 0, 1, 1, 0},
        {128, 1, 1, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 129},
        {128, 1, 1, 0, 0, 0, 1, 1, 0, 0, 1, 1, 1, 0, 0, 129},
        {128, 1, 1, 1, 1, 1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 129},
        {128, 0, 0, 1, 1, 0, 0, 0, 1, 1, 1, 0, 0, 1, 1, 129},
        {128, 0, 0, 0, 1, 1, 1, 1, 0, 0, 1, 1, 0, 0, 1, 129},
        {128, 0, 129, 1, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0},
        {128, 0, 129, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1, 1, 1, 0},
        {128, 1, 0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 0, 1, 1, 129},
    };
    static final int[][] BC7_PARTITIONS_3 = {
        {128, 0, 1, 129, 0, 0, 1, 1, 0, 2, 2, 1, 2, 2, 2, 130},
        {128, 0, 0, 129, 0, 0, 1, 1, 130, 2, 1, 1, 2, 2, 2, 1},
        {128, 0, 0, 0, 2, 0, 0, 1, 130, 2, 1, 1, 2, 2, 1, 129},
        {128, 2, 2, 130, 0, 0, 2, 2, 0, 0, 1, 1, 0, 1, 1, 129},
        {128, 0, 0, 0, 0, 0, 0, 0, 129, 1, 2, 2, 1, 1, 2, 130},
        {128, 0, 1, 129, 0, 0, 1, 1, 0, 0, 2, 2, 0, 0, 2, 130},
        {128, 0, 2, 130, 0, 0, 2, 2, 1, 1, 1, 1, 1, 1, 1, 129},
        {128, 0, 1, 1, 0, 0, 1, 1, 130, 2, 1, 1, 2, 2, 1, 129},
        {128, 0, 0, 0, 0, 0, 0, 0, 129, 1, 1, 1, 2, 2, 2, 130},
        {128, 0, 0, 0, 1, 1, 1, 1, 129, 1, 1, 1, 2, 2, 2, 130},
        {128, 0, 0, 0, 1, 1, 129, 1, 2, 2, 2, 2, 2, 2, 2, 130},
        {128, 0, 1, 2, 0, 0, 129, 2, 0, 0, 1, 2, 0, 0, 1, 130},
        {128, 1, 1, 2, 0, 1, 129, 2, 0, 1, 1, 2, 0, 1, 1, 130},
        {128, 1, 2, 2, 0, 129, 2, 2, 0, 1, 2, 2, 0, 1, 2, 130},
        {128, 0, 1, 129, 0, 1, 1, 2, 1, 1, 2, 2, 1, 2, 2, 130},
        {128, 0, 1, 129, 2, 0, 0, 1, 130, 2, 0, 0, 2, 2, 2, 0},
        {128, 0, 0, 129, 0, 0, 1, 1, 0, 1, 1, 2, 1, 1, 2, 130},
        {128, 1, 1, 129, 0, 0, 1, 1, 130, 0, 0, 1, 2, 2, 0, 0},
        {128, 0, 0, 0, 1, 1, 2, 2, 129, 1, 2, 2, 1, 1, 2, 130},
        {128, 0, 2, 130, 0, 0, 2, 2, 0, 0, 2, 2, 1, 1, 1, 129},
        {128, 1, 1, 129, 0, 1, 1, 1, 0, 2, 2, 2, 0, 2, 2, 130},
        {128, 0, 0, 129, 0, 0, 0, 1, 130, 2, 2, 1, 2, 2, 2, 1},
        {128, 0, 0, 0, 0, 0, 129, 1, 0, 1, 2, 2, 0, 1, 2, 130},
        {128, 0, 0, 0, 1, 1, 0, 0, 130, 2, 129, 0, 2, 2, 1, 0},
        {128, 1, 2, 130, 0, 129, 2, 2, 0, 0, 1, 1, 0, 0, 0, 0},
        {128, 0, 1, 2, 0, 0, 1, 2, 129, 1, 2, 2, 2, 2, 2, 130},
        {128, 1, 1, 0, 1, 2, 130, 1, 129, 2, 2, 1, 0, 1, 1, 0},
        {128, 0, 0, 0, 0, 1, 129, 0, 1, 2, 130, 1, 1, 2, 2, 1},
        {128, 0, 2, 2, 1, 1, 0, 2, 129, 1, 0, 2, 0, 0, 2, 130},
        {128, 1, 1, 0, 0, 129, 1, 0, 2, 0, 0, 2, 2, 2, 2, 130},
        {128, 0, 1, 1, 0, 1, 2, 2, 0, 1, 130, 2, 0, 0, 1, 129},
        {128, 0, 0, 0, 2, 0, 0, 0, 130, 2, 1, 1, 2, 2, 2, 129},
        {128, 0, 0, 0, 0, 0, 0, 2, 129, 1, 2, 2, 1, 2, 2, 130},
        {128, 2, 2, 130, 0, 0, 2, 2, 0, 0, 1, 2, 0, 0, 1, 129},
        {128, 0, 1, 129, 0, 0, 1, 2, 0, 0, 2, 2, 0, 2, 2, 130},
        {128, 1, 2, 0, 0, 129, 2, 0, 0, 1, 130, 0, 0, 1, 2, 0},
        {128, 0, 0, 0, 1, 1, 129, 1, 2, 2, 130, 2, 0, 0, 0, 0},
        {128, 1, 2, 0, 1, 2, 0, 1, 130, 0, 129, 2, 0, 1, 2, 0},
        {128, 1, 2, 0, 2, 0, 1, 2, 129, 130, 0, 1, 0, 1, 2, 0},
        {128, 0, 1, 1, 2, 2, 0, 0, 1, 1, 130, 2, 0, 0, 1, 129},
        {128, 0, 1, 1, 1, 1, 130, 2, 2, 2, 0, 0, 0, 0, 1, 129},
        {128, 1, 0, 129, 0, 1, 0, 1, 2, 2, 2, 2, 2, 2, 2, 130},
        {128, 0, 0, 0, 0, 0, 0, 0, 130, 1, 2, 1, 2, 1, 2, 129},
        {128, 0, 2, 2, 1, 129, 2, 2, 0, 0, 2, 2, 1, 1, 2, 130},
        {128, 0, 2, 130, 0, 0, 1, 1, 0, 0, 2, 2, 0, 0, 1, 129},
        {128, 2, 2, 0, 1, 2, 130, 1, 0, 2, 2, 0, 1, 2, 2, 129},
        {128, 1, 0, 1, 2, 2, 130, 2, 2, 2, 2, 2, 0, 1, 0, 129},
        {128, 0, 0, 0, 2, 1, 2, 1, 130, 1, 2, 1, 2, 1, 2, 129},
        {128, 1, 0, 129, 0, 1, 0, 1, 0, 1, 0, 1, 2, 2, 2, 130},
        {128, 2, 2, 130, 0, 1, 1, 1, 0, 2, 2, 2, 0, 1, 1, 129},
        {128, 0, 0, 2, 1, 129, 1, 2, 0, 0, 0, 2, 1, 1, 1, 130},
        {128, 0, 0, 0, 2, 129, 1, 2, 2, 1, 1, 2, 2, 1, 1, 130},
        {128, 2, 2, 2, 0, 129, 1, 1, 0, 1, 1, 1, 0, 2, 2, 130},
        {128, 0, 0, 2, 1, 1, 1, 2, 129, 1, 1, 2, 0, 0, 0, 130},
        {128, 1, 1, 0, 0, 129, 1, 0, 0, 1, 1, 0, 2, 2, 2, 130},
        {128, 0, 0, 0, 0, 0, 0, 0, 2, 1, 129, 2, 2, 1, 1, 130},
        {128, 1, 1, 0, 0, 129, 1, 0, 2, 2, 2, 2, 2, 2, 2, 130},
        {128, 0, 2, 2, 0, 0, 1, 1, 0, 0, 129, 1, 0, 0, 2, 130},
        {128, 0, 2, 2, 1, 1, 2, 2, 129, 1, 2, 2, 0, 0, 2, 130},
        {128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 129, 1, 130},
        {128, 0, 0, 130, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 129},
        {128, 2, 2, 2, 1, 2, 2, 2, 0, 2, 2, 2, 129, 2, 2, 130},
        {128, 1, 0, 129, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 130},
        {128, 1, 1, 129, 2, 0, 1, 1, 130, 2, 0, 1, 2, 2, 2, 0},
    };
}
