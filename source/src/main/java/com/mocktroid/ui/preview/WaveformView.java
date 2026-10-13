package com.mocktroid.ui.preview;

import com.mocktroid.format.audio.WavPcmReader;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;

/**
 * Renders a static min/max amplitude-envelope waveform to a {@link Canvas},
 * with an optional green loop-start marker and a white playhead line that
 * can be moved cheaply (no redraw of the waveform itself) as playback
 * progresses.
 */
public final class WaveformView extends StackPane {

    private final Canvas canvas;
    private final Line loopLine;
    private final Line playheadLine;
    private double durationSeconds;
    private double loopStartSecondsPending = -1;
    private WavPcmReader.WavData lastDrawn;

    public WaveformView(double initialWidth, double height) {
        canvas = new Canvas(initialWidth, height);

        loopLine = new Line(0, 0, 0, height);
        loopLine.getStyleClass().add("waveform-loop-marker");
        loopLine.setVisible(false);
        loopLine.setMouseTransparent(true);

        playheadLine = new Line(0, 0, 0, height);
        playheadLine.getStyleClass().add("waveform-playhead");
        playheadLine.setVisible(false);
        playheadLine.setMouseTransparent(true);

        // Unmanaged so their startX/endX are real positions: as managed
        // StackPane children the lines were re-centred on every layout, which
        // pinned both the loop marker and the playhead to the middle.
        loopLine.setManaged(false);
        playheadLine.setManaged(false);
        StackPane.setAlignment(canvas, javafx.geometry.Pos.TOP_LEFT);

        setMinHeight(height);
        setMaxHeight(height);
        getChildren().addAll(canvas, loopLine, playheadLine);

        // Constructed before being added to the scene, so the real assigned
        // width isn't known yet -- redraw (cheap: one pass over the cached
        // samples) whenever the actual layout width changes.
        widthProperty().addListener((obs, oldW, newW) -> {
            if (newW.doubleValue() > 0 && lastDrawn != null) {
                canvas.setWidth(newW.doubleValue());
                draw(lastDrawn);
                if (loopStartSecondsPending >= 0) {
                    setLoopStartSeconds(loopStartSecondsPending);
                }
            }
        });
    }

    /** Downsamples the raw samples into one min/max bar per pixel column and
     *  draws them. Channels are mixed by taking the widest min/max span
     *  across channels within each bin (a cheap, visually reasonable stereo
     *  mixdown for a waveform overview -- not audio-accurate summing). */
    public void draw(WavPcmReader.WavData wav) {
        this.lastDrawn = wav;
        this.durationSeconds = wav.durationSeconds();
        // Decoding usually finishes after layout already settled, so the
        // width listener won't fire again -- size to the real width here.
        if (getWidth() > 0) {
            canvas.setWidth(getWidth());
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        gc.clearRect(0, 0, w, h);
        gc.setStroke(Color.web("#4fd8f0", 0.85));
        gc.setLineWidth(1);

        int channels = wav.channels();
        short[] samples = wav.samples();
        int perChannel = samples.length / channels;
        if (perChannel <= 0) {
            return;
        }

        int pixelWidth = (int) w;
        double mid = h / 2;
        for (int x = 0; x < pixelWidth; x++) {
            long startSample = (long) x * perChannel / pixelWidth;
            long endSample = (long) (x + 1) * perChannel / pixelWidth;
            if (endSample <= startSample) {
                endSample = startSample + 1;
            }
            short min = Short.MAX_VALUE;
            short max = Short.MIN_VALUE;
            for (long s = startSample; s < endSample && s < perChannel; s++) {
                for (int c = 0; c < channels; c++) {
                    int idx = (int) (s * channels + c);
                    if (idx >= samples.length) continue;
                    short v = samples[idx];
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
            }
            double y1 = mid - (max / 32768.0) * mid;
            double y2 = mid - (min / 32768.0) * mid;
            gc.strokeLine(x + 0.5, y1, x + 0.5, y2);
        }
    }

    public void setLoopStartSeconds(double seconds) {
        loopStartSecondsPending = seconds;
        if (durationSeconds <= 0 || seconds < 0) {
            loopLine.setVisible(false);
            return;
        }
        double x = (seconds / durationSeconds) * canvas.getWidth();
        loopLine.setStartX(x);
        loopLine.setEndX(x);
        loopLine.setVisible(true);
    }

    /** Called with the clicked / dragged time in seconds. */
    public void setOnSeek(java.util.function.DoubleConsumer onSeek) {
        javafx.event.EventHandler<javafx.scene.input.MouseEvent> h = e -> {
            if (durationSeconds > 0 && canvas.getWidth() > 0) {
                onSeek.accept(Math.max(0, Math.min(1, e.getX() / canvas.getWidth())) * durationSeconds);
            }
        };
        setOnMousePressed(h);
        setOnMouseDragged(h);
        setCursor(javafx.scene.Cursor.HAND);
    }

    public void setPlayheadSeconds(double seconds) {
        if (durationSeconds <= 0) {
            playheadLine.setVisible(false);
            return;
        }
        double x = (seconds / durationSeconds) * canvas.getWidth();
        playheadLine.setStartX(x);
        playheadLine.setEndX(x);
        playheadLine.setVisible(true);
    }

    public void hidePlayhead() {
        playheadLine.setVisible(false);
    }
}
