package com.mocktroid.format.texture.decode;

/**
 * ASTC (LDR profile) block decoder, written from the Khronos Data Format
 * Specification's ASTC chapter: block mode decode, integer sequence
 * encoding (bits/trits/quints), color endpoint and weight unquantization,
 * the partition hash, bilinear weight infill, and dual-plane weights.
 * <p>
 * HDR endpoint modes (2, 3, 7, 11, 14, 15) aren't valid in an LDR texture
 * and decode to the spec's magenta error color, as do malformed blocks --
 * MP4 only uses the LDR/sRGB ASTC formats. Output is packed ARGB ints.
 */
public final class AstcDecoder {

    private static final int ERROR_COLOR = 0xFFFF00FF;

    /** {trits, quints, bits} for each of the 21 ISE quantization levels
     *  (ranges 2,3,4,5,6,8,10,12,16,20,24,32,40,48,64,80,96,128,160,192,256). */
    private static final int[][] ISE = {
        {0, 0, 1}, {1, 0, 0}, {0, 0, 2}, {0, 1, 0}, {1, 0, 1}, {0, 0, 3}, {0, 1, 1},
        {1, 0, 2}, {0, 0, 4}, {0, 1, 2}, {1, 0, 3}, {0, 0, 5}, {0, 1, 3}, {1, 0, 4},
        {0, 0, 6}, {0, 1, 4}, {1, 0, 5}, {0, 0, 7}, {0, 1, 5}, {1, 0, 6}, {0, 0, 8},
    };
    private static final int QUANT_6 = 4;

    private AstcDecoder() {}

    /** Decodes one 16-byte block into a {@code bw x bh} region of {@code dst}. */
    public static void decodeBlock(byte[] src, int off, int bw, int bh, boolean srgb,
                                   int[] dst, int dstOff, int pitch) {
        long lo = u64(src, off);
        long hi = u64(src, off + 8);
        if (!decode(lo, hi, bw, bh, srgb, dst, dstOff, pitch)) {
            fill(dst, dstOff, pitch, bw, bh, ERROR_COLOR);
        }
    }

