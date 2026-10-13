package com.mocktroid.format.texture;

import com.mocktroid.format.ChunkDescriptor;
import com.mocktroid.format.FormDescriptor;
import com.mocktroid.format.FourCC;
import com.mocktroid.format.LE;
import com.mocktroid.format.MocktroidFormatException;
import com.mocktroid.format.compress.Compression;

/**
 * Parses an MP4 TXTR asset into deswizzled, still block-compressed surface
 * data. Layout confirmed against the real romfs (every TXTR is form version
 * 65/66 and fully self-contained -- unlike Prime Remastered, no GPU data
 * lives outside the asset):
 * <pre>
 *   RFRM("TXTR")
 *     HEAD chunk   {@link TextureHeader}
 *     GPU  chunk   swizzled texel data, usually LZSS-compressed in one or
 *                  two pieces
 * </pre>
 * How the GPU bytes map onto the final swizzled buffer is described by the
 * asset's pak META blob (retrotool's {@code STextureMetaData}, mp4 branch):
 * <pre>
 *   u32 unk1, unk2, allocCategory, gpuOffset, align, decompressedSize
 *   u32 infoCount; {u8 index, u32 offset, u32 size}[infoCount]  -- byte ranges
 *                                                    of the asset itself
 *   u32 firstIndex, firstSize, firstDestOffset, firstDestSize, unk,
 *       secondIndex, secondSize, secondDestOffset, secondDestSize,
 *       secondDecompressedLen, secondCompressedLen, secondCompressedOffset
 * </pre>
 * The buffer assembly below mirrors retrotool's {@code TextureData::slice}
 * step for step. If a META blob is missing, falls back to treating the whole
 * GPU chunk body as one buffer.
 */
public final class TxtrReader {

    private static final FourCC TXTR = new FourCC("TXTR");
    private static final FourCC HEAD = new FourCC("HEAD");
    private static final FourCC GPU = new FourCC("GPU ");

    private TxtrReader() {}

    /** Parses just the header, without touching texel data -- cheap. */
    public static TextureHeader readHeader(byte[] asset) {
        FormDescriptor form = FormDescriptor.read(asset, 0);
        if (!form.id().equals(TXTR)) {
            throw new MocktroidFormatException("Expected TXTR form, found \"" + form.id() + "\"");
        }
        ChunkDescriptor head = ChunkDescriptor.read(asset, FormDescriptor.HEADER_SIZE);
        if (!head.id().equals(HEAD)) {
            throw new MocktroidFormatException("Expected HEAD chunk, found \"" + head.id() + "\"");
        }
        return TextureHeader.read(asset, head.bodyStart());
    }

    public static Texture read(byte[] asset, byte[] meta) {
        TextureHeader header = readHeader(asset);
        TextureFormat format = header.format();
        if (format.blockBytes == 0) {
            throw new MocktroidFormatException("Texture has no pixel format");
        }
        long expected = TegraSwizzle.surfaceSize(header.width(), header.height(), header.depth(), header.is3d(),
            format.blockWidth, format.blockHeight, format.blockBytes, header.mipCount(), header.layerCount());

        byte[] swizzled = meta != null ? assembleFromMeta(asset, meta) : assembleFromGpuChunk(asset, expected);
        if (swizzled.length < expected) {
            throw new MocktroidFormatException("GPU buffer is " + swizzled.length
                + " bytes, layout needs " + expected);
        }
        byte[][][] surfaces = TegraSwizzle.deswizzleSurface(swizzled, header.width(), header.height(),
            header.depth(), header.is3d(), format.blockWidth, format.blockHeight, format.blockBytes,
            header.mipCount(), header.layerCount());
        return new Texture(header, surfaces);
    }

    private static byte[] assembleFromMeta(byte[] asset, byte[] meta) {
        int decompressedSize = Math.toIntExact(LE.u32(meta, 20));
        int infoCount = Math.toIntExact(LE.u32(meta, 24));
        int[] infoOffset = new int[infoCount];
        int[] infoSize = new int[infoCount];
        int o = 28;
        for (int i = 0; i < infoCount; i++, o += 9) {
            infoOffset[i] = Math.toIntExact(LE.u32(meta, o + 1));
            infoSize[i] = Math.toIntExact(LE.u32(meta, o + 5));
        }
        int firstIndex = u32(meta, o);
        int firstSize = u32(meta, o + 4);
        int firstDestOffset = u32(meta, o + 8);
        int firstDestSize = u32(meta, o + 12);
        int secondIndex = u32(meta, o + 20);
        int secondSize = u32(meta, o + 24);
        int secondDestOffset = u32(meta, o + 28);
        int secondDestSize = u32(meta, o + 32);
        int secondDecompressedLen = u32(meta, o + 36);
        int secondCompressedLen = u32(meta, o + 40);
        int secondCompressedOffset = u32(meta, o + 44);

        byte[] buffer = new byte[decompressedSize];

        if (firstSize > 0) {
            int read = infoOffset[firstIndex];
            try {
                decompressInto(asset, read, firstSize, buffer, firstDestOffset, firstDestSize);
            } catch (MocktroidFormatException e) {
                // Two 4x4 placeholder textures in the romfs store this buffer
                // (only their padding mips) with compression mode 6, which no
                // reference implementation decodes yet. Leaving it zeroed still
                // shows mip 0, which lives in the second buffer.
                if (secondSize == 0) {
                    throw e;
                }
            }
        }

        if (secondSize > 0) {
            int read = infoOffset[secondIndex];
            // Compressed middle part of the second buffer...
            if (secondCompressedOffset < secondSize) {
                decompressInto(asset, read + secondCompressedOffset, secondCompressedLen,
                    buffer, secondDestOffset, secondDecompressedLen);
            }
            // ...stored raw bytes before it land *after* the decompressed part...
            if (secondCompressedOffset > 0) {
                System.arraycopy(asset, read, buffer, secondDestOffset + secondDecompressedLen,
                    secondCompressedOffset);
            }
            // ...and stored raw bytes after it fill the rest of the destination.
            int tailStart = secondCompressedOffset + secondCompressedLen;
            if (tailStart < secondSize) {
                int dst = secondDestOffset + secondCompressedOffset + secondDecompressedLen;
                int len = secondDestOffset + secondDestSize - dst;
                System.arraycopy(asset, read + tailStart, buffer, dst, len);
            }
        }
        return buffer;
    }

    private static byte[] assembleFromGpuChunk(byte[] asset, long expected) {
        FormDescriptor form = FormDescriptor.read(asset, 0);
        int pos = FormDescriptor.HEADER_SIZE;
        while (pos < form.nextOffset()) {
            ChunkDescriptor chunk = ChunkDescriptor.read(asset, pos);
            if (chunk.id().equals(GPU)) {
                return Compression.decompressAsset(asset, chunk.bodyStart(), chunk.bodyLen(), expected);
            }
            pos = chunk.nextOffset();
        }
        throw new MocktroidFormatException("TXTR has no GPU chunk");
    }

    /** Same rule as retrotool's decompress_into: equal sizes mean stored raw. */
    private static void decompressInto(byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff, int dstLen) {
        if (srcLen == dstLen) {
            System.arraycopy(src, srcOff, dst, dstOff, dstLen);
            return;
        }
        byte[] out = Compression.decompressAsset(src, srcOff, srcLen, dstLen);
        System.arraycopy(out, 0, dst, dstOff, Math.min(out.length, dstLen));
    }

    private static int u32(byte[] d, int o) {
        return Math.toIntExact(LE.u32(d, o));
    }
}
