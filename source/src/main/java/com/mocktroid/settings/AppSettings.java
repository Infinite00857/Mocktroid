package com.mocktroid.settings;

import java.util.prefs.Preferences;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * User-configurable settings, persisted across launches via
 * {@link java.util.prefs.Preferences}.
 * <p>
 * Add new settings here as their own JavaFX property plus a load/save line in
 * the constructor -- every other module should read and write through these
 * properties rather than touching Preferences directly.
 */
public final class AppSettings {

    /** The model previewer's 3D render size; the image is scaled to fit the viewport. */
    public enum RenderResolution {
        LOW("Low (fast)", 1280, 720),
        MEDIUM("Medium", 1920, 1080),
        HIGH("High (sharp)", 2560, 1440),
        ULTRA("Ultra", 3840, 2160),
        CUSTOM("Custom", 0, 0);

        public final String label;
        public final int width;
        public final int height;

        RenderResolution(String label, int width, int height) {
            this.label = label;
            this.width = width;
            this.height = height;
        }

        @Override
        public String toString() {
            return this == CUSTOM ? label : label + "  \u00b7  " + width + "\u00d7" + height;
        }
    }

    /** Which presentation the asset browser uses for the currently selected category. */
    public enum BrowseMode { LIST, GRID }

    private static final String KEY_MUTED = "audio.muted";
    private static final String KEY_MASTER_VOLUME = "audio.masterVolume";
    private static final String KEY_RENDER_RESOLUTION = "preview.renderResolution";
    private static final String KEY_ROMFS_PATH = "library.romfsPath";
    private static final String KEY_ONBOARDING_COMPLETE = "app.onboardingComplete";
    private static final String KEY_BROWSE_MODE = "browse.mode";
    private static final String KEY_GRID_COLUMNS = "browse.gridColumns";
    private static final String KEY_THUMBNAIL_CACHE_BUILT = "cache.thumbnailsBuilt";
    private static final String KEY_USE_GAME_UI_SOUNDS = "audio.useGameUiSounds";
    private static final String KEY_SOUND_LOOP_COUNT = "audio.soundLoopCount";
    private static final String KEY_AUTOPLAY_SOUND = "audio.autoplaySound";
    private static final String KEY_AUTOPLAY_VIDEO = "audio.autoplayVideo";
    private static final String KEY_CUSTOM_SKYBOX_PATH = "preview.customSkyboxPath";
    private static final String KEY_VGMSTREAM_PATH = "audio.vgmstreamPath";
    private static final String KEY_TRANSPARENT_VISOR = "preview.transparentVisor";
    private static final String KEY_SHOW_FLOOR = "preview.showFloor";
    private static final String KEY_CUSTOM_RENDER_WIDTH = "preview.customRenderWidth";
    private static final String KEY_CUSTOM_RENDER_HEIGHT = "preview.customRenderHeight";
    private static final String KEY_AUTOPLAY_ANIMATIONS = "preview.autoplayAnimations";
    private static final String KEY_ANIMATION_FPS = "preview.animationFps";
    private static final String KEY_HIDE_VISORS = "preview.hideVisors";
    private static final String KEY_SMOOTH_TEXTURES = "preview.smoothTextures";
    private static final String KEY_CANNON_FROST = "preview.cannonFrost";
    private static final String KEY_NEXT_SOUND = "audio.autoplayNextSound";
    private static final String KEY_UPDATE_ROMFS = "updateRomfsPath";
    private static final String KEY_NEXT_VIDEO = "audio.autoplayNextVideo";
    private static final String KEY_NEXT_ANIMATION = "preview.autoplayNextAnimation";

    private static final AppSettings INSTANCE = new AppSettings();
    public static AppSettings get() { return INSTANCE; }

    private final Preferences prefs = Preferences.userNodeForPackage(AppSettings.class);