    private static boolean decode(long lo, long hi, int bw, int bh, boolean srgb,
                                  int[] dst, int dstOff, int pitch) {
        int blockMode = bits(lo, hi, 0, 11);

        // Void-extent: a single constant color for the whole block.
        if ((blockMode & 0x1FF) == 0x1FC) {
            boolean hdr = (blockMode & 0x200) != 0;
            int r = (int) (hi & 0xFFFF);
            int g = (int) ((hi >>> 16) & 0xFFFF);
            int b = (int) ((hi >>> 32) & 0xFFFF);
            int a = (int) ((hi >>> 48) & 0xFFFF);
            int color = hdr
                ? argb(halfToByte(a), halfToByte(r), halfToByte(g), halfToByte(b))
                : argb(a >> 8, r >> 8, g >> 8, b >> 8);
            fill(dst, dstOff, pitch, bw, bh, color);
            return true;
        }

        // ---- block mode -> weight grid ----
        int baseQuant = (blockMode >> 4) & 1;
        int h = (blockMode >> 9) & 1;
        int d = (blockMode >> 10) & 1;
        int a = (blockMode >> 5) & 3;
        int gridW;
        int gridH;
        if ((blockMode & 3) != 0) {
            baseQuant |= (blockMode & 3) << 1;
            int b = (blockMode >> 7) & 3;
            switch ((blockMode >> 2) & 3) {
                case 0 -> { gridW = b + 4; gridH = a + 2; }
                case 1 -> { gridW = b + 8; gridH = a + 2; }
                case 2 -> { gridW = a + 2; gridH = b + 8; }
                default -> {
                    b &= 1;
                    if ((blockMode & 0x100) != 0) {
                        gridW = b + 2; gridH = a + 2;
                    } else {
                        gridW = a + 2; gridH = b + 6;
                    }
                }
            }
        } else {
            baseQuant |= ((blockMode >> 2) & 3) << 1;
            if (((blockMode >> 2) & 3) == 0) {
                return false;
            }
            int b = (blockMode >> 9) & 3;
            switch ((blockMode >> 7) & 3) {
                case 0 -> { gridW = 12; gridH = a + 2; }
                case 1 -> { gridW = a + 2; gridH = 12; }
                case 2 -> { gridW = a + 6; gridH = b + 6; d = 0; h = 0; }
                default -> {
                    switch ((blockMode >> 5) & 3) {
                        case 0 -> { gridW = 6; gridH = 10; }
                        case 1 -> { gridW = 10; gridH = 6; }
                        default -> { return false; }
                    }
                }
            }
        }
        boolean dualPlane = d != 0;
        int weightQuant = (baseQuant - 2) + 6 * h;
        int weightCount = gridW * gridH * (dualPlane ? 2 : 1);
        if (weightQuant < 0 || weightQuant > 11 || gridW > bw || gridH > bh || weightCount > 64) {
            return false;
        }
        int weightBits = iseBitCount(weightCount, weightQuant);
        if (weightBits < 24 || weightBits > 96) {
            return false;
        }

        // ---- partitions & color endpoint modes ----
        int partitions = bits(lo, hi, 11, 2) + 1;
        if (partitions == 4 && dualPlane) {
            return false;
        }
        int belowWeights = 128 - weightBits;
        int[] cem = new int[partitions];
        int extraCemBits = 0;
        int colorStart;
        int partitionIndex = 0;
        if (partitions == 1) {
            cem[0] = bits(lo, hi, 13, 4);
            colorStart = 17;
        } else {
            partitionIndex = bits(lo, hi, 13, 10);
            int cemLow = bits(lo, hi, 23, 6);
            colorStart = 29;
            int baseClass = cemLow & 3;
            if (baseClass == 0) {
                for (int i = 0; i < partitions; i++) {
                    cem[i] = (cemLow >> 2) & 0xF;
                }
            } else {
                extraCemBits = 3 * partitions - 4;
                int combined = cemLow | (bits(lo, hi, belowWeights - extraCemBits, extraCemBits) << 6);
                int base = baseClass - 1;
                for (int i = 0; i < partitions; i++) {
                    int c = (combined >> (2 + i)) & 1;
                    int m = (combined >> (2 + partitions + 2 * i)) & 3;
                    cem[i] = ((base + c) << 2) | m;
                }
            }
        }
        int ccs = dualPlane ? bits(lo, hi, belowWeights - extraCemBits - 2, 2) : -1;
        int colorBits = belowWeights - extraCemBits - (dualPlane ? 2 : 0) - colorStart;

        int colorCount = 0;
        for (int i = 0; i < partitions; i++) {
            colorCount += ((cem[i] >> 2) + 1) * 2;
        }
        if (colorCount > 18 || colorBits <= 0) {
            return false;
        }
        int colorQuant = -1;
        for (int q = ISE.length - 1; q >= QUANT_6; q--) {
            if (iseBitCount(colorCount, q) <= colorBits) {
                colorQuant = q;
                break;
            }
        }
        if (colorQuant < 0) {
            return false;
        }

        // ---- color endpoints ----
        int[] colorD = new int[colorCount];
        int[] colorM = new int[colorCount];
        decodeIse(lo, hi, colorStart, colorBits, colorCount, colorQuant, colorD, colorM);
        int[] colorValues = new int[colorCount];
        for (int i = 0; i < colorCount; i++) {
            colorValues[i] = unquantColor(colorQuant, colorD[i], colorM[i]);
        }
        int[][] e0 = new int[partitions][4];
        int[][] e1 = new int[partitions][4];
        int vi = 0;
        for (int p = 0; p < partitions; p++) {
            if (!decodeEndpoints(cem[p], colorValues, vi, e0[p], e1[p])) {
                return false;
            }
            vi += ((cem[p] >> 2) + 1) * 2;
        }

        // ---- weights (stored bit-reversed from the top of the block) ----
        long rlo = Long.reverse(hi);
        long rhi = Long.reverse(lo);
        int[] wD = new int[weightCount];
        int[] wM = new int[weightCount];
        decodeIse(rlo, rhi, 0, weightBits, weightCount, weightQuant, wD, wM);
        int planes = dualPlane ? 2 : 1;
        int[][] gridWeights = new int[planes][gridW * gridH + gridW + 1]; // padded for infill reads
        for (int i = 0; i < weightCount; i++) {
            gridWeights[i % planes][i / planes] = unquantWeight(weightQuant, wD[i], wM[i]);
        }

        // ---- per-texel infill + interpolation ----
        int ds = (1024 + bw / 2) / (bw - 1);
        int dt = (1024 + bh / 2) / (bh - 1);
        boolean smallBlock = bw * bh < 31;
        int[] texelWeights = new int[planes];
        for (int t = 0; t < bh; t++) {
            for (int s = 0; s < bw; s++) {
                int gs = (ds * s * (gridW - 1) + 32) >> 6;
                int gt = (dt * t * (gridH - 1) + 32) >> 6;
                int js = gs >> 4;
                int fs = gs & 0xF;
                int jt = gt >> 4;
                int ft = gt & 0xF;
                int w11 = (fs * ft + 8) >> 4;
                int w10 = ft - w11;
                int w01 = fs - w11;
                int w00 = 16 - fs - ft + w11;
                int v0 = js + jt * gridW;
                for (int p = 0; p < planes; p++) {
                    int[] gw = gridWeights[p];
                    int p00 = gw[v0];
                    int p01 = gw[v0 + 1];
                    int p10 = gw[v0 + gridW];
                    int p11 = gw[v0 + gridW + 1];
                    texelWeights[p] = (p00 * w00 + p01 * w01 + p10 * w10 + p11 * w11 + 8) >> 4;
                }

                int part = partitions == 1 ? 0
                    : selectPartition(partitionIndex, s, t, 0, partitions, smallBlock);
                int[] c0 = e0[part];
                int[] c1 = e1[part];
                int[] out = new int[4];
                for (int c = 0; c < 4; c++) {
                    int w = (c == ccs) ? texelWeights[1] : texelWeights[0];
                    int lo16 = expand(c0[c], srgb && c < 3);
                    int hi16 = expand(c1[c], srgb && c < 3);
                    int v = (lo16 * (64 - w) + hi16 * w + 32) >> 6;
                    out[c] = v >> 8;
                }
                dst[dstOff + t * pitch + s] = argb(out[3], out[0], out[1], out[2]);
            }
        }
        return true;
    }

