package com.mocktroid.library;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mocktroid.settings.AppDirs;

/**
 * Persisted cache of small per-sound waveform thumbnails (see
 * {@link CachedWaveform}), keyed by asset UUID, stored one small binary
 * file per sound under a per-OS app-data directory. A singleton (like
 * {@code AppSettings}) since it's a genuinely global, cross-cutting concern
 * -- the grid view needs to consult it for every sound card without every
 * caller having to thread a reference through.
 * <p>
 * Population is a separate, explicit background step (see
 * {@code SoundThumbnailCacheBuilder}, triggered from Settings) -- nothing
 * here decodes audio itself.
 */
public final class SoundThumbnailCache {

    public static final int BIN_COUNT = 48;

    private static final SoundThumbnailCache INSTANCE = new SoundThumbnailCache(defaultCacheDir());

    public static SoundThumbnailCache get() { return INSTANCE; }

    private final Path cacheDir;
    private final Map<UUID, CachedWaveform> memory = new ConcurrentHashMap<>();

    private SoundThumbnailCache(Path cacheDir) {
        this.cacheDir = cacheDir;
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Path defaultCacheDir() {
        return AppDirs.dataDir().resolve("sound-thumbnails");
    }

    public boolean has(UUID assetId) {
        return memory.containsKey(assetId) || Files.exists(fileFor(assetId));
    }

    public CachedWaveform get(UUID assetId) {
        CachedWaveform cached = memory.get(assetId);
        if (cached != null) {
            return cached;
        }
        Path file = fileFor(assetId);
        if (!Files.exists(file)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            int binCount = in.readInt();
            short[] minBins = new short[binCount];
            short[] maxBins = new short[binCount];
            for (int i = 0; i < binCount; i++) minBins[i] = in.readShort();
            for (int i = 0; i < binCount; i++) maxBins[i] = in.readShort();
            double duration = in.readDouble();
            boolean loops = in.readBoolean();
            double loopStart = in.readDouble();
            CachedWaveform waveform = new CachedWaveform(minBins, maxBins, duration, loops, loopStart);
            memory.put(assetId, waveform);
            return waveform;
        } catch (IOException e) {
            return null;
        }
    }

    public void put(UUID assetId, CachedWaveform waveform) {
        memory.put(assetId, waveform);
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(fileFor(assetId)))) {
            out.writeInt(waveform.minBins().length);
            for (short v : waveform.minBins()) out.writeShort(v);
            for (short v : waveform.maxBins()) out.writeShort(v);
            out.writeDouble(waveform.durationSeconds());
            out.writeBoolean(waveform.loops());
            out.writeDouble(waveform.loopStartSeconds());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Count of thumbnails currently on disk -- for a settings "N cached" display. */
    public int countOnDisk() {
        try (var stream = Files.list(cacheDir)) {
            return (int) stream.filter(p -> p.toString().endsWith(".bin")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private Path fileFor(UUID id) {
        return cacheDir.resolve(id + ".bin");
    }
}