    private final BooleanProperty muted = new SimpleBooleanProperty(this, "muted", false);
    private final DoubleProperty masterVolume = new SimpleDoubleProperty(this, "masterVolume", 0.8);
    private final ObjectProperty<RenderResolution> renderResolution =
        new SimpleObjectProperty<>(this, "renderResolution", RenderResolution.MEDIUM);
    private final StringProperty romfsPath = new SimpleStringProperty(this, "romfsPath", "");
    /** Optional romfs of the game update (dumped with an emulator); its paks override the base game's. */
    private final StringProperty updateRomfsPath = new SimpleStringProperty(this, "updateRomfsPath", "");
    private final BooleanProperty onboardingComplete = new SimpleBooleanProperty(this, "onboardingComplete", false);
    private final ObjectProperty<BrowseMode> browseMode = new SimpleObjectProperty<>(this, "browseMode", BrowseMode.GRID);
    private final IntegerProperty gridColumns = new SimpleIntegerProperty(this, "gridColumns", 3);
    private final BooleanProperty thumbnailCacheBuilt = new SimpleBooleanProperty(this, "thumbnailCacheBuilt", false);
    /** Reserved for a future filename -> UI-cue mapping (e.g. "hover.csmp" =
     *  ui-hover). Does nothing yet -- just the toggle for now. */
    private final BooleanProperty useGameUiSounds = new SimpleBooleanProperty(this, "useGameUiSounds", false);
    /** vgmstream loop count for looping sounds (matches its -l flag); has no
     *  effect on sounds without loop points. */
    private final DoubleProperty soundLoopCount = new SimpleDoubleProperty(this, "soundLoopCount", 1.0);
    private final BooleanProperty autoplaySound = new SimpleBooleanProperty(this, "autoplaySound", false);
    private final BooleanProperty autoplayVideo = new SimpleBooleanProperty(this, "autoplayVideo", false);
    /** Path to a user-supplied skybox image for the model previewer. Empty = default/no skybox.
     *  Not consumed anywhere yet -- reserved for the model preview module. */
    private final StringProperty customSkyboxPath = new SimpleStringProperty(this, "customSkyboxPath", "");
    /** Explicit vgmstream-cli location (file or containing folder). Empty =
     *  auto-discover; see {@code VgmstreamClient}. */
    private final StringProperty vgmstreamPath = new SimpleStringProperty(this, "vgmstreamPath", "");
    /** Draw models' blended submeshes (Samus's visor, glass) see-through. Off by
     *  default: in game the visor is opaque. */
    /** A see-through grid floor under models in the previewer. */
    private final BooleanProperty showFloor = new SimpleBooleanProperty(this, "showFloor", true);
    private final IntegerProperty customRenderWidth = new SimpleIntegerProperty(this, "customRenderWidth", 1920);
    private final IntegerProperty customRenderHeight = new SimpleIntegerProperty(this, "customRenderHeight", 1080);
    private final BooleanProperty transparentVisor = new SimpleBooleanProperty(this, "transparentVisor", false);
    /** Start playing an animation clip as soon as it's selected. */
    private final BooleanProperty autoplayAnimations = new SimpleBooleanProperty(this, "autoplayAnimations", false);
    /** Frames per second animation clips play at (the clip's frame rate is an assumption). */
    private final IntegerProperty animationFps = new SimpleIntegerProperty(this, "animationFps", 60);
    /** Leave models' blended submeshes (Samus's visor, glass) out of the previewer. */
    private final BooleanProperty hideVisors = new SimpleBooleanProperty(this, "hideVisors", false);
    /** 2x smoothed textures in the model preview (rounds off texel stair steps up close; 4x memory). */
    private final BooleanProperty smoothTextures = new SimpleBooleanProperty(this, "smoothTextures", false);
    /** 0-100: how much of the arm cannon's frost layer (the snowy white on its barrel) shows. */
    private final IntegerProperty cannonFrost = new SimpleIntegerProperty(this, "cannonFrost", 0);
    /** When a sound / video / animation clip finishes, move to the next one in the list and play it. */
    private final BooleanProperty autoplayNextSound = new SimpleBooleanProperty(this, "autoplayNextSound", false);
    private final BooleanProperty autoplayNextVideo = new SimpleBooleanProperty(this, "autoplayNextVideo", false);
    private final BooleanProperty autoplayNextAnimation = new SimpleBooleanProperty(this, "autoplayNextAnimation", false);