    private static int expand(int v8, boolean srgb) {
        return srgb ? (v8 << 8) | 0x80 : (v8 << 8) | v8;
    }

    // ---- integer sequence encoding ----------------------------------------

    private static int iseBitCount(int count, int quant) {
        int[] p = ISE[quant];
        int bits = count * p[2];
        if (p[0] != 0) {
            bits += (8 * count + 4) / 5;
        } else if (p[1] != 0) {
            bits += (7 * count + 2) / 3;
        }
        return bits;
    }

    /** Bit cursor over a 128-bit block that reads zeros past {@code end}. */
    private static final class Reader {
        final long lo;
        final long hi;
        int pos;
        final int end;

        Reader(long lo, long hi, int pos, int length) {
            this.lo = lo;
            this.hi = hi;
            this.pos = pos;
            this.end = pos + length;
        }

        int read(int n) {
            int v = 0;
            for (int i = 0; i < n; i++, pos++) {
                if (pos < end && pos < 128) {
                    v |= bits(lo, hi, pos, 1) << i;
                }
            }
            return v;
        }
    }

    /** Decodes {@code count} ISE values: {@code outD} gets each value's trit/quint
     *  digit (0 for pure-bit levels), {@code outM} its low bits. */
    private static void decodeIse(long lo, long hi, int start, int length, int count, int quant,
                                  int[] outD, int[] outM) {
        int trits = ISE[quant][0];
        int quints = ISE[quant][1];
        int b = ISE[quant][2];
        Reader r = new Reader(lo, hi, start, length);
        if (trits != 0) {
            int[] m = new int[5];
            int[] t = new int[5];
            for (int i = 0; i < count; i += 5) {
                int tBits;
                m[0] = r.read(b); tBits = r.read(2);
                m[1] = r.read(b); tBits |= r.read(2) << 2;
                m[2] = r.read(b); tBits |= r.read(1) << 4;
                m[3] = r.read(b); tBits |= r.read(2) << 5;
                m[4] = r.read(b); tBits |= r.read(1) << 7;
                decodeTrits(tBits, t);
                for (int k = 0; k < 5 && i + k < count; k++) {
                    outD[i + k] = t[k];
                    outM[i + k] = m[k];
                }
            }
        } else if (quints != 0) {
            int[] m = new int[3];
            int[] q = new int[3];
            for (int i = 0; i < count; i += 3) {
                int qBits;
                m[0] = r.read(b); qBits = r.read(3);
                m[1] = r.read(b); qBits |= r.read(2) << 3;
                m[2] = r.read(b); qBits |= r.read(2) << 5;
                decodeQuints(qBits, q);
                for (int k = 0; k < 3 && i + k < count; k++) {
                    outD[i + k] = q[k];
                    outM[i + k] = m[k];
                }
            }
        } else {
            for (int i = 0; i < count; i++) {
                outD[i] = 0;
                outM[i] = r.read(b);
            }
        }
    }

