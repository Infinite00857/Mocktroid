package com.mocktroid.library;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Disk format shared by the thumbnail caches: zlib-compressed {@code int width, int height,
 * int[] argb} (big-endian, the format the caches always wrote). Read and written in one go --
 * the per-int stream reads before made a cache hit almost as slow as regenerating -- and written
 * through a temp file so an interrupted save never leaves a broken thumbnail. A {@code .none}
 * marker remembers assets that couldn't be rendered, so they aren't retried every session.
 */
public final class ThumbFiles {

    private ThumbFiles() {}

    /** {width, height, argb...} or null when missing / unreadable. */
    public static int[] read(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            byte[] packed = Files.readAllBytes(file);
            Inflater inflater = new Inflater();
            inflater.setInput(packed);
            byte[] head = new byte[8];
            if (inflater.inflate(head) < 8) {
                return null;
            }
            ByteBuffer hb = ByteBuffer.wrap(head);
            int w = hb.getInt(), h = hb.getInt();
            if (w <= 0 || h <= 0 || (long) w * h > 4096L * 4096) {
                return null;
            }
            byte[] body = new byte[w * h * 4];
            int got = 0;
            while (got < body.length && !inflater.finished()) {
                int n = inflater.inflate(body, got, body.length - got);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                got += n;
            }
            inflater.end();
            if (got < body.length) {
                return null;
            }
            int[] out = new int[2 + w * h];
            out[0] = w;
            out[1] = h;
            ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN).asIntBuffer().get(out, 2, w * h);
            return out;
        } catch (IOException | DataFormatException e) {
            return null;
        }
    }

    public static void write(Path file, int width, int height, int[] argb) {
        try {
            Files.createDirectories(file.getParent());
            ByteBuffer raw = ByteBuffer.allocate(8 + argb.length * 4).order(ByteOrder.BIG_ENDIAN);
            raw.putInt(width).putInt(height);
            raw.asIntBuffer().put(argb);
            Deflater deflater = new Deflater();
            deflater.setInput(raw.array());
            deflater.finish();
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(argb.length);
            byte[] chunk = new byte[64 * 1024];
            while (!deflater.finished()) {
                bytes.write(chunk, 0, deflater.deflate(chunk));
            }
            deflater.end();
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, bytes.toByteArray());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.deleteIfExists(noneMarker(file));
        } catch (IOException ignored) {
            // A failed cache write just means regenerating next time.
        }
    }

    public static boolean isMarkedUnrenderable(Path file) {
        return Files.exists(noneMarker(file));
    }

    public static void markUnrenderable(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(noneMarker(file), new byte[0]);
        } catch (IOException ignored) {
            // retried next time
        }
    }

    private static Path noneMarker(Path file) {
        return file.resolveSibling(file.getFileName().toString().replace(".thumb", "") + ".none");
    }
}