    private AppSettings() {
        load();
        muted.addListener((obs, old, val) -> prefs.putBoolean(KEY_MUTED, val));
        masterVolume.addListener((obs, old, val) -> prefs.putDouble(KEY_MASTER_VOLUME, val.doubleValue()));
        renderResolution.addListener((obs, old, val) -> prefs.put(KEY_RENDER_RESOLUTION, val.name()));
        romfsPath.addListener((obs, old, val) -> prefs.put(KEY_ROMFS_PATH, val == null ? "" : val));
        updateRomfsPath.addListener((obs, old, val) -> prefs.put(KEY_UPDATE_ROMFS, val == null ? "" : val));
        onboardingComplete.addListener((obs, old, val) -> prefs.putBoolean(KEY_ONBOARDING_COMPLETE, val));
        browseMode.addListener((obs, old, val) -> prefs.put(KEY_BROWSE_MODE, val.name()));
        gridColumns.addListener((obs, old, val) -> prefs.putInt(KEY_GRID_COLUMNS, val.intValue()));
        thumbnailCacheBuilt.addListener((obs, old, val) -> prefs.putBoolean(KEY_THUMBNAIL_CACHE_BUILT, val));
        useGameUiSounds.addListener((obs, old, val) -> prefs.putBoolean(KEY_USE_GAME_UI_SOUNDS, val));
        soundLoopCount.addListener((obs, old, val) -> prefs.putDouble(KEY_SOUND_LOOP_COUNT, val.doubleValue()));
        autoplaySound.addListener((obs, old, val) -> prefs.putBoolean(KEY_AUTOPLAY_SOUND, val));
        autoplayVideo.addListener((obs, old, val) -> prefs.putBoolean(KEY_AUTOPLAY_VIDEO, val));
        customSkyboxPath.addListener((obs, old, val) -> prefs.put(KEY_CUSTOM_SKYBOX_PATH, val == null ? "" : val));
        vgmstreamPath.addListener((obs, old, val) -> prefs.put(KEY_VGMSTREAM_PATH, val == null ? "" : val));
        transparentVisor.addListener((obs, old, val) -> prefs.putBoolean(KEY_TRANSPARENT_VISOR, val));
        showFloor.addListener((obs, old, val) -> prefs.putBoolean(KEY_SHOW_FLOOR, val));
        customRenderWidth.addListener((obs, old, val) -> prefs.putInt(KEY_CUSTOM_RENDER_WIDTH, val.intValue()));
        customRenderHeight.addListener((obs, old, val) -> prefs.putInt(KEY_CUSTOM_RENDER_HEIGHT, val.intValue()));
        autoplayAnimations.addListener((obs, old, val) -> prefs.putBoolean(KEY_AUTOPLAY_ANIMATIONS, val));
        animationFps.addListener((obs, old, val) -> prefs.putInt(KEY_ANIMATION_FPS, val.intValue()));
        hideVisors.addListener((obs, old, val) -> prefs.putBoolean(KEY_HIDE_VISORS, val));
        smoothTextures.addListener((obs, old, val) -> prefs.putBoolean(KEY_SMOOTH_TEXTURES, val));
        cannonFrost.addListener((obs, old, val) -> prefs.putInt(KEY_CANNON_FROST, val.intValue()));
        autoplayNextSound.addListener((obs, old, val) -> prefs.putBoolean(KEY_NEXT_SOUND, val));
        autoplayNextVideo.addListener((obs, old, val) -> prefs.putBoolean(KEY_NEXT_VIDEO, val));
        autoplayNextAnimation.addListener((obs, old, val) -> prefs.putBoolean(KEY_NEXT_ANIMATION, val));
    }