    private static int bit(int v, int n) {
        return (v >> n) & 1;
    }

    private static void decodeTrits(int tb, int[] t) {
        int c;
        if (((tb >> 2) & 7) == 7) {
            c = (((tb >> 5) & 7) << 2) | (tb & 3);
            t[4] = 2;
            t[3] = 2;
        } else {
            c = tb & 0x1F;
            if (((tb >> 5) & 3) == 3) {
                t[4] = 2;
                t[3] = bit(tb, 7);
            } else {
                t[4] = bit(tb, 7);
                t[3] = (tb >> 5) & 3;
            }
        }
        if ((c & 3) == 3) {
            t[2] = 2;
            t[1] = bit(c, 4);
            t[0] = (bit(c, 3) << 1) | (bit(c, 2) & ~bit(c, 3) & 1);
        } else if (((c >> 2) & 3) == 3) {
            t[2] = 2;
            t[1] = 2;
            t[0] = c & 3;
        } else {
            t[2] = bit(c, 4);
            t[1] = (c >> 2) & 3;
            t[0] = (bit(c, 1) << 1) | (bit(c, 0) & ~bit(c, 1) & 1);
        }
    }

    private static void decodeQuints(int qb, int[] q) {
        if (((qb >> 1) & 3) == 3 && ((qb >> 5) & 3) == 0) {
            int q0bit = bit(qb, 0);
            q[2] = (q0bit << 2) | ((bit(qb, 4) & ~q0bit & 1) << 1) | (bit(qb, 3) & ~q0bit & 1);
            q[1] = 4;
            q[0] = 4;
            return;
        }
        int c;
        if (((qb >> 1) & 3) == 3) {
            q[2] = 4;
            c = (((qb >> 3) & 3) << 3) | ((~(qb >> 5) & 3) << 1) | bit(qb, 0);
        } else {
            q[2] = (qb >> 5) & 3;
            c = qb & 0x1F;
        }
        if ((c & 7) == 5) {
            q[1] = 4;
            q[0] = (c >> 3) & 3;
        } else {
            q[1] = (c >> 3) & 3;
            q[0] = c & 7;
        }
    }

    // ---- unquantization ------------------------------------------------------

