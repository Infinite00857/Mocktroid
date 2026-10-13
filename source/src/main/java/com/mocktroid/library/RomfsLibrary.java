package com.mocktroid.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.mocktroid.format.pack.PackAsset;
import com.mocktroid.format.pack.PackCatalog;
import com.mocktroid.model.AssetEntry;
import com.mocktroid.model.AssetLibrary;
import com.mocktroid.model.AssetType;

/**
 * Builds a real {@link AssetLibrary} by walking a romfs dump for every
 * {@code .pak} and cataloging its assets via {@link PackCatalog} (header
 * region only, not full-file reads -- a romfs runs tens of GB across
 * hundreds of paks). Individual malformed/unreadable paks are skipped
 * rather than aborting the whole scan.
 * <p>
 * Meant to be run off the JavaFX application thread -- see how
 * {@code MocktroidApp} wraps this in a background {@code Task}.
 */
public final class RomfsLibrary {
    private RomfsLibrary() {}

    public static AssetLibrary scan(Path romfsRoot) throws IOException {
        String update = com.mocktroid.settings.AppSettings.get().updateRomfsPathProperty().get();
        return scan(romfsRoot, update == null || update.isBlank() ? null : Path.of(update));
    }

    /**
     * With an update romfs (the game's update dumped by an emulator): its paks replace the base
     * paks at the same relative path, and paks only it has are added.
     */
    public static AssetLibrary scan(Path romfsRoot, Path updateRoot) throws IOException {
        AssetLibrary library = new AssetLibrary();
        java.util.Map<String, Path> byRelative = new java.util.LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(romfsRoot)) {
            walk.filter(p -> p.toString().toLowerCase().endsWith(".pak"))
                .forEach(p -> byRelative.put(romfsRoot.relativize(p).toString().toLowerCase(), p));
        }
        if (updateRoot != null && Files.isDirectory(updateRoot)) {
            try (Stream<Path> walk = Files.walk(updateRoot)) {
                walk.filter(p -> p.toString().toLowerCase().endsWith(".pak"))
                    .forEach(p -> byRelative.put(updateRoot.relativize(p).toString().toLowerCase(), p));
            }
        }
        List<Path> paks = List.copyOf(byRelative.values());

        for (Path pak : paks) {
            List<PackAsset> assets;
            try {
                assets = PackCatalog.scan(pak);
            } catch (Exception e) {
                continue; // skip this pak, keep scanning the rest
            }
            for (PackAsset asset : assets) {
                library.index(new PakAssetRef(pak, asset));
                AssetType type = AssetType.fromFourCc(asset.assetType().toString());
                if (type == AssetType.UNKNOWN) {
                    continue;
                }
                String displayName = asset.name() != null ? asset.name() : asset.assetId().toString();
                AssetEntry entry = new AssetEntry(
                    asset.assetType() + ":" + asset.assetId(),
                    displayName,
                    type,
                    pak.getFileName().toString(),
                    new PakAssetRef(pak, asset));
                library.category(type).entries().add(entry);
            }
        }
        // "All": the same entries (each keeps its own type, so previews still dispatch on it).
        List<AssetEntry> all = new java.util.ArrayList<>();
        for (AssetType type : List.of(AssetType.VIDEO, AssetType.TEXTURE, AssetType.SOUND, AssetType.MODEL, AssetType.ROOM)) {
            all.addAll(library.category(type).entries());
        }
        library.category(AssetType.ALL).entries().setAll(all);
        library.category(AssetType.ANIMATED_TEXTURE);
        return library;
    }
}
