package com.mocktroid.format.texture;

import com.mocktroid.format.LE;

/**
 * TXTR's HEAD chunk (MP4 layout, reader/writer version 65/66 -- matches
 * retrotool's mp4-branch {@code STextureHeader}):
 * <pre>
 *   kind        u32        see {@link Kind}
 *   format      u32        {@link TextureFormat} id
 *   width       u32
 *   height      u32
 *   layers      u32        array layers / cube faces / 3D depth
 *   components  u8[4]      channel swizzle (unused by the viewer)
 *   mipCount    u32
 *   mipSizes    u32[mipCount]
 *   sampler     u32 unk, u8 filter, mipFilter, wrapX, wrapY, wrapZ, aniso
 * </pre>
 */
public record TextureHeader(Kind kind, TextureFormat format, int width, int height, int layers,
                            int mipCount, long[] mipSizes, int filter, int wrapX, int wrapY) {

    public enum Kind {
        D1("1D"), D2("2D"), D3("3D"), CUBE("Cube"), D1_ARRAY("1D Array"), D2_ARRAY("2D Array"),
        D2_MULTISAMPLE("2D MS"), D2_MULTISAMPLE_ARRAY("2D MS Array"), CUBE_ARRAY("Cube Array");

        public final String label;
        Kind(String label) { this.label = label; }
    }

    public static TextureHeader read(byte[] d, int o) {
        Kind kind = Kind.values()[(int) LE.u32(d, o)];
        TextureFormat format = TextureFormat.fromId(LE.u32(d, o + 4));
        int width = (int) LE.u32(d, o + 8);
        int height = (int) LE.u32(d, o + 12);
        int layers = (int) LE.u32(d, o + 16);
        int mipCount = (int) LE.u32(d, o + 24);
        long[] mipSizes = new long[mipCount];
        for (int i = 0; i < mipCount; i++) {
            mipSizes[i] = LE.u32(d, o + 28 + i * 4);
        }
        int s = o + 28 + mipCount * 4 + 4;
        return new TextureHeader(kind, format, width, height, Math.max(1, layers), Math.max(1, mipCount),
            mipSizes, d[s] & 0xFF, d[s + 2] & 0xFF, d[s + 3] & 0xFF);
    }

    public boolean is3d() {
        return kind == Kind.D3;
    }

    /** Depth in slices (only &gt;1 for 3D textures). */
    public int depth() {
        return is3d() ? layers : 1;
    }

    /** Number of independently viewable layers (array layers / cube faces); 1 for 3D. */
    public int layerCount() {
        return is3d() ? 1 : layers;
    }

    public int mipWidth(int mip) {
        return Math.max(1, width >> mip);
    }

    public int mipHeight(int mip) {
        return Math.max(1, height >> mip);
    }

    public int mipDepth(int mip) {
        return Math.max(1, depth() >> mip);
    }
}
