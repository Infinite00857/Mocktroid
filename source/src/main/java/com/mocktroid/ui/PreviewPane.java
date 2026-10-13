package com.mocktroid.ui;

import com.mocktroid.model.AssetEntry;
import com.mocktroid.model.AssetLibrary;
import com.mocktroid.model.AssetType;
import com.mocktroid.ui.preview.ModelPreview;
import com.mocktroid.ui.preview.SoundPreview;
import com.mocktroid.ui.preview.TexturePreview;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Line;

/**
 * The always-visible preview pane on the right: "Previewing: X" header, a
 * viewport with decorative targeting-reticle corner brackets (square by
 * default; sound switches it wide to fit a waveform), a fullscreen toggle,
 * and a bottom transport bar whose shape depends on the asset type.
 * <p>
 * Drag-to-resize via the corner brackets was prototyped and shelved -- the
 * handle's outer StackPane had no size cap, so it silently stretched to
 * cover the whole viewport and all four handles ended up overlapping
 * (explains both the crosshair-everywhere cursor and the broken resizing).
 * Worth revisiting with a properly capped hit-area, but not blocking the
 * first preview module, so it's removed rather than shipped half-working.
 * <p>
 * The viewport shows the actual preview once a format module exists for a
 * given {@link AssetType} (wire it in via {@link #setViewportContent}); until
 * then every type shows the same "not wired up yet" placeholder.
 */
public final class PreviewPane extends VBox {

    private static final double DEFAULT_SIZE = 380;
    private static final double WIDE_HEIGHT = 220;

    private final Label titleLabel = new Label();
    private final Label metaLabel = new Label();
    private final StackPane viewport = new StackPane();
    private final StackPane viewportArea = new StackPane();
    private final HBox transportBar = new HBox();
    private Runnable currentDisposer = () -> {};
    private AssetLibrary library;
    private ModelPreview currentModel;
    private AssetEntry currentEntry;
    private final HBox titleRow = new HBox(8);
    private final Button cacheIconButton = new Button("Cache icon");
    private final Button fullscreenToggle = new Button("\u26F6");
    private final Button exportButton = new Button("Export\u2026");
    /** Extra buttons the current preview provides (the model camera toggles). */
    private final HBox previewTools = new HBox(6);
    private boolean fullscreen;
    private java.util.function.Consumer<Boolean> onFullscreen = on -> {};
    /** What the "Cache icon" button captures for the current preview (false = nothing to capture). */
    private java.util.function.BooleanSupplier iconCapture = () -> false;
    private final javafx.scene.control.TextField renameField = new javafx.scene.control.TextField();
    private final Button renameButton = new Button("✎");
    private final Button resetNameButton = new Button("↺");
    private boolean renaming;
    private java.util.function.Function<AssetEntry, AssetEntry> nextEntryFn = e -> null;
    private java.util.function.Function<AssetEntry, AssetEntry> previousEntryFn = e -> null;
    private java.util.function.Consumer<AssetEntry> navigateTo = e -> {};

