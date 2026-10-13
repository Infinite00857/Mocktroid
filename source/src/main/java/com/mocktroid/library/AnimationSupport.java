package com.mocktroid.library;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mocktroid.format.chpr.AnimClip;
import com.mocktroid.format.chpr.ChprInfo;
import com.mocktroid.format.pack.PackCatalog;
import com.mocktroid.model.AssetEntry;
import com.mocktroid.model.AssetLibrary;

/**
 * Which animation clips belong to a browse entry -- the data behind
 * "double-click a model to open its animation player".
 * <p>
 * Models carry no animation themselves; clips live in the CHPR characters
 * that reference the model (found by {@link NameIndex}). A CHPR entry lists
 * its own clips. {@link #decode} returns a clip's key data
 * ({@link AnimClip}). Blocking -- call off the FX thread.
 */
public final class AnimationSupport {

    /** A clip plus the character it came from. */
    public record ClipRef(UUID character, ChprInfo.Clip clip) {}

    private static final Map<UUID, List<ChprInfo.Clip>> CACHE = new ConcurrentHashMap<>();
    /** Per CHPR: payload offset by clip index (the payload id is the clip index plus a per-file offset). */
    private static final Map<UUID, Map<Integer, Integer>> PAYLOADS = new ConcurrentHashMap<>();
    /** Per CHPR: its constant pools (values clips hold for their whole length). */
    private static final Map<UUID, List<float[]>> POOLS = new ConcurrentHashMap<>();
    private record Bytes(UUID chpr, byte[] data) {}
    private static volatile Bytes last;

    private AnimationSupport() {}

    public static List<ClipRef> clipsFor(AssetEntry entry, AssetLibrary library) {
        if (!(entry.sourceRef() instanceof PakAssetRef ref) || library == null) {
            return List.of();
        }
        UUID id = ref.asset().assetId();
        List<UUID> characters = ref.asset().assetType().toString().equals("CHPR")
            ? List.of(id) : library.charactersOf(id);
        Map<String, ClipRef> byName = new LinkedHashMap<>();
        for (UUID chpr : characters) {
            for (ChprInfo.Clip clip : clipsOf(chpr, library)) {
                byName.putIfAbsent(clip.name(), new ClipRef(chpr, clip));
            }
        }
        return new ArrayList<>(byName.values());
    }

    private static List<ChprInfo.Clip> clipsOf(UUID chpr, AssetLibrary library) {
        return CACHE.computeIfAbsent(chpr, id -> {
            PakAssetRef ref = library.lookup(id);
            if (ref == null) {
                return List.of();
            }
            try {
                return ChprInfo.read(PackCatalog.extractDecompressed(ref.pakFile(), ref.asset())).clips();
            } catch (IOException | RuntimeException e) {
                return List.of();
            }
        });
    }

    /**
     * Pose addresses are numbered per CHPR: a clip stored in another character file than the
     * model's skeleton (the first-person arms' clips live in the player CHPR) writes to that
     * file's addresses. Maps clip address -> skeleton address through joint names; null when both
     * are the same file or nothing maps.
     */
    public static Map<Integer, Integer> addressRemap(UUID clipCharacter, UUID skeletonCharacter,
                                                     com.mocktroid.format.chpr.ChprSkeleton target, AssetLibrary library) {
        if (clipCharacter == null || clipCharacter.equals(skeletonCharacter) || target == null) {
            return null;
        }
        return REMAPS.computeIfAbsent(clipCharacter + ">" + skeletonCharacter, k -> {
            byte[] d = bytesOf(clipCharacter, library);
            if (d == null) {
                return Map.of();
            }
            com.mocktroid.format.chpr.ChprSkeleton source = com.mocktroid.format.chpr.ChprSkeleton.read(d);
            Map<String, Integer> targetByName = new java.util.HashMap<>();
            for (int j = 0; j < target.joints().size(); j++) {
                if (target.poseAddress(j) >= 0) {
                    targetByName.putIfAbsent(target.joints().get(j), target.poseAddress(j));
                }
            }
            Map<Integer, Integer> map = new java.util.HashMap<>();
            boolean differs = false;
            for (int j = 0; j < source.joints().size(); j++) {
                Integer to = targetByName.get(source.joints().get(j));
                int from = source.poseAddress(j);
                if (to != null && from >= 0) {
                    map.put(from, to);
                    differs |= from != to;
                }
            }
            return differs ? map : Map.of();
        });
    }

    private static final Map<String, Map<Integer, Integer>> REMAPS = new ConcurrentHashMap<>();

    /** The clip's decoded key data, or null when its payload can't be found. */
    public static AnimClip decode(ClipRef ref, AssetLibrary library) {
        byte[] d = bytesOf(ref.character(), library);
        if (d == null) {
            return null;
        }
        Map<Integer, Integer> offsets = PAYLOADS.computeIfAbsent(ref.character(), id -> payloadsByIndex(d, clipsOf(id, library)));
        Integer off = offsets.get(ref.clip().index());
        if (off == null) {
            return null;
        }
        AnimClip clip = AnimClip.read(d, off);
        clip.setPools(POOLS.computeIfAbsent(ref.character(), id -> AnimClip.findPools(d)));
        return clip;
    }

    /** Matches payload ids to clip indices: the offset that lines up the most frame counts. */
    private static Map<Integer, Integer> payloadsByIndex(byte[] d, List<ChprInfo.Clip> clips) {
        Map<Integer, int[]> byId = new java.util.HashMap<>();
        for (AnimClip.Located l : AnimClip.locate(d)) {
            byId.put(l.id(), new int[] {l.offset(), d.length >= l.offset() + 0x12 ? (d[l.offset() + 0x10] & 0xFF) | (d[l.offset() + 0x11] & 0xFF) << 8 : -1});
        }
        Map<Integer, Integer> votes = new java.util.HashMap<>();
        for (ChprInfo.Clip c : clips) {
            for (Map.Entry<Integer, int[]> e : byId.entrySet()) {
                if (e.getValue()[1] == c.frames()) {
                    votes.merge(e.getKey() - c.index(), 1, Integer::sum);
                }
            }
        }
        int delta = votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
        Map<Integer, Integer> out = new java.util.HashMap<>();
        for (ChprInfo.Clip c : clips) {
            int[] hit = byId.get(c.index() + delta);
            if (hit != null) {
                out.put(c.index(), hit[0]);
            }
        }
        return out;
    }

    private static byte[] bytesOf(UUID chpr, AssetLibrary library) {
        Bytes cached = last;
        if (cached != null && cached.chpr().equals(chpr)) {
            return cached.data();
        }
        PakAssetRef ref = library.lookup(chpr);
        if (ref == null) {
            return null;
        }
        try {
            byte[] d = PackCatalog.extractDecompressed(ref.pakFile(), ref.asset());
            last = new Bytes(chpr, d);
            return d;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