    private static int replicate(int v, int from, int to) {
        if (from == 0) {
            return 0;
        }
        int res = 0;
        int shift = to;
        while (shift > 0) {
            shift -= from;
            res |= shift >= 0 ? v << shift : v >> -shift;
        }
        return res & ((1 << to) - 1);
    }

    private static int unquantColor(int quant, int d, int m) {
        int trits = ISE[quant][0];
        int quints = ISE[quant][1];
        int nb = ISE[quant][2];
        if (trits == 0 && quints == 0) {
            return replicate(m, nb, 8);
        }
        int a = (m & 1) != 0 ? 0x1FF : 0;
        int b = bit(m, 1), c = bit(m, 2), dd = bit(m, 3), e = bit(m, 4), f = bit(m, 5);
        int bv = 0;
        int cv;
        if (trits != 0) {
            switch (nb) {
                case 1 -> cv = 204;
                case 2 -> { bv = (b << 8) | (b << 4) | (b << 2) | (b << 1); cv = 93; }
                case 3 -> { bv = (c << 8) | (b << 7) | (c << 3) | (b << 2) | (c << 1) | b; cv = 44; }
                case 4 -> { bv = (dd << 8) | (c << 7) | (b << 6) | (dd << 2) | (c << 1) | b; cv = 22; }
                case 5 -> { bv = (e << 8) | (dd << 7) | (c << 6) | (b << 5) | (e << 1) | dd; cv = 11; }
                default -> { bv = (f << 8) | (e << 7) | (dd << 6) | (c << 5) | (b << 4) | f; cv = 5; }
            }
        } else {
            switch (nb) {
                case 1 -> cv = 113;
                case 2 -> { bv = (b << 8) | (b << 3) | (b << 2); cv = 54; }
                case 3 -> { bv = (c << 8) | (b << 7) | (c << 2) | (b << 1) | c; cv = 26; }
                case 4 -> { bv = (dd << 8) | (c << 7) | (b << 6) | (dd << 1) | c; cv = 13; }
                default -> { bv = (e << 8) | (dd << 7) | (c << 6) | (b << 5) | e; cv = 6; }
            }
        }
        int t = d * cv + bv;
        t ^= a;
        return (a & 0x80) | (t >> 2);
    }

    private static int unquantWeight(int quant, int d, int m) {
        int trits = ISE[quant][0];
        int quints = ISE[quant][1];
        int nb = ISE[quant][2];
        int t;
        if (trits == 0 && quints == 0) {
            t = replicate(m, nb, 6);
        } else if (nb == 0) {
            return trits != 0 ? d * 32 : d * 16;
        } else {
            int a = (m & 1) != 0 ? 0x7F : 0;
            int b = bit(m, 1), c = bit(m, 2);
            int bv = 0;
            int cv;
            if (trits != 0) {
                switch (nb) {
                    case 1 -> cv = 50;
                    case 2 -> { bv = (b << 6) | (b << 2) | b; cv = 23; }
                    default -> { bv = (c << 6) | (b << 5) | (c << 1) | b; cv = 11; }
                }
            } else {
                switch (nb) {
                    case 1 -> cv = 28;
                    default -> { bv = (b << 6) | (b << 1); cv = 13; }
                }
            }
            t = d * cv + bv;
            t ^= a;
            t = (a & 0x20) | (t >> 2);
        }
        return t > 32 ? t + 1 : t;
    }

    // ---- color endpoint modes ------------------------------------------------

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    private static void set(int[] e, int r, int g, int b, int a) {
        e[0] = clamp(r);
        e[1] = clamp(g);
        e[2] = clamp(b);
        e[3] = clamp(a);
    }

    /** Blue-contraction applied when an RGB(A) pair was encoded "backwards". */
    private static void setBlueContract(int[] e, int r, int g, int b, int a) {
        set(e, (r + b) >> 1, (g + b) >> 1, b, a);
    }