    public PreviewPane() {
        getStyleClass().add("preview-pane");
        setPadding(new Insets(20, 24, 20, 20));
        setSpacing(14);

        titleLabel.getStyleClass().add("preview-title");
        metaLabel.getStyleClass().add("preview-meta");
        buildRenameControls();
        VBox header = new VBox(4, titleRow, metaLabel);
        header.getStyleClass().add("preview-header");

        viewport.getStyleClass().add("preview-viewport");
        setSquare();

        StackPane framed = withCornerBrackets(viewport);
        // Hug the viewport (rather than filling the whole area) so the reticle
        // brackets sit on the viewport's own corners, as in the mockups.
        framed.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        fullscreenToggle.getStyleClass().add("preview-fullscreen-toggle");
        fullscreenToggle.setTooltip(new javafx.scene.control.Tooltip("Fullscreen preview (Esc to leave)"));
        fullscreenToggle.setOnAction(e -> setFullscreen(!fullscreen));

        cacheIconButton.getStyleClass().add("preview-cache-icon-button");
        cacheIconButton.setTooltip(new javafx.scene.control.Tooltip("Use the current view as this asset's grid icon"));
        cacheIconButton.setOnAction(e -> {
            if (iconCapture.getAsBoolean()) {
                cacheIconButton.setText("\u2713 Icon cached");
                javafx.animation.PauseTransition reset = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
                reset.setOnFinished(ev -> cacheIconButton.setText("Cache icon"));
                reset.play();
            }
        });
        // The preview's buttons sit in a row above the viewport, never over what's being shown.
        Region toolSpacer = new Region();
        HBox.setHgrow(toolSpacer, Priority.ALWAYS);
        previewTools.setAlignment(Pos.CENTER_LEFT);
        exportButton.getStyleClass().add("preview-cache-icon-button");
        exportButton.setTooltip(new javafx.scene.control.Tooltip("Save this asset as a common file format"));
        exportButton.setOnAction(e -> exportCurrent());
        HBox toolRow = new HBox(8, cacheIconButton, exportButton, previewTools, toolSpacer, fullscreenToggle);
        toolRow.setAlignment(Pos.CENTER_LEFT);
        toolRow.getStyleClass().add("preview-tool-row");
        toolRow.prefWidthProperty().bind(framed.widthProperty());
        toolRow.maxWidthProperty().bind(framed.widthProperty());
        VBox column = new VBox(6, toolRow, framed);
        column.setAlignment(Pos.CENTER);
        column.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        viewportArea.getChildren().setAll(column);
        viewportArea.setAlignment(Pos.CENTER);
        VBox.setVgrow(viewportArea, Priority.ALWAYS);

        transportBar.getStyleClass().add("transport-bar");
        transportBar.setVisible(false);
        transportBar.setManaged(false);

        getChildren().addAll(header, viewportArea, transportBar);

        showEmpty();
    }

