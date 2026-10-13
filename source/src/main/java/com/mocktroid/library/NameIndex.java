package com.mocktroid.library;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import com.mocktroid.format.ChunkDescriptor;
import com.mocktroid.format.FormDescriptor;
import com.mocktroid.format.GuidUtil;
import com.mocktroid.format.LE;
import com.mocktroid.format.chpr.ChprInfo;
import com.mocktroid.format.pack.PackCatalog;
import com.mocktroid.model.AssetLibrary;
import com.mocktroid.settings.AppDirs;

/**
 * Works out readable names for assets the paks only know by GUID (the paks'
 * own STRG tables name ~650 of ~90,000 assets). Sources, all confirmed on
 * the real romfs:
 * <ul>
 *   <li><b>CHPR</b> characters reference their SMDL models by GUID in the same
 *       order as their skin names ("Primary", then e.g. default, psychic,
 *       violasuit, ...), so each SMDL gets its skin name, and the CHPR itself
 *       its first skin name. The model -&gt; CHPR links are kept too: they're
 *       how a model finds its animation clips.</li>
 *   <li><b>CMDL/SMDL</b> with inline (LEGA) materials carry material names
 *       ("gf_1020_vista_mat3" -&gt; "gf_1020_vista").</li>
 *   <li><b>TXTR</b> become "&lt;model&gt;_&lt;slot&gt;" (e.g. "violasuit_BCLR") from the
 *       first named model whose materials use them.</li>
 * </ul>
 * Slow on a cold disk (it reads every character and model header), so it
 * runs in the background after the library loads and caches its result.
 */
public final class NameIndex {

    private static final int CACHE_VERSION = 2;
    private static final Pattern MATERIAL_SUFFIX = Pattern.compile("_(mat|mtl|material)\\d*$", Pattern.CASE_INSENSITIVE);
    private static final int MATI_TEXTURE_TYPE = 5;

    /** Derived names by asset GUID, and for each model the CHPRs that reference it. */
    public record Result(Map<UUID, String> names, Map<UUID, List<UUID>> charactersByModel) {}

    private NameIndex() {}

    /** Loads the cached result for this library if it's still valid, else builds (and caches) it. */
    public static Result loadOrBuild(AssetLibrary library, Path romfs) {
        Path cache = AppDirs.dataDir().resolve("names-v" + CACHE_VERSION + ".bin");
        long fingerprint = fingerprint(library, romfs);
        Result cached = readCache(cache, fingerprint);
        if (cached != null) {
            return cached;
        }
        Result built = build(library);
        writeCache(cache, fingerprint, built);
        return built;
    }

