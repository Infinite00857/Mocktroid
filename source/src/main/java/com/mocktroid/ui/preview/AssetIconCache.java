package com.mocktroid.ui.preview;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import com.mocktroid.format.model.CmdlModel;
import com.mocktroid.library.ModelLoader;
import com.mocktroid.library.PakAssetRef;
import com.mocktroid.model.AssetEntry;
import com.mocktroid.model.AssetLibrary;
import com.mocktroid.settings.AppDirs;
import com.mocktroid.settings.AppSettings;

import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

/**
 * Grid icons for models and videos. Models: a small render from the preview's default camera,
 * generated lazily as cards scroll into view (load + texture decode in the
 * background, one off-screen snapshot on the FX thread) and persisted like
 * the texture thumbnails. Videos: the first frame (one player at a time --
 * Windows' decoder fails when players initialise concurrently).
 * {@link #store} replaces an icon with a capture of the live preview (the
 * preview's "Cache icon" button).
 */
public final class AssetIconCache {

    public static final int SIZE = 128;
    private static final int RENDER_SIZE = 256;
    private static final int TEXTURE_MAX = 256;
    private static final int MEMORY_ENTRIES = 800;
    private static final int VERSION = 1;
    // (Model and video icons share one cache directory; asset GUIDs don't collide.)

    /** A thumbnail, {@link #SIZE} square, ARGB with a transparent background. */
    public record Thumb(int width, int height, int[] argb) {}

    private static final AssetIconCache INSTANCE =
        new AssetIconCache(AppDirs.dataDir().resolve("model-thumbnails").resolve("v" + VERSION));

    public static AssetIconCache get() { return INSTANCE; }

