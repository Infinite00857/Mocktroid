package com.mocktroid.library;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.mocktroid.format.pack.PackCatalog;
import com.mocktroid.format.texture.Texture;
import com.mocktroid.format.texture.TextureDecoder;
import com.mocktroid.format.texture.TextureHeader;
import com.mocktroid.format.texture.TxtrReader;
import com.mocktroid.model.AssetEntry;
import com.mocktroid.model.AssetLibrary;
import com.mocktroid.model.AssetType;
import com.mocktroid.settings.AppDirs;

/**
 * UI animations stored as one texture per frame (the HUD's 31-frame icons in UI_HUD.pak). Which
 * textures form a sequence -- and in what order -- lives in UI data not decoded yet, so they are
 * found by look: in each UI pak, same-size same-format textures whose 16x16 thumbnails are close
 * are grouped (connected components), then ordered by walking from one end of the group to its
 * most similar unvisited frame each time. Cached in {@code <app data>/texture-sequences.bin}.
 */
public final class TextureSequences {

    /** An entry's source: the frames, in playback order. */
    public record SequenceRef(List<PakAssetRef> frames) {}

    private static final int CACHE_VERSION = 1;
    private static final int MIN_FRAMES = 6;
    private static final double THRESHOLD = 0.06;
    private static final int THUMB = 16;

    private TextureSequences() {}

    /** Finds (or loads) the sequences and returns them as Animated Textures entries. Blocking. */
    public static List<AssetEntry> find(AssetLibrary library) {
        List<List<UUID>> groups = loadCache();
        if (groups == null) {
            groups = scan(library);
            saveCache(groups);
        }
        List<AssetEntry> out = new ArrayList<>();
        for (List<UUID> g : groups) {
            List<PakAssetRef> frames = new ArrayList<>();
            for (UUID id : g) {
                PakAssetRef ref = library.lookup(id);
                if (ref != null) {
                    frames.add(ref);
                }
            }
            if (frames.size() < MIN_FRAMES) {
                continue;
            }
            PakAssetRef first = frames.get(0);
            String name = (first.asset().name() != null ? first.asset().name() : first.asset().assetId().toString())
                + "  (" + frames.size() + " frames)";
            out.add(new AssetEntry("SEQ:" + first.asset().assetId(), name, AssetType.TEXTURE,
                first.pakFile().getFileName().toString(), new SequenceRef(frames)));
        }
        return out;
    }

    private static boolean isUiPak(Path pak) {
        String p = pak.toString().replace('\\', '/').toLowerCase();
        return p.contains("/ui/") || pak.getFileName().toString().toLowerCase().startsWith("ui_");
    }

    private static List<List<UUID>> scan(AssetLibrary library) {
        Map<Path, List<PakAssetRef>> byPak = new LinkedHashMap<>();
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (PakAssetRef ref : library.allAssets()) {
            if (ref.asset().assetType().toString().equals("TXTR") && isUiPak(ref.pakFile()) && seen.add(ref.asset().assetId())) {
                byPak.computeIfAbsent(ref.pakFile(), p -> new ArrayList<>()).add(ref);
            }
        }
        List<List<UUID>> out = new ArrayList<>();
        for (List<PakAssetRef> refs : byPak.values()) {
            Map<String, List<UUID>> bySize = new TreeMap<>();
            Map<UUID, float[]> thumbs = new HashMap<>();
            for (PakAssetRef ref : refs) {
                try {
                    Texture t = TxtrReader.read(PackCatalog.extractDecompressed(ref.pakFile(), ref.asset()), ref.asset().meta());
                    TextureHeader h = t.header();
                    if (h.layerCount() > 1 || h.kind() != TextureHeader.Kind.D2) {
                        continue;
                    }
                    bySize.computeIfAbsent(h.width() + "x" + h.height() + ":" + h.format(), k -> new ArrayList<>()).add(ref.asset().assetId());
                    thumbs.put(ref.asset().assetId(), thumb(t));
                } catch (Exception | OutOfMemoryError e) {
                    // skip
                }
            }
            for (List<UUID> ids : bySize.values()) {
                if (ids.size() >= MIN_FRAMES) {
                    out.addAll(groups(ids, thumbs));
                }
            }
        }
        return out;
    }

