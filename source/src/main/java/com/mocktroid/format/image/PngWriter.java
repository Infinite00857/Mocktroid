package com.mocktroid.format.image;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Minimal 8-bit RGBA PNG encoder (IHDR + one zlib IDAT + IEND, no row
 * filtering). Exists so exporting a texture doesn't drag in javafx-swing
 * just for {@code ImageIO}.
 */
public final class PngWriter {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private PngWriter() {}

    /** Writes non-premultiplied ARGB pixels as an RGBA PNG. */
    public static void write(Path file, int width, int height, int[] argb) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            write(out, width, height, argb);
        }
    }

    public static void write(OutputStream out, int width, int height, int[] argb) throws IOException {
        out.write(SIGNATURE);

        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        DataOutputStream h = new DataOutputStream(ihdr);
        h.writeInt(width);
        h.writeInt(height);
        h.writeByte(8);  // bit depth
        h.writeByte(6);  // color type: RGBA
        h.writeByte(0);  // compression
        h.writeByte(0);  // filter
        h.writeByte(0);  // interlace
        chunk(out, "IHDR", ihdr.toByteArray());

        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(idat, new Deflater(Deflater.BEST_SPEED))) {
            byte[] row = new byte[1 + width * 4];
            for (int y = 0; y < height; y++) {
                row[0] = 0; // filter: none
                for (int x = 0; x < width; x++) {
                    int p = argb[y * width + x];
                    int o = 1 + x * 4;
                    row[o] = (byte) (p >> 16);
                    row[o + 1] = (byte) (p >> 8);
                    row[o + 2] = (byte) p;
                    row[o + 3] = (byte) (p >>> 24);
                }
                z.write(row);
            }
        }
        chunk(out, "IDAT", idat.toByteArray());
        chunk(out, "IEND", new byte[0]);
    }

    private static void chunk(OutputStream out, String type, byte[] data) throws IOException {
        DataOutputStream d = new DataOutputStream(out);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        d.writeInt(data.length);
        d.write(typeBytes);
        d.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        d.writeInt((int) crc.getValue());
        d.flush();
    }
}
