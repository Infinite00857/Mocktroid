package com.mocktroid.library;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mocktroid.format.audio.CsmpDuration;
import com.mocktroid.format.pack.PackAsset;
import com.mocktroid.format.pack.PackCatalog;
import com.mocktroid.model.AssetEntry;
import com.mocktroid.settings.AppDirs;

import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;

/**
 * Every sound's length, read from its CSMP header (see {@link CsmpDuration})
 * in the background right after the romfs scan -- what duration sort uses.
 * Before this existed, duration sort only worked for sounds that already had
 * a waveform thumbnail (a slow, opt-in full decode), so on a fresh install
 * every sound sorted as 0 seconds and the order never changed.
 * <p>
 * ~17,000 header reads are seek-bound on a hard drive, so they're done pak by
 * pak in file order, and the results are cached in
 * {@code <app data>/sound-durations.bin} (keyed by GUID; GUIDs are stable).
 */
public final class SoundDurations {

    private static final SoundDurations INSTANCE = new SoundDurations();
    private static final int CACHE_VERSION = 1;
    private static final int HEADER_BYTES = 4096;

    public static SoundDurations get() { return INSTANCE; }

    private final Map<UUID, Double> seconds = new ConcurrentHashMap<>();
    /** Bumped on the FX thread when durations land, so views can re-sort. */
    private final IntegerProperty revision = new SimpleIntegerProperty();

    private SoundDurations() {}

    /** Seconds, or -1 if this sound's length isn't known (yet). */
    public double seconds(UUID assetId) {
        Double s = seconds.get(assetId);
        return s == null ? -1 : s;
    }

    public IntegerProperty revisionProperty() {
        return revision;
    }

    /** Loads the cache, then reads any sounds it's missing, on a background thread. */
    public void loadInBackground(List<AssetEntry> sounds) {
        List<AssetEntry> copy = List.copyOf(sounds);
        Thread thread = new Thread(() -> {
            loadCache();
            Map<Path, List<PackAsset>> missing = new LinkedHashMap<>();
            for (AssetEntry entry : copy) {
                if (entry.sourceRef() instanceof PakAssetRef ref && !seconds.containsKey(ref.asset().assetId())) {
                    missing.computeIfAbsent(ref.pakFile(), p -> new ArrayList<>()).add(ref.asset());
                }
            }
            if (!missing.isEmpty()) {
                // Cached lengths are usable straight away while the rest are read.
                Platform.runLater(() -> revision.set(revision.get() + 1));
                missing.forEach(this::readPak);
                saveCache();
            }
            Platform.runLater(() -> revision.set(revision.get() + 1));
        }, "sound-durations");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private void readPak(Path pak, List<PackAsset> assets) {
        assets.sort(Comparator.comparingLong(PackAsset::offset));
        try (RandomAccessFile raf = new RandomAccessFile(pak.toFile(), "r")) {
            byte[] head = new byte[HEADER_BYTES];
            for (PackAsset asset : assets) {
                if (seconds.containsKey(asset.assetId())) {
                    continue;
                }
                double s;
                if (asset.size() == asset.decompressedSize()) {
                    int n = (int) Math.min(HEADER_BYTES, asset.size());
                    raf.seek(asset.offset());
                    raf.readFully(head, 0, n);
                    s = CsmpDuration.seconds(n == HEADER_BYTES ? head : java.util.Arrays.copyOf(head, n));
                    if (s < 0 && n == HEADER_BYTES) {
                        // A long CRMS chunk pushed the DSP header further in.
                        s = CsmpDuration.seconds(PackCatalog.extractDecompressed(pak, asset));
                    }
                } else {
                    s = CsmpDuration.seconds(PackCatalog.extractDecompressed(pak, asset));
                }
                if (s >= 0) {
                    seconds.put(asset.assetId(), s);
                }
            }
        } catch (IOException e) {
            // Unreadable pak: those sounds just sort last.
        }
    }

    private static Path cacheFile() {
        return AppDirs.dataDir().resolve("sound-durations.bin");
    }

    private void loadCache() {
        Path file = cacheFile();
        if (!Files.exists(file)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != CACHE_VERSION) {
                return;
            }
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                seconds.put(new UUID(in.readLong(), in.readLong()), in.readDouble());
            }
        } catch (IOException e) {
            // Corrupt cache: re-read everything.
        }
    }

    private void saveCache() {
        Path file = cacheFile();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Map<UUID, Double> snapshot = Map.copyOf(seconds);
            try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(CACHE_VERSION);
                out.writeInt(snapshot.size());
                for (Map.Entry<UUID, Double> e : snapshot.entrySet()) {
                    out.writeLong(e.getKey().getMostSignificantBits());
                    out.writeLong(e.getKey().getLeastSignificantBits());
                    out.writeDouble(e.getValue());
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Not fatal: lengths get re-read next launch.
        }
    }
}