    /** bit_transfer_signed: returns {offset, base}. */
    private static int[] bitTransferSigned(int a, int b) {
        b >>= 1;
        b |= a & 0x80;
        a >>= 1;
        a &= 0x3F;
        if ((a & 0x20) != 0) {
            a -= 0x40;
        }
        return new int[]{a, b};
    }

    private static boolean decodeEndpoints(int mode, int[] v, int o, int[] e0, int[] e1) {
        switch (mode) {
            case 0 -> {
                set(e0, v[o], v[o], v[o], 0xFF);
                set(e1, v[o + 1], v[o + 1], v[o + 1], 0xFF);
            }
            case 1 -> {
                int l0 = (v[o] >> 2) | (v[o + 1] & 0xC0);
                int l1 = Math.min(l0 + (v[o + 1] & 0x3F), 0xFF);
                set(e0, l0, l0, l0, 0xFF);
                set(e1, l1, l1, l1, 0xFF);
            }
            case 4 -> {
                set(e0, v[o], v[o], v[o], v[o + 2]);
                set(e1, v[o + 1], v[o + 1], v[o + 1], v[o + 3]);
            }
            case 5 -> {
                int[] l = bitTransferSigned(v[o + 1], v[o]);
                int[] al = bitTransferSigned(v[o + 3], v[o + 2]);
                set(e0, l[1], l[1], l[1], al[1]);
                int lum = l[1] + l[0];
                set(e1, lum, lum, lum, al[1] + al[0]);
            }
            case 6 -> {
                int s = v[o + 3];
                set(e0, (v[o] * s) >> 8, (v[o + 1] * s) >> 8, (v[o + 2] * s) >> 8, 0xFF);
                set(e1, v[o], v[o + 1], v[o + 2], 0xFF);
            }
            case 8, 12 -> {
                boolean alpha = mode == 12;
                int a0 = alpha ? v[o + 6] : 0xFF;
                int a1 = alpha ? v[o + 7] : 0xFF;
                int s0 = v[o] + v[o + 2] + v[o + 4];
                int s1 = v[o + 1] + v[o + 3] + v[o + 5];
                if (s1 >= s0) {
                    set(e0, v[o], v[o + 2], v[o + 4], a0);
                    set(e1, v[o + 1], v[o + 3], v[o + 5], a1);
                } else {
                    setBlueContract(e0, v[o + 1], v[o + 3], v[o + 5], a1);
                    setBlueContract(e1, v[o], v[o + 2], v[o + 4], a0);
                }
            }
            case 9, 13 -> {
                boolean alpha = mode == 13;
                int[] r = bitTransferSigned(v[o + 1], v[o]);
                int[] g = bitTransferSigned(v[o + 3], v[o + 2]);
                int[] b = bitTransferSigned(v[o + 5], v[o + 4]);
                int[] a = alpha ? bitTransferSigned(v[o + 7], v[o + 6]) : new int[]{0, 0xFF};
                if (r[0] + g[0] + b[0] >= 0) {
                    set(e0, r[1], g[1], b[1], a[1]);
                    set(e1, r[1] + r[0], g[1] + g[0], b[1] + b[0], a[1] + a[0]);
                } else {
                    setBlueContract(e0, r[1] + r[0], g[1] + g[0], b[1] + b[0], a[1] + a[0]);
                    setBlueContract(e1, r[1], g[1], b[1], a[1]);
                }
            }
            case 10 -> {
                int s = v[o + 3];
                set(e0, (v[o] * s) >> 8, (v[o + 1] * s) >> 8, (v[o + 2] * s) >> 8, v[o + 4]);
                set(e1, v[o], v[o + 1], v[o + 2], v[o + 5]);
            }
            default -> {
                return false; // HDR endpoint modes: not valid in an LDR texture
            }
        }
        return true;
    }

    // ---- partition hash (spec's select_partition) ------------------------------

    private static int hash52(int p) {
        p ^= p >>> 15;
        p *= 0xEEDE0891;
        p ^= p >>> 5;
        p += p << 16;
        p ^= p >>> 7;
        p ^= p >>> 3;
        p ^= p << 6;
        p ^= p >>> 17;
        return p;
    }

