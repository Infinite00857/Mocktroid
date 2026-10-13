package com.mocktroid.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import com.mocktroid.format.audio.VgmstreamClient;
import com.mocktroid.format.pack.PackCatalog;
import com.mocktroid.library.PakAssetRef;
import com.mocktroid.model.AssetLibrary;
import com.mocktroid.settings.AppSettings;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.input.MouseEvent;
import javafx.scene.media.AudioClip;

/**
 * Settings > Audio > Use game UI sounds: the game's own menu sounds on the app's interface. The
 * cue -> sound mapping comes from sounds the user identified (and renamed) in the Sounds tab.
 * Each cue is decoded once with vgmstream into an {@link AudioClip} (low latency, overlapping).
 */
public final class UiSounds {

    public enum Cue {
        OPEN_APP("644dfb1b-7ba6-4fa6-b1b2-c0a24942d815"),
        HOVER("14086ba7-80e3-4ec5-8953-0568f60bba3a"),
        OPEN_VIEWER("3527788e-7532-4122-b173-18c857c6fc25"),
        SELECT("2178f2b5-2214-41a7-846e-5b2cd9cebfa1"),
        TAB("990af14f-8da0-419a-8ef3-72d851eec497"),
        BACK("b215793d-a6b9-48ea-9f86-508ad0f0d64c"),
        CANCEL("5c9381c6-af58-4b7b-b83f-d78b51f95f62"),
        OPEN_LIST("41fe0575-e66e-457b-895a-4f579ad67d9b"),
        REFUSE("c30b0591-e114-407d-9aff-01b37ff82ed8"),
        PAUSE_RESUME("4a9ffa5d-ec0f-480e-ac42-5831e20b7eb2"),
        CONFIRM("18e2d757-0c1b-4017-8d09-ef0391cb0cd9"),
        CLOSE("8895227d-4cb5-4898-8c8f-de8beaad175a"),
        DRAG("aa30af2c-a457-48fc-8788-32f78b2ee90f");

        final UUID asset;

        Cue(String asset) {
            this.asset = UUID.fromString(asset);
        }
    }

    private static final UiSounds INSTANCE = new UiSounds();

    public static UiSounds get() {
        return INSTANCE;
    }

    private final Map<Cue, AudioClip> clips = new EnumMap<>(Cue.class);
    private AssetLibrary library;
    private boolean loading;
    private long lastHover;
    private final Map<Cue, Long> lastPlayed = new java.util.concurrent.ConcurrentHashMap<>();
    /** The app-open sound plays once, as soon as it's decoded. */
    private boolean greetPending = true;

    private UiSounds() {}

    /** Starts decoding the cues in the background (when the setting is on, now or later). */
    public void init(AssetLibrary library) {
        this.library = library;
        AppSettings.get().useGameUiSoundsProperty().addListener((obs, old, on) -> {
            if (on) {
                load();
            }
        });
        if (AppSettings.get().useGameUiSoundsProperty().get()) {
            load();
        }
    }

    private synchronized void load() {
        if (loading || library == null || !VgmstreamClient.isAvailable()) {
            return;
        }
        loading = true;
        Thread t = new Thread(() -> {
            for (Cue cue : Cue.values()) {
                PakAssetRef ref = library.lookup(cue.asset);
                if (ref == null) {
                    continue;
                }
                try {
                    Path wav = VgmstreamClient.decodeToWav(PackCatalog.extractDecompressed(ref.pakFile(), ref.asset()), 1.0);
                    AudioClip clip = new AudioClip(wav.toUri().toString());
                    wav.toFile().deleteOnExit();
                    Platform.runLater(() -> {
                        clips.put(cue, clip);
                        if (cue == Cue.OPEN_APP && greetPending) {
                            greetPending = false;
                            play(Cue.OPEN_APP);
                        }
                    });
                } catch (Exception e) {
                    // that cue stays silent
                }
            }
        }, "ui-sounds");
        t.setDaemon(true);
        t.start();
    }

    /** Plays a cue when game UI sounds are on (any thread). */
    public void play(Cue cue) {
        if (!AppSettings.get().useGameUiSoundsProperty().get() || AppSettings.get().mutedProperty().get()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastPlayed.get(cue);
        if (last != null && now - last < 50) {
            return; // the same cue from two hooks for one action
        }
        lastPlayed.put(cue, now);
        Runnable r = () -> {
            AudioClip clip = clips.get(cue);
            if (clip != null) {
                clip.play(AppSettings.get().masterVolumeProperty().get() * 0.8);
            }
        };
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    /**
     * Scene-wide hooks: any button click = select (toggle / transport buttons too), hovering a
     * card, row or tab = hover, scrolling a list = scroll (throttled).
     */
    public void install(Scene scene) {
        scene.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (e.getTarget() instanceof ButtonBase b && !b.getStyleClass().contains("transport-button")) {
                play(Cue.SELECT);
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_ENTERED_TARGET, e -> {
            if (e.getTarget() instanceof Node n && (n.getStyleClass().contains("asset-card") || n.getStyleClass().contains("category-tab")
                || n.getStyleClass().contains("asset-list-row"))) {
                long now = System.currentTimeMillis();
                if (now - lastHover > 60) {
                    lastHover = now;
                    play(Cue.HOVER);
                }
            }
        });
        // Drop-down menus (combo boxes, the Paks menu) opening.
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            for (Node p = e.getTarget() instanceof Node n ? n : null; p != null; p = p.getParent()) {
                if (p instanceof javafx.scene.control.ComboBoxBase<?> || p instanceof javafx.scene.control.MenuButton) {
                    play(Cue.OPEN_LIST);
                    return;
                }
            }
        });
    }


    /** True when the cue's sound exists in this romfs (used by the settings hint). */
    public static boolean available(AssetLibrary library) {
        return library != null && library.lookup(Cue.SELECT.asset) != null && VgmstreamClient.isAvailable();
    }

}
