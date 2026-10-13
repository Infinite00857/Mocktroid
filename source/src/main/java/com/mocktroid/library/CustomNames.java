package com.mocktroid.library;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

import com.mocktroid.model.AssetEntry;
import com.mocktroid.settings.AppDirs;

/**
 * The user's own names for assets -- a small database that never touches the
 * game files. Stored as {@code <app data>/custom-names.tsv}, one
 * {@code <asset GUID><TAB><name>} line per asset, so it can be hand-edited,
 * shared, or merged with someone else's list (see {@link #importFrom}).
 * <p>
 * Keyed by asset GUID rather than by entry, so every pak's copy of an asset
 * gets the same name. A singleton like {@code AppSettings}: lookups come from
 * the library and the rename UI alike.
 */
public final class CustomNames {

    private static final CustomNames INSTANCE = new CustomNames(AppDirs.dataDir().resolve("custom-names.tsv"));

    public static CustomNames get() { return INSTANCE; }

    private final Path file;
    private final Map<String, String> names = new TreeMap<>();

    private CustomNames(Path file) {
        this.file = file;
        try {
            read(file, names);
        } catch (IOException e) {
            // Unreadable list: start empty rather than refusing to launch.
        }
    }

    /** The key an entry's custom name is stored under: its GUID for pak assets. */
    public static String keyOf(AssetEntry entry) {
        if (entry.sourceRef() instanceof PakAssetRef ref) {
            return ref.asset().assetId().toString();
        }
        return entry.id();
    }

    public synchronized String get(String key) {
        return names.get(key);
    }

    /** Sets (or, for a null/blank name, removes) a custom name and saves. */
    public synchronized void set(String key, String name) {
        String clean = clean(name);
        if (clean.isEmpty()) {
            names.remove(key);
        } else {
            names.put(key, clean);
        }
        save();
    }

    public synchronized int count() {
        return names.size();
    }

    public synchronized Map<String, String> snapshot() {
        return new TreeMap<>(names);
    }

    public synchronized void clear() {
        names.clear();
        save();
    }

    /** Merges another list in; its names win over existing ones. Returns how many it held. */
    public synchronized int importFrom(Path source) throws IOException {
        Map<String, String> incoming = new TreeMap<>();
        read(source, incoming);
        names.putAll(incoming);
        save();
        return incoming.size();
    }

    public synchronized void exportTo(Path target) throws IOException {
        write(target);
    }

    public Path file() {
        return file;
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            write(tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(Path target) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            out.write("# Mocktroid custom asset names: <asset GUID><TAB><name>\n");
            for (Map.Entry<String, String> e : names.entrySet()) {
                out.write(e.getKey() + "\t" + e.getValue() + "\n");
            }
        }
    }

    private static void read(Path source, Map<String, String> into) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            String name = clean(line.substring(tab + 1));
            if (!name.isEmpty()) {
                into.put(line.substring(0, tab).trim(), name);
            }
        }
    }

    /** Names are single-line: tabs/newlines would break the file format. */
    private static String clean(String name) {
        return name == null ? "" : name.replaceAll("[\\t\\r\\n]+", " ").strip();
    }
}