    static Result build(AssetLibrary library) {
        Collection<PakAssetRef> all = library.allAssets();
        Map<UUID, String> names = new ConcurrentHashMap<>();
        Map<UUID, List<UUID>> charactersByModel = new ConcurrentHashMap<>();
        LongSet modelKeys = new LongSet();
        for (PakAssetRef r : all) {
            String t = type(r);
            if (t.equals("SMDL") || t.equals("CMDL")) {
                modelKeys.add(r.asset().assetId().getLeastSignificantBits());
            }
        }

        // 1. Characters -> skin names for their SMDLs.
        all.parallelStream().filter(r -> type(r).equals("CHPR")).forEach(chpr -> {
            byte[] d;
            try {
                d = PackCatalog.extractDecompressed(chpr.pakFile(), chpr.asset());
            } catch (IOException | RuntimeException e) {
                return;
            }
            List<String> strings = ChprInfo.readStrings(d);
            Set<UUID> models = new LinkedHashSet<>();
            for (int i = 0; i + 16 <= d.length; i++) {
                long lsb = LE.u64(d, i + 8);
                long beLsb = Long.reverseBytes(lsb);
                if (!modelKeys.contains(beLsb)) {
                    continue;
                }
                UUID id = GuidUtil.fromBytesLe(d, i);
                PakAssetRef ref = library.lookup(id);
                if (ref != null && (type(ref).equals("SMDL") || type(ref).equals("CMDL"))) {
                    models.add(id);
                }
            }
            UUID chprId = chpr.asset().assetId();
            if (strings.size() > 1 && chpr.asset().name() == null) {
                names.putIfAbsent(chprId, characterName(strings));
            }
            int i = 1;
            for (UUID model : models) {
                charactersByModel.computeIfAbsent(model, k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(chprId);
                if (i < strings.size() && !strings.get(i).isBlank() && !strings.get(i).contains(".")) {
                    names.putIfAbsent(model, strings.get(i));
                }
                i++;
            }
        });

        // 2. Models -> inline material names; collect texture slots per model.
        Map<UUID, Map<String, UUID>> textureSlots = new ConcurrentHashMap<>();
        Map<UUID, List<UUID>> matisByModel = new ConcurrentHashMap<>();
        all.parallelStream().filter(r -> type(r).equals("CMDL") || type(r).equals("SMDL")).forEach(model -> {
            try {
                readModelMaterials(model, library, names, textureSlots, matisByModel);
            } catch (IOException | RuntimeException ignored) {
                // unreadable header: leave unnamed
            }
        });

        // 3. Textures -> "<model>_<slot>" from named models.
        Map<UUID, Map<String, UUID>> matiCache = new ConcurrentHashMap<>();
        for (PakAssetRef model : all) {
            UUID modelId = model.asset().assetId();
            String modelName = model.asset().name() != null ? model.asset().name() : names.get(modelId);
            if (modelName == null) {
                continue;
            }
            Map<String, UUID> slots = new LinkedHashMap<>(textureSlots.getOrDefault(modelId, Map.of()));
            for (UUID mati : matisByModel.getOrDefault(modelId, List.of())) {
                matiCache.computeIfAbsent(mati, id -> scanMati(id, library)).forEach(slots::putIfAbsent);
            }
            slots.forEach((slot, tex) -> {
                PakAssetRef t = library.lookup(tex);
                if (t != null && t.asset().name() == null) {
                    names.putIfAbsent(tex, modelName + "_" + slot);
                }
            });
        }
        return new Result(new HashMap<>(names), new HashMap<>(charactersByModel));
    }

    private static final Set<String> GENERIC_NAMES = Set.of("default", "camera", "base");
    private static final Pattern CUTSCENE = Pattern.compile("^([a-z]{2}_\\d{4}_[A-Za-z0-9]+)_");

    /** First skin name; generic ones ("default", "Camera") get the cutscene id
     *  their clip names carry, e.g. "Camera (gf_1080_BossOutro)". */
    static String characterName(List<String> strings) {
        String first = strings.get(1);
        if (!GENERIC_NAMES.contains(first.toLowerCase())) {
            return first;
        }
        for (String s : strings) {
            java.util.regex.Matcher m = CUTSCENE.matcher(s);
            if (m.find()) {
                return first + " (" + m.group(1) + ")";
            }
        }
        return first;
    }

    private static void readModelMaterials(PakAssetRef model, AssetLibrary library, Map<UUID, String> names,
                                           Map<UUID, Map<String, UUID>> textureSlots,
                                           Map<UUID, List<UUID>> matisByModel) throws IOException {
        byte[] d = PackCatalog.readPrefix(model.pakFile(), model.asset(), 1 << 16);
        if (d == null || d.length < FormDescriptor.HEADER_SIZE + ChunkDescriptor.HEADER_SIZE) {
            return;
        }
        int pos = FormDescriptor.HEADER_SIZE;
        for (int n = 0; n < 4 && pos + ChunkDescriptor.HEADER_SIZE <= d.length; n++) {
            ChunkDescriptor c = ChunkDescriptor.read(d, pos);
            if (c.id().toString().equals("MTRL")) {
                if (c.nextOffset() > d.length) {
                    d = PackCatalog.readPrefix(model.pakFile(), model.asset(), c.nextOffset());
                }
                parseMtrl(model, d, c, names, textureSlots, matisByModel);
                return;
            }
            pos = c.nextOffset();
        }
    }

    private static void parseMtrl(PakAssetRef model, byte[] d, ChunkDescriptor c, Map<UUID, String> names,
                                  Map<UUID, Map<String, UUID>> textureSlots, Map<UUID, List<UUID>> matisByModel) {
        int b = c.bodyStart();
        String kind = LE.fourCC(d, b).toString();
        UUID modelId = model.asset().assetId();
        if (kind.equals("MSHP")) {
            int count = (int) LE.u32(d, b + 4);
            int first = b + 16 + (int) LE.u32(d, b + 12) * 4;
            List<UUID> matis = new ArrayList<>();
            for (int i = 0; i < count && first + (i + 1) * 38 <= c.nextOffset(); i++) {
                matis.add(GuidUtil.fromBytesLe(d, first + i * 38));
            }
            matisByModel.put(modelId, matis);
        } else if (kind.equals("LEGA")) {
            // First material: u32 unk, u32 count, u32 nameLen, name, then slots.
            int nameLen = (int) LE.u32(d, b + 12);
            if (nameLen > 0 && nameLen < 128 && b + 16 + nameLen <= d.length) {
                String name = new String(d, b + 16, nameLen, StandardCharsets.UTF_8);
                names.putIfAbsent(modelId, MATERIAL_SUFFIX.matcher(name).replaceFirst(""));
            }
            // Inline texture slots: "<slot>TXTR" followed by a GUID.
            Map<String, UUID> slots = new LinkedHashMap<>();
            for (int i = b; i + 24 <= c.nextOffset(); i++) {
                if (d[i + 4] == 'T' && d[i + 5] == 'X' && d[i + 6] == 'T' && d[i + 7] == 'R' && isTag(d, i)) {
                    slots.putIfAbsent(new String(d, i, 4, StandardCharsets.US_ASCII), GuidUtil.fromBytesLe(d, i + 8));
                }
            }
            textureSlots.put(modelId, slots);
        }
    }

    /** MATI texture slots: tag + type byte 5 + a GUID the index confirms is a TXTR (see ModelLoader). */
    static Map<String, UUID> scanMati(UUID id, AssetLibrary library) {
        Map<String, UUID> slots = new LinkedHashMap<>();
        PakAssetRef mati = library.lookup(id);
        if (mati == null) {
            return slots;
        }
        byte[] d;
        try {
            d = PackCatalog.extractDecompressed(mati.pakFile(), mati.asset());
        } catch (IOException | RuntimeException e) {
            return slots;
        }
        for (int i = 0; i + 21 <= d.length; i++) {
            if (d[i + 4] != MATI_TEXTURE_TYPE || !isTag(d, i)) {
                continue;
            }
            UUID tex = GuidUtil.fromBytesLe(d, i + 5);
            PakAssetRef ref = library.lookup(tex);
            if (ref != null && type(ref).equals("TXTR")) {
                slots.putIfAbsent(new String(d, i, 4, StandardCharsets.US_ASCII), tex);
            }
        }
        return slots;
    }

    private static boolean isTag(byte[] d, int o) {
        for (int k = 0; k < 4; k++) {
            int c = d[o + k] & 0xFF;
            if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    private static String type(PakAssetRef r) {
        return r.asset().assetType().toString();
    }

    // ---- cache ---------------------------------------------------------------

    private static long fingerprint(AssetLibrary library, Path romfs) {
        long h = romfs.toAbsolutePath().toString().hashCode();
        for (PakAssetRef r : library.allAssets()) {
            h = h * 31 + r.asset().assetId().getLeastSignificantBits() + r.asset().size();
        }
        return h;
    }

    private static Result readCache(Path file, long fingerprint) {
        if (!Files.exists(file)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(Files.newInputStream(file)))) {
            if (in.readLong() != fingerprint) {
                return null;
            }
            Map<UUID, String> names = new HashMap<>();
            for (int i = in.readInt(); i > 0; i--) {
                names.put(new UUID(in.readLong(), in.readLong()), in.readUTF());
            }
            Map<UUID, List<UUID>> chars = new HashMap<>();
            for (int i = in.readInt(); i > 0; i--) {
                UUID model = new UUID(in.readLong(), in.readLong());
                List<UUID> list = new ArrayList<>();
                for (int k = in.readInt(); k > 0; k--) {
                    list.add(new UUID(in.readLong(), in.readLong()));
                }
                chars.put(model, list);
            }
            return new Result(names, chars);
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeCache(Path file, long fingerprint, Result r) {
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(Files.newOutputStream(file)))) {
                out.writeLong(fingerprint);
                out.writeInt(r.names().size());
                for (Map.Entry<UUID, String> e : r.names().entrySet()) {
                    out.writeLong(e.getKey().getMostSignificantBits());
                    out.writeLong(e.getKey().getLeastSignificantBits());
                    out.writeUTF(e.getValue());
                }
                out.writeInt(r.charactersByModel().size());
                for (Map.Entry<UUID, List<UUID>> e : r.charactersByModel().entrySet()) {
                    out.writeLong(e.getKey().getMostSignificantBits());
                    out.writeLong(e.getKey().getLeastSignificantBits());
                    out.writeInt(e.getValue().size());
                    for (UUID u : e.getValue()) {
                        out.writeLong(u.getMostSignificantBits());
                        out.writeLong(u.getLeastSignificantBits());
                    }
                }
            }
        } catch (IOException ignored) {
            // no cache: rebuilt next launch
        }
    }

    /** Minimal open-addressing set of longs: the CHPR scan probes millions of offsets. */
    private static final class LongSet {
        private long[] keys = new long[1 << 16];
        private boolean[] used = new boolean[1 << 16];
        private int size;

        void add(long k) {
            if (size * 2 >= keys.length) {
                grow();
            }
            int i = slot(k, keys.length);
            while (used[i] && keys[i] != k) {
                i = (i + 1) & (keys.length - 1);
            }
            if (!used[i]) {
                used[i] = true;
                keys[i] = k;
                size++;
            }
        }

        boolean contains(long k) {
            int i = slot(k, keys.length);
            while (used[i]) {
                if (keys[i] == k) {
                    return true;
                }
                i = (i + 1) & (keys.length - 1);
            }
            return false;
        }

        private void grow() {
            long[] oldK = keys;
            boolean[] oldU = used;
            keys = new long[oldK.length * 2];
            used = new boolean[oldK.length * 2];
            size = 0;
            for (int i = 0; i < oldK.length; i++) {
                if (oldU[i]) {
                    add(oldK[i]);
                }
            }
        }

        private static int slot(long k, int len) {
            long h = k * 0x9E3779B97F4A7C15L;
            return (int) (h >>> 40) & (len - 1);
        }
    }
}
