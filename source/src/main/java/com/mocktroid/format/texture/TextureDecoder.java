package com.mocktroid.format.texture;

import com.mocktroid.format.MocktroidFormatException;
import com.mocktroid.format.texture.decode.AstcDecoder;
import com.mocktroid.format.texture.decode.BcnDecoder;

/**
 * Decodes one (layer, mip, depth-slice) of a {@link Texture} to packed,
 * non-premultiplied ARGB pixels ready for a JavaFX {@code WritableImage}.
 * <p>
 * MP4 stores texel rows bottom-up (OpenGL's origin convention -- obvious on
 * any UI art or font atlas, while right-pointing play triangles stay
 * right-pointing, so it's a vertical flip rather than a rotation). Decoded
 * images are flipped back so row 0 is the top.
 * <p>
 * HDR formats (BC6H, half/float, RG11B10) are linear light with values
 * above 1.0; they're shown with a simple exposure scale, clamp and the sRGB
 * transfer curve -- good enough to see what an environment map contains,
 * not a faithful HDR tonemap.
 */
public final class TextureDecoder {

    private TextureDecoder() {}

    /** Decoded pixels; {@code argb.length == width * height}. */
    public record Image(int width, int height, int[] argb) {}

    public static Image decode(Texture texture, int layer, int mip, int slice) {
        return decode(texture, layer, mip, slice, 1.0f);
    }

    public static Image decode(Texture texture, int layer, int mip, int slice, float hdrExposure) {
        TextureHeader h = texture.header();
        TextureFormat f = h.format();
        int width = h.mipWidth(mip);
        int height = h.mipHeight(mip);
        int depth = h.mipDepth(mip);
        if (slice < 0 || slice >= depth) {
            throw new IllegalArgumentException("Slice " + slice + " out of range (depth " + depth + ")");
        }
        byte[] data = texture.surface(layer, mip);
        int bw = f.blockWidth;
        int bh = f.blockHeight;
        int blocksX = (width + bw - 1) / bw;
        int blocksY = (height + bh - 1) / bh;
        int sliceBytes = blocksX * blocksY * f.blockBytes;
        int base = slice * sliceBytes;

        if (f.isBlockCompressed()) {
            int paddedW = blocksX * bw;
            int paddedH = blocksY * bh;
            int[] padded = new int[paddedW * paddedH];
            if (f == TextureFormat.BC6H_UFLOAT || f == TextureFormat.BC6H_SFLOAT) {
                float[] rgb = new float[paddedW * paddedH * 3];
                boolean signed = f == TextureFormat.BC6H_SFLOAT;
                for (int by = 0; by < blocksY; by++) {
                    for (int bx = 0; bx < blocksX; bx++) {
                        int src = base + (by * blocksX + bx) * 16;
                        BcnDecoder.bc6h(data, src, rgb, (by * 4 * paddedW + bx * 4) * 3, paddedW * 3, signed);
                    }
                }
                for (int i = 0; i < padded.length; i++) {
                    padded[i] = hdrToArgb(rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2], 1f, hdrExposure);
                }
            } else {
                boolean srgb = f.isSrgb();
                for (int by = 0; by < blocksY; by++) {
                    for (int bx = 0; bx < blocksX; bx++) {
                        int src = base + (by * blocksX + bx) * f.blockBytes;
                        int dst = by * bh * paddedW + bx * bw;
                        decodeLdrBlock(f, data, src, padded, dst, paddedW, srgb);
                    }
                }
            }
            return new Image(width, height, flipRows(crop(padded, paddedW, width, height), width, height));
        }

