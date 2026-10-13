package com.mocktroid.library;

import java.util.UUID;

import com.mocktroid.format.chpr.ChprSkeleton;
import com.mocktroid.format.model.CmdlModel;
import com.mocktroid.format.pack.PackCatalog;
import com.mocktroid.model.AssetEntry;
import com.mocktroid.model.AssetLibrary;

/**
 * Finds a skinned model's skeleton: the first CHPR that references the model
 * and has a skin palette matching its SKHD joint count.
 */
public final class SkeletonLoader {

    /** A skeleton plus the skin whose palette the model's vertex joint indices use. */
    public record Loaded(UUID character, ChprSkeleton skeleton, ChprSkeleton.Skin skin, double[][] bindWorld) {}

    private SkeletonLoader() {}

    /** The model's skeleton, or null for static models / when none is found. */
    public static Loaded load(AssetEntry entry, CmdlModel model, AssetLibrary library) {
        if (model.skinJointCount() <= 0 || library == null || !(entry.sourceRef() instanceof PakAssetRef ref)) {
            return null;
        }
        Loaded fallback = null;
        for (UUID character : library.charactersOf(ref.asset().assetId())) {
            PakAssetRef chpr = library.lookup(character);
            if (chpr == null) {
                continue;
            }
            try {
                ChprSkeleton skeleton = ChprSkeleton.read(PackCatalog.extractDecompressed(chpr.pakFile(), chpr.asset()));
                for (ChprSkeleton.Skin skin : skeleton.skins()) {
                    if (skin.palette().length == model.skinJointCount()) {
                        return new Loaded(character, skeleton, skin, skeleton.bindWorld(skin));
                    }
                }
                if (fallback == null && !skeleton.joints().isEmpty()) {
                    fallback = new Loaded(character, skeleton, null, skeleton.bindWorld(null));
                }
            } catch (Exception e) {
                // Unreadable CHPR: try the next character.
            }
        }
        return fallback;
    }
}