    private void buildRenameControls() {
        renameButton.getStyleClass().add("preview-rename-button");
        renameButton.setTooltip(new javafx.scene.control.Tooltip("Rename (F2) -- only in Mocktroid, the game files aren't touched"));
        renameButton.setOnAction(e -> beginRename());
        resetNameButton.getStyleClass().add("preview-rename-button");
        resetNameButton.setTooltip(new javafx.scene.control.Tooltip("Restore the original name"));
        resetNameButton.setOnAction(e -> {
            if (currentEntry != null && library != null) {
                library.rename(currentEntry, null);
                updateTitle();
            }
        });
        renameField.getStyleClass().add("preview-rename-field");
        HBox.setHgrow(renameField, Priority.ALWAYS);
        renameField.setOnAction(e -> commitRename());
        renameField.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                endRename();
                e.consume();
            }
        });
        renameField.focusedProperty().addListener((obs, was, is) -> {
            if (!is && renaming) {
                commitRename();
            }
        });
        titleLabel.setMinWidth(0);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.getChildren().setAll(titleLabel);
    }

    /** Starts editing the current asset's name in place (the pencil button, F2, or the context menu). */
    public void beginRename() {
        if (currentEntry == null || library == null) {
            return;
        }
        renaming = true;
        renameField.setText(currentEntry.displayName());
        renameField.setPromptText(currentEntry.originalName());
        titleRow.getChildren().setAll(renameField);
        renameField.requestFocus();
        renameField.selectAll();
    }

    private void commitRename() {
        if (!renaming) {
            return;
        }
        String text = renameField.getText() == null ? "" : renameField.getText().strip();
        // Typing the original name back (or clearing it) drops the custom name.
        library.rename(currentEntry, text.equals(currentEntry.originalName()) ? null : text);
        endRename();
    }

    private void endRename() {
        renaming = false;
        updateTitle();
    }

    private void updateTitle() {
        if (currentEntry == null) {
            titleRow.getChildren().setAll(titleLabel);
            titleLabel.setText("");
            return;
        }
        titleLabel.setText("Previewing: " + currentEntry.displayName());
        titleLabel.setTooltip(currentEntry.hasCustomName()
            ? new javafx.scene.control.Tooltip("Original name: " + currentEntry.originalName()) : null);
        titleRow.getChildren().setAll(titleLabel, renameButton);
        if (currentEntry.hasCustomName()) {
            titleRow.getChildren().add(resetNameButton);
        }
    }

    /** RootLayout's handler: hides everything but the preview and puts the window in fullscreen. */
    public void setOnFullscreen(java.util.function.Consumer<Boolean> handler) {
        this.onFullscreen = handler;
    }

    public void setFullscreen(boolean on) {
        if (fullscreen == on) {
            return;
        }
        fullscreen = on;
        fullscreenToggle.setText(on ? "\u2715" : "\u26F6");
        fullscreenToggle.getTooltip().setText(on ? "Leave fullscreen (Esc)" : "Fullscreen preview (Esc to leave)");
        onFullscreen.accept(on);
    }

    private Runnable onClipFinished = () -> {};
    private java.util.function.IntConsumer onStepClip = d -> {};

    public void setOnStepClip(java.util.function.IntConsumer c) {
        onStepClip = c;
    }

    /** What "play the next animation" does (RootLayout: select the next clip in the list). */
    public void setOnClipFinished(Runnable r) {
        onClipFinished = r;
    }

    /** Export: models as OBJ / glTF, sounds as WAV (+ FLAC / OGG / MP3 with ffmpeg), videos as MP4. */
    private void exportCurrent() {
        AssetEntry entry = currentEntry;
        if (entry == null || !(entry.sourceRef() instanceof com.mocktroid.library.PakAssetRef ref) || getScene() == null) {
            return;
        }
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Export " + entry.displayName());
        String base = entry.displayName().replaceAll("[^A-Za-z0-9 _.()-]", "_");
        java.util.Map<javafx.stage.FileChooser.ExtensionFilter, String> extensions = new java.util.LinkedHashMap<>();
        if (entry.type() == AssetType.MODEL) {
            for (com.mocktroid.export.ModelExporter.Format f : com.mocktroid.export.ModelExporter.Format.values()) {
                extensions.put(new javafx.stage.FileChooser.ExtensionFilter(f.label, "*." + f.extension), f.extension);
            }
        } else if (entry.type() == AssetType.SOUND) {
            for (com.mocktroid.export.MediaExporter.SoundFormat f : com.mocktroid.export.MediaExporter.soundFormats()) {
                extensions.put(new javafx.stage.FileChooser.ExtensionFilter(f.label, "*." + f.extension), f.extension);
            }
        } else {
            extensions.put(new javafx.stage.FileChooser.ExtensionFilter("MP4 video (*.mp4)", "*.mp4"), "mp4");
        }
        chooser.getExtensionFilters().addAll(extensions.keySet());
        chooser.setInitialFileName(base + "." + extensions.values().iterator().next());
        java.io.File file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        String ext = extensions.getOrDefault(chooser.getSelectedExtensionFilter(), extensions.values().iterator().next());
        String name = file.getName().toLowerCase();
        java.nio.file.Path target = name.endsWith("." + ext) ? file.toPath() : file.toPath().resolveSibling(file.getName() + "." + ext);
        ModelPreview model = currentModel;
        exportButton.setText("Exporting\u2026");
        exportButton.setDisable(true);
        Thread thread = new Thread(() -> {
            String result;
            try {
                byte[] raw = com.mocktroid.format.pack.PackCatalog.extractDecompressed(ref.pakFile(), ref.asset());
                if (entry.type() == AssetType.MODEL) {
                    com.mocktroid.library.ModelLoader.LoadedModel loaded = model != null && model.loadedModel() != null
                        ? model.loadedModel() : com.mocktroid.library.ModelLoader.load(entry, library);
                    com.mocktroid.export.ModelExporter.export(loaded, target,
                        ext.equals("obj") ? com.mocktroid.export.ModelExporter.Format.OBJ : com.mocktroid.export.ModelExporter.Format.GLTF);
                } else if (entry.type() == AssetType.SOUND) {
                    com.mocktroid.export.MediaExporter.SoundFormat format = java.util.Arrays.stream(
                        com.mocktroid.export.MediaExporter.SoundFormat.values()).filter(f -> f.extension.equals(ext)).findFirst().orElseThrow();
                    com.mocktroid.export.MediaExporter.exportSound(raw, 1.0, target, format);
                } else {
                    java.nio.file.Path mp4 = com.mocktroid.format.video.FmvExtractor.toPlayableMp4(raw, 0);
                    java.nio.file.Files.move(mp4, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                result = "\u2713 Exported";
            } catch (Exception | OutOfMemoryError ex) {
                result = "Export failed";
                String msg = ex.getMessage();
                javafx.application.Platform.runLater(() -> exportButton.setTooltip(new javafx.scene.control.Tooltip(
                    "Last export failed: " + msg)));
            }
            String text = result;
            javafx.application.Platform.runLater(() -> {
                exportButton.setText(text);
                exportButton.setDisable(false);
                javafx.animation.PauseTransition reset = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
                reset.setOnFinished(ev -> exportButton.setText("Export\u2026"));
                reset.play();
            });
        }, "asset-export");
        thread.setDaemon(true);
        thread.start();
    }

    /** The asset currently shown, or null. */
    public AssetEntry currentEntry() {
        return currentEntry;
    }

    /** Animation mode: shows a clip in the current model preview's transport. */
    public void showClip(com.mocktroid.library.AnimationSupport.ClipRef clip) {
        if (currentModel != null) {
            currentModel.showClip(clip);
        }
    }

    /** Gives previews that follow cross-asset references (models -> materials
     *  -> textures) access to the romfs-wide GUID index. */
    public void setLibrary(AssetLibrary library) {
        this.library = library;
        // Renames (and derived names arriving) retitle the open preview.
        library.namesRevisionProperty().addListener((obs, old, val) -> {
            if (!renaming) {
                updateTitle();
            }
        });
    }

    /** Lets RootLayout wire in "what's next/previous in the current browse
     *  list" and "actually select that entry" -- used by SoundPreview's
     *  next/previous buttons. */
    public void setNavigation(java.util.function.Function<AssetEntry, AssetEntry> nextEntryFn,
                               java.util.function.Function<AssetEntry, AssetEntry> previousEntryFn,
                               java.util.function.Consumer<AssetEntry> navigateTo) {
        this.nextEntryFn = nextEntryFn;
        this.previousEntryFn = previousEntryFn;
        this.navigateTo = navigateTo;
    }

    private void unbindViewportSize() {
        viewport.prefWidthProperty().unbind();
        viewport.prefHeightProperty().unbind();
        viewport.maxWidthProperty().unbind();
        viewport.maxHeightProperty().unbind();
    }

    private void setSquare() {
        unbindViewportSize();
        viewport.setPrefSize(DEFAULT_SIZE, DEFAULT_SIZE);
        viewport.setMinSize(200, 200);
        viewport.setMaxSize(DEFAULT_SIZE, DEFAULT_SIZE);
    }

    private void setWide() {
        unbindViewportSize();
        viewport.setMinSize(320, WIDE_HEIGHT);
        viewport.setMaxSize(Double.MAX_VALUE, WIDE_HEIGHT);
        viewport.setPrefHeight(WIDE_HEIGHT);
        viewport.prefWidthProperty().bind(viewportArea.widthProperty().subtract(24));
    }

    /** Largest square that fits the available area -- for textures, where
     *  every extra pixel of viewport is useful. */
    private void setFill() {
        unbindViewportSize();
        DoubleBinding side = Bindings.createDoubleBinding(
            () -> Math.max(200, Math.min(viewportArea.getWidth(), viewportArea.getHeight()) - 24),
            viewportArea.widthProperty(), viewportArea.heightProperty());
        viewport.setMinSize(200, 200);
        viewport.prefWidthProperty().bind(side);
        viewport.prefHeightProperty().bind(side);
        viewport.maxWidthProperty().bind(side);
        viewport.maxHeightProperty().bind(side);
    }

    /** Largest rectangle of the given width/height ratio that fits the available area (models: 16:9). */
    private void setAspect(double ratio) {
        unbindViewportSize();
        DoubleBinding width = Bindings.createDoubleBinding(() -> {
            double w = viewportArea.getWidth() - 24, h = viewportArea.getHeight() - 24;
            return Math.max(320, Math.min(w, h * ratio));
        }, viewportArea.widthProperty(), viewportArea.heightProperty());
        DoubleBinding height = width.divide(ratio);
        viewport.setMinSize(320, 320 / ratio);
        viewport.prefWidthProperty().bind(width);
        viewport.prefHeightProperty().bind(height);
        viewport.maxWidthProperty().bind(width);
        viewport.maxHeightProperty().bind(height);
    }

    /** Decorative-only targeting-reticle corner brackets (no interaction --
     *  see class doc for why drag-resize was pulled). */
    private StackPane withCornerBrackets(Region content) {
        StackPane framed = new StackPane(content);
        // The brackets sit just outside the viewport, in this padding.
        framed.setPadding(new Insets(9));
        for (Pos corner : new Pos[]{Pos.TOP_LEFT, Pos.TOP_RIGHT, Pos.BOTTOM_LEFT, Pos.BOTTOM_RIGHT}) {
            var bracket = bracket(corner);
            StackPane.setAlignment(bracket, corner);
            bracket.setMouseTransparent(true);
            framed.getChildren().add(bracket);
        }
        return framed;
    }

    private javafx.scene.Group bracket(Pos corner) {
        double len = 16;
        boolean left = corner == Pos.TOP_LEFT || corner == Pos.BOTTOM_LEFT;
        boolean top = corner == Pos.TOP_LEFT || corner == Pos.TOP_RIGHT;

        Line horizontal = new Line(0, 0, left ? len : -len, 0);
        Line vertical = new Line(0, 0, 0, top ? len : -len);
        horizontal.getStyleClass().add("viewport-bracket");
        vertical.getStyleClass().add("viewport-bracket");

        javafx.scene.Group group = new javafx.scene.Group(horizontal, vertical);
        group.setTranslateX(0);
        group.setTranslateY(0);
        return group;
    }

    public void showEmpty() {
        previewTools.getChildren().clear();
        exportButton.setVisible(false);
        exportButton.setManaged(false);
        cacheIconButton.setVisible(false);
        cacheIconButton.setManaged(false);
        currentDisposer.run();
        currentDisposer = () -> {};
        setSquare();
        currentEntry = null;
        renaming = false;
        updateTitle();
        metaLabel.setText("");
        transportBar.setVisible(false);
        transportBar.setManaged(false);
        VBox box = new VBox(GameIcons.screwAttackPlaceholder(90));
        box.setAlignment(Pos.CENTER);
        viewport.getChildren().setAll(box);
    }

    public void showEntry(AssetEntry entry) {
        currentDisposer.run();
        currentDisposer = () -> {};
        currentModel = null;
        previewTools.getChildren().clear();
        boolean model = entry.type() == AssetType.MODEL && !entry.id().startsWith("CHPR:");
        boolean iconable = model || entry.type() == AssetType.VIDEO;
        boolean exportable = model || entry.type() == AssetType.VIDEO || entry.type() == AssetType.SOUND;
        exportButton.setVisible(exportable);
        exportButton.setManaged(exportable);
        cacheIconButton.setVisible(iconable);
        cacheIconButton.setManaged(iconable);
        iconCapture = () -> false;
        cacheIconButton.setText("Cache icon");

        currentEntry = entry;
        renaming = false;
        updateTitle();
        metaLabel.setText(entry.type().displayName.replaceAll("s$", "")
            + (entry.sourcePakName() != null ? "  \u00b7  " + entry.sourcePakName() : ""));

        if (entry.type() == AssetType.SOUND) {
            setWide();
            SoundPreview preview = new SoundPreview(entry,
                () -> nextEntryFn.apply(entry), () -> previousEntryFn.apply(entry), navigateTo);
            viewport.getChildren().setAll(preview.viewportNode());
            setTransport(preview.transportNode());
            setTransportVisible(true);
            currentDisposer = preview::dispose;
            return;
        }

        if (entry.type() == AssetType.VIDEO) {
            setAspect(16.0 / 9.0);
            com.mocktroid.ui.preview.VideoPreview preview = new com.mocktroid.ui.preview.VideoPreview(entry,
                () -> nextEntryFn.apply(entry), () -> previousEntryFn.apply(entry), navigateTo);
            viewport.getChildren().setAll(preview.viewportNode());
            setTransport(preview.transportNode());
            setTransportVisible(true);
            currentDisposer = preview::dispose;
            iconCapture = () -> preview.cacheIcon(entry);
            return;
        }

        if (entry.type() == AssetType.MODEL && !entry.id().startsWith("CHPR:")) {
            setAspect(16.0 / 9.0);
            ModelPreview preview = new ModelPreview(entry, library);
            currentModel = preview;
            preview.setOnClipFinished(() -> onClipFinished.run());
            preview.setOnStepClip(d -> onStepClip.accept(d));
            iconCapture = preview::cacheIcon;
            previewTools.getChildren().setAll(preview.toolsNode());
            viewport.getChildren().setAll(preview.viewportNode());
            setTransport(preview.transportNode());
            setTransportVisible(true);
            currentDisposer = preview::dispose;
            return;
        }
        if (entry.id().startsWith("CHPR:")) {
            setSquare();
            VBox box = new VBox(14, GameIcons.screwAttackPlaceholder(70),
                styledHint("Characters (CHPR) aren't supported yet -- they bundle skeleton, skins and events"));
            box.setAlignment(Pos.CENTER);
            viewport.getChildren().setAll(box);
            setTransportVisible(false);
            return;
        }

        if (entry.type() == AssetType.TEXTURE) {
            setFill();
            TexturePreview preview = new TexturePreview(entry);
            viewport.getChildren().setAll(preview.viewportNode());
            setTransport(preview.transportNode());
            setTransportVisible(true);
            currentDisposer = preview::dispose;
            return;
        }

        setSquare();
        VBox box = new VBox(14, GameIcons.screwAttackPlaceholder(70),
            styledHint("Preview for " + entry.type().displayName + " isn't wired up yet"));
        box.setAlignment(Pos.CENTER);
        viewport.getChildren().setAll(box);

        configureTransportBar(entry.type());
    }

    private Label styledHint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("preview-not-ready");
        return label;
    }

    private void configureTransportBar(AssetType type) {
        transportBar.getChildren().clear();
        switch (type) {
            case MODEL -> {
                transportBar.getChildren().add(buildAnimationTransport());
                setTransportVisible(true);
            }
            case VIDEO -> {
                transportBar.getChildren().add(buildMediaTransport());
                setTransportVisible(true);
            }
            default -> setTransportVisible(false);
        }
    }

    /** Installs a preview's transport controls, stretched across the whole bar. */
    private void setTransport(javafx.scene.Node node) {
        HBox.setHgrow(node, Priority.ALWAYS);
        if (node instanceof Region r) {
            r.setMaxWidth(Double.MAX_VALUE);
        }
        transportBar.getChildren().setAll(node);
    }

    private void setTransportVisible(boolean visible) {
        transportBar.setVisible(visible);
        transportBar.setManaged(visible);
    }

    private HBox buildAnimationTransport() {
        Label label = new Label("animation playback");
        label.getStyleClass().add("transport-label");
        Button stop = new Button("\u25C6");
        Button play = new Button("\u25B7");
        stop.getStyleClass().add("transport-button");
        play.getStyleClass().add("transport-button");
        Slider scrub = new Slider(0, 1, 0);
        scrub.getStyleClass().add("transport-scrub");
        scrub.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(scrub, Priority.ALWAYS);
        // No real animation data yet -- disabled until the model module exists.
        stop.setDisable(true);
        play.setDisable(true);
        scrub.setDisable(true);
        HBox bar = new HBox(12, label, stop, play, scrub);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private HBox buildMediaTransport() {
        Button play = new Button("\u25B7");
        play.getStyleClass().add("transport-button");
        Slider seek = new Slider(0, 1, 0);
        seek.getStyleClass().add("transport-scrub");
        seek.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(seek, Priority.ALWAYS);
        play.setDisable(true);
        seek.setDisable(true);
        HBox bar = new HBox(12, play, seek);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    /** Lets a future preview module (texture/video/model/audio) install its own
     *  content Node into the viewport in place of the placeholder. */
    public void setViewportContent(javafx.scene.Node node) {
        viewport.getChildren().setAll(node);
    }
}
