package com.mocktroid.ui.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.mocktroid.format.audio.VgmstreamClient;
import com.mocktroid.format.audio.WavPcmReader;
import com.mocktroid.format.pack.PackCatalog;
import com.mocktroid.library.PakAssetRef;
import com.mocktroid.model.AssetEntry;
import com.mocktroid.settings.AppSettings;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

/**
 * Extracts a CSMP asset from its pak, reads its loop metadata, decodes it
 * via {@link VgmstreamClient} (background thread, honoring
 * {@code AppSettings.soundLoopCount} for looping tracks), and plays it back
 * through a real transport bar (previous/play-pause/next + full-width seek)
 * with a waveform view showing a white playhead and, for looping tracks, a
 * green loop-start marker.
 * <p>
 * One instance is built per selected sound entry; {@link #dispose()} stops
 * playback and cleans up the decoded temp WAV -- {@code PreviewPane} calls
 * it whenever the selection changes.
 */
public final class SoundPreview {

    private final WaveformView waveform = new WaveformView(700, 200);
    private final HBox transportNode = new HBox(10);
    private final Button previousButton = new Button("\u23EE");
    private final Button playButton = new Button("\u25B7");
    private final Button nextButton = new Button("\u23ED");
    private final Slider seekSlider = new Slider(0, 1, 0);
    private final Label statusLabel = new Label("Loading\u2026");

    private MediaPlayer mediaPlayer;
    private Path tempWavFile;
    private volatile boolean disposed = false;
    private volatile boolean userSeeking = false;
    private double totalDurationSeconds = 0;

    public SoundPreview(AssetEntry entry, Supplier<AssetEntry> nextEntry, Supplier<AssetEntry> previousEntry,
                         Consumer<AssetEntry> navigateTo) {
        buildViewport();
        this.nextEntrySupplier = nextEntry;
        this.navigate = navigateTo;
        buildTransport(nextEntry, previousEntry, navigateTo);
        loadAsync(entry);
    }

    private void buildViewport() {
        waveform.getStyleClass().add("waveform-view");
        waveform.setOnSeek(seconds -> {
            if (mediaPlayer != null) {
                mediaPlayer.seek(Duration.seconds(seconds));
                waveform.setPlayheadSeconds(seconds);
                seekSlider.setValue(seconds);
            }
        });
    }

    private Supplier<AssetEntry> nextEntrySupplier;
    private Consumer<AssetEntry> navigate;

    private void buildTransport(Supplier<AssetEntry> nextEntry, Supplier<AssetEntry> previousEntry,
                                 Consumer<AssetEntry> navigateTo) {
        previousButton.getStyleClass().add("transport-button");
        previousButton.setOnAction(e -> {
            AssetEntry target = previousEntry.get();
            if (target != null) navigateTo.accept(target);
        });

        playButton.getStyleClass().add("transport-button");
        playButton.setDisable(true);
        playButton.setOnAction(e -> togglePlay());

        nextButton.getStyleClass().add("transport-button");
        nextButton.setOnAction(e -> {
            AssetEntry target = nextEntry.get();
            if (target != null) navigateTo.accept(target);
        });

        seekSlider.getStyleClass().add("transport-scrub");
        seekSlider.setDisable(true);
        seekSlider.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(seekSlider, Priority.ALWAYS);
        seekSlider.setOnMousePressed(e -> userSeeking = true);
        seekSlider.setOnMouseReleased(e -> {
            userSeeking = false;
            if (mediaPlayer != null) {
                mediaPlayer.seek(Duration.seconds(seekSlider.getValue()));
            }
        });

        statusLabel.getStyleClass().add("transport-status-label");
        // Long status messages (e.g. a missing vgmstream) truncate rather than squeezing the seek bar away.
        seekSlider.setMinWidth(160);
        statusLabel.setMinWidth(0);
        statusLabel.setMaxWidth(420);

        transportNode.setAlignment(Pos.CENTER_LEFT);
        transportNode.getChildren().addAll(previousButton, playButton, nextButton, seekSlider, statusLabel);
    }

