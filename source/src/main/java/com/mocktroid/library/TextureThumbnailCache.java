package com.mocktroid.library;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import com.mocktroid.format.texture.Texture;
import com.mocktroid.format.texture.TextureDecoder;
import com.mocktroid.format.texture.TextureHeader;
import com.mocktroid.model.AssetEntry;
import com.mocktroid.settings.AppDirs;

import javafx.application.Platform;

/**
 * Small per-texture grid thumbnails, generated lazily the first time a card
 * scrolls into view and persisted (deflated raw ARGB, a few KB each) so the
 * next launch shows them instantly.
 * <p>
 * Unlike the sound waveform cache there's no explicit "build" step: decoding
 * one small mip of a texture takes milliseconds, so generating on demand is
 * cheap enough. Requests run newest-first (LIFO) on a small pool, so when the
 * user scrolls quickly the cards they're actually looking at win over ones
 * that already scrolled past.
 */
public final class TextureThumbnailCache {

    public static final int SIZE = 96;
    private static final int MEMORY_ENTRIES = 1500;

    /** A decoded thumbnail, at most {@link #SIZE} on its longer side. */
    public record Thumb(int width, int height, int[] argb) {}

    /** Bump whenever decoding changes what a thumbnail looks like, so stale
     *  ones regenerate (v2: rows flipped to top-down). */
    private static final int VERSION = 2;

    private static final TextureThumbnailCache INSTANCE =
        new TextureThumbnailCache(AppDirs.dataDir().resolve("texture-thumbnails").resolve("v" + VERSION));

    public static TextureThumbnailCache get() { return INSTANCE; }

    private final Path cacheDir;
    private final Map<UUID, Thumb> memory = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Thumb> eldest) {
            return size() > MEMORY_ENTRIES;
        }
    };
    private final Map<UUID, List<Consumer<Thumb>>> pending = new HashMap<>();
    private final ThreadPoolExecutor executor;

    private TextureThumbnailCache(Path cacheDir) {
        this.cacheDir = cacheDir;
        LinkedBlockingDeque<Runnable> lifo = new LinkedBlockingDeque<>() {
            @Override
            public boolean offer(Runnable r) {
                return offerFirst(r);
            }
        };
        int threads = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 2));
        executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, lifo, r -> {
            Thread t = new Thread(r, "texture-thumbnail");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    /** Memory-only lookup; safe and cheap on the FX thread. */
    public synchronized Thumb getIfLoaded(UUID id) {
        return memory.get(id);
    }

    /**
     * Calls {@code onReady} on the FX thread with the entry's thumbnail,
     * loading it from disk or generating it in the background as needed.
     * {@code onReady} receives null if the texture can't be decoded.
     */
    public void request(AssetEntry entry, Consumer<Thumb> onReady) {
        if (!(entry.sourceRef() instanceof PakAssetRef ref)) {
            onReady.accept(null);
            return;
        }
        UUID id = ref.asset().assetId();
        synchronized (this) {
            Thumb cached = memory.get(id);
            if (cached != null) {
                onReady.accept(cached);
                return;
            }
            List<Consumer<Thumb>> waiters = pending.get(id);
            if (waiters != null) {
                waiters.add(onReady);
                return;
            }
            waiters = new ArrayList<>();
            waiters.add(onReady);
            pending.put(id, waiters);
        }
        executor.execute(() -> {
            Thumb thumb = null;
            try {
                thumb = loadFromDisk(id);
                if (thumb == null && !com.mocktroid.library.ThumbFiles.isMarkedUnrenderable(fileFor(id))) {
                    thumb = generate(entry);
                    if (thumb != null) {
                        saveToDisk(id, thumb);
                    } else {
                        com.mocktroid.library.ThumbFiles.markUnrenderable(fileFor(id));
                    }
                }
            } catch (Exception | OutOfMemoryError e) {
                thumb = null;
            }
            List<Consumer<Thumb>> done;
            synchronized (this) {
                if (thumb != null) {
                    memory.put(id, thumb);
                }
                done = pending.remove(id);
            }
            Thumb result = thumb;
            Platform.runLater(() -> done.forEach(c -> c.accept(result)));
        });
    }

    /** Decodes the smallest mip that still covers {@link #SIZE} and box-filters it down. */
    static Thumb generate(AssetEntry entry) throws IOException {
        Texture texture = TextureLoader.load(entry);
        TextureHeader h = texture.header();
        int mip = h.mipCount() - 1;
        while (mip > 0 && Math.max(h.mipWidth(mip), h.mipHeight(mip)) < SIZE) {
            mip--;
        }
        TextureDecoder.Image img = TextureDecoder.decode(texture, 0, mip, 0);
        return downscale(img.width(), img.height(), img.argb());
    }

    private static Thumb downscale(int w, int h, int[] src) {
        double scale = Math.min(1.0, (double) SIZE / Math.max(w, h));
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));
        int[] out = new int[tw * th];
        for (int ty = 0; ty < th; ty++) {
            int y0 = ty * h / th;
            int y1 = Math.max(y0 + 1, (ty + 1) * h / th);
            for (int tx = 0; tx < tw; tx++) {
                int x0 = tx * w / tw;
                int x1 = Math.max(x0 + 1, (tx + 1) * w / tw);
                long a = 0, r = 0, g = 0, b = 0;
                int n = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int p = src[y * w + x];
                        a += p >>> 24;
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                        n++;
                    }
                }
                out[ty * tw + tx] = (int) ((a / n) << 24 | (r / n) << 16 | (g / n) << 8 | (b / n));
            }
        }
        return new Thumb(tw, th, out);
    }

    private Path fileFor(UUID id) {
        return cacheDir.resolve(id + ".thumb");
    }

    private Thumb loadFromDisk(UUID id) {
        int[] d = com.mocktroid.library.ThumbFiles.read(fileFor(id));
        return d == null ? null : new Thumb(d[0], d[1], java.util.Arrays.copyOfRange(d, 2, d.length));
    }

    private void saveToDisk(UUID id, Thumb thumb) {
        com.mocktroid.library.ThumbFiles.write(fileFor(id), thumb.width(), thumb.height(), thumb.argb());
    }

    public int countOnDisk() {
        if (!Files.isDirectory(cacheDir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(cacheDir)) {
            return (int) files.filter(p -> p.toString().endsWith(".thumb")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    public void clear() {
        synchronized (this) {
            memory.clear();
        }
        if (!Files.isDirectory(cacheDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(cacheDir)) {
            files.filter(p -> p.toString().endsWith(".thumb") || p.toString().endsWith(".none")).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