    private static float[] thumb(Texture t) {
        TextureHeader h = t.header();
        int mip = 0;
        while (mip < h.mipCount() - 1 && Math.max(h.mipWidth(mip), h.mipHeight(mip)) > 64) {
            mip++;
        }
        TextureDecoder.Image img = TextureDecoder.decode(t, 0, mip, 0);
        float[] f = new float[THUMB * THUMB * 4];
        for (int y = 0; y < THUMB; y++) {
            for (int x = 0; x < THUMB; x++) {
                int p = img.argb()[(y * img.height() / THUMB) * img.width() + x * img.width() / THUMB];
                int k = (y * THUMB + x) * 4;
                float a = (p >>> 24) / 255f;
                f[k] = a;
                f[k + 1] = ((p >> 16) & 255) / 255f * a;
                f[k + 2] = ((p >> 8) & 255) / 255f * a;
                f[k + 3] = (p & 255) / 255f * a;
            }
        }
        return f;
    }

    private static List<List<UUID>> groups(List<UUID> ids, Map<UUID, float[]> thumbs) {
        int n = ids.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        double[][] dist = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                dist[i][j] = dist[j][i] = distance(thumbs.get(ids.get(i)), thumbs.get(ids.get(j)));
                if (dist[i][j] < THRESHOLD) {
                    parent[find(parent, i)] = find(parent, j);
                }
            }
        }
        Map<Integer, List<Integer>> comps = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            comps.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }
        List<List<UUID>> out = new ArrayList<>();
        for (List<Integer> comp : comps.values()) {
            if (comp.size() < MIN_FRAMES) {
                continue;
            }
            // Start at an end: the frame farthest from the group's most central one.
            int centre = comp.get(0);
            double bestSum = Double.MAX_VALUE;
            for (int i : comp) {
                double s = 0;
                for (int j : comp) {
                    s += dist[i][j];
                }
                if (s < bestSum) {
                    bestSum = s;
                    centre = i;
                }
            }
            int cur = comp.get(0);
            double far = -1;
            for (int i : comp) {
                if (dist[centre][i] > far) {
                    far = dist[centre][i];
                    cur = i;
                }
            }
            List<UUID> order = new ArrayList<>();
            java.util.Set<Integer> left = new java.util.HashSet<>(comp);
            while (true) {
                order.add(ids.get(cur));
                left.remove(cur);
                if (left.isEmpty()) {
                    break;
                }
                int next = -1;
                double d = Double.MAX_VALUE;
                for (int j : left) {
                    if (dist[cur][j] < d) {
                        d = dist[cur][j];
                        next = j;
                    }
                }
                cur = next;
            }
            out.add(order);
        }
        return out;
    }

    private static double distance(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            s += d * d;
        }
        return Math.sqrt(s / a.length);
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) {
            i = p[i] = p[p[i]];
        }
        return i;
    }

    /** A 2D-array texture made of the frames (same size and format), for the flipbook preview. */
    public static Texture asTexture(SequenceRef seq) throws IOException {
        List<Texture> frames = new ArrayList<>();
        for (PakAssetRef ref : seq.frames()) {
            frames.add(TxtrReader.read(PackCatalog.extractDecompressed(ref.pakFile(), ref.asset()), ref.asset().meta()));
        }
        TextureHeader h0 = frames.get(0).header();
        int mips = h0.mipCount();
        for (Texture t : frames) {
            mips = Math.min(mips, t.header().mipCount());
        }
        byte[][][] surfaces = new byte[frames.size()][][];
        for (int i = 0; i < frames.size(); i++) {
            surfaces[i] = java.util.Arrays.copyOf(frames.get(i).surfaces()[0], mips);
        }
        TextureHeader h = new TextureHeader(TextureHeader.Kind.D2_ARRAY, h0.format(), h0.width(), h0.height(), frames.size(),
            mips, java.util.Arrays.copyOf(h0.mipSizes(), mips), h0.filter(), h0.wrapX(), h0.wrapY());
        return new Texture(h, surfaces);
    }

    private static Path cacheFile() {
        return AppDirs.dataDir().resolve("texture-sequences.bin");
    }

    private static List<List<UUID>> loadCache() {
        Path file = cacheFile();
        if (!Files.exists(file)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != CACHE_VERSION) {
                return null;
            }
            int n = in.readInt();
            List<List<UUID>> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int m = in.readInt();
                List<UUID> g = new ArrayList<>(m);
                for (int k = 0; k < m; k++) {
                    g.add(new UUID(in.readLong(), in.readLong()));
                }
                out.add(g);
            }
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    private static void saveCache(List<List<UUID>> groups) {
        Path file = cacheFile();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(CACHE_VERSION);
                out.writeInt(groups.size());
                for (List<UUID> g : groups) {
                    out.writeInt(g.size());
                    for (UUID id : g) {
                        out.writeLong(id.getMostSignificantBits());
                        out.writeLong(id.getLeastSignificantBits());
                    }
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // re-scanned next launch
        }
    }
}
