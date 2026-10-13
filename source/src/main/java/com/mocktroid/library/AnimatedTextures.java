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

import com.mocktroid.format.pack.PackAsset;
import com.mocktroid.format.pack.PackCatalog;
import com.mocktroid.format.texture.TextureHeader;
import com.mocktroid.format.texture.TxtrReader;
import com.mocktroid.model.AssetEntry;
import com.mocktroid.model.AssetLibrary;
import com.mocktroid.model.AssetType;
import com.mocktroid.settings.AppDirs;

import javafx.application.Platform;

/**
 * Fills the "Animated Textures" category: 2D array textures with at least
 * {@link #MIN_FRAMES} layers, which the game uses as flipbooks (smoke,
 * explosions, screens -- every layer is one frame). Only each texture's HEAD
 * chunk is needed, so it's a few hundred bytes per asset, read pak by pak in
 * file order in the background and cached in {@code <app data>/texture-layers.bin}.
 */
public final class AnimatedTextures {

    public static final int MIN_FRAMES = 8;
    private static final int CACHE_VERSION = 1;
    private static final int HEADER_BYTES = 512;
    private static final AnimatedTextures INSTANCE = new AnimatedTextures();

    public static AnimatedTextures get() { return INSTANCE; }

    /** Per texture GUID: layer count of 2D arrays, 0 for anything else. */
    private final Map<UUID, Integer> layers = new ConcurrentHashMap<>();

    private AnimatedTextures() {}

    /** Reads (or loads from cache) every texture's header, then fills the category on the FX thread. */
    public void loadInBackground(AssetLibrary library) {
        List<AssetEntry> textures = List.copyOf(library.category(AssetType.TEXTURE).entries());
        Thread thread = new Thread(() -> {
            loadCache();
            Map<Path, List<PackAsset>> missing = new LinkedHashMap<>();
            for (AssetEntry entry : textures) {
                if (entry.sourceRef() instanceof PakAssetRef ref && !layers.containsKey(ref.asset().assetId())) {
                    missing.computeIfAbsent(ref.pakFile(), p -> new ArrayList<>()).add(ref.asset());
                }
            }
            if (!missing.isEmpty()) {
                missing.forEach(this::readPak);
                saveCache();
            }
            List<AssetEntry> animated = new ArrayList<>();
            for (AssetEntry entry : textures) {
                if (entry.sourceRef() instanceof PakAssetRef ref
                    && layers.getOrDefault(ref.asset().assetId(), 0) >= MIN_FRAMES) {
                    animated.add(entry);
                }
            }
            // UI animations stored as one texture per frame, found by look (TextureSequences).
            try {
                animated.addAll(TextureSequences.find(library));
            } catch (RuntimeException | OutOfMemoryError e) {
                // arrays only
            }
            Platform.runLater(() -> library.category(AssetType.ANIMATED_TEXTURE).entries().setAll(animated));
        }, "animated-textures");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private void readPak(Path pak, List<PackAsset> assets) {
        assets.sort(Comparator.comparingLong(PackAsset::offset));
        try (RandomAccessFile raf = new RandomAccessFile(pak.toFile(), "r")) {
            byte[] head = new byte[HEADER_BYTES];
            for (PackAsset asset : assets) {
                if (layers.containsKey(asset.assetId())) {
                    continue;
                }
                int count = 0;
                try {
                    byte[] data;
                    if (asset.size() == asset.decompressedSize()) {
                        int n = (int) Math.min(HEADER_BYTES, asset.size());
                        raf.seek(asset.offset());
                        raf.readFully(head, 0, n);
                        data = n == HEADER_BYTES ? head : java.util.Arrays.copyOf(head, n);
                    } else {
                        data = PackCatalog.extractDecompressed(pak, asset);
                    }
                    TextureHeader h = TxtrReader.readHeader(data);
                    count = h.kind() == TextureHeader.Kind.D2_ARRAY ? h.layers() : 0;
                } catch (Exception e) {
                    count = 0;
                }
                layers.put(asset.assetId(), count);
            }
        } catch (IOException e) {
            // Unreadable pak: its textures just aren't listed.
        }
    }

    private static Path cacheFile() {
        return AppDirs.dataDir().resolve("texture-layers.bin");
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
                layers.put(new UUID(in.readLong(), in.readLong()), in.readInt());
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
            Map<UUID, Integer> snapshot = Map.copyOf(layers);
            try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(CACHE_VERSION);
                out.writeInt(snapshot.size());
                for (Map.Entry<UUID, Integer> e : snapshot.entrySet()) {
                    out.writeLong(e.getKey().getMostSignificantBits());
                    out.writeLong(e.getKey().getLeastSignificantBits());
                    out.writeInt(e.getValue());
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Not fatal: headers get re-read next launch.
        }
    }
}
