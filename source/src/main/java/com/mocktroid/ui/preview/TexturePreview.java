package com.mocktroid.ui.preview;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mocktroid.format.image.PngWriter;
import com.mocktroid.format.texture.Texture;
import com.mocktroid.format.texture.TextureDecoder;
import com.mocktroid.format.texture.TextureFormat;
import com.mocktroid.format.texture.TextureHeader;
import com.mocktroid.library.TextureLoader;
import com.mocktroid.model.AssetEntry;
import com.mocktroid.ui.LoadingSpinner;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundImage;
import javafx.scene.layout.BackgroundPosition;
import javafx.scene.layout.BackgroundRepeat;
import javafx.scene.layout.BackgroundSize;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;

/**
 * Texture viewer: extracts + decodes a TXTR in the background, shows it
 * fitted to the viewport over a transparency checkerboard, with mouse-wheel
 * zoom (nearest-neighbour once magnified, so texels stay crisp), drag to pan
 * and double-click to reset.
 * <p>
 * The bottom bar holds the texture controls: mip level, array layer / cube
 * face, 3D slice, channel isolation, HDR exposure (BC6H/float formats only)
 * and PNG export of the current slice.
 */
public final class TexturePreview {

    private enum Channel {
        RGBA("RGBA"), RGB("RGB"), R("R"), G("G"), B("B"), A("A");
        final String label;
        Channel(String label) { this.label = label; }
    }

    private static final String[] CUBE_FACES = {"+X", "-X", "+Y", "-Y", "+Z", "-Z"};

    private final AssetEntry entry;
    private final StackPane viewport = new StackPane();
    private final ImageView imageView = new ImageView();
    private final VBox controls = new VBox(8);
    private final HBox controlRow = new HBox(10);
    private final Label infoLabel = new Label("Loading\u2026");
    private final Button mipDown = new Button("\u25C2");
    private final Button mipUp = new Button("\u25B8");
    private final Label mipLabel = new Label();
    private final ComboBox<String> layerBox = new ComboBox<>();
    private final ComboBox<String> sliceBox = new ComboBox<>();
    private final Slider exposure = new Slider(-6, 6, 0);
    private final Button exportButton = new Button("Export PNG\u2026");

    // Flipbook playback for 2D array textures (each layer is one frame).
    private static final int FLIPBOOK_AUTOPLAY_MIN_FRAMES = 8;
    private final HBox flipRow = new HBox(10);
    private final Button flipPlay = new Button("\u25B7");
    private final Slider flipSlider = new Slider(0, 1, 0);
    private final Label flipLabel = new Label();
    private final ComboBox<Integer> fpsBox = new ComboBox<>();
    private final javafx.animation.Timeline flipTimer = new javafx.animation.Timeline();
    private final Map<Integer, TextureDecoder.Image> frames = new java.util.concurrent.ConcurrentHashMap<>();
    private int framesKey = -1;
    private boolean suppressLayerDecode;

    private Texture texture;
    private int mip = 0;
    private Channel channel = Channel.RGBA;
    private TextureDecoder.Image decoded;
    private int decodeGeneration = 0;
    private volatile boolean disposed = false;

    private double zoom = 1;
    private double dragX;
    private double dragY;

    public TexturePreview(AssetEntry entry) {
        this.entry = entry;
        buildViewport();
        buildControls();
        loadAsync();
    }

    public Node viewportNode() { return viewport; }
    public Node transportNode() { return controls; }

    public void dispose() {
        disposed = true;
        flipTimer.stop();
    }

    // ---- viewport ------------------------------------------------------------

