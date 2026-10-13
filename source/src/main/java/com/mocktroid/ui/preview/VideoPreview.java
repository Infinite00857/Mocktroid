package com.mocktroid.ui.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.mocktroid.format.pack.PackCatalog;
import com.mocktroid.library.PakAssetRef;
import com.mocktroid.model.AssetEntry;
import com.mocktroid.settings.AppSettings;
import com.mocktroid.ui.LoadingSpinner;

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
import javafx.scene.media.MediaView;
import javafx.util.Duration;

/**
 * FMV0 playback: {@link com.mocktroid.format.video.FmvExtractor} turns the
 * resource into an MP4 (H.264 + AAC) that JavaFX's own media stack plays. Transport: previous / play-pause / next, a full-width seek bar and
 * the time; honours Settings > Autoplay videos.
 */
public final class VideoPreview {

    private final StackPane viewport = new StackPane();
    private final MediaView mediaView = new MediaView();
    private final HBox transportNode = new HBox(10);
    private final Button previousButton = new Button("⏮");
    private final Button playButton = new Button("▷");
    private final Button nextButton = new Button("⏭");
    private final Slider seekSlider = new Slider(0, 1, 0);
    private final Label statusLabel = new Label("Loading…");

    private MediaPlayer mediaPlayer;
    private Path tempFile;
    private volatile boolean disposed;
    private boolean userSeeking;
    private double totalSeconds;
    private int retries;

    public VideoPreview(AssetEntry entry, Supplier<AssetEntry> nextEntry, Supplier<AssetEntry> previousEntry,
                        Consumer<AssetEntry> navigateTo) {
        viewport.getStyleClass().add("video-viewport");
        viewport.setMinSize(0, 0);
        mediaView.setPreserveRatio(true);
        mediaView.fitWidthProperty().bind(viewport.widthProperty());
        mediaView.fitHeightProperty().bind(viewport.heightProperty());
        viewport.getChildren().setAll(new LoadingSpinner(56));
        viewport.setOnMouseClicked(e -> togglePlay());
        this.nextEntrySupplier = nextEntry;
        this.navigate = navigateTo;
        buildTransport(nextEntry, previousEntry, navigateTo);
        loadAsync(entry);
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
        seekSlider.setMinWidth(160);
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
                if (com.mocktroid.format.video.FmvExtractor.mp4Start(raw) < 0) {
                    Platform.runLater(() -> statusLabel.setText("Converting WebM video with ffmpeg…"));
                }
                Path file = com.mocktroid.format.video.FmvExtractor.toPlayableMp4(raw, 0);
                if (disposed) {
                    Files.deleteIfExists(file);
                    return;
                }
                Platform.runLater(() -> onExtracted(file));
            } catch (Exception e) {
                if (!disposed) {
                    String message = e.getMessage();
                    Platform.runLater(() -> showError(message));
                }
            }
        }, "video-preview-extract");
        thread.setDaemon(true);
        thread.start();
    }

    private void onExtracted(Path file) {
        if (disposed) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) {}
            return;
        }
        tempFile = file;
        try {
            mediaPlayer = new MediaPlayer(new Media(file.toUri().toString()));
        } catch (Exception e) {
            showError("Playback error: " + e.getMessage());
            return;
        }
        mediaView.setMediaPlayer(mediaPlayer);
        mediaPlayer.setOnReady(() -> {
            totalSeconds = mediaPlayer.getTotalDuration().toSeconds();
            seekSlider.setMax(Math.max(0.01, totalSeconds));
            viewport.getChildren().setAll(mediaView);
            playButton.setDisable(false);
            seekSlider.setDisable(false);
            int w = mediaPlayer.getMedia().getWidth(), h = mediaPlayer.getMedia().getHeight();
            statusLabel.setText(time(0) + " / " + time(totalSeconds) + (w > 0 ? "  ·  " + w + "×" + h : ""));
            if (AppSettings.get().autoplayVideoProperty().get() || PlayChain.take()) {
                togglePlay();
            }
        });
        mediaPlayer.currentTimeProperty().addListener((obs, old, val) -> {
            double s = val.toSeconds();
            if (!userSeeking) {
                seekSlider.setValue(s);
            }
            int w = mediaPlayer.getMedia().getWidth(), h = mediaPlayer.getMedia().getHeight();
            statusLabel.setText(time(s) + " / " + time(totalSeconds) + (w > 0 ? "  ·  " + w + "×" + h : ""));
        });
        mediaPlayer.setOnEndOfMedia(() -> {
            playButton.setText("▷");
            mediaPlayer.seek(Duration.ZERO);
            mediaPlayer.pause();
            AssetIconCache.get().setPaused(false);
            if (AppSettings.get().autoplayNextVideoProperty().get()) {
                PlayChain.next(nextEntrySupplier, navigate);
            }
        });
        mediaPlayer.setOnError(() -> {
            // Windows' H.264 decoder can refuse to initialise while another player is still
            // setting up (ERROR_MEDIA_INVALID) -- the same file plays on a retry.
            if (retries < 3 && !disposed) {
                retries++;
                MediaPlayer failed = mediaPlayer;
                mediaPlayer = null;
                mediaView.setMediaPlayer(null);
                failed.dispose();
                statusLabel.setText("Retrying\u2026");
                javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(Duration.millis(500));
                wait.setOnFinished(e -> onExtracted(file));
                wait.play();
                return;
            }
            showError("Playback error: " + mediaPlayer.getError().getMessage());
        });
    }

    /** Saves the frame on screen as this video's grid icon. */
    public boolean cacheIcon(AssetEntry entry) {
        if (mediaPlayer == null || disposed || !viewport.getChildren().contains(mediaView)) {
            return false;
        }
        javafx.scene.image.WritableImage frame = mediaView.snapshot(null, null);
        AssetIconCache.get().store(entry, frame, false);
        return true;
    }

    private void togglePlay() {
        com.mocktroid.ui.UiSounds.get().play(com.mocktroid.ui.UiSounds.Cue.PAUSE_RESUME);
        if (mediaPlayer == null || playButton.isDisabled()) {
            return;
        }
        if (mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
            mediaPlayer.pause();
            playButton.setText("▷");
            AssetIconCache.get().setPaused(false);
        } else {
            mediaPlayer.play();
            playButton.setText("⏸");
            AssetIconCache.get().setPaused(true);
        }
    }

    private void showError(String message) {
        statusLabel.setText(message != null && !message.isBlank() ? message : "Couldn't play this video");
        Label label = new Label("Couldn't play this video");
        label.getStyleClass().add("preview-not-ready");
        viewport.getChildren().setAll(label);
    }

    private static String time(double seconds) {
        int s = (int) Math.floor(Math.max(0, seconds));
        return String.format("%d:%02d", s / 60, s % 60);
    }

    public Node viewportNode() { return viewport; }
    public Node transportNode() { return transportNode; }

    public void dispose() {
        disposed = true;
        AssetIconCache.get().setPaused(false);
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.dispose();
        }
        if (tempFile != null) {
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
        }
    }
}
