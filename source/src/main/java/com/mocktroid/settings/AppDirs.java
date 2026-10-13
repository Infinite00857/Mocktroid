package com.mocktroid.settings;

import java.nio.file.Path;

/** Per-OS locations for Mocktroid's own on-disk data (caches etc.). */
public final class AppDirs {
    private AppDirs() {}

    /** {@code ~/Library/Application Support/Mocktroid} on macOS,
     *  {@code %APPDATA%\Mocktroid} on Windows, {@code ~/.mocktroid} elsewhere. */
    public static Path dataDir() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "Mocktroid");
        }
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return appData != null ? Path.of(appData, "Mocktroid") : Path.of(home, "Mocktroid");
        }
        return Path.of(home, ".mocktroid");
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
