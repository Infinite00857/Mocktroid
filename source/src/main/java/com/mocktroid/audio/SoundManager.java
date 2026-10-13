package com.mocktroid.audio;

import java.net.URL;
import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.mocktroid.settings.AppSettings;

import javafx.scene.media.AudioClip;

/**
 * Plays short UI feedback cues (hover, select, back, open, error).
 * <p>
 * Looks for {@code /sounds/<cue-name>.<ext>} on the classpath. If a file
 * hasn't been authored yet, the cue silently no-ops instead of throwing --
 * the app is fully usable with zero sound assets present. Drop a file in
 * {@code src/main/resources/sounds/} named after a {@link Cue}'s resource
 * name (e.g. {@code ui-hover.wav}) and it's picked up automatically the next
 * time that cue plays -- no code changes needed.
 */
public final class SoundManager {

    /** A named UI feedback moment. Add more here as the app grows. */
    public enum Cue {
        HOVER("ui-hover"),
        SELECT("ui-select"),
        BACK("ui-back"),
        OPEN("ui-open"),
        ERROR("ui-error");

        final String resourceName;
        Cue(String resourceName) { this.resourceName = resourceName; }
    }

    private static final String[] EXTENSIONS = {".wav", ".mp3", ".m4a"};
    private static final Logger LOG = Logger.getLogger(SoundManager.class.getName());

    private static final SoundManager INSTANCE = new SoundManager();
    public static SoundManager get() { return INSTANCE; }

    // Caches both successful loads and "we already checked, nothing there" misses,
    // so a missing cue only costs one classpath lookup for the whole run.
    private final Map<Cue, AudioClip> loaded = new EnumMap<>(Cue.class);
    private final Map<Cue, Boolean> attempted = new EnumMap<>(Cue.class);

    private SoundManager() {}

    public void play(Cue cue) {
        AppSettings settings = AppSettings.get();
        if (settings.useGameUiSoundsProperty().get()) {
            com.mocktroid.ui.UiSounds.get().play(switch (cue) {
                case HOVER -> com.mocktroid.ui.UiSounds.Cue.HOVER;
                case SELECT -> com.mocktroid.ui.UiSounds.Cue.SELECT;
                case BACK -> com.mocktroid.ui.UiSounds.Cue.BACK;
                case OPEN -> com.mocktroid.ui.UiSounds.Cue.OPEN_LIST;
                case ERROR -> com.mocktroid.ui.UiSounds.Cue.REFUSE;
            });
            return;
        }
        if (settings.mutedProperty().get()) {
            return;
        }
        AudioClip clip = loaded.get(cue);
        if (clip == null && !Boolean.TRUE.equals(attempted.get(cue))) {
            clip = tryLoad(cue);
            attempted.put(cue, true);
            if (clip != null) {
                loaded.put(cue, clip);
            }
        }
        if (clip == null) {
            return; // not authored yet -- expected until sound assets are added
        }
        clip.setVolume(settings.masterVolumeProperty().get());
        clip.play();
    }

    private AudioClip tryLoad(Cue cue) {
        for (String ext : EXTENSIONS) {
            URL url = SoundManager.class.getResource("/sounds/" + cue.resourceName + ext);
            if (url != null) {
                try {
                    return new AudioClip(url.toExternalForm());
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Found sound cue file but failed to load it: " + cue, e);
                    return null;
                }
            }
        }
        return null;
    }
}
