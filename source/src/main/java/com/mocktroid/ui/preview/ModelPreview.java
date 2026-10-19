package com.mocktroid.ui.preview;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mocktroid.format.model.CmdlModel;
import com.mocktroid.format.texture.Texture;
import com.mocktroid.format.texture.TextureDecoder;
import com.mocktroid.format.texture.TextureHeader;
import com.mocktroid.format.texture.TxtrReader;
import com.mocktroid.format.pack.PackCatalog;
import com.mocktroid.format.chpr.AnimClip;
import com.mocktroid.format.chpr.ChprSkeleton;
import com.mocktroid.library.AnimationSupport;
import com.mocktroid.library.ModelLoader;
import com.mocktroid.library.SkeletonLoader;
import com.mocktroid.library.PakAssetRef;
import com.mocktroid.model.AssetEntry;
import com.mocktroid.model.AssetLibrary;
import com.mocktroid.settings.AppSettings;
import com.mocktroid.ui.LoadingSpinner;

import javafx.application.Platform;
import javafx.geometry.Point3D;
import javafx.geometry.Pos;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.DrawMode;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.shape.VertexFormat;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

/**
 * 3D model viewer (CMDL/SMDL) on a JavaFX {@link SubScene}: left-drag
 * orbits, ctrl/middle-drag pans, the wheel zooms, WASD/QE moves, double-click resets.
 * Geometry shows immediately with a neutral material; base-colour textures
 * stream in afterwards. Only LOD 0 submeshes are drawn.
 * <p>
 * MP4 is Y-up (unlike the older Z-up Primes -- obvious from Samus's suit
 * models and level bounds); JavaFX is Y-down with Z into the screen.
 * Positions/normals are mapped (x, y, z) -&gt; (x, -y, -z), a proper rotation,
 * so winding and handedness are preserved. UVs get v -&gt; 1-v because decoded textures are flipped to
 * top-down (see {@code TextureDecoder}).
 */
public final class ModelPreview {

    private static final int MAX_TEXTURE_SIZE = 2048;
    private static final Color DEFAULT_COLOR = Color.web("#9db3bf");

    private final AssetEntry entry;
    private final AssetLibrary library;
    private final StackPane viewport = new StackPane();
    private final VBox controls = new VBox(8);
    private final Label infoLabel = new Label("Loading\u2026");
    private final ToggleButton texturesToggle = new ToggleButton("Textures");
    private final ToggleButton wireframeToggle = new ToggleButton("Wireframe");
    private final ToggleButton bonesToggle = new ToggleButton("Bones");
    private final ToggleButton lockjawToggle = new ToggleButton("Lockjaw");
    /** Arm cannon glow layers, one per weapon state (see {@link #WEAPON_GLOWS}); shown only for models that have them. */
    private final ToggleButton iceToggle = new ToggleButton("Ice");
    private final ToggleButton fireToggle = new ToggleButton("Fire");
    private final ToggleButton thunderToggle = new ToggleButton("Thunder");
    /** The weapon toggles' state, kept while browsing from one suit to the next. */
    private static boolean iceOn, fireOn, thunderOn;
    /** Skeleton overlay: joints + parent links, drawn over the mesh (no depth test). */
    private final Group bonesGroup = new Group();

    private final Group modelGroup = new Group();
    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private final Translate target = new Translate();
    private final Rotate yaw = new Rotate(-35, Rotate.Y_AXIS);
    private final Rotate pitch = new Rotate(-20, Rotate.X_AXIS);
    private final Translate dolly = new Translate();
    private final Group rig = new Group();

    private final List<MeshView> meshViews = new ArrayList<>();
    /** Keyed by material index, negated-minus-one for the blended variant
     *  used by transparent submeshes (see {@link #materialKey}). */
    private final Map<Integer, PhongMaterial> materials = new HashMap<>();
    private final Map<Integer, Image> textures = new HashMap<>();
    /** Detail maps per material key: NMAP normal map (as JavaFX bump map), METL-derived specular, glow. */
    private final Map<Integer, Image> normalMaps = new HashMap<>();
    private final Map<Integer, Image> specularMaps = new HashMap<>();
    /** Base colour darkened by metalness (METL): metal gets its colour from reflections, which JavaFX lacks. */
    private final Map<Integer, Image> metalTextures = new HashMap<>();
    /** Material keys of opaque submeshes recognised as visors (see {@link #markVisors}). */
    private final java.util.Set<Integer> visorKeys = new java.util.HashSet<>();
    /** Glow per material key: one image, or several frames played in a loop (animated ICAN). */
    private final Map<Integer, Image[]> glowMaps = new HashMap<>();
    private int glowFrame;
    /** Cycles animated glow frames (GLOW_FRAMES per GLOW_PERIOD seconds). */
    private final javafx.animation.Timeline glowTimer = new javafx.animation.Timeline(
        new javafx.animation.KeyFrame(javafx.util.Duration.seconds(GLOW_PERIOD / GLOW_FRAMES), e -> {
            glowFrame = (glowFrame + 1) % GLOW_FRAMES;
            applyGlow();
        }));
    private final ToggleButton detailToggle = new ToggleButton("Detail maps");
    private Node floor;
    private double radius = 1;
    private Point3D center = Point3D.ZERO;
    private double dragX;
    private double dragY;
    private volatile boolean disposed = false;

    public ModelPreview(AssetEntry entry, AssetLibrary library) {
        this.entry = entry;
        this.library = library;
        buildControls();
        AppSettings.get().transparentVisorProperty().addListener(visorListener);
        AppSettings.get().hideVisorsProperty().addListener(hideVisorListener);
        AppSettings.get().cannonFrostProperty().addListener(frostListener);
        AppSettings.get().smoothTexturesProperty().addListener(smoothListener);
        glowTimer.setCycleCount(javafx.animation.Animation.INDEFINITE);
        viewport.getStyleClass().add("model-viewport");
        viewport.setMinSize(0, 0);
        viewport.getChildren().setAll(new LoadingSpinner(56));
        loadAsync();
    }

    public Node viewportNode() { return viewport; }
    /** Called when a playing clip reaches its end and Settings > play the next animation is on. */
    private Runnable onClipFinished;
    public void setOnClipFinished(Runnable r) { onClipFinished = r; }
    /** Previous (-1) / next (+1) animation in the clip list. */
    private java.util.function.IntConsumer onStepClip = d -> {};
    public void setOnStepClip(java.util.function.IntConsumer c) { onStepClip = c; }
    /** The camera toggles, shown by PreviewPane in its tool row above the viewport. */
    public Node toolsNode() { return cameraTools; }
    private final HBox cameraTools = new HBox(6);
    public Node transportNode() { return controls; }