    static int selectPartition(int seed, int x, int y, int z, int partitionCount, boolean smallBlock) {
        if (smallBlock) {
            x <<= 1;
            y <<= 1;
            z <<= 1;
        }
        seed += (partitionCount - 1) * 1024;
        int rnum = hash52(seed);
        int seed1 = rnum & 0xF;
        int seed2 = (rnum >>> 4) & 0xF;
        int seed3 = (rnum >>> 8) & 0xF;
        int seed4 = (rnum >>> 12) & 0xF;
        int seed5 = (rnum >>> 16) & 0xF;
        int seed6 = (rnum >>> 20) & 0xF;
        int seed7 = (rnum >>> 24) & 0xF;
        int seed8 = (rnum >>> 28) & 0xF;
        int seed9 = (rnum >>> 18) & 0xF;
        int seed10 = (rnum >>> 22) & 0xF;
        int seed11 = (rnum >>> 26) & 0xF;
        int seed12 = ((rnum >>> 30) | (rnum << 2)) & 0xF;

        seed1 *= seed1; seed2 *= seed2; seed3 *= seed3; seed4 *= seed4;
        seed5 *= seed5; seed6 *= seed6; seed7 *= seed7; seed8 *= seed8;
        seed9 *= seed9; seed10 *= seed10; seed11 *= seed11; seed12 *= seed12;

        int sh1;
        int sh2;
        if ((seed & 1) != 0) {
            sh1 = (seed & 2) != 0 ? 4 : 5;
            sh2 = partitionCount == 3 ? 6 : 5;
        } else {
            sh1 = partitionCount == 3 ? 6 : 5;
            sh2 = (seed & 2) != 0 ? 4 : 5;
        }
        int sh3 = (seed & 0x10) != 0 ? sh1 : sh2;

        seed1 >>= sh1; seed2 >>= sh2; seed3 >>= sh1; seed4 >>= sh2;
        seed5 >>= sh1; seed6 >>= sh2; seed7 >>= sh1; seed8 >>= sh2;
        seed9 >>= sh3; seed10 >>= sh3; seed11 >>= sh3; seed12 >>= sh3;

        int a = seed1 * x + seed2 * y + seed11 * z + (rnum >>> 14);
        int b = seed3 * x + seed4 * y + seed12 * z + (rnum >>> 10);
        int c = seed5 * x + seed6 * y + seed9 * z + (rnum >>> 6);
        int d = seed7 * x + seed8 * y + seed10 * z + (rnum >>> 2);
        a &= 0x3F;
        b &= 0x3F;
        c &= 0x3F;
        d &= 0x3F;
        if (partitionCount < 4) d = 0;
        if (partitionCount < 3) c = 0;
        if (a >= b && a >= c && a >= d) return 0;
        if (b >= c && b >= d) return 1;
        if (c >= d) return 2;
        return 3;
    }

    // ---- helpers ---------------------------------------------------------------

    private static long u64(byte[] s, int o) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (s[o + i] & 0xFFL);
        }
        return v;
    }

    /** {@code count} (&lt;= 32) bits starting at {@code start} of the 128-bit block. */
    private static int bits(long lo, long hi, int start, int count) {
        if (count == 0) {
            return 0;
        }
        long v;
        if (start >= 64) {
            v = hi >>> (start - 64);
        } else if (start + count <= 64) {
            v = lo >>> start;
        } else {
            v = (lo >>> start) | (hi << (64 - start));
        }
        return (int) (v & ((1L << count) - 1));
    }

    private static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int halfToByte(int half) {
        float f = Float.float16ToFloat((short) half);
        return (int) Math.max(0, Math.min(255, Math.round(f * 255f)));
    }

    private static void fill(int[] dst, int dstOff, int pitch, int bw, int bh, int color) {
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                dst[dstOff + y * pitch + x] = color;
            }
        }
    }
}