    private final Path cacheDir;
    private final Map<UUID, Thumb> memory = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Thumb> eldest) {
            return size() > MEMORY_ENTRIES;
        }
    };
    private final Map<UUID, List<Consumer<Thumb>>> pending = new HashMap<>();
    private final ThreadPoolExecutor executor;
    /** Bumped when an icon is replaced (or the cache cleared) so grids redraw. */
    private final IntegerProperty revision = new SimpleIntegerProperty(0);
    private volatile AssetLibrary library;

    private AssetIconCache(Path cacheDir) {
        this.cacheDir = cacheDir;
        LinkedBlockingDeque<Runnable> lifo = new LinkedBlockingDeque<>() {
            @Override
            public boolean offer(Runnable r) {
                return offerFirst(r);
            }
        };
        executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, lifo, r -> {
            Thread t = new Thread(r, "model-thumbnail");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    /** The romfs index, needed to find a model's textures. */
    public void setLibrary(AssetLibrary library) {
        this.library = library;
    }

    public IntegerProperty revisionProperty() { return revision; }

    /**
     * While a video plays, icon generation waits: rendering model icons and grabbing video frames
     * both use the FX thread and the video decoder, and made playback stutter.
     */
    private volatile boolean paused;

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    private void waitWhilePaused() throws InterruptedException {
        while (paused) {
            Thread.sleep(250);
        }
    }

    public synchronized Thumb getIfLoaded(UUID id) {
        return memory.get(id);
    }

    /** Calls {@code onReady} on the FX thread with the model's icon (null if it can't be rendered). */
    public void request(AssetEntry entry, Consumer<Thumb> onReady) {
        if (!(entry.sourceRef() instanceof PakAssetRef ref)) {
            onReady.accept(null);
            return;
        }
        UUID id = ref.asset().assetId();
        synchronized (this) {
            Thumb cached = memory.get(id);
            if (cached != null) {
                onReady.accept(cached);
                return;
            }
            List<Consumer<Thumb>> waiters = pending.get(id);
            if (waiters != null) {
                waiters.add(onReady);
                return;
            }
            waiters = new ArrayList<>();
            waiters.add(onReady);
            pending.put(id, waiters);
        }
        executor.execute(() -> {
            Thumb thumb = null;
            try {
                thumb = loadFromDisk(id);
                if (thumb == null && !com.mocktroid.library.ThumbFiles.isMarkedUnrenderable(fileFor(id))) {
                    waitWhilePaused();
                    thumb = generate(entry);
                    if (thumb != null) {
                        saveToDisk(id, thumb);
                    } else if (entry.type() != com.mocktroid.model.AssetType.VIDEO) {
                        // Models that can't be rendered stay iconless (video frame grabs can fail transiently).
                        com.mocktroid.library.ThumbFiles.markUnrenderable(fileFor(id));
                    }
                }
            } catch (Exception | OutOfMemoryError e) {
                if (Boolean.getBoolean("mocktroid.debugIcons")) {
                    e.printStackTrace();
                }
                thumb = null;
            }
            List<Consumer<Thumb>> done;
            synchronized (this) {
                if (thumb != null) {
                    memory.put(id, thumb);
                }
                done = pending.remove(id);
            }
            Thumb result = thumb;
            Platform.runLater(() -> done.forEach(c -> c.accept(result)));
        });
    }

    /** Replaces the entry's icon with {@code image} (centre-cropped to a square). FX thread. */
    public void store(AssetEntry entry, Image image) {
        store(entry, image, true);
    }

    /** Replaces the entry's icon; {@code square} crops to the centre square, else keeps the aspect. FX thread. */
    public void store(AssetEntry entry, Image image, boolean square) {
        if (!(entry.sourceRef() instanceof PakAssetRef ref)) {
            return;
        }
        Thumb thumb = square ? squareThumb(image) : fitThumb(image);
        UUID id = ref.asset().assetId();
        synchronized (this) {
            memory.put(id, thumb);
        }
        Thread t = new Thread(() -> saveToDisk(id, thumb), "model-thumbnail-save");
        t.setDaemon(true);
        t.start();
        revision.set(revision.get() + 1);
    }

    private Thumb generate(AssetEntry entry) throws Exception {
        if (entry.type() == com.mocktroid.model.AssetType.VIDEO) {
            return generateVideo(entry);
        }
        ModelLoader.LoadedModel loaded = ModelLoader.load(entry, library);
        CmdlModel model = loaded.model();
        boolean transparentVisor = AppSettings.get().transparentVisorProperty().get();
        // Which material keys the drawn submeshes use, and their textures.
        java.util.Set<Integer> keys = new java.util.HashSet<>();
        java.util.Set<Integer> blended = new java.util.HashSet<>();
        for (int t : model.transparentMeshes()) {
            blended.add(t);
        }
        for (int si : model.drawnSubmeshes()) {
            keys.add(ModelPreview.materialKey(model.submeshes().get(si).materialIndex(), blended.contains(si)));
        }
        Map<Integer, Image> textures = new HashMap<>();
        for (int key : keys) {
            int mat = key < 0 ? -key - 1 : key;
            PakAssetRef tex = mat < loaded.baseColorTextures().size() ? loaded.baseColorTextures().get(mat) : null;
            if (tex != null) {
                try {
                    textures.put(key, ModelPreview.decodeForModel(tex, key < 0 && transparentVisor, TEXTURE_MAX));
                } catch (Exception | OutOfMemoryError ignored) {
                    // untextured
                }
            }
        }
        Thumb[] out = new Thumb[1];
        Exception[] error = new Exception[1];
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                out[0] = render(model, textures);
            } catch (Exception e) {
                error[0] = e;
            } finally {
                latch.countDown();
            }
        });
        latch.await();
        if (error[0] != null) {
            throw error[0];
        }
        return out[0];
    }

    /** Same framing as the preview's default view (yaw -35, pitch -20, distance 2.6 x radius). */
    private static Thumb render(CmdlModel model, Map<Integer, Image> textures) {
        List<MeshView> views = ModelPreview.buildMeshes(model);
        if (views.isEmpty()) {
            return null;
        }
        Map<Integer, PhongMaterial> materials = new HashMap<>();
        for (MeshView view : views) {
            int key = (int) view.getProperties().get("material");
            view.setMaterial(materials.computeIfAbsent(key, k -> {
                Image tex = textures.get(k);
                PhongMaterial m = new PhongMaterial(tex != null ? Color.WHITE : ModelPreview.untexturedColor(k));
                m.setDiffuseMap(tex);
                m.setSpecularColor(Color.gray(0.25));
                m.setSpecularPower(24);
                return m;
            }));
        }
        float[] mn = model.boundsMin();
        float[] mx = model.boundsMax();
        double cx = (mn[0] + mx[0]) / 2, cy = -(mn[1] + mx[1]) / 2, cz = -(mn[2] + mx[2]) / 2;
        double dx = mx[0] - mn[0], dy = mx[1] - mn[1], dz = mx[2] - mn[2];
        double radius = Math.max(0.01, Math.sqrt(dx * dx + dy * dy + dz * dz) / 2);
        if (!Double.isFinite(radius) || !Double.isFinite(cx + cy + cz)) {
            return null;
        }
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setNearClip(radius * 0.002);
        camera.setFarClip(radius * 400);
        camera.setFieldOfView(40);
        Group rig = new Group(camera, new PointLight(Color.gray(0.8)));
        rig.getTransforms().setAll(new Translate(cx, cy, cz), new Rotate(-35, Rotate.Y_AXIS),
            new Rotate(-20, Rotate.X_AXIS), new Translate(0, 0, -radius * 2.4));
        Group meshes = new Group();
        meshes.getChildren().addAll(views);
        Group root = new Group(meshes, rig, new AmbientLight(Color.gray(0.42)));
        Scene scene = new Scene(root, RENDER_SIZE, RENDER_SIZE, true, SceneAntialiasing.BALANCED);
        scene.setFill(Color.TRANSPARENT);
        scene.setCamera(camera);
        return squareThumb(scene.snapshot(null));
    }

    /** First frame of an FMV0: extract the MP4, show frame 0 in an off-screen MediaView and snapshot it. */
    private Thumb generateVideo(AssetEntry entry) throws Exception {
        PakAssetRef ref = (PakAssetRef) entry.sourceRef();
        byte[] raw = com.mocktroid.format.pack.PackCatalog.extractDecompressed(ref.pakFile(), ref.asset());
        Path file = com.mocktroid.format.video.FmvExtractor.toPlayableMp4(raw, 1.0);
        try {
            for (int attempt = 0; attempt < 5; attempt++) {
                Thumb thumb = captureFirstFrame(file);
                if (thumb != null) {
                    return thumb;
                }
                Thread.sleep(800);
            }
            return null;
        } finally {
            // The player may still hold the file for a moment after dispose; never let cleanup lose the icon.
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                file.toFile().deleteOnExit();
            }
        }
    }

    private static Thumb captureFirstFrame(Path file) throws InterruptedException {
        Thumb[] out = new Thumb[1];
        CountDownLatch latch = new CountDownLatch(1);
        javafx.scene.media.MediaPlayer[] holder = new javafx.scene.media.MediaPlayer[1];
        Platform.runLater(() -> {
            try {
                javafx.scene.media.MediaPlayer player = new javafx.scene.media.MediaPlayer(
                    new javafx.scene.media.Media(file.toUri().toString()));
                holder[0] = player;
                player.setMute(true);
                javafx.scene.media.MediaView view = new javafx.scene.media.MediaView(player);
                view.setFitWidth(RENDER_SIZE);
                view.setFitHeight(RENDER_SIZE * 9 / 16.0);
                view.setPreserveRatio(true);
                Scene scene = new Scene(new Group(view), RENDER_SIZE, RENDER_SIZE * 9 / 16.0);
                scene.setFill(Color.BLACK);
                player.setOnError(latch::countDown);
                player.setOnReady(() -> {
                    player.play();
                    player.currentTimeProperty().addListener((obs, old, val) -> {
                        if (out[0] == null && val.toMillis() > 40) {
                            player.pause();
                            // Give the frame one pulse to reach the view before the snapshot.
                            Platform.runLater(() -> {
                                out[0] = fitThumb(scene.snapshot(null));
                                latch.countDown();
                            });
                        }
                    });
                });
            } catch (Exception e) {
                latch.countDown();
            }
        });
        latch.await(15, TimeUnit.SECONDS);
        CountDownLatch released = new CountDownLatch(1);
        Platform.runLater(() -> {
            if (holder[0] != null) {
                holder[0].stop();
                holder[0].dispose();
            }
            released.countDown();
        });
        released.await(5, TimeUnit.SECONDS);
        return out[0];
    }

    /** Scales the image so its longer side is {@link #SIZE}, keeping the aspect ratio. */
    private static Thumb fitThumb(Image image) {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] src = new int[w * h];
        image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), src, 0, w);
        double scale = Math.min(1.0, (double) SIZE / Math.max(w, h));
        int tw = Math.max(1, (int) Math.round(w * scale)), th = Math.max(1, (int) Math.round(h * scale));
        int[] out = new int[tw * th];
        for (int ty = 0; ty < th; ty++) {
            int y0 = ty * h / th, y1 = Math.max(y0 + 1, (ty + 1) * h / th);
            for (int tx = 0; tx < tw; tx++) {
                int x0 = tx * w / tw, x1 = Math.max(x0 + 1, (tx + 1) * w / tw);
                long a = 0, r = 0, g = 0, b = 0;
                int c = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int p = src[y * w + x];
                        a += p >>> 24;
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                        c++;
                    }
                }
                out[ty * tw + tx] = (int) ((a / c) << 24 | (r / c) << 16 | (g / c) << 8 | (b / c));
            }
        }
        return new Thumb(tw, th, out);
    }

    private static Thumb squareThumb(Image image) {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int side = Math.min(w, h);
        int x0 = (w - side) / 2, y0 = (h - side) / 2;
        int[] src = new int[side * side];
        image.getPixelReader().getPixels(x0, y0, side, side, PixelFormat.getIntArgbInstance(), src, 0, side);
        int n = Math.min(SIZE, side);
        int[] out = new int[n * n];
        for (int ty = 0; ty < n; ty++) {
            int sy0 = ty * side / n, sy1 = Math.max(sy0 + 1, (ty + 1) * side / n);
            for (int tx = 0; tx < n; tx++) {
                int sx0 = tx * side / n, sx1 = Math.max(sx0 + 1, (tx + 1) * side / n);
                long a = 0, r = 0, g = 0, b = 0;
                int c = 0;
                for (int y = sy0; y < sy1; y++) {
                    for (int x = sx0; x < sx1; x++) {
                        int p = src[y * side + x];
                        a += p >>> 24;
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                        c++;
                    }
                }
                out[ty * n + tx] = (int) ((a / c) << 24 | (r / c) << 16 | (g / c) << 8 | (b / c));
            }
        }
        return new Thumb(n, n, out);
    }

    /** The thumbnail as an image, for display. */
    public static WritableImage toImage(Thumb thumb) {
        WritableImage image = new WritableImage(thumb.width(), thumb.height());
        image.getPixelWriter().setPixels(0, 0, thumb.width(), thumb.height(),
            PixelFormat.getIntArgbInstance(), thumb.argb(), 0, thumb.width());
        return image;
    }

    private Path fileFor(UUID id) {
        return cacheDir.resolve(id + ".thumb");
    }

    private Thumb loadFromDisk(UUID id) {
        int[] d = com.mocktroid.library.ThumbFiles.read(fileFor(id));
        return d == null ? null : new Thumb(d[0], d[1], java.util.Arrays.copyOfRange(d, 2, d.length));
    }

    private void saveToDisk(UUID id, Thumb thumb) {
        com.mocktroid.library.ThumbFiles.write(fileFor(id), thumb.width(), thumb.height(), thumb.argb());
    }

    public int countOnDisk() {
        if (!Files.isDirectory(cacheDir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(cacheDir)) {
            return (int) files.filter(p -> p.toString().endsWith(".thumb")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    public void clear() {
        synchronized (this) {
            memory.clear();
        }
        if (Files.isDirectory(cacheDir)) {
            try (Stream<Path> files = Files.list(cacheDir)) {
                files.filter(p -> p.toString().endsWith(".thumb") || p.toString().endsWith(".none")).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best effort
                    }
                });
            } catch (IOException ignored) {
                // best effort
            }
        }
        revision.set(revision.get() + 1);
    }
}
