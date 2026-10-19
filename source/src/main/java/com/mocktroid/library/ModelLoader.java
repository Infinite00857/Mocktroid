package com.mocktroid.library;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mocktroid.format.GuidUtil;
import com.mocktroid.format.pack.PackCatalog;
import com.mocktroid.format.model.CmdlModel;
import com.mocktroid.format.model.CmdlReader;
import com.mocktroid.model.AssetEntry;
import com.mocktroid.model.AssetLibrary;

/**
 * Loads a model plus what's needed to texture it. Blocking -- call off the FX thread.
 * <p>
 * Each material's texture slots come either inline (LEGA-format models) or
 * from the MATI material instance it references (MSHP format). MATI's MDAT
 * layout varies too much between shaders to parse field by field yet, so its
 * slots are found by scanning for a slot tag + type byte 5 + a GUID that the
 * romfs index confirms is a TXTR -- with random 128-bit GUIDs that check
 * rules out false matches.
 */
public final class ModelLoader {

    /** Slot tags tried, in order, for a material's base colour texture. */
    private static final String[] BASE_COLOR_SLOTS = {"BCLR", "DIFT"};
    private static final int MATI_TEXTURE_TYPE = 5;

    private ModelLoader() {}

    /**
     * @param baseColorTextures per material index, the base-colour TXTR (or null)
     * @param textureSlots      per material index, every texture slot found (tag -> TXTR): BCLR base colour,
     *                          NMAP normal map, METL packed metal (R) / roughness (G), ICAN incandescence
     *                          (glow colour ramp), TCHn tiling detail layers
     * @param glowColors        per material index, the MATI's ICNC colour (RGBA, may exceed 1: a glow
     *                          multiplier), or null
     */
    public record LoadedModel(CmdlModel model, List<PakAssetRef> baseColorTextures,
                              List<Map<String, PakAssetRef>> textureSlots, List<float[]> glowColors) {
        /** Material {@code index}'s ICNC glow colour, or null. */
        public float[] glowColor(int index) {
            return index >= 0 && index < glowColors.size() ? glowColors.get(index) : null;
        }

        /** The texture in {@code slot} for material {@code index}, or null. */
        public PakAssetRef slot(int index, String slot) {
            return index >= 0 && index < textureSlots.size() ? textureSlots.get(index).get(slot) : null;
        }
    }

    public static LoadedModel load(AssetEntry entry, AssetLibrary library) throws IOException {
        if (!(entry.sourceRef() instanceof PakAssetRef ref)) {
            throw new IOException("No source data for " + entry.displayName());
        }
        byte[] raw = PackCatalog.extractDecompressed(ref.pakFile(), ref.asset());
        CmdlModel model = CmdlReader.read(raw, ref.asset().meta());

        List<PakAssetRef> baseColors = new ArrayList<>();
        List<Map<String, PakAssetRef>> allSlots = new ArrayList<>();
        List<float[]> glowColors = new ArrayList<>();
        Map<UUID, Map<String, UUID>> matiCache = new LinkedHashMap<>();
        Map<UUID, float[]> colorCache = new LinkedHashMap<>();
        for (CmdlModel.MaterialRef mat : model.materials()) {
            glowColors.add(mat.materialInstance() == null || library == null ? null
                : colorCache.computeIfAbsent(mat.materialInstance(), id -> materialColor(id, library, "ICNC")));
            Map<String, UUID> slots = mat.inlineTextures();
            if (slots.isEmpty() && mat.materialInstance() != null && library != null) {
                slots = matiCache.computeIfAbsent(mat.materialInstance(), id -> scanMaterialInstance(id, library));
            }
            baseColors.add(pickBaseColor(slots, library));
            Map<String, PakAssetRef> refs = new LinkedHashMap<>();
            if (library != null) {
                for (Map.Entry<String, UUID> e : slots.entrySet()) {
                    PakAssetRef tex = library.lookup(e.getValue());
                    if (tex != null && tex.asset().assetType().toString().equals("TXTR")) {
                        refs.put(e.getKey(), tex);
                    }
                }
            }
            allSlots.add(refs);
        }
        return new LoadedModel(model, baseColors, allSlots, glowColors);
    }

    /**
     * A colour value from a MATI: {@code tag}, type 3, then four floats. The MATI's name table
     * repeats the tags with other bytes after them, so only plausible values count (the last wins:
     * the values come after the table). Null if absent.
     */
    private static float[] materialColor(UUID id, String tag, byte[] d) {
        float[] found = null;
        for (int i = 0; i + 21 <= d.length; i++) {
            if (d[i + 4] != 3 || !isTag(d, i) || !new String(d, i, 4, java.nio.charset.StandardCharsets.US_ASCII).equals(tag)) {
                continue;
            }
            float[] c = new float[4];
            boolean ok = true;
            for (int k = 0; k < 4; k++) {
                c[k] = Float.intBitsToFloat(com.mocktroid.format.LE.i32(d, i + 5 + k * 4));
                ok &= Float.isFinite(c[k]) && c[k] >= 0 && c[k] <= 100;
            }
            if (ok) {
                found = c;
            }
        }
        return found;
    }

    private static float[] materialColor(UUID id, AssetLibrary library, String tag) {
        PakAssetRef mati = library.lookup(id);
        if (mati == null) {
            return null;
        }
        try {
            return materialColor(id, tag, PackCatalog.extractDecompressed(mati.pakFile(), mati.asset()));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static PakAssetRef pickBaseColor(Map<String, UUID> slots, AssetLibrary library) {
        if (library == null) {
            return null;
        }
        for (String slot : BASE_COLOR_SLOTS) {
            UUID id = slots.get(slot);
            if (id != null) {
                PakAssetRef tex = library.lookup(id);
                if (tex != null && tex.asset().assetType().toString().equals("TXTR")) {
                    return tex;
                }
            }
        }
        return null;
    }

    private static Map<String, UUID> scanMaterialInstance(UUID id, AssetLibrary library) {
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
            // Type 5 = texture; type 8 = texture with sampler settings (the map holograms' base colour).
            // Type 6 (106 MATIs, ~30 shaders) is kept under "<tag>:6": the arm cannon's light strips
            // (shader c558787e) keep their colour ramp there; what it is for the other shaders is unchecked.
            int type = d[i + 4];
            if ((type != MATI_TEXTURE_TYPE && type != 6 && type != 8) || !isTag(d, i)) {
                continue;
            }
            UUID tex = GuidUtil.fromBytesLe(d, i + 5);
            PakAssetRef ref = library.lookup(tex);
            if (ref != null && ref.asset().assetType().toString().equals("TXTR")) {
                String tag = new String(d, i, 4, java.nio.charset.StandardCharsets.US_ASCII);
                slots.putIfAbsent(type == 6 ? tag + ":6" : tag, tex);
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
}
