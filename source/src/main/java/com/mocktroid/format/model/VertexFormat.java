package com.mocktroid.format.model;

import com.mocktroid.format.MocktroidFormatException;

/**
 * Vertex attribute storage formats, numbered as retrotool's
 * {@code EVertexDataFormat}. {@link #read} decodes one attribute to floats
 * (normalized formats to [0,1] / [-1,1], integer formats as plain values).
 */
public enum VertexFormat {
    R8_UNORM(1, 1), R8_UINT(1, 1), R8_SNORM(1, 1), R8_SINT(1, 1),
    R16_UNORM(2, 1), R16_UINT(2, 1), R16_SNORM(2, 1), R16_SINT(2, 1), R16_FLOAT(2, 1),
    RG8_UNORM(2, 2), RG8_UINT(2, 2), RG8_SNORM(2, 2), RG8_SINT(2, 2),
    R32_UINT(4, 1), R32_SINT(4, 1), R32_FLOAT(4, 1),
    RG16_UNORM(4, 2), RG16_UINT(4, 2), RG16_SNORM(4, 2), RG16_SINT(4, 2), RG16_FLOAT(4, 2),
    RGBA8_UNORM(4, 4), RGBA8_UINT(4, 4), RGBA8_SNORM(4, 4), RGBA8_SINT(4, 4),
    RGB10A2_UNORM(4, 4), RGB10A2_UINT(4, 4),
    RG32_UINT(8, 2), RG32_SINT(8, 2), RG32_FLOAT(8, 2),
    RGBA16_UNORM(8, 4), RGBA16_UINT(8, 4), RGBA16_SNORM(8, 4), RGBA16_SINT(8, 4), RGBA16_FLOAT(8, 4),
    RGB32_UINT(12, 3), RGB32_SINT(12, 3), RGB32_FLOAT(12, 3),
    RGBA32_UINT(16, 4), RGBA32_SINT(16, 4), RGBA32_FLOAT(16, 4);

    public final int byteSize;
    public final int components;

    VertexFormat(int byteSize, int components) {
        this.byteSize = byteSize;
        this.components = components;
    }

    public static VertexFormat fromId(long id) {
        if (id < 0 || id >= values().length) {
            throw new MocktroidFormatException("Unknown vertex format " + id);
        }
        return values()[(int) id];
    }

    /** Decodes the attribute at {@code o} into {@code out[0..components)}. */
    public void read(byte[] d, int o, float[] out) {
        switch (this) {
            case R8_UNORM, RG8_UNORM, RGBA8_UNORM -> { for (int i = 0; i < components; i++) out[i] = (d[o + i] & 0xFF) / 255f; }
            case R8_UINT, RG8_UINT, RGBA8_UINT -> { for (int i = 0; i < components; i++) out[i] = d[o + i] & 0xFF; }
            case R8_SNORM, RG8_SNORM, RGBA8_SNORM -> { for (int i = 0; i < components; i++) out[i] = Math.max(-1f, d[o + i] / 127f); }
            case R8_SINT, RG8_SINT, RGBA8_SINT -> { for (int i = 0; i < components; i++) out[i] = d[o + i]; }
            case R16_UNORM, RG16_UNORM, RGBA16_UNORM -> { for (int i = 0; i < components; i++) out[i] = u16(d, o + i * 2) / 65535f; }
            case R16_UINT, RG16_UINT, RGBA16_UINT -> { for (int i = 0; i < components; i++) out[i] = u16(d, o + i * 2); }
            case R16_SNORM, RG16_SNORM, RGBA16_SNORM -> { for (int i = 0; i < components; i++) out[i] = Math.max(-1f, (short) u16(d, o + i * 2) / 32767f); }
            case R16_SINT, RG16_SINT, RGBA16_SINT -> { for (int i = 0; i < components; i++) out[i] = (short) u16(d, o + i * 2); }
            case R16_FLOAT, RG16_FLOAT, RGBA16_FLOAT -> { for (int i = 0; i < components; i++) out[i] = Float.float16ToFloat((short) u16(d, o + i * 2)); }
            case R32_FLOAT, RG32_FLOAT, RGB32_FLOAT, RGBA32_FLOAT -> { for (int i = 0; i < components; i++) out[i] = Float.intBitsToFloat(i32(d, o + i * 4)); }
            case R32_UINT, RG32_UINT, RGB32_UINT, RGBA32_UINT -> { for (int i = 0; i < components; i++) out[i] = i32(d, o + i * 4) & 0xFFFFFFFFL; }
            case R32_SINT, RG32_SINT, RGB32_SINT, RGBA32_SINT -> { for (int i = 0; i < components; i++) out[i] = i32(d, o + i * 4); }
            case RGB10A2_UNORM, RGB10A2_UINT -> {
                int v = i32(d, o);
                float scale = this == RGB10A2_UNORM ? 1f / 1023f : 1f;
                out[0] = (v & 0x3FF) * scale;
                out[1] = ((v >>> 10) & 0x3FF) * scale;
                out[2] = ((v >>> 20) & 0x3FF) * scale;
                out[3] = ((v >>> 30) & 3) * (this == RGB10A2_UNORM ? 1f / 3f : 1f);
            }
        }
    }

    private static int u16(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    private static int i32(byte[] d, int o) {
        return u16(d, o) | (u16(d, o + 2) << 16);
    }
}