    private void buildViewport() {
        viewport.getStyleClass().add("texture-viewport");
        viewport.setBackground(checkerboard());
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(viewport.widthProperty());
        clip.heightProperty().bind(viewport.heightProperty());
        viewport.setClip(clip);
        viewport.setMinSize(0, 0);

        imageView.setPreserveRatio(true);
        imageView.setManaged(false);
        viewport.getChildren().setAll(new LoadingSpinner(56));

        viewport.widthProperty().addListener((o, a, b) -> layoutImage());
        viewport.heightProperty().addListener((o, a, b) -> layoutImage());

        viewport.setOnScroll(e -> {
            if (decoded == null || e.getDeltaY() == 0) {
                return;
            }
            double factor = e.getDeltaY() > 0 ? 1.25 : 0.8;
            double newZoom = Math.max(0.25, Math.min(64, zoom * factor));
            // Zoom about the cursor: keep the point under it stationary.
            double cx = e.getX() - viewport.getWidth() / 2 - imageView.getTranslateX();
            double cy = e.getY() - viewport.getHeight() / 2 - imageView.getTranslateY();
            double k = newZoom / zoom;
            imageView.setTranslateX(imageView.getTranslateX() - cx * (k - 1));
            imageView.setTranslateY(imageView.getTranslateY() - cy * (k - 1));
            zoom = newZoom;
            layoutImage();
            e.consume();
        });
        viewport.setOnMousePressed(e -> {
            dragX = e.getSceneX() - imageView.getTranslateX();
            dragY = e.getSceneY() - imageView.getTranslateY();
        });
        viewport.setOnMouseDragged(e -> {
            imageView.setTranslateX(e.getSceneX() - dragX);
            imageView.setTranslateY(e.getSceneY() - dragY);
        });
        viewport.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                resetView();
            }
        });
    }

    private void resetView() {
        zoom = 1;
        imageView.setTranslateX(0);
        imageView.setTranslateY(0);
        layoutImage();
    }

    /** Fits the image to the viewport at zoom 1, scaled by {@link #zoom}, centred. */
    private void layoutImage() {
        if (decoded == null) {
            return;
        }
        double vw = viewport.getWidth();
        double vh = viewport.getHeight();
        if (vw <= 0 || vh <= 0) {
            return;
        }
        double fit = Math.min(vw / decoded.width(), vh / decoded.height());
        double scale = fit * zoom;
        double w = decoded.width() * scale;
        double h = decoded.height() * scale;
        imageView.setFitWidth(w);
        imageView.setFitHeight(h);
        imageView.setSmooth(scale < 1);
        imageView.relocate((vw - w) / 2, (vh - h) / 2);
    }

    private static Background checkerboard() {
        int cell = 8;
        WritableImage tile = new WritableImage(cell * 2, cell * 2);
        for (int y = 0; y < cell * 2; y++) {
            for (int x = 0; x < cell * 2; x++) {
                boolean dark = ((x / cell) + (y / cell)) % 2 == 0;
                tile.getPixelWriter().setArgb(x, y, dark ? 0xFF0B1820 : 0xFF13242E);
            }
        }
        return new Background(new BackgroundImage(tile, BackgroundRepeat.REPEAT, BackgroundRepeat.REPEAT,
            BackgroundPosition.DEFAULT, BackgroundSize.DEFAULT));
    }

    // ---- controls ------------------------------------------------------------

    private void buildControls() {
        infoLabel.getStyleClass().add("transport-status-label");

        for (Button b : new Button[]{mipDown, mipUp}) {
            b.getStyleClass().add("transport-button");
            b.setDisable(true);
        }
        mipDown.setTooltip(new Tooltip("Larger mip"));
        mipUp.setTooltip(new Tooltip("Smaller mip"));
        mipDown.setOnAction(e -> setMip(mip - 1));
        mipUp.setOnAction(e -> setMip(mip + 1));
        mipLabel.getStyleClass().add("transport-label");
        mipLabel.setMinWidth(Region.USE_PREF_SIZE);

        layerBox.getStyleClass().add("settings-combo");
        sliceBox.getStyleClass().add("settings-combo");
        for (ComboBox<String> box : List.of(layerBox, sliceBox)) {
            box.setVisible(false);
            box.setManaged(false);
            box.valueProperty().addListener((o, a, b) -> {
                if (!suppressLayerDecode) {
                    decodeCurrent();
                }
            });
        }

        ToggleGroup channels = new ToggleGroup();
        HBox channelRow = new HBox(2);
        for (Channel c : Channel.values()) {
            ToggleButton t = new ToggleButton(c.label);
            t.getStyleClass().add("channel-toggle");
            t.setToggleGroup(channels);
            t.setSelected(c == Channel.RGBA);
            t.setOnAction(e -> {
                if (!t.isSelected()) {
                    t.setSelected(true); // keep exactly one selected
                }
                channel = c;
                showDecoded();
            });
            channelRow.getChildren().add(t);
        }

        exposure.getStyleClass().add("transport-scrub");
        exposure.setPrefWidth(110);
        exposure.setTooltip(new Tooltip("HDR exposure (stops)"));
        exposure.setVisible(false);
        exposure.setManaged(false);
        exposure.valueChangingProperty().addListener((o, was, is) -> {
            if (!is) {
                decodeCurrent();
            }
        });
        exposure.setOnMouseReleased(e -> decodeCurrent());

        exportButton.getStyleClass().add("settings-secondary-button");
        exportButton.setDisable(true);
        exportButton.setOnAction(e -> exportPng());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        controlRow.setAlignment(Pos.CENTER_LEFT);
        controlRow.getChildren().addAll(mipDown, mipLabel, mipUp, layerBox, sliceBox, channelRow,
            exposure, spacer, exportButton);
        controls.getChildren().addAll(infoLabel, controlRow);
        HBox.setHgrow(controls, Priority.ALWAYS);
        buildFlipbookRow();
    }

    private void buildFlipbookRow() {
        flipPlay.getStyleClass().add("transport-button");
        flipPlay.setTooltip(new Tooltip("Play the layers as an animation"));
        flipPlay.setOnAction(e -> toggleFlipbook());
        flipSlider.getStyleClass().add("transport-scrub");
        flipSlider.setMaxWidth(Double.MAX_VALUE);
        flipSlider.setMinWidth(160);
        flipSlider.setBlockIncrement(1);
        flipSlider.setSnapToTicks(true);
        flipSlider.setMajorTickUnit(1);
        flipSlider.setMinorTickCount(0);
        HBox.setHgrow(flipSlider, Priority.ALWAYS);
        flipSlider.valueProperty().addListener((o, a, b) -> {
            int f = (int) Math.round(b.doubleValue());
            if (texture != null && f != Math.max(0, layerBox.getSelectionModel().getSelectedIndex())) {
                showFrame(f);
            }
        });
        flipLabel.getStyleClass().add("transport-status-label");
        flipLabel.setMinWidth(Region.USE_PREF_SIZE);
        fpsBox.getStyleClass().add("settings-combo");
        fpsBox.getItems().setAll(10, 15, 24, 30, 60);
        fpsBox.setValue(30);
        fpsBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Integer v) { return v == null ? "" : v + " fps"; }
            @Override public Integer fromString(String s) { return Integer.valueOf(s.replace(" fps", "").trim()); }
        });
        fpsBox.valueProperty().addListener((o, a, b) -> restartTimer());
        Label title = new Label("flipbook");
        title.getStyleClass().add("transport-label");
        flipRow.setAlignment(Pos.CENTER_LEFT);
        flipRow.getChildren().addAll(title, flipPlay, flipSlider, flipLabel, fpsBox);
        flipRow.setVisible(false);
        flipRow.setManaged(false);
        flipTimer.setCycleCount(javafx.animation.Animation.INDEFINITE);
        restartTimer();
        controls.getChildren().add(flipRow);
    }

    private void restartTimer() {
        boolean running = flipTimer.getStatus() == javafx.animation.Animation.Status.RUNNING;
        flipTimer.stop();
        int fps = fpsBox.getValue() == null ? 30 : fpsBox.getValue();
        flipTimer.getKeyFrames().setAll(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1.0 / fps), e -> {
            int n = texture == null ? 1 : texture.header().layerCount();
            int next = (Math.max(0, layerBox.getSelectionModel().getSelectedIndex()) + 1) % Math.max(1, n);
            flipSlider.setValue(next);
        }));
        if (running) {
            flipTimer.play();
        }
    }

    private void toggleFlipbook() {
        if (flipTimer.getStatus() == javafx.animation.Animation.Status.RUNNING) {
            flipTimer.pause();
            flipPlay.setText("\u25B7");
        } else {
            prefetchFrames();
            flipTimer.play();
            flipPlay.setText("\u23F8");
        }
    }

    /** Key for the decoded-frame cache: frames are only valid for one mip / exposure. */
    private int frameKey() {
        return mip * 1000 + (int) Math.round(exposure.getValue() * 10) + 500;
    }

    /** Decodes every layer at the current mip in the background so playback runs from memory. */
    private void prefetchFrames() {
        if (texture == null) {
            return;
        }
        int key = frameKey();
        if (key != framesKey) {
            frames.clear();
            framesKey = key;
        }
        Texture t = texture;
        int m = mip;
        float exp = (float) Math.pow(2, exposure.getValue());
        int n = t.header().layerCount();
        Thread thread = new Thread(() -> {
            for (int i = 0; i < n && !disposed && framesKey == key; i++) {
                if (!frames.containsKey(i)) {
                    try {
                        frames.put(i, TextureDecoder.decode(t, i, m, 0, exp));
                    } catch (Exception | OutOfMemoryError e) {
                        return;
                    }
                }
            }
        }, "texture-flipbook");
        thread.setDaemon(true);
        thread.start();
    }

    /** Shows layer {@code f}: from the frame cache when decoded, else via a normal decode. */
    private void showFrame(int f) {
        suppressLayerDecode = true;
        layerBox.getSelectionModel().select(f);
        suppressLayerDecode = false;
        flipLabel.setText("frame " + (f + 1) + " / " + texture.header().layerCount());
        TextureDecoder.Image img = framesKey == frameKey() ? frames.get(f) : null;
        if (img != null) {
            decodeGeneration++;
            decoded = img;
            showDecoded();
        } else {
            decodeCurrent();
        }
    }

    private void loadAsync() {
        Thread thread = new Thread(() -> {
            try {
                Texture t = TextureLoader.load(entry);
                if (!disposed) {
                    Platform.runLater(() -> onLoaded(t));
                }
            } catch (Exception | OutOfMemoryError e) {
                if (!disposed) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                    Platform.runLater(() -> showError(msg));
                }
            }
        }, "texture-load");
        thread.setDaemon(true);
        thread.start();
    }

    private void onLoaded(Texture t) {
        if (disposed) {
            return;
        }
        this.texture = t;
        TextureHeader h = t.header();
        TextureFormat f = h.format();
        String dims = h.width() + "\u00D7" + h.height() + (h.is3d() ? "\u00D7" + h.depth() : "");
        String layers = h.layerCount() > 1 ? "  \u00B7  " + h.layerCount() + (h.kind() == TextureHeader.Kind.CUBE ? " faces" : " layers") : "";
        infoLabel.setText(f.label + "  \u00B7  " + h.kind().label + "  \u00B7  " + dims
            + "  \u00B7  " + h.mipCount() + (h.mipCount() == 1 ? " mip" : " mips") + layers);

        if (h.layerCount() > 1) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < h.layerCount(); i++) {
                boolean cube = h.kind() == TextureHeader.Kind.CUBE || h.kind() == TextureHeader.Kind.CUBE_ARRAY;
                names.add(cube ? "Face " + CUBE_FACES[i % 6] + (i >= 6 ? " #" + (i / 6) : "") : "Layer " + i);
            }
            layerBox.getItems().setAll(names);
            layerBox.getSelectionModel().select(0);
            layerBox.setVisible(true);
            layerBox.setManaged(true);
        }
        boolean flipbook = h.kind() == TextureHeader.Kind.D2_ARRAY && h.layerCount() > 1;
        flipRow.setVisible(flipbook);
        flipRow.setManaged(flipbook);
        if (flipbook) {
            flipSlider.setMax(h.layerCount() - 1);
            flipSlider.setValue(0);
            flipLabel.setText("frame 1 / " + h.layerCount());
        }
        boolean hdr = f == TextureFormat.BC6H_UFLOAT || f == TextureFormat.BC6H_SFLOAT
            || f == TextureFormat.RGBA16_FLOAT || f == TextureFormat.RGBA32_FLOAT
            || f == TextureFormat.RG11B10_FLOAT || f == TextureFormat.R16_FLOAT;
        exposure.setVisible(hdr);
        exposure.setManaged(hdr);
        exportButton.setDisable(false);
        exportButton.setText(flipbook ? "Export GIF / WebP\u2026" : "Export PNG\u2026");
        viewport.getChildren().setAll(imageView);
        setMip(0);
        if (flipbook && h.layerCount() >= FLIPBOOK_AUTOPLAY_MIN_FRAMES
            && com.mocktroid.settings.AppSettings.get().autoplayAnimationsProperty().get()) {
            toggleFlipbook();
        }
    }

    private void setMip(int newMip) {
        if (texture == null) {
            return;
        }
        TextureHeader h = texture.header();
        mip = Math.max(0, Math.min(h.mipCount() - 1, newMip));
        mipDown.setDisable(mip == 0);
        mipUp.setDisable(mip == h.mipCount() - 1);
        mipLabel.setText("mip " + mip + "  " + h.mipWidth(mip) + "\u00D7" + h.mipHeight(mip));

        int depth = h.mipDepth(mip);
        if (depth > 1) {
            List<String> slices = new ArrayList<>();
            for (int i = 0; i < depth; i++) {
                slices.add("Slice " + i);
            }
            int keep = Math.min(Math.max(0, sliceBox.getSelectionModel().getSelectedIndex()), depth - 1);
            sliceBox.getItems().setAll(slices);
            sliceBox.getSelectionModel().select(keep);
        }
        sliceBox.setVisible(depth > 1);
        sliceBox.setManaged(depth > 1);
        decodeCurrent();
    }

    private void decodeCurrent() {
        if (texture == null) {
            return;
        }
        int gen = ++decodeGeneration;
        int layer = Math.max(0, layerBox.getSelectionModel().getSelectedIndex());
        int slice = Math.max(0, sliceBox.getSelectionModel().getSelectedIndex());
        int m = mip;
        float exp = (float) Math.pow(2, exposure.getValue());
        Texture t = texture;
        Thread thread = new Thread(() -> {
            try {
                TextureDecoder.Image img = TextureDecoder.decode(t, layer, m, slice, exp);
                Platform.runLater(() -> {
                    if (!disposed && gen == decodeGeneration) {
                        boolean sizeChanged = decoded == null
                            || decoded.width() != img.width() || decoded.height() != img.height();
                        decoded = img;
                        showDecoded();
                        if (sizeChanged) {
                            resetView();
                        }
                    }
                });
            } catch (Exception | OutOfMemoryError e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                Platform.runLater(() -> {
                    if (!disposed && gen == decodeGeneration) {
                        showError(msg);
                    }
                });
            }
        }, "texture-decode");
        thread.setDaemon(true);
        thread.start();
    }

    private void showDecoded() {
        if (decoded == null) {
            return;
        }
        int[] src = decoded.argb();
        int[] shown = channel == Channel.RGBA ? src : new int[src.length];
        if (channel != Channel.RGBA) {
            for (int i = 0; i < src.length; i++) {
                int p = src[i];
                shown[i] = switch (channel) {
                    case RGB -> p | 0xFF000000;
                    case R -> gray((p >> 16) & 0xFF);
                    case G -> gray((p >> 8) & 0xFF);
                    case B -> gray(p & 0xFF);
                    case A -> gray(p >>> 24);
                    default -> p;
                };
            }
        }
        WritableImage image = new WritableImage(decoded.width(), decoded.height());
        image.getPixelWriter().setPixels(0, 0, decoded.width(), decoded.height(),
            PixelFormat.getIntArgbInstance(), shown, 0, decoded.width());
        imageView.setImage(image);
        if (!viewport.getChildren().contains(imageView)) {
            viewport.getChildren().setAll(imageView);
        }
        layoutImage();
    }

    private static int gray(int v) {
        return 0xFF000000 | (v << 16) | (v << 8) | v;
    }

    private void showError(String message) {
        infoLabel.setText(message);
        Label label = new Label("Couldn't decode this texture");
        label.getStyleClass().add("preview-not-ready");
        viewport.getChildren().setAll(label);
    }

    private void exportPng() {
        if (decoded == null || viewport.getScene() == null) {
            return;
        }
        if (texture.header().kind() == TextureHeader.Kind.D2_ARRAY && texture.header().layerCount() > 1) {
            exportAnimation();
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export texture as PNG");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
        String suffix = mip > 0 ? "_mip" + mip : "";
        if (texture.header().layerCount() > 1) {
            suffix += "_layer" + Math.max(0, layerBox.getSelectionModel().getSelectedIndex());
        }
        chooser.setInitialFileName(entry.displayName().replaceAll("[\\\\/:*?\"<>|]", "_") + suffix + ".png");
        File file = chooser.showSaveDialog(viewport.getScene().getWindow());
        if (file == null) {
            return;
        }
        TextureDecoder.Image img = decoded;
        try {
            PngWriter.write(file.toPath(), img.width(), img.height(), img.argb());
            infoLabel.setText("Exported " + file.getName());
        } catch (IOException ex) {
            infoLabel.setText("Export failed: " + ex.getMessage());
        }
    }

    /** Flipbooks: every frame at the current mip, as an animated GIF or (with ffmpeg) WebP, at the chosen fps. */
    private void exportAnimation() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export animation");
        FileChooser.ExtensionFilter gif = new FileChooser.ExtensionFilter("Animated GIF", "*.gif");
        chooser.getExtensionFilters().add(gif);
        boolean ffmpeg = com.mocktroid.export.MediaExporter.ffmpegAvailable();
        if (ffmpeg) {
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Animated WebP", "*.webp"));
        }
        chooser.setInitialFileName(entry.displayName().replaceAll("[^A-Za-z0-9 _.()-]", "_") + ".gif");
        File file = chooser.showSaveDialog(viewport.getScene().getWindow());
        if (file == null) {
            return;
        }
        boolean webp = file.getName().toLowerCase().endsWith(".webp")
            || (chooser.getSelectedExtensionFilter() != null && chooser.getSelectedExtensionFilter() != gif);
        java.nio.file.Path target = webp && !file.getName().toLowerCase().endsWith(".webp")
            ? file.toPath().resolveSibling(file.getName() + ".webp") : file.toPath();
        Texture t = texture;
        int m = mip;
        float exp = (float) Math.pow(2, exposure.getValue());
        double fps = fpsBox.getValue() == null ? 30 : fpsBox.getValue();
        infoLabel.setText("Exporting " + t.header().layerCount() + " frames\u2026");
        Thread thread = new Thread(() -> {
            try {
                java.util.List<int[]> list = new java.util.ArrayList<>();
                int w = 0, h = 0;
                for (int i = 0; i < t.header().layerCount(); i++) {
                    TextureDecoder.Image img = TextureDecoder.decode(t, i, m, 0, exp);
                    w = img.width();
                    h = img.height();
                    list.add(img.argb());
                }
                if (webp) {
                    com.mocktroid.export.MediaExporter.exportWebp(list, w, h, fps, target);
                } else {
                    com.mocktroid.export.MediaExporter.exportGif(list, w, h, fps, target);
                }
                Platform.runLater(() -> infoLabel.setText("Exported " + target.getFileName()));
            } catch (Exception | OutOfMemoryError ex) {
                Platform.runLater(() -> infoLabel.setText("Export failed: " + ex.getMessage()));
            }
        }, "texture-export");
        thread.setDaemon(true);
        thread.start();
    }
}