    private void loadAsync(AssetEntry entry) {
        if (!(entry.sourceRef() instanceof PakAssetRef ref)) {
            showError("No source data for this entry");
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                byte[] raw = PackCatalog.extractDecompressed(ref.pakFile(), ref.asset());
                VgmstreamClient.Metadata meta = VgmstreamClient.getMetadata(raw);
                double loopCount = AppSettings.get().soundLoopCountProperty().get();
                Path wav = VgmstreamClient.decodeToWav(raw, meta.loops() ? loopCount : 1.0);
                if (disposed) {
                    Files.deleteIfExists(wav);
                    return;
                }
                WavPcmReader.WavData wavData = WavPcmReader.read(wav);
                if (disposed) {
                    Files.deleteIfExists(wav);
                    return;
                }
                Platform.runLater(() -> onDecoded(wav, wavData, meta));
            } catch (Exception e) {
                if (!disposed) {
                    String message = e.getMessage();
                    Platform.runLater(() -> showError(message));
                }
            }
        }, "sound-preview-decode");
        thread.setDaemon(true);
        thread.start();
    }

    private void onDecoded(Path wavFile, WavPcmReader.WavData wavData, VgmstreamClient.Metadata meta) {
        if (disposed) {
            try { Files.deleteIfExists(wavFile); } catch (IOException ignored) {}
            return;
        }
        this.tempWavFile = wavFile;
        this.totalDurationSeconds = wavData.durationSeconds();

        waveform.draw(wavData);
        if (meta.loops()) {
            waveform.setLoopStartSeconds(meta.loopStartSeconds());
        }

        try {
            Media media = new Media(wavFile.toUri().toString());
            mediaPlayer = new MediaPlayer(media);
            mediaPlayer.setOnReady(() -> {
                seekSlider.setMax(totalDurationSeconds);
                statusLabel.setText(formatDuration(0) + " / " + formatDuration(totalDurationSeconds));
                playButton.setDisable(false);
                seekSlider.setDisable(false);
                boolean autoplay = entryTypeIsSoundAutoplay() || PlayChain.take();
                if (autoplay) {
                    togglePlay();
                }
            });
            mediaPlayer.currentTimeProperty().addListener((obs, old, val) -> {
                double seconds = val.toSeconds();
                waveform.setPlayheadSeconds(seconds);
                if (!userSeeking) {
                    seekSlider.setValue(seconds);
                }
                statusLabel.setText(formatDuration(seconds) + " / " + formatDuration(totalDurationSeconds));
            });
            mediaPlayer.setOnEndOfMedia(() -> {
                playButton.setText("\u25B7");
                mediaPlayer.seek(Duration.ZERO);
                mediaPlayer.pause();
                waveform.setPlayheadSeconds(0);
                if (AppSettings.get().autoplayNextSoundProperty().get()) {
                    PlayChain.next(nextEntrySupplier, navigate);
                }
            });
            mediaPlayer.setOnError(() ->
                showError("Playback error: " + mediaPlayer.getError().getMessage()));
        } catch (Exception e) {
            showError("Playback error: " + e.getMessage());
        }
    }

    private boolean entryTypeIsSoundAutoplay() {
        return AppSettings.get().autoplaySoundProperty().get();
    }

    private void togglePlay() {
        com.mocktroid.ui.UiSounds.get().play(com.mocktroid.ui.UiSounds.Cue.PAUSE_RESUME);
        if (mediaPlayer == null) {
            return;
        }
        if (mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
            mediaPlayer.pause();
            playButton.setText("\u25B7");
        } else {
            mediaPlayer.play();
            playButton.setText("\u23F8");
        }
    }

    private void showError(String message) {
        statusLabel.setText(message != null && !message.isBlank() ? message : "Couldn't preview this sound");
    }

    private static String formatDuration(double seconds) {
        int s = (int) Math.round(Math.max(0, seconds));
        int h = s / 3600;
        int m = (s % 3600) / 60;
        int sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }

    public Node viewportNode() { return waveform; }
    public Node transportNode() { return transportNode; }

    public void dispose() {
        disposed = true;
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.dispose();
        }
        if (tempWavFile != null) {
            try { Files.deleteIfExists(tempWavFile); } catch (IOException ignored) {}
        }
    }
}
