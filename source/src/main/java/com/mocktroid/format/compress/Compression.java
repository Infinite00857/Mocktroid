package com.mocktroid.format.compress;

import com.mocktroid.format.LE;
import com.mocktroid.format.MocktroidFormatException;

/**
 * Dispatches a PACK asset's stored bytes to the right decompressor based on
 * the 4-byte little-endian mode prefix every asset is stored with, ported
 * from retrotool's {@code decompress_buffer}/{@code decompress_into}.
 */
public final class Compression {
    private Compression() {}

    /** Decompresses one PACK asset's raw stored bytes (as sliced straight out
     *  of the pak file) to exactly {@code decompressedSize} bytes.
     *  <p>
     *  Matches retrotool's Package::read_full exactly: the 4-byte mode
     *  prefix only exists when {@code size != decompressedSize} at all --
     *  when they're equal the asset is stored fully raw (no prefix
     *  whatsoever), and treating it as mode-prefixed corrupts the first 4
     *  bytes of real data (this was an actual bug caught against a real
     *  pak: an uncompressed TXTR's leading "RFRM" magic got misread as a
     *  compression mode). */
    public static byte[] decompressAsset(byte[] compressedData, int offset, int length, long decompressedSize) {
        if (length == decompressedSize) {
            byte[] out = new byte[length];
            System.arraycopy(compressedData, offset, out, 0, length);
            return out;
        }
        if (length < 4) {
            throw new MocktroidFormatException("Compressed asset data too short: " + length + " bytes");
        }
        // Shortcut: a zero mode prefix with no further dispatch means "raw,
        // skip the 4-byte prefix" -- matches retrotool's early-out check.
        if (isZero(compressedData, offset, 4)) {
            byte[] out = new byte[length - 4];
            System.arraycopy(compressedData, offset + 4, out, 0, out.length);
            return out;
        }
        byte[] out = new byte[Math.toIntExact(decompressedSize)];
        decompressInto(compressedData, offset, length, out);
        return out;
    }

    /** Decompresses a GPU sub-buffer (model vertex/index data), which --
     *  unlike a whole pak entry -- always carries the 4-byte mode prefix,
     *  even when its compressed size happens to equal {@code decompressedSize}
     *  (matches retrotool's decompress_buffer). Assuming "equal sizes = raw"
     *  here corrupted 22 real models' index buffers. */
    public static byte[] decompressBuffer(byte[] data, int offset, int length, long decompressedSize) {
        if (length < 4) {
            throw new MocktroidFormatException("Compressed buffer too short: " + length + " bytes");
        }
        if (isZero(data, offset, 4)) {
            byte[] out = new byte[length - 4];
            System.arraycopy(data, offset + 4, out, 0, out.length);
            return out;
        }
        byte[] out = new byte[Math.toIntExact(decompressedSize)];
        decompressInto(data, offset, length, out);
        return out;
    }

    private static void decompressInto(byte[] compressedData, int offset, int length, byte[] out) {
        if (length < 4) {
            throw new MocktroidFormatException("Compressed asset data too short: " + length + " bytes");
        }
        long mode = LE.u32(compressedData, offset);
        int dataOffset = offset + 4;
        int dataLen = length - 4;

        boolean ok = switch ((int) mode) {
            case 0 -> {
                if (dataLen == out.length) {
                    System.arraycopy(compressedData, dataOffset, out, 0, dataLen);
                    yield true;
                }
                yield false;
            }
            case 1 -> Lzss.decompress(0, compressedData, dataOffset, out);
            case 2 -> Lzss.decompress(1, compressedData, dataOffset, out);
            case 3 -> Lzss.decompress(2, compressedData, dataOffset, out);
            case 12 -> Lzss.decompressHuffman(0, compressedData, dataOffset, out);
            case 13 -> Lzss.decompressHuffman(1, compressedData, dataOffset, out);
            case 14 -> Lzss.decompressHuffman(2, compressedData, dataOffset, out);
            case 15 -> inflate(compressedData, dataOffset, dataLen, out); // zlib, per mp4x's FORMATS.md
            default -> throw new MocktroidFormatException("Unsupported compression mode " + mode);
        };
        if (!ok) {
            throw new MocktroidFormatException("Decompression failed (mode " + mode + ")");
        }
    }

    private static boolean inflate(byte[] data, int offset, int length, byte[] out) {
        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        try {
            inflater.setInput(data, offset, length);
            int n = 0;
            while (n < out.length && !inflater.finished()) {
                int got = inflater.inflate(out, n, out.length - n);
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                n += got;
            }
            return n == out.length;
        } catch (java.util.zip.DataFormatException e) {
            return false;
        } finally {
            inflater.end();
        }
    }

    private static boolean isZero(byte[] data, int offset, int count) {
        for (int i = 0; i < count; i++) {
            if (data[offset + i] != 0) {
                return false;
            }
        }
        return true;
    }
}