    public void dispose() {
        disposed = true;
        player.stop();
        moveTimer.stop();
        heldKeys.clear();
        if (viewport.getScene() != null) {
            viewport.getScene().removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, spaceFilter);
        }
        AppSettings.get().transparentVisorProperty().removeListener(visorListener);
        AppSettings.get().hideVisorsProperty().removeListener(hideVisorListener);
        AppSettings.get().cannonFrostProperty().removeListener(frostListener);
        AppSettings.get().smoothTexturesProperty().removeListener(smoothListener);
        glowTimer.stop();
        AppSettings.get().animationFpsProperty().removeListener(fpsListener);
        AppSettings.get().renderResolutionProperty().removeListener(renderSizeListener);
        AppSettings.get().customRenderWidthProperty().removeListener(renderSizeListener);
        AppSettings.get().customRenderHeightProperty().removeListener(renderSizeListener);
    }

    private final javafx.beans.InvalidationListener fpsListener = o -> {
        this.player.setRate(clipFps() / 30.0);
        updateFrameLabel();
    };

    private final javafx.beans.InvalidationListener renderSizeListener = o -> applyRenderSize();

    private void applyRenderSize() {
        if (subScene == null) {
            return;
        }
        int[] size = AppSettings.get().renderSize();
        subScene.setWidth(size[0]);
        subScene.setHeight(size[1]);
        layoutSubScene();
    }

    /** Scales the fixed-size render to fit the viewport, centred (letterboxed if the aspects differ). */
    private void layoutSubScene() {
        if (subScene == null) {
            return;
        }
        double w = subScene.getWidth(), h = subScene.getHeight();
        double vw = viewport.getWidth(), vh = viewport.getHeight();
        if (w <= 0 || h <= 0 || vw <= 0 || vh <= 0) {
            return;
        }
        double s = Math.min(vw / w, vh / h);
        subScene.setScaleX(s);
        subScene.setScaleY(s);
        subScene.relocate((vw - w) / 2, (vh - h) / 2);
    }

    private volatile ModelLoader.LoadedModel loadedModel;

    /** The loaded model (null until it has loaded) -- for export. */
    public ModelLoader.LoadedModel loadedModel() {
        return loadedModel;
    }
    private SubScene subScene;

    /** Saves what the viewport shows right now (camera, pose) as this model's grid icon. */
    public boolean cacheIcon() {
        if (subScene == null || disposed) {
            return false;
        }
        javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        Paint fill = subScene.getFill();
        subScene.setFill(Color.TRANSPARENT);
        boolean bones = bonesGroup.isVisible();
        bonesGroup.setVisible(false);
        WritableImage shot = subScene.snapshot(params, null);
        bonesGroup.setVisible(bones);
        subScene.setFill(fill);
        AssetIconCache.get().store(entry, shot);
        return true;
    }

    /** Settings > Extra smooth textures: re-decodes every texture at the new size. */
    private final javafx.beans.InvalidationListener smoothListener = o -> {
        ModelLoader.LoadedModel loaded = loadedModel;
        if (loaded == null || disposed) {
            return;
        }
        Thread t = new Thread(() -> loadTextures(loaded, new ArrayList<>(meshViews)), "model-textures");
        t.setDaemon(true);
        t.start();
    };

    /** Settings > Arm Cannon Frost: the opacity of the cannon's ice shell. */
    private final javafx.beans.InvalidationListener frostListener = o -> applyFrost();

    /** The first-person cannons' ice shell: a snow-textured mesh over the barrel (shown in cold areas). */
    private static final java.util.UUID ICE_SHADER = java.util.UUID.fromString("a9739d76-bc6d-40ef-8529-8d687a94f328");
    private final java.util.Set<Integer> iceKeys = new java.util.HashSet<>();

    private void applyFrost() {
        double f = AppSettings.get().cannonFrostProperty().get() / 100.0;
        for (MeshView v : meshViews) {
            if (iceKeys.contains((int) v.getProperties().get("material"))) {
                v.setVisible(f > 0);
            }
        }
        applyTextures();
    }

    /** Settings > Hide visors: blended submeshes (Samus's visor, glass) are left out. */
    private final javafx.beans.InvalidationListener hideVisorListener = o -> applyVisorVisibility();

    private void applyVisorVisibility() {
        boolean hide = AppSettings.get().hideVisorsProperty().get();
        for (MeshView v : meshViews) {
            if ((int) v.getProperties().get("material") < 0 || Boolean.TRUE.equals(v.getProperties().get("visor"))) {
                v.setVisible(!hide);
            }
        }
    }

    /** Settings > Transparent visor: re-decodes the blended submeshes' textures with or without alpha. */
    private final javafx.beans.value.ChangeListener<Boolean> visorListener = (obs, old, val) -> {
        ModelLoader.LoadedModel loaded = loadedModel;
        if (loaded == null || disposed) {
            return;
        }
        textures.keySet().removeIf(k -> k < 0);
        metalTextures.keySet().removeIf(k -> k < 0);
        applyTextures();
        Thread t = new Thread(() -> loadTextures(loaded, new ArrayList<>(meshViews)), "model-textures");
        t.setDaemon(true);
        t.start();
    };

    // ---- controls ------------------------------------------------------------

    private void buildControls() {
        infoLabel.getStyleClass().add("transport-status-label");
        texturesToggle.getStyleClass().add("channel-toggle");
        wireframeToggle.getStyleClass().add("channel-toggle");
        texturesToggle.setSelected(true);
        texturesToggle.setOnAction(e -> applyTextures());
        detailToggle.getStyleClass().add("channel-toggle");
        detailToggle.setSelected(true);
        detailToggle.setTooltip(new javafx.scene.control.Tooltip("Normal, shine (metal/roughness) and glow maps"));
        detailToggle.setOnAction(e -> applyTextures());
        wireframeToggle.setOnAction(e -> {
            DrawMode mode = wireframeToggle.isSelected() ? DrawMode.LINE : DrawMode.FILL;
            meshViews.forEach(m -> m.setDrawMode(mode));
        });
        lockjawToggle.getStyleClass().add("channel-toggle");
        lockjawToggle.setSelected(true);
        lockjawToggle.setVisible(false);
        lockjawToggle.setManaged(false);
        lockjawToggle.setTooltip(new javafx.scene.control.Tooltip("Show Sylux's Lockjaw"));
        lockjawToggle.setOnAction(e -> meshViews.stream().filter(v -> Boolean.TRUE.equals(v.getProperties().get("lockjaw")))
            .forEach(v -> v.setVisible(lockjawToggle.isSelected())));
        ToggleButton[] weapons = {iceToggle, fireToggle, thunderToggle};
        boolean[] weaponOn = {iceOn, fireOn, thunderOn};
        for (int i = 0; i < weapons.length; i++) {
            ToggleButton t = weapons[i];
            t.getStyleClass().add("channel-toggle");
            t.setSelected(weaponOn[i]);
            t.setVisible(false);
            t.setManaged(false);
            t.setTooltip(new javafx.scene.control.Tooltip("Arm cannon " + t.getText() + " Shot lights (the game lights one weapon's layer at a time)"));
            t.setOnAction(e -> {
                iceOn = iceToggle.isSelected();
                fireOn = fireToggle.isSelected();
                thunderOn = thunderToggle.isSelected();
                applyGlow();
            });
        }
        bonesToggle.getStyleClass().add("channel-toggle");
        bonesToggle.setDisable(true);
        bonesToggle.setTooltip(new javafx.scene.control.Tooltip("Show the skeleton (skinned models)"));
        bonesToggle.setOnAction(e -> {
            bonesGroup.setVisible(bonesToggle.isSelected());
            if (bonesToggle.isSelected() && lastPosed != null) {
                drawBones(lastPosed); // catch up: hidden overlays aren't updated
            }
        });
        bonesGroup.setVisible(false);
        bonesGroup.setDepthTest(javafx.scene.DepthTest.DISABLE);
        Button reset = new Button("Reset view");
        reset.getStyleClass().add("settings-secondary-button");
        reset.setOnAction(e -> resetView());
        Label hint = new Label("drag: orbit  \u00B7  ctrl/middle-drag: pan  \u00B7  wheel: zoom  \u00B7  "
            + "WASD, Q/E: move (shift: fast)  \u00B7  shift+wheel: fast speed  \u00B7  "
            + "space: play/pause  \u00B7  Z/X or ctrl+wheel: next/prev frame");
        hint.getStyleClass().add("transport-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        for (javafx.scene.control.Control c : List.of(texturesToggle, detailToggle, wireframeToggle, bonesToggle, lockjawToggle,
                iceToggle, fireToggle, thunderToggle, reset)) {
            c.setMinWidth(Region.USE_PREF_SIZE);
        }
        hint.setMinWidth(0);
        hint.setTooltip(new javafx.scene.control.Tooltip(hint.getText().replace("  ·  ", "\n")));
        HBox row = new HBox(10, texturesToggle, detailToggle, wireframeToggle, bonesToggle, lockjawToggle,
            iceToggle, fireToggle, thunderToggle, reset, spacer, hint);
        row.setAlignment(Pos.CENTER_LEFT);
        viewRow = row;
        controls.getChildren().addAll(infoLabel, row);
        HBox.setHgrow(controls, Priority.ALWAYS);
        buildAnimationRow();
    }

    // ---- animation transport (animation mode) ---------------------------------

    /** Clips play at Settings > Animation Speed (default 60 fps, the game's rate; the clip
     *  header's rate is 1.0 in every clip seen so far). */
    private static double clipFps() {
        return Math.max(1, AppSettings.get().animationFpsProperty().get());
    }

    private HBox viewRow;
    private final VBox animationBox = new VBox(6);
    private final Label clipLabel = new Label();
    private final Label frameLabel = new Label();
    private final Slider frameSlider = new Slider(0, 1, 0);
    private final Button playButton = new Button("\u25B7");
    private final javafx.animation.Timeline player = new javafx.animation.Timeline();
    private AnimationSupport.ClipRef clip;
    private final Label animNotice = new Label();
    private AnimClip.Player anim;
    /** Clip address -> skeleton address when the clip comes from another CHPR (see AnimationSupport#addressRemap). */
    private Map<Integer, Integer> remap;

    /** The pose with its addresses moved onto this skeleton's; transforms with no matching joint are dropped. */
    private AnimClip.Pose remapped(AnimClip.Pose pose) {
        if (pose != null && !frozenAddresses.isEmpty()) {
            AnimClip.Pose kept = new AnimClip.Pose();
            pose.transforms.forEach((addr, x) -> {
                if (!frozenAddresses.contains(addr)) {
                    kept.transforms.put(addr, x);
                }
            });
            kept.floats.putAll(pose.floats);
            kept.ints.putAll(pose.ints);
            pose = kept;
        }
        if (remap == null || pose == null) {
            return pose;
        }
        AnimClip.Pose out = new AnimClip.Pose();
        pose.transforms.forEach((addr, x) -> {
            Integer to = remap.get(addr);
            if (to != null) {
                out.transforms.put(to, x);
            }
        });
        out.floats.putAll(pose.floats);
        out.ints.putAll(pose.ints);
        return out;
    }
    private SkeletonLoader.Loaded skeleton;
    private double[][] bindChain;

    private void buildAnimationRow() {
        clipLabel.getStyleClass().add("transport-label");
        frameLabel.getStyleClass().add("transport-status-label");
        frameLabel.setMinWidth(Region.USE_PREF_SIZE);
        Button stop = new Button("\u25C6");
        stop.getStyleClass().add("transport-button");
        playButton.getStyleClass().add("transport-button");
        stop.setOnAction(e -> {
            player.stop();
            playButton.setText("\u25B7");
            frameSlider.setValue(0);
        });
        playButton.setOnAction(e -> togglePlay());
        frameSlider.getStyleClass().add("transport-scrub");
        frameSlider.setMaxWidth(Double.MAX_VALUE);
        frameSlider.setBlockIncrement(1);
        frameSlider.setSnapToTicks(true);
        frameSlider.setMajorTickUnit(1);
        frameSlider.setMinorTickCount(0);
        HBox.setHgrow(frameSlider, Priority.ALWAYS);
        frameSlider.valueProperty().addListener((o, a, b) -> {
            updateFrameLabel();
            applyPose();
        });
        Button prevClip = new Button("\u23EE");
        Button nextClip = new Button("\u23ED");
        prevClip.getStyleClass().add("transport-button");
        nextClip.getStyleClass().add("transport-button");
        prevClip.setTooltip(new javafx.scene.control.Tooltip("Previous animation"));
        nextClip.setTooltip(new javafx.scene.control.Tooltip("Next animation"));
        prevClip.setOnAction(e -> onStepClip.accept(-1));
        nextClip.setOnAction(e -> onStepClip.accept(1));
        HBox transport = new HBox(10, prevClip, stop, playButton, nextClip, frameSlider, frameLabel);
        transport.setAlignment(Pos.CENTER_LEFT);
        animNotice.getStyleClass().add("transport-label");
        animNotice.setWrapText(true);
        animationBox.getChildren().addAll(clipLabel, transport, animNotice);

        player.setCycleCount(javafx.animation.Animation.INDEFINITE);
        player.getKeyFrames().add(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1 / 30.0), e -> {
            double next = frameSlider.getValue() + 1;
            if (next > frameSlider.getMax() && AppSettings.get().autoplayNextAnimationProperty().get() && onClipFinished != null) {
                player.stop();
                playButton.setText("\u25B7");
                PlayChain.arm();
                onClipFinished.run();
                return;
            }
            frameSlider.setValue(next > frameSlider.getMax() ? 0 : next);
        }));
        // The timeline's key frame is fixed at 1/30 s; its rate scales it to the chosen fps.
        player.setRate(clipFps() / 30.0);
        AppSettings.get().animationFpsProperty().addListener(fpsListener);
        viewport.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            javafx.scene.input.KeyCode code = e.getCode();
            if (code == javafx.scene.input.KeyCode.SPACE) {
                if (clip != null) {
                    togglePlay();
                }
                e.consume();
                return;
            }
            switch (code) {
                case W, A, S, D, Q, E, SHIFT -> {
                    heldKeys.add(code);
                    moveTimer.start();
                    e.consume();
                }
                case Z -> {
                    stepFrame(1);
                    e.consume();
                }
                case X -> {
                    stepFrame(-1);
                    e.consume();
                }
                default -> { }
            }
        });
        viewport.setFocusTraversable(true);
        viewport.sceneProperty().addListener((obs, old, scene) -> {
            if (old != null) {
                old.removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, spaceFilter);
            }
            if (scene != null && !disposed) {
                scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, spaceFilter);
            }
        });
    }

    /** Space plays/pauses the clip wherever focus is (except text fields). */
    private final javafx.event.EventHandler<javafx.scene.input.KeyEvent> spaceFilter = e -> {
        if (e.getCode() != javafx.scene.input.KeyCode.SPACE || clip == null || disposed || viewport.isFocused()
            || e.getTarget() instanceof javafx.scene.control.TextInputControl) {
            return;
        }
        togglePlay();
        e.consume();
    };

    /** Animation mode: shows {@code clip} in the transport, or (null) returns to the view controls. */
    public void showClip(AnimationSupport.ClipRef clip) {
        this.clip = clip;
        player.stop();
        playButton.setText("\u25B7");
        anim = null;
        if (clip == null) {
            controls.getChildren().setAll(infoLabel, viewRow);
            applyPose();
            return;
        }
        animNotice.setText("Decoding clip\u2026");
        Runnable decode = () -> {
            if (this.clip != clip || disposed) {
                return; // already moved on to another clip
            }
            AnimClip decoded;
            try {
                decoded = AnimationSupport.decode(clip, library);
            } catch (RuntimeException e) {
                decoded = null;
            }
            AnimClip result = decoded;
            Platform.runLater(() -> {
                if (this.clip != clip || disposed) {
                    return;
                }
                anim = result == null ? null : result.player();
                remap = skeleton == null ? null
                    : AnimationSupport.addressRemap(clip.character(), skeleton.character(), skeleton.skeleton(), library);
                if (remap != null && remap.isEmpty()) {
                    remap = null;
                }
                double[] previousBody = lastFollowPos;
                lastFollowPos = null;
                if (result == null) {
                    animNotice.setText("This clip's key data wasn't found in its character file. Z / X (or ctrl+wheel) step one frame.");
                } else if (skeleton == null) {
                    animNotice.setText("Clip decoded, but this model's skeleton couldn't be read, so it stays in its bind pose.");
                } else {
                    animNotice.setText("Posed from the decoded clip; values it holds constant are taken as the bind pose. "
                        + "Z / X (or ctrl+wheel) step one frame.");
                }
                applyPose();
                // The camera stays where it is in the world between clips (moving it by the body's
                // offset crept it down and sideways over a run of short clips); only the first clip
                // after loading frames the model.
                if (previousBody == null) {
                    frameOnModel();
                }
                if (anim != null && (AppSettings.get().autoplayAnimationsProperty().get() || PlayChain.take())
                    && player.getStatus() != javafx.animation.Animation.Status.RUNNING) {
                    togglePlay();
                }
            });
        };
        CLIP_DECODER.execute(decode);
        int frames = Math.max(1, clip.clip().frames());
        frameSlider.setMax(frames - 1);
        frameSlider.setValue(0);
        clipLabel.setText("animation playback  \u00b7  " + clip.clip().name());
        updateFrameLabel();
        controls.getChildren().setAll(infoLabel, animationBox);
        viewport.requestFocus();
    }

    /** Pauses and moves {@code delta} frames (wrapping), when a clip is shown. */
    private void stepFrame(int delta) {
        if (clip == null) {
            return;
        }
        if (player.getStatus() == javafx.animation.Animation.Status.RUNNING) {
            togglePlay();
        }
        int max = (int) Math.round(frameSlider.getMax());
        int next = (int) Math.round(frameSlider.getValue()) + delta;
        frameSlider.setValue(next > max ? 0 : next < 0 ? max : next);
    }

    /** One decoder for every preview: quickly stepping through clips no longer starts a thread (and a
     *  multi-megabyte character file decompression) per clip; superseded clips are skipped. */
    private static final java.util.concurrent.ExecutorService CLIP_DECODER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clip-decode");
        t.setDaemon(true);
        return t;
    });

    private void togglePlay() {
        com.mocktroid.ui.UiSounds.get().play(com.mocktroid.ui.UiSounds.Cue.PAUSE_RESUME);
        if (player.getStatus() == javafx.animation.Animation.Status.RUNNING) {
            player.pause();
            playButton.setText("\u25B7");
        } else {
            player.play();
            playButton.setText("\u23F8");
        }
    }

    private void updateFrameLabel() {
        if (clip == null) {
            return;
        }
        int frame = (int) Math.round(frameSlider.getValue());
        int frames = Math.max(1, clip.clip().frames());
        frameLabel.setText(String.format("frame %d / %d  \u00b7  %.2fs", frame + 1, frames, frame / clipFps()));
    }

    // ---- loading -------------------------------------------------------------

    private void loadAsync() {
        Thread thread = new Thread(() -> {
            try {
                ModelLoader.LoadedModel loaded = ModelLoader.load(entry, library);
                if (disposed) {
                    return;
                }
                List<MeshView> views = buildMeshes(loaded.model());
                java.util.Set<Integer> visors = markVisors(loaded, views);
                visorKeySnapshot = java.util.Set.copyOf(visors);
                java.util.Set<Integer> blendedVisors = new java.util.HashSet<>();
                for (MeshView v : views) {
                    int k = (int) v.getProperties().get("material");
                    if (k < 0 && Boolean.TRUE.equals(v.getProperties().get("singleJoint")) && (int) v.getProperties().get("tris") <= 400) {
                        blendedVisors.add(k);
                    }
                }
                blendedVisorSnapshot = java.util.Set.copyOf(blendedVisors);
                Platform.runLater(() -> visorKeys.addAll(visors));
                SkeletonLoader.Loaded skeleton = SkeletonLoader.load(entry, loaded.model(), library);
                Platform.runLater(() -> {
                    onLoaded(loaded, views);
                    showSkeleton(skeleton);
                });
                loadTextures(loaded, views);
            } catch (Exception | OutOfMemoryError e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                Platform.runLater(() -> showError(msg));
            }
        }, "model-load");
        thread.setDaemon(true);
        thread.start();
    }

    /** True when every vertex in the range is bound fully to one and the same joint (e.g. the head). */
    private static boolean singleJoint(CmdlModel.VertexStream vs, int min, int n) {
        if (vs.joints() == null || vs.weights() == null) {
            return false;
        }
        int joint = -1;
        for (int v = min; v < min + n; v++) {
            int found = -1;
            for (int k = 0; k < 4; k++) {
                if (vs.weights()[v * 4 + k] >= 0.99f) {
                    found = (int) vs.joints()[v * 4 + k]; // the full weight can sit in any of the 4 slots
                }
            }
            if (found < 0 || (joint >= 0 && found != joint)) {
                return false;
            }
            joint = found;
        }
        return joint >= 0;
    }

    /**
     * Visors: the model's blended submeshes, plus -- for suits that draw the visor opaque (Varia,
     * Psychic) -- small submeshes bound wholly to one joint whose material is theirs alone, shares
     * the body's base colour, and has a full-size glow texture (the visor glass). Recognised opaque
     * visors are moved last in draw order (they can be made see-through). Returns their keys.
     */
    static java.util.Set<Integer> markVisors(ModelLoader.LoadedModel loaded, List<MeshView> views) {
        Map<Integer, Integer> uses = new HashMap<>();
        for (MeshView v : views) {
            uses.merge((int) v.getProperties().get("material"), 1, Integer::sum);
        }
        java.util.Set<Integer> keys = new java.util.HashSet<>();
        for (MeshView v : views) {
            int key = (int) v.getProperties().get("material");
            if (key < 0 || uses.get(key) != 1 || (int) v.getProperties().get("tris") > 300
                || !Boolean.TRUE.equals(v.getProperties().get("singleJoint"))) {
                continue;
            }
            PakAssetRef base = loaded.slot(key, "BCLR"), ican = loaded.slot(key, "ICAN");
            if (base == null || ican == null) {
                continue;
            }
            boolean shared = false;
            for (int m = 0; m < loaded.textureSlots().size() && !shared; m++) {
                PakAssetRef other = loaded.slot(m, "BCLR");
                shared = m != key && other != null && other.asset().assetId().equals(base.asset().assetId());
            }
            try {
                TextureHeader bh = header(base), ih = header(ican);
                if (shared && bh.width() == ih.width() && bh.height() == ih.height()) {
                    keys.add(key);
                    v.getProperties().put("visor", true);
                }
            } catch (java.io.IOException | RuntimeException e) {
                // not a visor then
            }
        }
        views.sort(java.util.Comparator.comparing(v -> (int) v.getProperties().get("material") < 0
            || Boolean.TRUE.equals(v.getProperties().get("visor"))));
        return keys;
    }

    static int materialKey(int materialIndex, boolean transparent) {
        return transparent ? -materialIndex - 1 : materialIndex;
    }

    static List<MeshView> buildMeshes(CmdlModel model) {
        List<MeshView> views = new ArrayList<>();
        java.util.Set<Integer> transparent = new java.util.HashSet<>();
        for (int t : model.transparentMeshes()) {
            transparent.add(t);
        }
        for (int si : model.drawnSubmeshes()) {
            CmdlModel.Submesh s = model.submeshes().get(si);
            if (s.vertexStream() >= model.vertexStreams().size() || s.indexBuffer() >= model.indexBuffers().size()) {
                continue;
            }
            CmdlModel.VertexStream vs = model.vertexStreams().get(s.vertexStream());
            int[] ib = model.indexBuffers().get(s.indexBuffer());
            int start = s.indexStart();
            int count = Math.min(s.indexCount(), ib.length - start) / 3 * 3;
            if (count <= 0) {
                continue;
            }
            int min = Integer.MAX_VALUE;
            int max = -1;
            for (int k = start; k < start + count; k++) {
                min = Math.min(min, ib[k]);
                max = Math.max(max, ib[k]);
            }
            if (max >= vs.vertexCount()) {
                continue;
            }
            int n = max - min + 1;
            boolean hasNormals = vs.normals() != null;
            TriangleMesh mesh = new TriangleMesh(hasNormals ? VertexFormat.POINT_NORMAL_TEXCOORD : VertexFormat.POINT_TEXCOORD);
            float[] points = new float[n * 3];
            float[] p = vs.positions();
            for (int v = 0; v < n; v++) {
                int src = (min + v) * 3;
                points[v * 3] = p[src];
                points[v * 3 + 1] = -p[src + 1];
                points[v * 3 + 2] = -p[src + 2];
            }
            mesh.getPoints().setAll(points);
            if (hasNormals) {
                float[] nm = new float[n * 3];
                float[] src = vs.normals();
                for (int v = 0; v < n; v++) {
                    int o = (min + v) * 3;
                    nm[v * 3] = src[o];
                    nm[v * 3 + 1] = -src[o + 1];
                    nm[v * 3 + 2] = -src[o + 2];
                }
                mesh.getNormals().setAll(nm);
            }
            if (vs.uv0() != null) {
                float[] uv = new float[n * 2];
                float[] src = vs.uv0();
                for (int v = 0; v < n; v++) {
                    uv[v * 2] = src[(min + v) * 2];
                    uv[v * 2 + 1] = 1f - src[(min + v) * 2 + 1];
                }
                mesh.getTexCoords().setAll(uv);
            } else {
                mesh.getTexCoords().setAll(new float[n * 2]);
            }
            int per = hasNormals ? 3 : 2;
            int[] faces = new int[count * per];
            for (int k = 0; k < count; k++) {
                int idx = ib[start + k] - min;
                int o = k * per;
                faces[o] = idx;
                faces[o + 1] = idx;
                if (hasNormals) {
                    faces[o + 2] = idx;
                }
            }
            mesh.getFaces().setAll(faces);
            MeshView view = new MeshView(mesh);
            view.setCullFace(CullFace.NONE);
            view.getProperties().put("material", materialKey(s.materialIndex(), transparent.contains(si)));
            view.getProperties().put("tris", count / 3);
            view.getProperties().put("singleJoint", singleJoint(vs, min, n));
            if (vs.joints() != null) {
                float[] bp = new float[n * 3];
                System.arraycopy(p, min * 3, bp, 0, n * 3);
                float[] bn = hasNormals ? java.util.Arrays.copyOfRange(vs.normals(), min * 3, (min + n) * 3) : null;
                view.getProperties().put("skin", new SkinnedMesh(mesh, bp, bn,
                    java.util.Arrays.copyOfRange(vs.joints(), min * 4, (min + n) * 4),
                    java.util.Arrays.copyOfRange(vs.weights(), min * 4, (min + n) * 4)));
            }
            views.add(view);
        }
        // Blended geometry last, so what's behind it has already been drawn.
        views.sort(java.util.Comparator.comparing(v -> (int) v.getProperties().get("material") < 0));
        return views;
    }

    private void onLoaded(ModelLoader.LoadedModel loaded, List<MeshView> views) {
        if (disposed) {
            return;
        }
        CmdlModel model = loaded.model();
        loadedModel = loaded;
        meshViews.addAll(views);
        for (MeshView view : views) {
            int mat = (int) view.getProperties().get("material");
            view.setMaterial(materials.computeIfAbsent(mat, k -> {
                PhongMaterial m = new PhongMaterial(untexturedColor(k));
                m.setSpecularColor(Color.gray(0.25));
                m.setSpecularPower(24);
                return m;
            }));
        }
        modelGroup.getChildren().setAll(views);
        applyVisorVisibility();
        for (int m = 0; m < model.materials().size(); m++) {
            if (ICE_SHADER.equals(model.materials().get(m).shader())) {
                iceKeys.add(m);
            }
        }
        // The ice shell blends, so it draws after the opaque meshes.
        List<Node> ordered = new ArrayList<>(modelGroup.getChildren());
        ordered.sort(java.util.Comparator.comparing(n -> iceKeys.contains((int) n.getProperties().get("material"))));
        modelGroup.getChildren().setAll(ordered);
        applyFrost();

        // Frame the declared bounds (falls back to unit size if degenerate).
        float[] mn = model.boundsMin();
        float[] mx = model.boundsMax();
        double cx = (mn[0] + mx[0]) / 2, cy = -(mn[1] + mx[1]) / 2, cz = -(mn[2] + mx[2]) / 2;
        double dx = mx[0] - mn[0], dy = mx[1] - mn[1], dz = mx[2] - mn[2];
        radius = Math.max(0.01, Math.sqrt(dx * dx + dy * dy + dz * dz) / 2);
        if (!Double.isFinite(radius)) {
            radius = 1;
        }
        center = new Point3D(cx, cy, cz);
        camera.setNearClip(radius * 0.002);
        camera.setFarClip(radius * 400);
        camera.setFieldOfView(40);

        // A soft headlight plus a key light from the upper left (both ride with the camera) so
        // normal-mapped detail reads; ambient fills the shadows.
        PointLight headlight = new PointLight(Color.gray(0.35));
        javafx.scene.DirectionalLight key = new javafx.scene.DirectionalLight(Color.gray(0.85));
        key.setDirection(new Point3D(0.45, 0.6, 0.65));
        rig.getChildren().setAll(camera, headlight, key);
        rig.getTransforms().setAll(target, yaw, pitch, dolly);
        AmbientLight ambient = new AmbientLight(Color.gray(0.24));

        floor = buildFloor(-mn[1]);
        floor.visibleProperty().bind(AppSettings.get().showFloorProperty());
        // The floor is see-through, so it's drawn after the model.
        Group root = new Group(modelGroup, floor, bonesGroup, rig, ambient);
        Node sky = buildSkybox();
        if (sky != null) {
            root.getChildren().add(0, sky);
        }
        SubScene sub = new SubScene(root, 10, 10, true, SceneAntialiasing.BALANCED);
        subScene = sub;
        // Rendered at Settings > Preview Render Resolution, then scaled to fit the viewport.
        sub.setManaged(false);
        sub.setFill(Color.web("#071019"));
        sub.setCamera(camera);
        applyRenderSize();
        viewport.widthProperty().addListener((o, a, b) -> layoutSubScene());
        viewport.heightProperty().addListener((o, a, b) -> layoutSubScene());
        AppSettings.get().renderResolutionProperty().addListener(renderSizeListener);
        AppSettings.get().customRenderWidthProperty().addListener(renderSizeListener);
        AppSettings.get().customRenderHeightProperty().addListener(renderSizeListener);
        viewport.getChildren().setAll(sub);
        buildCameraOverlay();
        installMouse();
        resetView();

        int textured = (int) loaded.baseColorTextures().stream().filter(t -> t != null).count();
        infoLabel.setText(String.format("%s  \u00B7  %d submeshes  \u00B7  %,d verts  \u00B7  %,d tris  \u00B7  %d materials (%d textured)",
            model.form(), model.drawnSubmeshes().length, model.vertexCount(), model.triangleCount(),
            model.materials().size(), textured));
    }

    // ---- skeleton overlay ------------------------------------------------------

    private static final Color BONE_COLOR = Color.web("#4dffa0");
    private static final Color JOINT_COLOR = Color.web("#ffd24d");

    /** Builds the Bones overlay from the CHPR bind pose (on the FX thread, after onLoaded). */
    private void showSkeleton(SkeletonLoader.Loaded skeleton) {
        if (disposed || skeleton == null) {
            return;
        }
        this.skeleton = skeleton;
        markLockjaw(skeleton);
        freezeOtherRig(skeleton);
        followJoint = pickFollowJoint(skeleton.skeleton(), skeleton.skin() != null ? skeleton.skin().palette() : null);
        claimedAddresses.clear();
        for (int j = 0; j < skeleton.skeleton().joints().size(); j++) {
            if (skeleton.skeleton().poseAddress(j) >= 0) {
                claimedAddresses.add(skeleton.skeleton().poseAddress(j));
            }
        }
        bindChain = chainWorld(null);
        int drawn = drawBones(skeleton.bindWorld());
        bonesToggle.setDisable(drawn == 0);
        if (drawn > 0) {
            infoLabel.setText(infoLabel.getText() + String.format("  \u00B7  %d joints", drawn));
        }
        applyPose();
    }

    /** Rebuilds the bones overlay for the given joint world matrices; returns the joints drawn. */
    /** Overlay nodes, built once per skeleton: a ball per joint and a bone per (parent, joint). */
    private final List<javafx.scene.shape.Sphere> boneBalls = new ArrayList<>();
    private final List<int[]> boneLinks = new ArrayList<>();
    private final List<javafx.scene.shape.Cylinder> boneSticks = new ArrayList<>();
    private int[] ballJoints = new int[0];
    /** The last posed joint matrices, so the overlay can catch up when it's switched on. */
    private double[][] lastPosed;

    /**
     * The skeleton overlay. Nodes and materials are made on the first call and only moved after
     * that -- rebuilding them every frame (with new materials and textures each time) is what made
     * long animation sessions lag. While the overlay is hidden nothing is updated.
     */
    private int drawBones(double[][] world) {
        lastPosed = world;
        SkeletonLoader.Loaded skeleton = this.skeleton;
        int n = world.length;
        boolean fresh = boneBalls.isEmpty() && boneSticks.isEmpty();
        if (fresh) {
            buildBoneNodes(skeleton, world);
        }
        if (!fresh && !bonesGroup.isVisible()) {
            return boneBalls.size();
        }
        for (int i = 0; i < boneBalls.size(); i++) {
            double[] m = world[ballJoints[i]];
            javafx.scene.shape.Sphere ball = boneBalls.get(i);
            ball.setVisible(m != null);
            if (m != null) {
                ball.setTranslateX(m[3]);
                ball.setTranslateY(-m[7]);
                ball.setTranslateZ(-m[11]);
            }
        }
        for (int i = 0; i < boneSticks.size(); i++) {
            int[] link = boneLinks.get(i);
            double[] a = world[link[0]], b = world[link[1]];
            javafx.scene.shape.Cylinder c = boneSticks.get(i);
            if (a == null || b == null) {
                c.setVisible(false);
                continue;
            }
            placeBone(c, new Point3D(a[3], -a[7], -a[11]), new Point3D(b[3], -b[7], -b[11]));
        }
        return boneBalls.size();
    }

    private void buildBoneNodes(SkeletonLoader.Loaded skeleton, double[][] world) {
        int n = world.length;
        PhongMaterial boneMat = new PhongMaterial(Color.BLACK);
        boneMat.setSpecularColor(Color.BLACK);
        boneMat.setSelfIlluminationMap(solid(BONE_COLOR));
        PhongMaterial jointMat = new PhongMaterial(Color.BLACK);
        jointMat.setSpecularColor(Color.BLACK);
        jointMat.setSelfIlluminationMap(solid(JOINT_COLOR));
        double r = radius * 0.006;
        java.util.Set<Integer> used = new java.util.HashSet<>();
        if (skeleton.skin() != null) {
            for (int j : skeleton.skin().palette()) {
                used.add(j);
            }
        }
        List<Node> nodes = new ArrayList<>();
        List<Integer> joints = new ArrayList<>();
        for (int j = 0; j < n; j++) {
            if (world[j] == null || (!used.isEmpty() && !used.contains(j))) {
                continue;
            }
            javafx.scene.shape.Sphere ball = new javafx.scene.shape.Sphere(r * 1.8, 8);
            ball.setMaterial(jointMat);
            ball.getProperties().put("joint", skeleton.skeleton().joints().get(j));
            boneBalls.add(ball);
            joints.add(j);
            nodes.add(ball);
            int p = skeleton.skeleton().parentOf(j);
            if (p >= 0 && p < n && world[p] != null && (used.isEmpty() || used.contains(p))) {
                javafx.scene.shape.Cylinder c = new javafx.scene.shape.Cylinder(r, 1, 6);
                c.setMaterial(boneMat);
                boneSticks.add(c);
                boneLinks.add(new int[] {p, j});
                nodes.add(c);
            }
        }
        ballJoints = joints.stream().mapToInt(Integer::intValue).toArray();
        bonesGroup.getChildren().setAll(nodes);
    }

    /** A unit-height cylinder stretched and turned to run from a to b. */
    private static void placeBone(javafx.scene.shape.Cylinder c, Point3D a, Point3D b) {
        Point3D diff = b.subtract(a);
        double len = diff.magnitude();
        if (len < 1e-9) {
            c.setVisible(false);
            return;
        }
        c.setVisible(true);
        Point3D mid = a.midpoint(b);
        Point3D axis = Rotate.Y_AXIS.crossProduct(diff);
        double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, diff.normalize().dotProduct(Rotate.Y_AXIS)))));
        javafx.scene.transform.Transform turn = axis.magnitude() > 1e-9 ? new Rotate(angle, axis)
            : diff.getY() < 0 ? new Rotate(180, Rotate.X_AXIS) : new Rotate(0);
        // The stretch goes last in the list (innermost): the scaleY property would apply in world space.
        c.getTransforms().setAll(new Translate(mid.getX(), mid.getY(), mid.getZ()), turn, new javafx.scene.transform.Scale(1, len, 1));
    }

    // ---- posing ----------------------------------------------------------------

    /** Base vertex data of a skinned submesh (model space, before the axis flip). */
    /**
     * The player rig carries two skeletons in one: the first-person arms (fps_* joints) and the
     * third-person body, whose spine hangs from fps_main_bind. Clips drive both, and the
     * first-person joints carry camera-relative offsets (a crouch of about 0.17 in third-person
     * idles) that would slide the third-person torso off its hips. When the model's skin uses none
     * of the fps_* joints, those joints keep their bind pose.
     */
    private final java.util.Set<Integer> frozenAddresses = new java.util.HashSet<>();

    private void freezeOtherRig(SkeletonLoader.Loaded sk) {
        frozenAddresses.clear();
        if (sk.skin() == null) {
            return;
        }
        List<String> names = sk.skeleton().joints();
        for (int j : sk.skin().palette()) {
            if (j < names.size() && names.get(j).startsWith("fps_")) {
                return; // a first-person model
            }
        }
        for (int j = 0; j < names.size(); j++) {
            if (names.get(j).startsWith("fps_") && sk.skeleton().poseAddress(j) >= 0) {
                frozenAddresses.add(sk.skeleton().poseAddress(j));
            }
        }
    }

    /** Sylux's Lockjaw: submeshes mostly weighted to lockjaw_* joints get the Lockjaw toggle. */
    private void markLockjaw(SkeletonLoader.Loaded sk) {
        if (sk.skin() == null) {
            return;
        }
        int[] pal = sk.skin().palette();
        boolean any = false;
        for (MeshView v : meshViews) {
            if (!(v.getProperties().get("skin") instanceof SkinnedMesh m)) {
                continue;
            }
            double lock = 0, total = 0;
            for (int i = 0; i < m.weights().length; i++) {
                int slot = (int) m.joints()[i];
                double w = m.weights()[i];
                total += w;
                if (slot < pal.length && pal[slot] < sk.skeleton().joints().size()
                    && sk.skeleton().joints().get(pal[slot]).toLowerCase().contains("lockjaw")) {
                    lock += w;
                }
            }
            if (total > 0 && lock / total > 0.5) {
                v.getProperties().put("lockjaw", true);
                any = true;
            }
        }
        lockjawToggle.setVisible(any);
        lockjawToggle.setManaged(any);
    }

    private record SkinnedMesh(TriangleMesh mesh, float[] positions, float[] normals, float[] joints, float[] weights) {}

    /** Poses the model at the slider's frame (bind pose when no clip is decoded). */
    private void applyPose() {
        if (skeleton == null || bindChain == null) {
            return;
        }
        float frame = (float) frameSlider.getValue();
        if (anim != null && anim == posedAnim && frame == posedFrame) {
            return; // the timeline and the slider listener can both ask for the same frame
        }
        posedAnim = anim;
        posedFrame = frame;
        double[][] chain = anim == null ? bindChain
            : chainWorld(remapped(anim.sample((float) frameSlider.getValue(), null)));
        int n = chain.length;
        double[][] delta = new double[n][];
        if (chain != bindChain) {
            for (int j = 0; j < n; j++) {
                delta[j] = ChprSkeleton.mul(chain[j], ChprSkeleton.invertAffine(bindChain[j]));
            }
        }
        int[] palette = skeleton.skin() != null ? skeleton.skin().palette() : null;
        for (MeshView view : meshViews) {
            if (view.getProperties().get("skin") instanceof SkinnedMesh sm) {
                skin(sm, delta, palette);
            }
        }
        double[][] bindWorld = skeleton.bindWorld();
        double[][] posed = new double[n][];
        for (int j = 0; j < n; j++) {
            posed[j] = bindWorld[j] == null || delta[j] == null ? bindWorld[j] : ChprSkeleton.mul(delta[j], bindWorld[j]);
        }
        drawBones(posed);
        followModel(posed);
    }

    /** Per-mesh output buffers, reused every frame (allocating them per frame churned the GC). */
    private final Map<SkinnedMesh, float[][]> skinBuffers = new java.util.IdentityHashMap<>();

    private void skin(SkinnedMesh sm, double[][] delta, int[] palette) {
        float[] pos = sm.positions(), nrm = sm.normals();
        int count = pos.length / 3;
        float[][] buf = skinBuffers.computeIfAbsent(sm, k -> new float[][] {new float[count * 3], nrm != null ? new float[count * 3] : null});
        float[] out = buf[0];
        float[] nout = buf[1];
        for (int v = 0; v < count; v++) {
            double x = pos[v * 3], y = pos[v * 3 + 1], z = pos[v * 3 + 2];
            double ix = 0, iy = 0, iz = 0;
            if (nrm != null) {
                ix = nrm[v * 3];
                iy = nrm[v * 3 + 1];
                iz = nrm[v * 3 + 2];
            }
            double ox = 0, oy = 0, oz = 0, nx = 0, ny = 0, nz = 0, wsum = 0;
            for (int k = 0; k < 4; k++) {
                double w = sm.weights()[v * 4 + k];
                if (w <= 0) {
                    continue;
                }
                int slot = (int) sm.joints()[v * 4 + k];
                int j = palette != null && slot < palette.length ? palette[slot] : slot;
                double[] m = j >= 0 && j < delta.length ? delta[j] : null;
                if (m == null) {
                    ox += w * x;
                    oy += w * y;
                    oz += w * z;
                    nx += w * ix;
                    ny += w * iy;
                    nz += w * iz;
                } else {
                    ox += w * (m[0] * x + m[1] * y + m[2] * z + m[3]);
                    oy += w * (m[4] * x + m[5] * y + m[6] * z + m[7]);
                    oz += w * (m[8] * x + m[9] * y + m[10] * z + m[11]);
                    nx += w * (m[0] * ix + m[1] * iy + m[2] * iz);
                    ny += w * (m[4] * ix + m[5] * iy + m[6] * iz);
                    nz += w * (m[8] * ix + m[9] * iy + m[10] * iz);
                }
                wsum += w;
            }
            if (wsum <= 0) {
                ox = x;
                oy = y;
                oz = z;
                nx = ix;
                ny = iy;
                nz = iz;
            } else if (Math.abs(wsum - 1) > 1e-3) {
                ox /= wsum;
                oy /= wsum;
                oz /= wsum;
            }
            // Same axis mapping as buildMeshes: (x, y, z) -> (x, -y, -z).
            out[v * 3] = (float) ox;
            out[v * 3 + 1] = (float) -oy;
            out[v * 3 + 2] = (float) -oz;
            if (nout != null) {
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > 1e-9) {
                    nx /= len;
                    ny /= len;
                    nz /= len;
                }
                nout[v * 3] = (float) nx;
                nout[v * 3 + 1] = (float) -ny;
                nout[v * 3 + 2] = (float) -nz;
            }
        }
        sm.mesh().getPoints().setAll(out);
        if (nout != null) {
            sm.mesh().getNormals().setAll(nout);
        }
    }

    /**
     * Joint world matrices chained from local transforms: the clip's sampled
     * transform at each joint's pose address (bind values where the clip has
     * none, or for components it holds constant), roots anchored at their bind
     * parent frame. {@code pose == null} gives the bind chain.
     */
    private double[][] chainWorld(AnimClip.Pose pose) {
        ChprSkeleton sk = skeleton.skeleton();
        double[][] bindWorld = skeleton.bindWorld();
        double[][] out = new double[bindWorld.length][];
        double[] root = rootMotion(pose);
        for (int j = 0; j < bindWorld.length; j++) {
            chainJoint(j, sk, bindWorld, pose, root, out, 0);
        }
        return out;
    }

    /** Pose addresses some joint owns; root motion only uses transforms nobody claims. */
    private final java.util.Set<Integer> claimedAddresses = new java.util.HashSet<>();

    /**
     * Whole-body motion: the clip writes the body's placement (yaw, rolls, falls) at index 0 of the
     * top joint's slot and the actor's travel at index 0 of the next slot -- addresses that belong
     * to root joints whose hierarchy records aren't decoded (Skeleton_Root / *.anchorBlend). Applied
     * above every joint without hierarchy data. Null when the clip has none.
     */
    private double[] rootMotion(AnimClip.Pose pose) {
        if (pose == null || followJoint < 0) {
            return null;
        }
        int slot = skeleton.skeleton().poseAddress(followJoint) & 63;
        float[] body = claimedAddresses.contains(slot) ? null : pose.transforms.get(slot);
        float[] travel = claimedAddresses.contains(slot + 1) ? null : pose.transforms.get(slot + 1);
        if (body == null && travel == null) {
            return null;
        }
        double qw = 1, qx = 0, qy = 0, qz = 0, tx = 0, ty = 0, tz = 0;
        if (body != null) {
            if (!Float.isNaN(body[0])) {
                qw = body[0];
                qx = body[1];
                qy = body[2];
                qz = body[3];
            }
            int t = body.length == 10 ? 7 : 4;
            if (!Float.isNaN(body[t])) {
                tx += body[t];
                ty += body[t + 1];
                tz += body[t + 2];
            }
        }
        if (travel != null) {
            int t = travel.length == 10 ? 7 : 4;
            if (!Float.isNaN(travel[t])) {
                tx += travel[t];
                ty += travel[t + 1];
                tz += travel[t + 2];
            }
        }
        return ChprSkeleton.quatMat(qw, qx, qy, qz, tx, ty, tz);
    }

    private static double[] chainJoint(int j, ChprSkeleton sk, double[][] bindWorld, AnimClip.Pose pose, double[] root,
                                       double[][] out, int depth) {
        if (out[j] != null) {
            return out[j];
        }
        float[] bind = sk.localTRS(j);
        double[] bw = bindWorld[j] != null ? bindWorld[j] : ChprSkeleton.identity();
        if (bind == null) {
            return out[j] = root != null ? ChprSkeleton.mul(root, bw) : bw;
        }
        double[] local = localMatrix(bind, pose != null ? pose.transforms.get(sk.poseAddress(j)) : null);
        int p = sk.parentOf(j);
        if (p >= 0 && p < bindWorld.length && p != j && depth < 512) {
            return out[j] = ChprSkeleton.mul(chainJoint(p, sk, bindWorld, pose, root, out, depth + 1), local);
        }
        double[] anchor = ChprSkeleton.mul(bw, ChprSkeleton.invertAffine(localMatrix(bind, null)));
        if (root != null) {
            anchor = ChprSkeleton.mul(root, anchor);
        }
        return out[j] = ChprSkeleton.mul(anchor, local);
    }

    /**
     * T * R * S of a joint: clip transforms (10 floats: q, s, t; 7: q, t) are
     * relative to the bind pose -- rotation clip * bind (the clip rotation
     * applied in parent space), translation bind + clip offset, scale as given. Constants the clip holds (NaN until the
     * clip constant pool is located) count as identity / zero, which is what
     * the pool references seen so far contain.
     */
    private static double[] localMatrix(float[] bind, float[] x) {
        double qw = bind[0], qx = bind[1], qy = bind[2], qz = bind[3];
        double tx = bind[4], ty = bind[5], tz = bind[6];
        double sx = 1, sy = 1, sz = 1;
        if (x != null) {
            if (!Float.isNaN(x[0])) {
                double aw = x[0], ax = x[1], ay = x[2], az = x[3];
                double bw = qw, bx = qx, by = qy, bz = qz;
                qw = aw * bw - ax * bx - ay * by - az * bz;
                qx = aw * bx + ax * bw + ay * bz - az * by;
                qy = aw * by - ax * bz + ay * bw + az * bx;
                qz = aw * bz + ax * by - ay * bx + az * bw;
            }
            int t = x.length == 10 ? 7 : 4;
            if (!Float.isNaN(x[t])) {
                tx += x[t];
                ty += x[t + 1];
                tz += x[t + 2];
            }
            if (x.length == 10 && !Float.isNaN(x[4])) {
                sx = x[4];
                sy = x[5];
                sz = x[6];
            }
        }
        double[] m = ChprSkeleton.quatMat(qw, qx, qy, qz, tx, ty, tz);
        for (int r = 0; r < 3; r++) {
            m[r * 4] *= sx;
            m[r * 4 + 1] *= sy;
            m[r * 4 + 2] *= sz;
        }
        return m;
    }


    private static Image solid(Color c) {
        javafx.scene.image.WritableImage img = new javafx.scene.image.WritableImage(1, 1);
        img.getPixelWriter().setColor(0, 0, c);
        return img;
    }

    /** A thin cylinder from {@code a} to {@code b} (JavaFX cylinders run along Y, centred). */
    private AnimClip.Player posedAnim;
    private float posedFrame = Float.NaN;

    private static Node boneBetween(Point3D a, Point3D b, double r, PhongMaterial mat) {
        Point3D diff = b.subtract(a);
        double len = diff.magnitude();
        if (len < 1e-6) {
            return null;
        }
        javafx.scene.shape.Cylinder c = new javafx.scene.shape.Cylinder(r, len, 6);
        c.setMaterial(mat);
        Point3D mid = a.midpoint(b);
        Point3D axis = Rotate.Y_AXIS.crossProduct(diff);
        double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, diff.normalize().dotProduct(Rotate.Y_AXIS)))));
        c.getTransforms().add(new Translate(mid.getX(), mid.getY(), mid.getZ()));
        if (axis.magnitude() > 1e-9) {
            c.getTransforms().add(new Rotate(angle, axis));
        } else if (diff.getY() < 0) {
            c.getTransforms().add(new Rotate(180, Rotate.X_AXIS));
        }
        return c;
    }

    /** Decodes each material's base colour and detail maps, applying each material as it arrives. */
    private void loadTextures(ModelLoader.LoadedModel loaded, List<MeshView> views) {
        java.util.Set<Integer> keys = new java.util.HashSet<>();
        for (MeshView v : views) {
            keys.add((int) v.getProperties().get("material"));
        }
        Map<String, Image> byTexture = new HashMap<>();
        Map<String, Image[]> byFrames = new HashMap<>();
        List<PakAssetRef> refs = loaded.baseColorTextures();
        for (int key : keys) {
            int mat = key < 0 ? -key - 1 : key;
            if (disposed) {
                return;
            }
            PakAssetRef base = mat < refs.size() ? refs.get(mat) : null;
            PakAssetRef lightRamp = LIGHT_STRIP_SHADER.equals(loaded.model().materials().get(mat).shader()) ? loaded.slot(mat, "BCLR:6") : null;
            if (lightRamp != null) {
                Image ramp = cached(byTexture, lightRamp.asset().assetId() + ":strip", () -> toImage(decodeRaw(lightRamp, MAX_TEXTURE_SIZE)));
                if (ramp != null) {
                    Platform.runLater(() -> {
                        textures.put(key, ramp);
                        glowMaps.put(key, new Image[] {ramp});
                        applyTextures();
                    });
                }
                continue;
            }
            // Blended submeshes: alpha is opacity (visor setting). Opaque ones keep it only when it's a
            // cut-out (eyelashes on the face texture) -- otherwise it's a glow mask.
            boolean cutout = key >= 0 && base != null && isCutout(base);
            // Blended visors follow Settings > Transparent visor; other blended meshes (holograms,
            // icon cards, glass) always keep their transparency.
            boolean keepAlpha = key < 0 ? !blendedVisorSnapshot.contains(key) || AppSettings.get().transparentVisorProperty().get() : cutout;
            if (cutout) {
                Platform.runLater(() -> drawCutoutsAfterOpaque(key));
            }
            double frost = base != null && isFrostLayer(loaded, mat, base) ? 0 : -1;
            PaintStrip strip = frost >= 0 ? null : paintOf(loaded, mat);
            PaintStrip paint = frost >= 0 ? new PaintStrip(null, null, frost) : strip;
            String paintKey = frost >= 0 ? ":f" + Math.round(frost * 100)
                : strip == null ? "" : ":p:" + strip.ramp().asset().assetId() + strip.mask().asset().assetId();
            Image diffuse = base == null ? null : cached(byTexture, base.asset().assetId() + paintKey + (keepAlpha ? ":a" : ""),
                () -> toImage(baseColor(base, paint, keepAlpha, MAX_TEXTURE_SIZE)));
            PakAssetRef nmap = loaded.slot(mat, "NMAP");
            Image normal = nmap == null ? null : cached(byTexture, nmap.asset().assetId() + ":n",
                () -> decodeNormalMap(nmap, MAX_TEXTURE_SIZE));
            PakAssetRef metl = loaded.slot(mat, "METL");
            Image specular = metl == null || base == null ? null
                : cached(byTexture, metl.asset().assetId() + ":s:" + base.asset().assetId() + paintKey,
                    () -> decodeSpecularMap(metl, baseColor(base, paint, false, MAX_TEXTURE_SIZE), MAX_TEXTURE_SIZE));
            Image metalDiffuse = metl == null || base == null ? null
                : cached(byTexture, base.asset().assetId() + ":m:" + metl.asset().assetId() + paintKey + (keepAlpha ? ":a" : ""),
                    () -> decodeMetalDiffuse(baseColor(base, paint, keepAlpha, MAX_TEXTURE_SIZE), metl, MAX_TEXTURE_SIZE));
            PakAssetRef ican = loaded.slot(mat, "ICAN");
            boolean blended = key < 0 || isVisorKey(key);
            float[] tint = loaded.glowColor(mat);
            String glowKey = ican == null || base == null ? null : base.asset().assetId() + ":g:" + ican.asset().assetId() + (blended ? ":b" : "")
                + (tint == null ? "" : ":t" + java.util.Arrays.toString(tint));
            Image[] maskGlow = glowKey == null ? null
                : cachedFrames(byFrames, glowKey, () -> decodeGlowMap(base, ican, blended, tint, MAX_TEXTURE_SIZE));
            // The layered shader's emissive layer (the slots on top of the cannon): lit, and black underneath
            // -- in game it replaces the base colour, and the purple base turned the green glow aqua.
            // Its green lights are steady in game, though its table changes down its length: one frame.
            boolean layered = !blended && LAYERED_SHADER.equals(loaded.model().materials().get(mat).shader());
            Image[] steady = layered && maskGlow != null && maskGlow.length > 1 ? new Image[] {maskGlow[maskGlow.length / 2]} : maskGlow;
            LayerMask layer = glowKey == null || metl == null || !layered ? null : emissiveLayerOrNull(metl);
            Image[] glow = layer == null ? steady
                : cachedFrames(byFrames, glowKey + ":L:" + metl.asset().assetId(), () -> withEmissiveLayer(steady, layer, ican));
            Image baseOut = layer == null || diffuse == null ? diffuse : blackOut(diffuse, layer);
            Image metalOut = layer == null || metalDiffuse == null ? metalDiffuse : blackOut(metalDiffuse, layer);
            boolean holo = isHologram(loaded, mat);
            Image[] holoImages = holo ? cachedFrames(byFrames, "holo:" + mat + ":" + loaded.model().materials().get(mat).materialInstance(),
                () -> decodeHologram(base, loaded.slot(mat, "TCH0"), ican, MAX_TEXTURE_SIZE)) : null;
            if (holoImages != null) {
                Image[] hi = holoImages;
                Platform.runLater(() -> {
                    hologramKeys.add(key);
                    textures.put(key, hi[0]);
                    metalTextures.remove(key);
                    specularMaps.remove(key);
                    glowMaps.put(key, new Image[] {hi[1]});
                    applyTextures();
                });
                continue;
            }
            String weapon = ican == null ? null : WEAPON_GLOWS.get(ican.asset().assetId());
            Platform.runLater(() -> {
                if (weapon != null && weaponKeys.put(key, weapon) == null) {
                    showWeaponToggles();
                }
                if (baseOut != null) textures.put(key, baseOut);
                if (metalOut != null) metalTextures.put(key, metalOut);
                if (normal != null) normalMaps.put(key, normal);
                if (specular != null) specularMaps.put(key, specular);
                if (glow != null) glowMaps.put(key, glow);
                applyTextures();
            });
        }
    }

    /** Hologram materials (see {@link #isHologram}): drawn translucent and glowing, no lighting. */
    private final java.util.Set<Integer> hologramKeys = new java.util.HashSet<>();
    private static final java.util.Set<java.util.UUID> HOLOGRAM_SHADERS = java.util.Set.of(
        java.util.UUID.fromString("ef1043d8-7a6e-4050-b95e-92439601049d"));  // holo tables, trooper holograms

    /** The hologram shader (map tables, trooper / creature holograms), or a material with only a glow strip. */
    static boolean isHologram(ModelLoader.LoadedModel loaded, int mat) {
        if (mat >= loaded.model().materials().size()) {
            return false;
        }
        return HOLOGRAM_SHADERS.contains(loaded.model().materials().get(mat).shader())
            || (loaded.slot(mat, "BCLR") == null && loaded.slot(mat, "ICAN") != null);
    }

    /**
     * {diffuse, glow} for a hologram: the diffuse is black with the base colour's alpha as opacity
     * (holograms don't reflect light), the glow is the base colour tinted by the TCH0 colour strip
     * (or, without a base, the ICAN strip's average colour).
     */
    static Image[] decodeHologram(PakAssetRef base, PakAssetRef strip, PakAssetRef ican, int maxSize) throws java.io.IOException {
        int tint = 0xFFFFFF;
        PakAssetRef tintRef = strip != null ? strip : ican;
        if (tintRef != null) {
            int[] t = decodeRaw(tintRef, 256).argb();
            long r = 0, g = 0, b = 0;
            for (int p : t) {
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
            }
            int n = t.length;
            double max = Math.max(1, Math.max(r, Math.max(g, b)) / (double) n);
            // Normalised so the brightest channel is full: a hue, not a dimmer.
            tint = (int) (r / n * 255 / max) << 16 | (int) (g / n * 255 / max) << 8 | (int) (b / n * 255 / max);
        }
        int tr = (tint >> 16) & 0xFF, tg = (tint >> 8) & 0xFF, tb = tint & 0xFF;
        if (base == null) {
            // Strip-only holograms (the CSI emblem): half see-through, glowing at half strength.
            int[] diffuse = {0x70000000};
            int[] glow = {0xFF000000 | (tr / 2) << 16 | (tg / 2) << 8 | tb / 2};
            return new Image[] {toImageRaw(1, 1, diffuse), toImageRaw(1, 1, glow)};
        }
        TextureDecoder.Image img = decodeRaw(base, maxSize);
        int[] px = img.argb();
        int[] diffuse = new int[px.length], glow = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            int c = px[i], a = c >>> 24;
            double lum = (((c >> 16) & 0xFF) * 0.3 + ((c >> 8) & 0xFF) * 0.59 + (c & 0xFF) * 0.11) / 255.0;
            double k = Math.min(1, 0.35 + lum) * (0.4 + 0.6 * a / 255.0);
            diffuse[i] = Math.min(255, a + 40) << 24;
            glow[i] = 0xFF000000 | (int) (tr * k) << 16 | (int) (tg * k) << 8 | (int) (tb * k);
        }
        return new Image[] {toImage(img.width(), img.height(), diffuse), toImage(img.width(), img.height(), glow)};
    }

    /** Visor keys are filled on the FX thread right after the meshes are built (before textures load). */
    private volatile java.util.Set<Integer> visorKeySnapshot = java.util.Set.of();
    /** Blended submeshes that look like visors (small, bound to one joint): the only ones the setting makes opaque. */
    private volatile java.util.Set<Integer> blendedVisorSnapshot = java.util.Set.of();

    private boolean isVisorKey(int key) {
        return visorKeySnapshot.contains(key);
    }

    private interface Decode {
        Image run() throws Exception;
    }

    private interface DecodeFrames {
        Image[] run() throws Exception;
    }

    private static Image[] cachedFrames(Map<String, Image[]> cache, String key, DecodeFrames decode) {
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        Image[] frames;
        try {
            frames = decode.run();
        } catch (Exception | OutOfMemoryError e) {
            frames = null;
        }
        cache.put(key, frames);
        return frames;
    }

    private static LayerMask emissiveLayerOrNull(PakAssetRef metl) {
        try {
            return emissiveLayer(metl, MAX_TEXTURE_SIZE);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static Image cached(Map<String, Image> cache, String key, Decode decode) {
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        Image image;
        try {
            image = decode.run();
        } catch (Exception | OutOfMemoryError e) {
            image = null;
        }
        cache.put(key, image);
        return image;
    }

    /** The largest mip of the texture no bigger than {@code maxSize}, decoded to ARGB. */
    private static TextureDecoder.Image decodeRaw(PakAssetRef ref, int maxSize) throws java.io.IOException {
        Texture t = TxtrReader.read(PackCatalog.extractDecompressed(ref.pakFile(), ref.asset()), ref.asset().meta());
        TextureHeader h = t.header();
        int mip = 0;
        while (mip < h.mipCount() - 1 && Math.max(h.mipWidth(mip), h.mipHeight(mip)) > maxSize) {
            mip++;
        }
        return TextureDecoder.decode(t, 0, mip, 0);
    }

    /**
     * JavaFX samples 3D textures without filtering, so small textures show hard texel blocks up
     * close (Samus's 256px face, stair-stepped edges): they're upscaled bilinearly to >= 1024 first.
     */
    private static final int SMOOTH_SIZE = 1024;

    private static Image toImage(int w, int h, int[] argb) {
        if (AppSettings.get().smoothTexturesProperty().get() && Math.max(w, h) <= 2048) {
            return toImageRaw(w * 2, h * 2, smoothUpscale(w, h, argb));
        }
        int f = 1;
        while (Math.max(w, h) * f < SMOOTH_SIZE && (long) w * h * f * f * 4 <= 2048L * 2048) {
            f *= 2;
        }
        if (f > 1) {
            TextureDecoder.Image src = new TextureDecoder.Image(w, h, argb);
            int W = w * f, H = h * f;
            int[] big = new int[W * H];
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    big[y * W + x] = sample(src, x, y, W, H);
                }
            }
            return toImageRaw(W, H, big);
        }
        return toImageRaw(w, h, argb);
    }

    /**
     * Settings > Extra smooth textures: 2x upscale with Catmull-Rom (bicubic) interpolation, then
     * a light [1 2 1] blur. The game's textures have hard 1-2 texel seams (normal-map panel lines);
     * JavaFX magnifies them bilinearly, which shows their stair steps up close. This rounds them
     * off at 4x the memory per texture.
     */
    static int[] smoothUpscale(int w, int h, int[] src) {
        int W = w * 2, H = h * 2;
        int[] up = new int[W * H];
        double[] wx = new double[4], wy = new double[4];
        for (int y = 0; y < H; y++) {
            double fy = (y + 0.5) / 2 - 0.5;
            int y0 = (int) Math.floor(fy);
            cubicWeights(fy - y0, wy);
            for (int x = 0; x < W; x++) {
                double fx = (x + 0.5) / 2 - 0.5;
                int x0 = (int) Math.floor(fx);
                cubicWeights(fx - x0, wx);
                double a = 0, r = 0, g = 0, b = 0;
                for (int j = 0; j < 4; j++) {
                    int yy = Math.max(0, Math.min(h - 1, y0 - 1 + j));
                    for (int i = 0; i < 4; i++) {
                        int xx = Math.max(0, Math.min(w - 1, x0 - 1 + i));
                        int p = src[yy * w + xx];
                        double k = wx[i] * wy[j];
                        a += (p >>> 24) * k;
                        r += ((p >> 16) & 0xFF) * k;
                        g += ((p >> 8) & 0xFF) * k;
                        b += (p & 0xFF) * k;
                    }
                }
                up[y * W + x] = clamp8(a) << 24 | clamp8(r) << 16 | clamp8(g) << 8 | clamp8(b);
            }
        }
        // [1 2 1] x [1 2 1] / 16
        int[] out = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int a = 0, r = 0, g = 0, b = 0;
                for (int j = -1; j <= 1; j++) {
                    int yy = Math.max(0, Math.min(H - 1, y + j));
                    for (int i = -1; i <= 1; i++) {
                        int xx = Math.max(0, Math.min(W - 1, x + i));
                        int k = (i == 0 ? 2 : 1) * (j == 0 ? 2 : 1);
                        int p = up[yy * W + xx];
                        a += (p >>> 24) * k;
                        r += ((p >> 16) & 0xFF) * k;
                        g += ((p >> 8) & 0xFF) * k;
                        b += (p & 0xFF) * k;
                    }
                }
                out[y * W + x] = (a / 16) << 24 | (r / 16) << 16 | (g / 16) << 8 | b / 16;
            }
        }
        return out;
    }

    private static void cubicWeights(double t, double[] w) {
        double t2 = t * t, t3 = t2 * t;
        w[0] = 0.5 * (-t3 + 2 * t2 - t);
        w[1] = 0.5 * (3 * t3 - 5 * t2 + 2);
        w[2] = 0.5 * (-3 * t3 + 4 * t2 + t);
        w[3] = 0.5 * (t3 - t2);
    }

    private static int clamp8(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    private static Image toImageRaw(int w, int h, int[] argb) {
        WritableImage out = new WritableImage(w, h);
        out.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
        return out;
    }

    /**
     * NMAP is a tangent-space normal map: RGB with blue up, or (most ASTC ones) two-channel with X
     * in RGB and Y in alpha -- the usual ASTC normal-map swizzle; reading those as RGB gave the
     * arm cannon its blotchy, z-fight-like shading. The preview flips texture V, which mirrors the
     * bitangent, so Y is inverted; two-channel maps get Z rebuilt.
     */
    private static final double NORMAL_STRENGTH = 1.0;

    static Image decodeNormalMap(PakAssetRef ref, int maxSize) throws java.io.IOException {
        TextureDecoder.Image img = decodeRaw(ref, maxSize);
        int[] px = img.argb();
        boolean xInRgbYInAlpha = isLuminanceAlpha(px);
        int[] out = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            int r = (px[i] >> 16) & 0xFF;
            int g = 255 - (xInRgbYInAlpha ? px[i] >>> 24 : (px[i] >> 8) & 0xFF);
            int b = xInRgbYInAlpha ? 0 : px[i] & 0xFF;
            double x = r / 127.5 - 1, y = g / 127.5 - 1, z;
            if (b < 16) {
                z = Math.sqrt(Math.max(0, 1 - x * x - y * y));
            } else {
                z = b / 127.5 - 1;
            }
            // Strengthened: JavaFX's simple lighting (no shadows, reflections or ambient occlusion)
            // flattens the panel grooves the game's lighting brings out.
            x *= NORMAL_STRENGTH;
            y *= NORMAL_STRENGTH;
            double len = Math.sqrt(x * x + y * y + z * z);
            if (len > 1e-6) {
                x /= len;
                y /= len;
                z /= len;
            }
            r = (int) Math.round((x * 0.5 + 0.5) * 255);
            g = (int) Math.round((y * 0.5 + 0.5) * 255);
            b = (int) Math.round((z * 0.5 + 0.5) * 255);
            out[i] = 0xFF000000 | r << 16 | g << 8 | b;
        }
        return toImage(img.width(), img.height(), out);
    }

    /** True when R == G == B (nearly) everywhere while alpha varies: X in RGB, Y in alpha. */
    private static boolean isLuminanceAlpha(int[] px) {
        int grey = 0, n = 0, aMin = 255, aMax = 0;
        for (int i = 0; i < px.length; i += 61) {
            int p = px[i], r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF, a = p >>> 24;
            if (Math.abs(r - g) <= 3 && Math.abs(g - b) <= 3) {
                grey++;
            }
            aMin = Math.min(aMin, a);
            aMax = Math.max(aMax, a);
            n++;
        }
        return n > 0 && grey > n * 0.97 && aMax - aMin > 32;
    }

    /**
     * METL packs metalness (R) and roughness (G). Shine = smoothness^2, stronger on metal, and
     * tinted by the base colour on metal (metal highlights take its colour, the rest stay white).
     */
    static Image decodeSpecularMap(PakAssetRef ref, TextureDecoder.Image col, int maxSize) throws java.io.IOException {
        TextureDecoder.Image img = decodeRaw(ref, maxSize);
        int[] px = img.argb();
        int w = img.width(), h = img.height();
        int[] out = new int[px.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                double metal = ((px[i] >> 16) & 0xFF) / 255.0;
                double smooth = 1 - ((px[i] >> 8) & 0xFF) / 255.0;
                double k = Math.min(1, smooth * smooth * (0.3 + 0.7 * metal));
                int c = sample(col, x, y, w, h);
                int r = (int) Math.round(k * 255 * (1 - metal + metal * ((c >> 16) & 0xFF) / 255.0));
                int g = (int) Math.round(k * 255 * (1 - metal + metal * ((c >> 8) & 0xFF) / 255.0));
                int b = (int) Math.round(k * 255 * (1 - metal + metal * (c & 0xFF) / 255.0));
                out[i] = 0xFF000000 | r << 16 | g << 8 | b;
            }
        }
        return toImage(w, h, out);
    }

    /** How much metal darkens the base colour (it's lit mostly by reflections, which aren't drawn). */
    private static final double METAL_DARKEN = 0.55;

    /** Base colour darkened where METL marks metal -- the Varia suit's bronze, the arm cannon's dark teal. */
    static Image decodeMetalDiffuse(TextureDecoder.Image col, PakAssetRef metl, int maxSize) throws java.io.IOException {
        TextureDecoder.Image m = decodeRaw(metl, maxSize);
        int w = col.width(), h = col.height();
        int[] px = col.argb();
        int[] out = new int[px.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x, c = px[i];
                double f = 1 - METAL_DARKEN * ((sample(m, x, y, w, h) >> 16) & 0xFF) / 255.0;
                out[i] = (c & 0xFF000000) | (int) (((c >> 16) & 0xFF) * f) << 16 | (int) (((c >> 8) & 0xFF) * f) << 8 | (int) ((c & 0xFF) * f);
            }
        }
        return toImage(w, h, out);
    }

    /**
     * Paint: a 1-pixel-tall colour strip (TCH1) indexed by a grey map in the model's UV layout
     * (TCH2) -- the Viola bikes' iridescent finish (the grey base colour is multiplied by it).
     */
    record PaintStrip(PakAssetRef ramp, PakAssetRef mask, double frost) {
        PaintStrip(PakAssetRef ramp, PakAssetRef mask) {
            this(ramp, mask, -1);
        }
    }

    /** The arm cannon's gunmetal (its base texture's average teal, darkened as metal). */
    private static final int CANNON_TEAL = 0x3E5A58;
    private static final java.util.UUID FROST_SHADER = java.util.UUID.fromString("3425eca6-4e6b-4deb-ae61-9fc6fce4f12a");

    /**
     * Cannon-shader materials whose base colour is white where its alpha is low (the fire-shot gun
     * parts): the shader colours those areas; drawn as the cannon's gunmetal.
     */
    static boolean isFrostLayer(ModelLoader.LoadedModel loaded, int mat, PakAssetRef base) {
        if (mat >= loaded.model().materials().size() || !FROST_SHADER.equals(loaded.model().materials().get(mat).shader())) {
            return false;
        }
        try {
            int[] px = decodeRaw(base, 128).argb();
            int low = 0, whiteLow = 0;
            for (int p : px) {
                if ((p >>> 24) < 64) {
                    low++;
                    if (((p >> 16) & 0xFF) > 200 && ((p >> 8) & 0xFF) > 200 && (p & 0xFF) > 200) {
                        whiteLow++;
                    }
                }
            }
            return low > px.length / 3 && whiteLow > low * 0.6;
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    static PaintStrip paintOf(ModelLoader.LoadedModel loaded, int mat) {
        PakAssetRef ramp = loaded.slot(mat, "TCH1"), mask = loaded.slot(mat, "TCH2");
        if (ramp == null || mask == null) {
            return null;
        }
        try {
            TextureHeader rh = header(ramp), mh = header(mask);
            return rh.height() == 1 && rh.width() >= 16 && mh.width() >= 64 ? new PaintStrip(ramp, mask) : null;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** The base colour (BCLR) as shown: painted when the material has a paint strip, alpha kept or made opaque. */
    static TextureDecoder.Image baseColor(PakAssetRef base, PaintStrip paint, boolean keepAlpha, int maxSize) throws java.io.IOException {
        TextureDecoder.Image img = decodeRaw(base, maxSize);
        int w = img.width(), h = img.height();
        int[] px = img.argb().clone();
        if (paint != null && paint.frost() >= 0) {
            double f = paint.frost();
            int tr = (CANNON_TEAL >> 16) & 0xFF, tg = (CANNON_TEAL >> 8) & 0xFF, tb = CANNON_TEAL & 0xFF;
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                double a = (c >>> 24) / 255.0;
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                // Clean: gunmetal where the frost mask (low alpha) is, the texture elsewhere.
                double cr = tr + (r - tr) * a, cg = tg + (g - tg) * a, cb = tb + (b - tb) * a;
                px[i] = 0xFF000000 | (int) Math.round(cr + (r - cr) * f) << 16 | (int) Math.round(cg + (g - cg) * f) << 8
                    | (int) Math.round(cb + (b - cb) * f);
            }
        } else if (paint != null) {
            int[] ramp = decodeRaw(paint.ramp(), 4096).argb();
            TextureDecoder.Image mask = decodeRaw(paint.mask(), maxSize);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x, c = px[i];
                    int t = ramp[((sample(mask, x, y, w, h) >> 16) & 0xFF) * (ramp.length - 1) / 255];
                    px[i] = (c & 0xFF000000) | (((c >> 16) & 0xFF) * ((t >> 16) & 0xFF) / 255) << 16
                        | (((c >> 8) & 0xFF) * ((t >> 8) & 0xFF) / 255) << 8 | (c & 0xFF) * (t & 0xFF) / 255;
                }
            }
        }
        if (!keepAlpha) {
            for (int i = 0; i < px.length; i++) {
                px[i] |= 0xFF000000;
            }
        }
        return new TextureDecoder.Image(w, h, px);
    }

    private static Image toImage(TextureDecoder.Image img) {
        return toImage(img.width(), img.height(), img.argb());
    }

    /**
     * {@code img} at pixel (x, y) of a w x h grid, bilinear -- the textures combined can differ in
     * size, and nearest sampling turned every edge of a half-size METL into stair steps.
     */
    private static int sample(TextureDecoder.Image img, int x, int y, int w, int h) {
        int iw = img.width(), ih = img.height();
        if (iw == w && ih == h) {
            return img.argb()[y * w + x];
        }
        double fx = Math.max(0, (x + 0.5) * iw / w - 0.5), fy = Math.max(0, (y + 0.5) * ih / h - 0.5);
        int x0 = Math.min(iw - 1, (int) fx), y0 = Math.min(ih - 1, (int) fy);
        int x1 = Math.min(iw - 1, x0 + 1), y1 = Math.min(ih - 1, y0 + 1);
        double ax = fx - x0, ay = fy - y0;
        int[] px = img.argb();
        int p00 = px[y0 * iw + x0], p10 = px[y0 * iw + x1], p01 = px[y1 * iw + x0], p11 = px[y1 * iw + x1];
        int out = 0;
        for (int sh = 0; sh < 32; sh += 8) {
            double top = ((p00 >>> sh) & 0xFF) * (1 - ax) + ((p10 >>> sh) & 0xFF) * ax;
            double bottom = ((p01 >>> sh) & 0xFF) * (1 - ax) + ((p11 >>> sh) & 0xFF) * ax;
            out |= ((int) Math.round(top * (1 - ay) + bottom * ay) & 0xFF) << sh;
        }
        return out;
    }

    /**
     * Base-colour alpha that is a cut-out rather than a glow mask: mostly opaque, with a real share
     * of fully clear pixels (the face texture's eyelash cards). Glow masks are mostly clear.
     */
    static boolean isCutout(PakAssetRef base) {
        try {
            int[] px = decodeRaw(base, 256).argb();
            long sum = 0;
            int clear = 0;
            for (int p : px) {
                int a = p >>> 24;
                sum += a;
                if (a < 24) {
                    clear++;
                }
            }
            return sum / px.length > 150 && clear > px.length / 100;
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    /** Cut-out submeshes blend their clear pixels, so they draw after every opaque one (before blended). */
    private void drawCutoutsAfterOpaque(int key) {
        List<Node> kids = new ArrayList<>(modelGroup.getChildren());
        List<Node> cut = kids.stream().filter(n -> Integer.valueOf(key).equals(n.getProperties().get("material"))).toList();
        if (cut.isEmpty()) {
            return;
        }
        kids.removeAll(cut);
        int at = 0;
        while (at < kids.size() && (int) kids.get(at).getProperties().getOrDefault("material", 0) >= 0
            && !Boolean.TRUE.equals(kids.get(at).getProperties().get("visor"))) {
            at++;
        }
        kids.addAll(at, cut);
        modelGroup.getChildren().setAll(kids);
    }

    /**
     * The arm cannon's light strips: no texture slots of type 5, a 128x8 colour ramp (dark grey ->
     * orange -> pale yellow) as a type-6 base colour, mapped by UV0. Drawn as a steady light (the ramp
     * as base colour and glow); in game the light runs along the strip -- not reproduced yet.
     */
    private static final java.util.UUID LIGHT_STRIP_SHADER = java.util.UUID.fromString("c558787e-2ae0-4088-a233-56335f79f59e");
    /** The cannon's layered shader (TCH0..TCH5 detail layers); METL blue picks a layer per texel. */
    private static final java.util.UUID LAYERED_SHADER = java.util.UUID.fromString("fdf33b34-ce0a-4a6b-8c12-78e60953ac92");
    /** METL blue of the layered shader's emissive layer: only the two small slots on top of the cannon
     *  (purple in the base colour, green lights in game). The other layer values are 0, 12, 56, 76, 255. */
    private static final int EMISSIVE_LAYER_ID = 40;

    /** Texels of the layered shader's emissive layer, on the METL texture's grid. */
    record LayerMask(boolean[] lit, int width, int height) {
        boolean at(int x, int y, int w, int h) {
            return lit[(y * height / h) * width + x * width / w];
        }
    }

    /**
     * The layered shader's emissive layer: METL blue = {@link #EMISSIVE_LAYER_ID}, or null when the
     * material has none. Texels need most of their 3x3 neighbours in the layer too: blends between
     * other layers pass through the value along their edges.
     */
    static LayerMask emissiveLayer(PakAssetRef metl, int maxSize) throws java.io.IOException {
        TextureDecoder.Image ids = decodeRaw(metl, maxSize);
        int iw = ids.width(), ih = ids.height();
        boolean[] band = new boolean[iw * ih];
        for (int i = 0; i < band.length; i++) {
            band[i] = Math.abs((ids.argb()[i] & 0xFF) - EMISSIVE_LAYER_ID) <= 6;
        }
        boolean[] lit = new boolean[band.length];
        boolean any = false;
        for (int y = 0; y < ih; y++) {
            for (int x = 0; x < iw; x++) {
                if (!band[y * iw + x]) {
                    continue;
                }
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx >= 0 && yy >= 0 && xx < iw && yy < ih && band[yy * iw + xx]) {
                            n++;
                        }
                    }
                }
                lit[y * iw + x] = n >= 5;
                any |= n >= 5;
            }
        }
        return any ? new LayerMask(lit, iw, ih) : null;
    }

    /**
     * Adds the emissive layer to a glow map, lit in the brightest colour of each frame's row of the
     * material's ICAN table (green on Varia).
     */
    static Image[] withEmissiveLayer(Image[] glow, LayerMask layer, PakAssetRef ican) throws java.io.IOException {
        TextureDecoder.Image lut = decodeRaw(ican, 256);
        int lw = lut.width(), lh = lut.height();
        int frames = glow != null ? glow.length : 1;
        int w = glow != null ? (int) glow[0].getWidth() : layer.width(), h = glow != null ? (int) glow[0].getHeight() : layer.height();
        Image[] out = new Image[frames];
        for (int f = 0; f < frames; f++) {
            int row = frames > 1 ? (int) ((f + 0.5) / frames * lh) : lh / 2, colour = 0, best = -1;
            for (int x = 0; x < lw; x++) {
                int p = lut.argb()[row * lw + x];
                int sum = ((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF);
                if (sum > best) {
                    best = sum;
                    colour = p & 0xFFFFFF;
                }
            }
            int[] px = new int[w * h];
            if (glow != null) {
                glow[f].getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), px, 0, w);
            } else {
                java.util.Arrays.fill(px, 0xFF000000);
            }
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (layer.at(x, y, w, h)) {
                        px[y * w + x] = 0xFF000000 | colour;
                    }
                }
            }
            out[f] = toImageRaw(w, h, px);
        }
        return out;
    }

    /** A copy of {@code img} with the emissive layer's texels black (alpha kept). */
    static Image blackOut(Image img, LayerMask layer) {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        int[] px = new int[w * h];
        img.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), px, 0, w);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (layer.at(x, y, w, h)) {
                    px[y * w + x] &= 0xFF000000;
                }
            }
        }
        return toImageRaw(w, h, px);
    }

    /** Animated glow: frames per loop and loop length in seconds (the game's timing isn't decoded). */
    private static final int GLOW_FRAMES = 20;
    private static final double GLOW_PERIOD = 3.0;

    /**
     * Glow (incandescence) from ICAN, two ways:
     * <ul>
     * <li>ICAN the size of the base colour is a glow texture in the model's UV layout (Samus's
     *     visor HUD lines, the cinematic gun's fire-shot panels). Opaque submeshes mask it with the
     *     base colour's alpha; blended ones (the visor) use it as is.</li>
     * <li>A smaller ICAN is a lookup table: across = the base colour's alpha (the glow mask, which
     *     also scales the brightness), down = time -- strips that change down the table (the arm cannon's lights)
     *     are played as an animation of {@link #GLOW_FRAMES} frames.</li>
     * </ul>
     * A grey table gets its colour from the material's ICNC ({@code tint}, may exceed 1): the
     * third-person body's cannon (a white 4x4 table, ICNC 4.0 / 2.95 / 0.12 = orange) glowed white.
     * Returns null when nothing glows.
     */
    static Image[] decodeGlowMap(PakAssetRef base, PakAssetRef ican, boolean blended, float[] tint, int maxSize) throws java.io.IOException {
        int baseWidth = header(base).width();
        TextureHeader icanHeader = header(ican);
        boolean uvLayout = icanHeader.width() == baseWidth && icanHeader.height() == header(base).height();
        if (uvLayout) {
            TextureDecoder.Image glow = decodeRaw(ican, maxSize);
            TextureDecoder.Image mask = blended ? null : decodeRaw(base, maxSize);
            int w = glow.width(), h = glow.height();
            int[] out = new int[w * h];
            boolean lit = false;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int g = glow.argb()[y * w + x];
                    int a = 255;
                    if (mask != null) {
                        a = mask.argb()[(y * mask.height() / h) * mask.width() + x * mask.width() / w] >>> 24;
                    }
                    // Visors (no mask) glow at 60%: at full strength the glow washes out the glass's
                    // own shading and reflections.
                    if (mask == null) {
                        a = 153;
                    }
                    int r = ((g >> 16) & 0xFF) * a / 255, gg = ((g >> 8) & 0xFF) * a / 255, b = (g & 0xFF) * a / 255;
                    lit |= r + gg + b > 24;
                    out[y * w + x] = 0xFF000000 | r << 16 | gg << 8 | b;
                }
            }
            return lit ? new Image[] {toImage(w, h, out)} : null;
        }
        if (blended) {
            return null; // blended base alpha is opacity, not a glow mask
        }
        TextureDecoder.Image lut = decodeRaw(ican, 256);
        int lw = lut.width(), lh = lut.height();
        int[] table = lut.argb();
        if (!looksLikeLookupTable(table, lw, lh)) {
            // A tiling pattern (e.g. an energy-smoke overlay the game fades in): its average
            // colour under the glow mask -- drawing the pattern itself needs the shader's UVs.
            long r = 0, g = 0, b = 0;
            for (int p : table) {
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
            }
            int n = table.length;
            int[] solid = new int[lw * lh];
            java.util.Arrays.fill(solid, (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n));
            table = solid;
        }
        boolean animated = false;
        for (int x = 0; x < lw && !animated; x++) {
            int first = table[x];
            for (int y = 1; y < lh; y++) {
                int p = table[y * lw + x];
                if (Math.abs(((p >> 16) & 0xFF) - ((first >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((first >> 8) & 0xFF))
                    + Math.abs((p & 0xFF) - (first & 0xFF)) > 48) {
                    animated = true;
                    break;
                }
            }
        }
        // A tall, narrow ramp (the arm cannon's orange vent light): read down by the mask instead,
        // shifted back and forth over time -- the lit band sweeps up and down the light.
        boolean sweep = lw <= 16 && lh >= 64 && isBand(table, lw, lh);
        animated = sweep || (animated && !(lw <= 16 && lh >= 64));
        // Animated frames are kept smaller: GLOW_FRAMES full-size images would be too much memory.
        TextureDecoder.Image mask = decodeRaw(base, animated ? Math.min(512, maxSize) : maxSize);
        int[] px = mask.argb();
        // A base colour that is opaque (almost) everywhere has no glow mask in its alpha -- looking
        // the table up at alpha 255 lit whole bodies (the senior troopers glowed pale yellow).
        int masked = 0;
        for (int i = 0; i < px.length; i += 7) {
            if ((px[i] >>> 24) < 200) {
                masked++;
            }
        }
        if (masked < px.length / 7 / 50) {
            return null;
        }
        int[] column = dominantColumns(mask, lw);
        boolean grey = tint != null && !sweep;
        for (int i = 0; i < table.length && grey; i++) {
            int p = table[i], r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            grey = Math.abs(r - g) <= 8 && Math.abs(g - b) <= 8;
        }
        int frames = animated ? GLOW_FRAMES : 1;
        Image[] out = new Image[frames];
        boolean lit = false;
        for (int f = 0; f < frames; f++) {
            int row = animated ? (int) ((f + 0.5) / frames * lh) : lh / 2;
            double offset = 0.5 * Math.sin(2 * Math.PI * f / frames);
            int[] frame = new int[px.length];
            for (int i = 0; i < px.length; i++) {
                int a = px[i] >>> 24;
                if (sweep) {
                    int v = (int) Math.max(0, Math.min(lh - 1, (a / 255.0 + offset) * lh));
                    int c = a <= 8 ? 0 : table[v * lw + lw / 2] & 0xFFFFFF;
                    lit |= c != 0 && ((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF) > 24;
                    frame[i] = 0xFF000000 | c;
                    continue;
                }
                int c = a == 0 ? 0 : table[row * lw + column[i]] & 0xFFFFFF;
                // The mask also sets brightness: some tables are one colour across (Sylux's green),
                // and their faint mask noise must stay faint.
                c = (((c >> 16) & 0xFF) * a / 255) << 16 | (((c >> 8) & 0xFF) * a / 255) << 8 | (c & 0xFF) * a / 255;
                if (grey) {
                    c = Math.min(255, Math.round(((c >> 16) & 0xFF) * tint[0])) << 16
                        | Math.min(255, Math.round(((c >> 8) & 0xFF) * tint[1])) << 8
                        | Math.min(255, Math.round((c & 0xFF) * tint[2]));
                }
                lit |= c != 0 && ((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF) > 24;
                frame[i] = 0xFF000000 | c;
            }
            out[f] = frames > 1 ? toImageRaw(mask.width(), mask.height(), frame) : toImage(mask.width(), mask.height(), frame);
        }
        return lit ? out : null;
    }

    /**
     * The table column each lit mask pixel uses: the most common column among its lit 5x5
     * neighbours. A striped table quantises the mask, and where it fades to zero (strip edges,
     * block-compression noise) the in-between values picked other stripes' colours -- orange specks
     * along the Viola suit's purple trims. Unlit pixels stay -1.
     */
    private static int[] dominantColumns(TextureDecoder.Image img, int lw) {
        int w = img.width(), h = img.height();
        int[] px = img.argb();
        int[] col = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            int a = px[i] >>> 24;
            col[i] = a == 0 ? -1 : (int) Math.round(a / 255.0 * (lw - 1));
        }
        int[] out = new int[px.length];
        int[] count = new int[lw];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (col[i] < 0) {
                    out[i] = 0;
                    continue;
                }
                java.util.Arrays.fill(count, 0);
                int best = col[i];
                for (int dy = -2; dy <= 2; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) {
                        continue;
                    }
                    for (int dx = -2; dx <= 2; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) {
                            continue;
                        }
                        int c = col[yy * w + xx];
                        if (c >= 0 && ++count[c] > count[best]) {
                            best = c;
                        }
                    }
                }
                out[i] = best;
            }
        }
        return out;
    }

    /**
     * A ramp whose rows really change (a lit band: dark -> bright -> dark) rather than one colour
     * with the odd flash row (Sylux's green): more than a quarter of the rows sit far from the
     * median brightness.
     */
    private static boolean isBand(int[] t, int w, int h) {
        int[] lum = new int[h];
        for (int y = 0; y < h; y++) {
            int p = t[y * w + w / 2];
            lum[y] = ((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF);
        }
        int[] sorted = lum.clone();
        java.util.Arrays.sort(sorted);
        int median = sorted[h / 2], far = 0;
        for (int v : lum) {
            if (Math.abs(v - median) > 60) {
                far++;
            }
        }
        return far > h / 4;
    }

    /** Narrow ramps, tables starting with a black column and strongly striped ones are lookup tables. */
    private static boolean looksLikeLookupTable(int[] t, int w, int h) {
        if (w <= 16 || h <= 16) {
            return true;
        }
        // Mask 0 must not glow, so a table indexed by the mask starts with a black column.
        long first = 0;
        for (int y = 0; y < h; y++) {
            int p = t[y * w];
            first += ((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF);
        }
        if (first / h < 24) {
            return true;
        }
        long du = 0, dv = 0;
        for (int y = 0; y + 1 < h; y++) {
            for (int x = 0; x + 1 < w; x++) {
                du += colorDistance(t[y * w + x], t[y * w + x + 1]);
                dv += colorDistance(t[y * w + x], t[(y + 1) * w + x]);
            }
        }
        return Math.min(du, dv) < 0.35 * Math.max(du, dv);
    }

    private static int colorDistance(int p, int q) {
        return Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
            + Math.abs((p & 0xFF) - (q & 0xFF));
    }

    private static TextureHeader header(PakAssetRef ref) throws java.io.IOException {
        return TxtrReader.read(PackCatalog.extractDecompressed(ref.pakFile(), ref.asset()), ref.asset().meta()).header();
    }

    static Image decodeForModel(PakAssetRef ref, boolean keepAlpha, int maxSize) throws java.io.IOException {
        Texture t = TxtrReader.read(PackCatalog.extractDecompressed(ref.pakFile(), ref.asset()), ref.asset().meta());
        TextureHeader h = t.header();
        int mip = 0;
        while (mip < h.mipCount() - 1 && Math.max(h.mipWidth(mip), h.mipHeight(mip)) > maxSize) {
            mip++;
        }
        TextureDecoder.Image img = TextureDecoder.decode(t, 0, mip, 0);
        int[] px = img.argb();
        // On opaque submeshes base-colour alpha stores masks, not opacity (Samus's
        // solid armour is ~95% "transparent" by it), so only blended submeshes
        // -- the model's transparent LOD range -- keep it.
        if (!keepAlpha) {
            for (int i = 0; i < px.length; i++) {
                px[i] |= 0xFF000000;
            }
        }
        WritableImage out = new WritableImage(img.width(), img.height());
        out.getPixelWriter().setPixels(0, 0, img.width(), img.height(), PixelFormat.getIntArgbInstance(), px, 0, img.width());
        return out;
    }

    private void applyTextures() {
        boolean on = texturesToggle.isSelected();
        boolean detail = on && detailToggle.isSelected();
        boolean seeThrough = AppSettings.get().transparentVisorProperty().get();
        materials.forEach((idx, mat) -> {
            Image tex = !on ? null : detail && metalTextures.containsKey(idx) ? metalTextures.get(idx) : textures.get(idx);
            mat.setDiffuseMap(tex);
            Color color = tex != null ? Color.WHITE : untexturedColor(idx);
            if (seeThrough && visorKeys.contains(idx)) {
                color = color.deriveColor(0, 1, 1, 0.45); // opaque visors have no opacity of their own
            }
            if (hologramKeys.contains(idx)) {
                mat.setDiffuseColor(Color.WHITE);
                mat.setBumpMap(null);
                mat.setSpecularMap(null);
                mat.setSpecularColor(Color.BLACK);
                return;
            }
            if (iceKeys.contains(idx)) {
                color = color.deriveColor(0, 1, 1, AppSettings.get().cannonFrostProperty().get() / 100.0);
            }
            mat.setDiffuseColor(color);
            mat.setBumpMap(detail ? normalMaps.get(idx) : null);
            Image spec = detail ? specularMaps.get(idx) : null;
            mat.setSpecularMap(spec);
            mat.setSpecularColor(spec != null ? Color.WHITE : Color.gray(0.25));
            mat.setSpecularPower(spec != null ? 64 : 24);
        });
        applyGlow();
    }

    /**
     * The arm cannon's weapon-state glow layers, by ICAN texture: the game lights only the active
     * weapon's layer, so drawing all of them at once covered the cannon in blue, red and gold glow
     * (docs/ARM-CANNON-LIGHTS.txt). Shared by every suit's first-person viewmodel and the cinematic
     * gun. The green strip (d417b350 / 73ea4a64), orange top strips (82e288ea) and green arm dot
     * (4465a1bf) stay always on.
     */
    static final Map<java.util.UUID, String> WEAPON_GLOWS = Map.of(
        java.util.UUID.fromString("cfff67c8-6265-4078-a2a3-cb73c21a8876"), "Ice",     // blue panels (UV layout)
        java.util.UUID.fromString("abbaa677-eac5-4d61-99ce-5d67f6bfae03"), "Fire",    // red / orange (UV layout)
        java.util.UUID.fromString("564eb50e-2dd0-41ab-8b98-eab4eab1e0eb"), "Fire",    // orange ramp (Varia)
        java.util.UUID.fromString("1df73e2f-f21c-4432-bcb0-22643fc0183d"), "Fire",    // the same slot on the other suits
        java.util.UUID.fromString("24661de5-61b9-4ea3-9b88-c263fea9038a"), "Thunder"); // gold patterns (UV layout)
    /** Material key -> weapon state, for materials whose glow is a weapon layer. */
    private final Map<Integer, String> weaponKeys = new HashMap<>();

    private void showWeaponToggles() {
        for (ToggleButton t : List.of(iceToggle, fireToggle, thunderToggle)) {
            boolean has = weaponKeys.containsValue(t.getText());
            t.setVisible(has);
            t.setManaged(has);
        }
    }

    private boolean weaponLit(String weapon) {
        return switch (weapon) {
            case "Ice" -> iceToggle.isSelected();
            case "Fire" -> fireToggle.isSelected();
            default -> thunderToggle.isSelected();
        };
    }

    private void applyGlow() {
        boolean detail = texturesToggle.isSelected() && detailToggle.isSelected();
        boolean animating = false;
        for (Map.Entry<Integer, PhongMaterial> e : materials.entrySet()) {
            String weapon = weaponKeys.get(e.getKey());
            Image[] frames = detail && (weapon == null || weaponLit(weapon)) ? glowMaps.get(e.getKey()) : null;
            e.getValue().setSelfIlluminationMap(frames == null ? null : frames[glowFrame % frames.length]);
            animating |= frames != null && frames.length > 1;
        }
        if (animating && !disposed && glowTimer.getStatus() != javafx.animation.Animation.Status.RUNNING) {
            glowTimer.play();
        } else if (!animating) {
            glowTimer.stop();
        }
    }

    /** Settings > Show floor: a see-through grid plane at the model's lowest bind point. */
    private Node buildFloor(double y) {
        int px = 1024, cells = 32;
        WritableImage grid = new WritableImage(px, px);
        javafx.scene.image.PixelWriter w = grid.getPixelWriter();
        int cell = px / cells;
        for (int j = 0; j < px; j++) {
            for (int i = 0; i < px; i++) {
                boolean major = i % (cell * 4) < 2 || j % (cell * 4) < 2;
                boolean line = i % cell == 0 || j % cell == 0;
                // Fade towards the edges so the plane doesn't end in a hard line.
                double dx = (i - px / 2.0) / (px / 2.0), dz = (j - px / 2.0) / (px / 2.0);
                double fade = Math.max(0, 1 - Math.sqrt(dx * dx + dz * dz));
                int a = (int) ((major ? 150 : line ? 90 : 55) * fade);
                int rgb = major || line ? 0x4FD8F0 : 0x0B1A24;
                w.setArgb(i, j, a << 24 | rgb);
            }
        }
        PhongMaterial m = new PhongMaterial(Color.WHITE);
        m.setDiffuseMap(grid);
        m.setSpecularColor(Color.BLACK);
        double size = Math.max(radius * 40, 20);
        javafx.scene.shape.Box plane = new javafx.scene.shape.Box(size, Math.max(radius * 0.001, 1e-4), size);
        plane.setMaterial(m);
        plane.setTranslateY(y + plane.getHeight() / 2);
        plane.setMouseTransparent(true);
        return plane;
    }

    /** Puts the camera on the model (its posed body) -- clips can place the character far from the origin. */
    private void frameOnModel() {
        if (lastFollowPos == null) {
            return;
        }
        Point3D pos = new Point3D(lastFollowPos[0], lastFollowPos[1], lastFollowPos[2]);
        if (freeCamToggle.isSelected()) {
            Point3D forward = rig.getLocalToSceneTransform().deltaTransform(0, 0, 1).normalize();
            pos = pos.subtract(forward.multiply(radius * 2.6));
        }
        target.setX(pos.getX());
        target.setY(pos.getY());
        target.setZ(pos.getZ());
        if (!freeCamToggle.isSelected()) {
            dolly.setZ(-radius * 2.6);
        }
    }

    static Color untexturedColor(int materialKey) {
        return materialKey < 0 && AppSettings.get().transparentVisorProperty().get()
            ? DEFAULT_COLOR.deriveColor(0, 1, 1, 0.45) : DEFAULT_COLOR;
    }

    /** Settings > Preview > Model Preview Skybox: an inward-facing textured sphere. */
    private Node buildSkybox() {
        String path = AppSettings.get().customSkyboxPathProperty().get();
        if (path == null || path.isBlank() || !new File(path).isFile()) {
            return null;
        }
        Image image = new Image(new File(path).toURI().toString(), true);
        PhongMaterial m = new PhongMaterial(Color.BLACK);
        m.setSelfIlluminationMap(image);
        Sphere sky = new Sphere(1, 64);
        sky.setMaterial(m);
        sky.setCullFace(CullFace.FRONT);
        double r = radius * 150;
        // Viewed from inside, the texture is mirrored; negative X scale undoes it.
        sky.setScaleX(-r);
        sky.setScaleY(r);
        sky.setScaleZ(r);
        sky.translateXProperty().bind(target.xProperty());
        sky.translateYProperty().bind(target.yProperty());
        sky.translateZProperty().bind(target.zProperty());
        return sky;
    }

    // ---- camera --------------------------------------------------------------
    //
    // Locked (default): the camera orbits a pivot (the model's centre) -- left-drag
    // orbits, wheel zooms. Free: the camera is a point that looks around in place.
    // WASD moves along the view, Q/E down/up (Shift = fast, its speed set with
    // Shift+wheel); Ctrl+wheel or Z/X steps frames.
    // "Follow model" moves the camera along with the animated body (root motion).

    private final ToggleButton freeCamToggle = new ToggleButton("Free camera");
    private final ToggleButton followToggle = new ToggleButton("Follow model");
    private final java.util.Set<javafx.scene.input.KeyCode> heldKeys = java.util.EnumSet.noneOf(javafx.scene.input.KeyCode.class);
    /** Shift's speed multiplier for WASD movement (Shift+wheel changes it). */
    private double fastMultiplier = 3;
    private final Label speedLabel = new Label();
    private final javafx.animation.PauseTransition speedLabelHide =
        new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.2));
    private double[] lastFollowPos;
    private int followJoint = -1;
    private final javafx.animation.AnimationTimer moveTimer = new javafx.animation.AnimationTimer() {
        private long last;
        private boolean running;

        @Override
        public void start() {
            if (!running) { // key repeat calls this constantly
                running = true;
                last = 0;
                super.start();
            }
        }

        @Override
        public void stop() {
            running = false;
            super.stop();
        }

        @Override
        public void handle(long now) {
            double dt = last == 0 ? 0 : Math.min(0.1, (now - last) / 1e9);
            last = now;
            if (heldKeys.isEmpty()) {
                stop();
                return;
            }
            if (dt == 0) {
                return;
            }
            javafx.scene.transform.Transform t = rig.getLocalToSceneTransform();
            Point3D forward = t.deltaTransform(0, 0, 1).normalize();
            Point3D right = t.deltaTransform(1, 0, 0).normalize();
            Point3D up = new Point3D(0, -1, 0); // the mesh is flipped to JavaFX's y-down
            Point3D move = Point3D.ZERO;
            if (heldKeys.contains(javafx.scene.input.KeyCode.W)) move = move.add(forward);
            if (heldKeys.contains(javafx.scene.input.KeyCode.S)) move = move.subtract(forward);
            if (heldKeys.contains(javafx.scene.input.KeyCode.D)) move = move.add(right);
            if (heldKeys.contains(javafx.scene.input.KeyCode.A)) move = move.subtract(right);
            if (heldKeys.contains(javafx.scene.input.KeyCode.E)) move = move.add(up);
            if (heldKeys.contains(javafx.scene.input.KeyCode.Q)) move = move.subtract(up);
            if (move.magnitude() < 1e-9) {
                return;
            }
            double speed = radius * 0.9 * (heldKeys.contains(javafx.scene.input.KeyCode.SHIFT) ? fastMultiplier : 1);
            move = move.normalize().multiply(speed * dt);
            target.setX(target.getX() + move.getX());
            target.setY(target.getY() + move.getY());
            target.setZ(target.getZ() + move.getZ());
        }
    };

    private void buildCameraOverlay() {
        freeCamToggle.getStyleClass().add("preview-overlay-toggle");
        followToggle.getStyleClass().add("preview-overlay-toggle");
        freeCamToggle.setTooltip(new javafx.scene.control.Tooltip("Unlock the camera from the model: drag looks around in place, "
            + "WASD moves"));
        followToggle.setTooltip(new javafx.scene.control.Tooltip("Move the camera along with the model when an animation moves it"));
        freeCamToggle.setFocusTraversable(false);
        followToggle.setFocusTraversable(false);
        freeCamToggle.setOnAction(e -> setFreeCamera(freeCamToggle.isSelected()));
        followToggle.setOnAction(e -> lastFollowPos = null);
        cameraTools.getChildren().setAll(freeCamToggle, followToggle);
        cameraTools.setAlignment(Pos.CENTER_LEFT);
        speedLabel.getStyleClass().add("preview-overlay-toggle");
        speedLabel.setMouseTransparent(true);
        speedLabel.setVisible(false);
        StackPane.setAlignment(speedLabel, Pos.TOP_RIGHT);
        StackPane.setMargin(speedLabel, new javafx.geometry.Insets(10, 10, 0, 0));
        viewport.getChildren().add(speedLabel);
        speedLabelHide.setOnFinished(e -> speedLabel.setVisible(false));
    }

    private void changeFastSpeed(boolean up) {
        fastMultiplier = Math.max(1.25, Math.min(50, fastMultiplier * (up ? 1.25 : 0.8)));
        speedLabel.setText(String.format("Fast speed \u00D7%.1f", fastMultiplier));
        speedLabel.setVisible(true);
        speedLabelHide.playFromStart();
    }

    /** Free camera: the pivot becomes the camera's own position (same view, no jump). Locked: orbit the model again. */
    private void setFreeCamera(boolean free) {
        Point3D eye = rig.localToScene(Point3D.ZERO);
        if (free) {
            target.setX(eye.getX());
            target.setY(eye.getY());
            target.setZ(eye.getZ());
            dolly.setZ(0);
        } else if (dolly.getZ() == 0) {
            Point3D pivot = lastFollowPos != null && followToggle.isSelected()
                ? new Point3D(lastFollowPos[0], lastFollowPos[1], lastFollowPos[2]) : center;
            double dist = Math.max(radius * 0.1, eye.distance(pivot));
            Point3D forward = rig.getLocalToSceneTransform().deltaTransform(0, 0, 1).normalize();
            Point3D p = eye.add(forward.multiply(dist));
            target.setX(p.getX());
            target.setY(p.getY());
            target.setZ(p.getZ());
            dolly.setZ(-dist);
        }
        freeCamToggle.setSelected(free);
    }

    private void resetView() {
        target.setX(center.getX());
        target.setY(center.getY());
        target.setZ(center.getZ());
        yaw.setAngle(-35);
        pitch.setAngle(-20);
        dolly.setZ(-radius * 2.6);
        freeCamToggle.setSelected(false);
        lastFollowPos = null;
    }

    private void installMouse() {
        viewport.setOnMousePressed(e -> {
            viewport.requestFocus();
            dragX = e.getSceneX();
            dragY = e.getSceneY();
        });
        viewport.setOnMouseDragged(e -> {
            double dx = e.getSceneX() - dragX;
            double dy = e.getSceneY() - dragY;
            dragX = e.getSceneX();
            dragY = e.getSceneY();
            if (e.getButton() == MouseButton.PRIMARY && !e.isControlDown()) {
                double k = freeCamToggle.isSelected() ? 0.25 : 0.4;
                yaw.setAngle(yaw.getAngle() + dx * k);
                pitch.setAngle(Math.max(-89, Math.min(89, pitch.getAngle() - dy * k)));
            } else if (e.getButton() == MouseButton.MIDDLE || e.isControlDown()) {
                // Pan along the camera's own right/up axes.
                double scale = Math.max(radius * 0.5, -dolly.getZ()) * 0.0018;
                Point3D right = rig.localToSceneTransformProperty().get().deltaTransform(1, 0, 0).normalize();
                Point3D up = rig.localToSceneTransformProperty().get().deltaTransform(0, 1, 0).normalize();
                Point3D move = right.multiply(-dx * scale).add(up.multiply(-dy * scale));
                target.setX(target.getX() + move.getX());
                target.setY(target.getY() + move.getY());
                target.setZ(target.getZ() + move.getZ());
            }
        });
        viewport.setOnScroll(e -> {
            // Windows reports Shift+wheel as horizontal scrolling.
            double delta = e.getDeltaY() != 0 ? e.getDeltaY() : e.getDeltaX();
            if (delta == 0) {
                return;
            }
            if (e.isShiftDown()) {
                changeFastSpeed(delta > 0);
            } else if (e.isControlDown()) {
                stepFrame(delta > 0 ? 1 : -1);
            } else if (freeCamToggle.isSelected()) {
                Point3D forward = rig.getLocalToSceneTransform().deltaTransform(0, 0, 1).normalize();
                Point3D move = forward.multiply(radius * 0.15 * (delta > 0 ? 1 : -1));
                target.setX(target.getX() + move.getX());
                target.setY(target.getY() + move.getY());
                target.setZ(target.getZ() + move.getZ());
            } else {
                double factor = delta > 0 ? 0.88 : 1.0 / 0.88;
                dolly.setZ(Math.max(-radius * 100, Math.min(-radius * 0.05, dolly.getZ() * factor)));
            }
            e.consume();
        });
        viewport.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && e.isStillSincePress()) {
                resetView();
            }
        });
        viewport.addEventFilter(javafx.scene.input.KeyEvent.KEY_RELEASED, e -> heldKeys.remove(e.getCode()));
        // Losing focus (alt-tab, clicking the list) must not leave a movement key stuck.
        viewport.focusedProperty().addListener((obs, was, is) -> {
            if (!is) {
                heldKeys.clear();
            }
        });
    }

    /** Camera follow + the joint the camera tracks (the top skinned joint, e.g. the pelvis). */
    private void followModel(double[][] posed) {
        if (followJoint < 0 || followJoint >= posed.length || posed[followJoint] == null) {
            return;
        }
        double[] m = posed[followJoint];
        double[] pos = {m[3], -m[7], -m[11]};
        if (followToggle.isSelected() && lastFollowPos != null) {
            target.setX(target.getX() + pos[0] - lastFollowPos[0]);
            target.setY(target.getY() + pos[1] - lastFollowPos[1]);
            target.setZ(target.getZ() + pos[2] - lastFollowPos[2]);
        }
        lastFollowPos = pos;
    }

    /** The skinned joint closest to the root -- the body's anchor for camera follow. */
    private static int pickFollowJoint(ChprSkeleton sk, int[] palette) {
        int best = -1, bestDepth = Integer.MAX_VALUE;
        if (palette == null) {
            return -1;
        }
        for (int j : palette) {
            int depth = 0;
            for (int p = sk.parentOf(j); p >= 0 && depth < 512; p = sk.parentOf(p)) {
                depth++;
            }
            if (depth < bestDepth) {
                bestDepth = depth;
                best = j;
            }
        }
        return best;
    }

    private void showError(String message) {
        infoLabel.setText(message);
        Label label = new Label("Couldn't load this model");
        label.getStyleClass().add("preview-not-ready");
        viewport.getChildren().setAll(label);
    }
}