        int[] out = new int[width * height];
        int bpp = f.blockBytes;
        for (int i = 0; i < out.length; i++) {
            out[i] = decodeUncompressed(f, data, base + i * bpp, hdrExposure);
        }
        return new Image(width, height, flipRows(out, width, height));
    }

    private static void decodeLdrBlock(TextureFormat f, byte[] data, int src, int[] dst, int dstOff,
                                       int pitch, boolean srgb) {
        switch (f) {
            case BC1_UNORM, BC1_SRGB -> BcnDecoder.bc1(data, src, dst, dstOff, pitch);
            case BC2_UNORM, BC2_SRGB -> BcnDecoder.bc2(data, src, dst, dstOff, pitch);
            case BC3_UNORM, BC3_SRGB -> BcnDecoder.bc3(data, src, dst, dstOff, pitch);
            case BC4_UNORM, BC4_SNORM -> BcnDecoder.bc4(data, src, dst, dstOff, pitch);
            case BC5_UNORM, BC5_SNORM -> BcnDecoder.bc5(data, src, dst, dstOff, pitch);
            case BC7_UNORM, BC7_SRGB -> BcnDecoder.bc7(data, src, dst, dstOff, pitch);
            default -> {
                if (!f.isAstc()) {
                    throw new MocktroidFormatException("Unsupported texture format " + f);
                }
                AstcDecoder.decodeBlock(data, src, f.blockWidth, f.blockHeight, srgb, dst, dstOff, pitch);
            }
        }
    }

    private static int decodeUncompressed(TextureFormat f, byte[] d, int o, float exposure) {
        return switch (f) {
            case R8_UNORM, R8_UINT -> gray(d[o] & 0xFF);
            case R8_SNORM, R8_SINT -> gray(d[o] + 128);
            case RG8_UNORM, RG8_UINT -> argb(0xFF, d[o] & 0xFF, d[o + 1] & 0xFF, 0);
            case RG8_SNORM, RG8_SINT -> argb(0xFF, d[o] + 128, d[o + 1] + 128, 0);
            case RGB8_UNORM -> argb(0xFF, d[o] & 0xFF, d[o + 1] & 0xFF, d[o + 2] & 0xFF);
            case RGBA8_UNORM, RGBA8_SRGB -> argb(d[o + 3] & 0xFF, d[o] & 0xFF, d[o + 1] & 0xFF, d[o + 2] & 0xFF);
            case R16_UNORM, R16_UINT, D16_UNORM, D16_UNORM_2 -> gray(u16(d, o) >> 8);
            case R16_FLOAT -> {
                float v = half(d, o);
                yield hdrToArgb(v, v, v, 1f, exposure);
            }
            case RG16_FLOAT -> hdrToArgb(half(d, o), half(d, o + 2), 0f, 1f, exposure);
            case RGBA16_FLOAT -> hdrToArgb(half(d, o), half(d, o + 2), half(d, o + 4), half(d, o + 6), exposure);
            case R32_FLOAT, D32_FLOAT -> {
                float v = Float.intBitsToFloat(i32(d, o));
                yield hdrToArgb(v, v, v, 1f, exposure);
            }
            case RGBA32_FLOAT -> hdrToArgb(Float.intBitsToFloat(i32(d, o)), Float.intBitsToFloat(i32(d, o + 4)),
                Float.intBitsToFloat(i32(d, o + 8)), Float.intBitsToFloat(i32(d, o + 12)), exposure);
            case RGBA16_UNORM -> argb(d[o + 7] & 0xFF, d[o + 1] & 0xFF, d[o + 3] & 0xFF, d[o + 5] & 0xFF);
            case RGB10A2_UNORM -> {
                int v = i32(d, o);
                yield argb(((v >>> 30) & 3) * 85, ((v) & 0x3FF) >> 2, ((v >>> 10) & 0x3FF) >> 2, ((v >>> 20) & 0x3FF) >> 2);
            }
            case RG11B10_FLOAT -> {
                int v = i32(d, o);
                yield hdrToArgb(small(v & 0x7FF, 6), small((v >>> 11) & 0x7FF, 6), small((v >>> 22) & 0x3FF, 5),
                    1f, exposure);
            }
            default -> throw new MocktroidFormatException("Unsupported texture format " + f);
        };
    }

    /** Unsigned 11/10-bit float (5-bit exponent, no sign) -> float. */
    private static float small(int bits, int mantissaBits) {
        int exp = bits >>> mantissaBits;
        int mant = bits & ((1 << mantissaBits) - 1);
        int half = (exp << 10) | (mant << (10 - mantissaBits));
        return Float.float16ToFloat((short) half);
    }

    private static int[] flipRows(int[] px, int width, int height) {
        int[] row = new int[width];
        for (int top = 0, bottom = height - 1; top < bottom; top++, bottom--) {
            System.arraycopy(px, top * width, row, 0, width);
            System.arraycopy(px, bottom * width, px, top * width, width);
            System.arraycopy(row, 0, px, bottom * width, width);
        }
        return px;
    }

    private static int[] crop(int[] padded, int paddedW, int width, int height) {
        if (paddedW == width && padded.length == width * height) {
            return padded;
        }
        int[] out = new int[width * height];
        for (int y = 0; y < height; y++) {
            System.arraycopy(padded, y * paddedW, out, y * width, width);
        }
        return out;
    }

    private static int hdrToArgb(float r, float g, float b, float a, float exposure) {
        return argb(unit(a), toSrgb(r * exposure), toSrgb(g * exposure), toSrgb(b * exposure));
    }

    private static int toSrgb(float linear) {
        if (!(linear > 0f)) {
            return 0;
        }
        if (linear >= 1f) {
            return 255;
        }
        double s = linear <= 0.0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
        return (int) Math.round(s * 255);
    }

    private static int unit(float v) {
        return (int) Math.round(Math.max(0f, Math.min(1f, v)) * 255);
    }

    private static int gray(int v) {
        return argb(0xFF, v, v, v);
    }

    private static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int u16(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    private static int i32(byte[] d, int o) {
        return u16(d, o) | (u16(d, o + 2) << 16);
    }

    private static float half(byte[] d, int o) {
        return Float.float16ToFloat((short) u16(d, o));
    }
}
