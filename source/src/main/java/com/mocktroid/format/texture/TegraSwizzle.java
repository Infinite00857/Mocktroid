package com.mocktroid.format.texture;

/**
 * Undoes the Tegra X1 "block linear" GPU memory layout every Switch texture
 * is stored in. Written from the hardware layout itself rather than ported
 * from any one implementation:
 * <ul>
 *   <li>A GOB ("group of bytes") is 64 bytes wide x 8 rows = 512 bytes,
 *       internally interleaved in 16-byte x 2-row sectors (see
 *       {@link #gobOffset}).</li>
 *   <li>A block is 1 GOB wide, {@code blockHeight} GOBs tall and
 *       {@code blockDepth} GOBs deep, stored contiguously (GOB rows first,
 *       then depth). Blocks tile the surface left-to-right, then
 *       top-to-bottom, then front-to-back.</li>
 *   <li>Block height for mip 0 is chosen from the texture height; smaller
 *       mips shrink it so a tiny mip isn't padded out to a huge block. 3D
 *       textures instead always use a block height of 1 GOB (even at depth
 *       1) and spend the block on depth -- confirmed against every volume
 *       texture in the romfs.</li>
 *   <li>A full surface is layer-major: every mip of layer 0, then every mip
 *       of layer 1, ...; with more than one layer, each layer's total is
 *       padded to a whole mip-0 block.</li>
 * </ul>
 * All "pixel" units here are format elements: single pixels for
 * uncompressed formats, 4x4 (etc.) blocks for BCn/ASTC. Verified against the
 * size the game itself records for every TXTR in the romfs.
 */
public final class TegraSwizzle {

    private static final int GOB_WIDTH = 64;
    private static final int GOB_HEIGHT = 8;
    private static final int GOB_SIZE = GOB_WIDTH * GOB_HEIGHT;

    private TegraSwizzle() {}

    /** Mip-0 block height in GOBs (1-16) for a surface this many elements tall. */
    public static int blockHeightMip0(int heightInElements) {
        int heightAndHalf = heightInElements + heightInElements / 2;
        if (heightAndHalf >= 128) return 16;
        if (heightAndHalf >= 64) return 8;
        if (heightAndHalf >= 32) return 4;
        if (heightAndHalf >= 16) return 2;
        return 1;
    }

    /** Mip-0 block depth in GOBs (1-16) for a 3D surface this many slices deep. */
    public static int blockDepthMip0(int depth) {
        int depthAndHalf = depth + depth / 2;
        if (depthAndHalf >= 16) return 16;
        if (depthAndHalf >= 8) return 8;
        if (depthAndHalf >= 4) return 4;
        if (depthAndHalf >= 2) return 2;
        return 1;
    }

    static int mipBlockHeight(int mipHeightInElements, int blockHeightMip0) {
        int bh = blockHeightMip0;
        while (mipHeightInElements <= (bh / 2) * GOB_HEIGHT && bh > 1) {
            bh /= 2;
        }
        return bh;
    }

    static int mipBlockDepth(int mipDepth, int blockDepthMip0) {
        int bd = blockDepthMip0;
        while (mipDepth <= bd / 2 && bd > 1) {
            bd /= 2;
        }
        return bd;
    }

    /** Byte offset of (x byte, y row) inside one 512-byte GOB. */
    private static int gobOffset(int x, int y) {
        return ((x & 63) >> 5) * 256 + ((y & 7) >> 1) * 64 + ((x & 31) >> 4) * 32 + (y & 1) * 16 + (x & 15);
    }

    private static long mipSize(int wElems, int hElems, int depth, int bh, int bd, int bpe) {
        long widthInGobs = ceilDiv((long) wElems * bpe, GOB_WIDTH);
        long blocksTall = ceilDiv(hElems, GOB_HEIGHT * bh);
        long blocksDeep = ceilDiv(depth, bd);
        return widthInGobs * blocksTall * blocksDeep * GOB_SIZE * bh * bd;
    }

    /** Linear (deswizzled) element dimensions of one mip level. */
    public static int mipDim(int base, int mip, int blockDim) {
        return Math.max(1, ceilDiv(Math.max(1, base >> mip), blockDim));
    }