    private void load() {
        muted.set(prefs.getBoolean(KEY_MUTED, false));
        masterVolume.set(prefs.getDouble(KEY_MASTER_VOLUME, 0.8));
        renderResolution.set(enumOrDefault(RenderResolution.class,
            prefs.get(KEY_RENDER_RESOLUTION, RenderResolution.MEDIUM.name()), RenderResolution.MEDIUM));
        romfsPath.set(prefs.get(KEY_ROMFS_PATH, ""));
        updateRomfsPath.set(prefs.get(KEY_UPDATE_ROMFS, ""));
        onboardingComplete.set(prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false));
        browseMode.set(enumOrDefault(BrowseMode.class,
            prefs.get(KEY_BROWSE_MODE, BrowseMode.GRID.name()), BrowseMode.GRID));
        gridColumns.set(clamp(prefs.getInt(KEY_GRID_COLUMNS, 3), 1, 8));
        thumbnailCacheBuilt.set(prefs.getBoolean(KEY_THUMBNAIL_CACHE_BUILT, false));
        useGameUiSounds.set(prefs.getBoolean(KEY_USE_GAME_UI_SOUNDS, false));
        soundLoopCount.set(clampD(prefs.getDouble(KEY_SOUND_LOOP_COUNT, 1.0), 1.0, 20.0));
        autoplaySound.set(prefs.getBoolean(KEY_AUTOPLAY_SOUND, false));
        autoplayVideo.set(prefs.getBoolean(KEY_AUTOPLAY_VIDEO, false));
        customSkyboxPath.set(prefs.get(KEY_CUSTOM_SKYBOX_PATH, ""));
        vgmstreamPath.set(prefs.get(KEY_VGMSTREAM_PATH, ""));
        transparentVisor.set(prefs.getBoolean(KEY_TRANSPARENT_VISOR, false));
        showFloor.set(prefs.getBoolean(KEY_SHOW_FLOOR, true));
        customRenderWidth.set(clamp(prefs.getInt(KEY_CUSTOM_RENDER_WIDTH, 1920), MIN_RENDER, MAX_RENDER));
        customRenderHeight.set(clamp(prefs.getInt(KEY_CUSTOM_RENDER_HEIGHT, 1080), MIN_RENDER, MAX_RENDER));
        autoplayAnimations.set(prefs.getBoolean(KEY_AUTOPLAY_ANIMATIONS, false));
        animationFps.set(clamp(prefs.getInt(KEY_ANIMATION_FPS, 60), 1, 120));
        hideVisors.set(prefs.getBoolean(KEY_HIDE_VISORS, false));
        smoothTextures.set(prefs.getBoolean(KEY_SMOOTH_TEXTURES, false));
        cannonFrost.set(clamp(prefs.getInt(KEY_CANNON_FROST, 0), 0, 100));
        autoplayNextSound.set(prefs.getBoolean(KEY_NEXT_SOUND, false));
        autoplayNextVideo.set(prefs.getBoolean(KEY_NEXT_VIDEO, false));
        autoplayNextAnimation.set(prefs.getBoolean(KEY_NEXT_ANIMATION, false));
    }

    private static <E extends Enum<E>> E enumOrDefault(Class<E> type, String stored, E fallback) {
        try {
            return Enum.valueOf(type, stored);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clampD(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Matches the Settings screen's "Restore All Defaults" action. Does not
     *  touch romfsPath/onboardingComplete -- those aren't display prefs. */
    public void restoreDefaults() {
        muted.set(false);
        masterVolume.set(0.8);
        renderResolution.set(RenderResolution.MEDIUM);
        browseMode.set(BrowseMode.GRID);
        gridColumns.set(3);
        soundLoopCount.set(1.0);
        autoplaySound.set(false);
        autoplayVideo.set(false);
        customSkyboxPath.set("");
        transparentVisor.set(false);
        autoplayAnimations.set(false);
        showFloor.set(true);
        animationFps.set(60);
        hideVisors.set(false);
        smoothTextures.set(false);
        cannonFrost.set(0);
        autoplayNextSound.set(false);
        autoplayNextVideo.set(false);
        autoplayNextAnimation.set(false);
    }

    public BooleanProperty mutedProperty() { return muted; }
    public DoubleProperty masterVolumeProperty() { return masterVolume; }
    public ObjectProperty<RenderResolution> renderResolutionProperty() { return renderResolution; }
    public StringProperty romfsPathProperty() { return romfsPath; }
    public StringProperty updateRomfsPathProperty() { return updateRomfsPath; }
    public BooleanProperty onboardingCompleteProperty() { return onboardingComplete; }
    public ObjectProperty<BrowseMode> browseModeProperty() { return browseMode; }
    public IntegerProperty gridColumnsProperty() { return gridColumns; }
    public BooleanProperty thumbnailCacheBuiltProperty() { return thumbnailCacheBuilt; }
    public BooleanProperty useGameUiSoundsProperty() { return useGameUiSounds; }
    public DoubleProperty soundLoopCountProperty() { return soundLoopCount; }
    public BooleanProperty autoplaySoundProperty() { return autoplaySound; }
    public BooleanProperty autoplayVideoProperty() { return autoplayVideo; }
    public StringProperty customSkyboxPathProperty() { return customSkyboxPath; }
    public StringProperty vgmstreamPathProperty() { return vgmstreamPath; }
    public BooleanProperty transparentVisorProperty() { return transparentVisor; }
    public BooleanProperty showFloorProperty() { return showFloor; }
    public IntegerProperty customRenderWidthProperty() { return customRenderWidth; }
    public IntegerProperty customRenderHeightProperty() { return customRenderHeight; }

    public static final int MIN_RENDER = 64;
    public static final int MAX_RENDER = 7680;

    /** {width, height} of the model previewer's render (custom size when Custom is picked). */
    public int[] renderSize() {
        RenderResolution r = renderResolution.get();
        if (r == null || r == RenderResolution.CUSTOM) {
            return new int[] {clamp(customRenderWidth.get(), MIN_RENDER, MAX_RENDER), clamp(customRenderHeight.get(), MIN_RENDER, MAX_RENDER)};
        }
        return new int[] {r.width, r.height};
    }
    public BooleanProperty autoplayAnimationsProperty() { return autoplayAnimations; }
    public IntegerProperty animationFpsProperty() { return animationFps; }
    public BooleanProperty hideVisorsProperty() { return hideVisors; }
    public BooleanProperty smoothTexturesProperty() { return smoothTextures; }
    public IntegerProperty cannonFrostProperty() { return cannonFrost; }
    public BooleanProperty autoplayNextSoundProperty() { return autoplayNextSound; }
    public BooleanProperty autoplayNextVideoProperty() { return autoplayNextVideo; }
    public BooleanProperty autoplayNextAnimationProperty() { return autoplayNextAnimation; }
}