    /** Total swizzled byte size the given surface occupies. */
    public static long surfaceSize(int width, int height, int depth, boolean volume, int blockW, int blockH,
                                   int bytesPerElement, int mipCount, int layerCount) {
        int hElems0 = mipDim(height, 0, blockH);
        int bh0 = volume ? 1 : blockHeightMip0(hElems0);
        int bd0 = blockDepthMip0(depth);
        long size = 0;
        for (int layer = 0; layer < layerCount; layer++) {
            for (int mip = 0; mip < mipCount; mip++) {
                int w = mipDim(width, mip, blockW);
                int h = mipDim(height, mip, blockH);
                int d = Math.max(1, depth >> mip);
                size += mipSize(w, h, d, mipBlockHeight(h, bh0), mipBlockDepth(d, bd0), bytesPerElement);
            }
            if (layerCount > 1) {
                size = alignLayer(size, hElems0, bh0);
            }
        }
        return size;
    }

    private static long alignLayer(long size, int hElems0, int bh0) {
        long align = (long) GOB_SIZE * mipBlockHeight(hElems0, bh0);
        return ceilDiv(size, align) * align;
    }

    /**
     * Deswizzles a whole surface. Returns {@code [layer][mip]} tightly packed
     * linear element data (row-major; for 3D, depth slices back-to-back).
     */
    public static byte[][][] deswizzleSurface(byte[] src, int width, int height, int depth, boolean volume,
                                              int blockW, int blockH, int bytesPerElement,
                                              int mipCount, int layerCount) {
        int hElems0 = mipDim(height, 0, blockH);
        int bh0 = volume ? 1 : blockHeightMip0(hElems0);
        int bd0 = blockDepthMip0(depth);
        byte[][][] out = new byte[layerCount][mipCount][];
        long offset = 0;
        for (int layer = 0; layer < layerCount; layer++) {
            for (int mip = 0; mip < mipCount; mip++) {
                int w = mipDim(width, mip, blockW);
                int h = mipDim(height, mip, blockH);
                int d = Math.max(1, depth >> mip);
                int bh = mipBlockHeight(h, bh0);
                int bd = mipBlockDepth(d, bd0);
                byte[] linear = new byte[Math.toIntExact((long) w * h * d * bytesPerElement)];
                deswizzleMip(src, offset, linear, w, h, d, bh, bd, bytesPerElement);
                out[layer][mip] = linear;
                offset += mipSize(w, h, d, bh, bd, bytesPerElement);
            }
            if (layerCount > 1) {
                offset = alignLayer(offset, hElems0, bh0);
            }
        }
        return out;
    }

    private static void deswizzleMip(byte[] src, long base, byte[] dst, int w, int h, int d,
                                     int bh, int bd, int bpe) {
        int widthInGobs = ceilDiv(w * bpe, GOB_WIDTH);
        long blockSize = (long) GOB_SIZE * bh * bd;
        long slabSize = widthInGobs * ceilDiv(h, GOB_HEIGHT * bh) * blockSize;
        int rowBytes = w * bpe;
        // Elements of power-of-two size never straddle a 16-byte sector, so
        // they can be copied whole; odd sizes (RGB8) fall back to per-byte.
        boolean wholeElements = Integer.bitCount(bpe) == 1;
        int dstPos = 0;
        for (int z = 0; z < d; z++) {
            long offZ = base + (z / bd) * slabSize + (long) (z % bd) * GOB_SIZE * bh;
            for (int y = 0; y < h; y++) {
                long offY = offZ + (long) (y / (GOB_HEIGHT * bh)) * widthInGobs * blockSize
                    + (long) ((y % (GOB_HEIGHT * bh)) / GOB_HEIGHT) * GOB_SIZE;
                if (wholeElements) {
                    for (int xb = 0; xb < rowBytes; xb += bpe) {
                        long s = offY + (xb / GOB_WIDTH) * blockSize + gobOffset(xb, y);
                        System.arraycopy(src, (int) s, dst, dstPos, bpe);
                        dstPos += bpe;
                    }
                } else {
                    for (int xb = 0; xb < rowBytes; xb++) {
                        long s = offY + (xb / GOB_WIDTH) * blockSize + gobOffset(xb, y);
                        dst[dstPos++] = src[(int) s];
                    }
                }
            }
        }
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }
}
