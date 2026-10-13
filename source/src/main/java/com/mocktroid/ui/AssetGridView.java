package com.mocktroid.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mocktroid.audio.SoundManager;
import com.mocktroid.library.CachedWaveform;
import com.mocktroid.library.PakAssetRef;
import com.mocktroid.library.SoundThumbnailCache;
import com.mocktroid.library.TextureThumbnailCache;
import com.mocktroid.model.AssetCategory;
import com.mocktroid.model.AssetEntry;
import com.mocktroid.model.AssetType;
import com.mocktroid.settings.AppSettings;
import com.mocktroid.ui.preview.AssetIconCache;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * Grid-mode asset browser: square thumbnails, {@code AppSettings.gridColumns}
 * per row.
 * <p>
 * Backed by {@link ListView}, where each "item" is actually a full row's
 * worth of assets (a {@code List<AssetEntry>}) rendered as an HBox of
 * cards -- this gets JavaFX's built-in cell virtualization (only visible
 * rows are ever built) for a grid layout, which JavaFX has no native
 * control for. Categories can run into the thousands of assets (CSMP alone
 * is ~9,500 across the full romfs); eagerly building every card up front
 * (the original approach) is what caused the app to hang on a real scan.
 * <p>
 * Sound entries with a cached waveform ({@link SoundThumbnailCache}, built
 * via Settings) show a small real waveform. Textures show a real decoded
 * thumbnail ({@link TextureThumbnailCache}), generated on first view with the
 * morph-ball spinner standing in meanwhile. Everything else -- and sounds
 * with no cached waveform yet -- shows the static placeholder glyph.
 */
public final class AssetGridView extends ListView<List<AssetEntry>> {

    private static final double CUT = 12;
    private static final double MIN_CARD_SIZE = 96;
    private static final double MAX_CARD_SIZE = 220;
    private static final double CARD_GAP = 16;
    /** Row padding (4 + 4) plus room for the vertical scrollbar. */
    private static final double ROW_CHROME = 8 + 18;
    private static final double THUMB_WAVEFORM_HEIGHT = 32;

    /** Cards are sized to exactly fill the row at the configured column count,
     *  so there's never a horizontal scrollbar and they grow with the window. */
    private double cardSize = 130;
    private java.util.function.Consumer<AssetEntry> onSelect = e -> {};
    private java.util.function.BiConsumer<AssetEntry, javafx.scene.Node> onActivate = (e, n) -> {};
    private AssetEntry selectedEntry;

    public AssetGridView() {
        getStyleClass().add("asset-grid-view");
        setItems(FXCollections.observableArrayList());
        setCellFactory(lv -> new RowOfCards());
        setFocusTraversable(false);
        widthProperty().addListener((obs, old, val) -> updateCardSize());
        AppSettings.get().gridColumnsProperty().addListener((obs, old, val) -> updateCardSize());
        // A replaced model icon (the preview's "Cache icon") or a cleared cache redraws the cards.
        AssetIconCache.get().revisionProperty().addListener((obs, old, val) -> refresh());
    }

    private void updateCardSize() {
        int columns = Math.max(1, AppSettings.get().gridColumnsProperty().get());
        double available = getWidth() - ROW_CHROME - (columns - 1) * CARD_GAP;
        double size = Math.floor(Math.max(MIN_CARD_SIZE, Math.min(MAX_CARD_SIZE, available / columns)));
        if (getWidth() > 0 && size != cardSize) {
            cardSize = size;
            refresh();
        }
    }

    /** Double-click on a card; receives the card so callers can give feedback on it. */
    public void setOnActivate(java.util.function.BiConsumer<AssetEntry, javafx.scene.Node> onActivate) {
        this.onActivate = onActivate;
    }

    private EntryMenuHandler onMenu = (e, n, x, y) -> {};

    public void setOnMenu(EntryMenuHandler onMenu) {
        this.onMenu = onMenu;
    }

    public void setOnSelect(java.util.function.Consumer<AssetEntry> onSelect) {
        this.onSelect = onSelect;
    }

    public void showCategory(AssetCategory category) {
        ObservableList<List<AssetEntry>> rows = FXCollections.observableArrayList();
        if (category != null) {
            int columns = Math.max(1, AppSettings.get().gridColumnsProperty().get());
            List<AssetEntry> entries = category.entries();
            for (int i = 0; i < entries.size(); i += columns) {
                rows.add(new ArrayList<>(entries.subList(i, Math.min(i + columns, entries.size()))));
            }
        }
        setItems(rows);
    }

    /** No-op placeholder for when a real model-thumbnail-cache module exists
     *  to call it -- sound thumbnails are handled separately via
     *  {@link SoundThumbnailCache}, consulted directly in buildCard below. */
    public void setLoading(AssetEntry entry, boolean loading) {}

    /** Selects an entry programmatically (e.g. next/previous navigation from
     *  the preview pane), same code path as clicking its card. */
    public void select(AssetEntry entry) {
        selectedEntry = entry;
        onSelect.accept(entry);
        // Restyle the visible cards in place instead of refresh()-ing: a refresh
        // rebuilds every card, and any rebuild that landed between a mouse
        // press and its release swallowed the click (MOUSE_CLICKED only fires
        // on a node that saw both), which is why cards sometimes needed a
        // second click. Recycled cells pick the selection up in buildCard.
        for (javafx.scene.Node card : lookupAll(".asset-card")) {
            boolean isSelected = entry.equals(card.getProperties().get(ENTRY_KEY));
            card.getStyleClass().remove("selected");
            if (isSelected) {
                card.getStyleClass().add("selected");
            }
        }
    }

    private static final String ENTRY_KEY = "asset-entry";

    private final class RowOfCards extends ListCell<List<AssetEntry>> {
        private final HBox row = new HBox(CARD_GAP);

        RowOfCards() {
            getStyleClass().add("asset-grid-row-cell");
            row.setPadding(new Insets(8, 4, 8, 4));
        }

        @Override
        protected void updateItem(List<AssetEntry> rowEntries, boolean empty) {
            super.updateItem(rowEntries, empty);
            if (empty || rowEntries == null) {
                setGraphic(null);
                return;
            }
            row.getChildren().clear();
            for (AssetEntry entry : rowEntries) {
                row.getChildren().add(buildCard(entry));
            }
            setGraphic(row);
        }

        private AngledPanel buildCard(AssetEntry entry) {
            AngledPanel card = new AngledPanel(CUT);
            card.setPrefSize(cardSize, cardSize);
            card.setMinSize(cardSize, cardSize);
            card.setMaxSize(cardSize, cardSize);
            card.getStyleClass().add("asset-card");
            if (entry.equals(selectedEntry)) {
                card.getStyleClass().add("selected");
            }

            StackPane thumb = new StackPane(thumbnailNode(entry));
            thumb.getStyleClass().add("asset-card-thumb");

            Label name = new Label(entry.displayName());
            name.getStyleClass().add("asset-card-label");
            name.setWrapText(true);
            name.setAlignment(Pos.CENTER);
            name.setMaxWidth(cardSize - 10);

            VBox layout = new VBox(6, thumb, name);
            layout.setAlignment(Pos.CENTER);
            layout.setPadding(new Insets(8));
            VBox.setVgrow(thumb, Priority.ALWAYS);
            card.getChildren().add(layout);

            card.getProperties().put(ENTRY_KEY, entry);
            card.setOnMouseEntered(e -> SoundManager.get().play(SoundManager.Cue.HOVER));
            // Select on press, not click: a press can't be lost to a layout pass
            // or card rebuild between press and release.
            card.setOnMousePressed(e -> {
                if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) {
                    return;
                }
                if (e.getClickCount() == 2) {
                    onActivate.accept(entry, card);
                    return;
                }
                if (entry.equals(selectedEntry)) {
                    return;
                }
                SoundManager.get().play(SoundManager.Cue.OPEN);
                select(entry);
            });
            card.setOnContextMenuRequested(e -> {
                onMenu.show(entry, card, e.getScreenX(), e.getScreenY());
                e.consume();
            });
            return card;
        }

        private javafx.scene.Node thumbnailNode(AssetEntry entry) {
            if (entry.sourceRef() instanceof com.mocktroid.library.TextureSequences.SequenceRef seq) {
                // A frame sequence: its first frame as the icon.
                PakAssetRef first = seq.frames().get(0);
                entry = new AssetEntry("TXTR:" + first.asset().assetId(), entry.displayName(), AssetType.TEXTURE,
                    entry.sourcePakName(), first);
            }
            if (entry.type() == AssetType.TEXTURE && entry.sourceRef() instanceof PakAssetRef ref) {
                TextureThumbnailCache cache = TextureThumbnailCache.get();
                TextureThumbnailCache.Thumb ready = cache.getIfLoaded(ref.asset().assetId());
                if (ready != null) {
                    return textureThumb(ready);
                }
                // Cards are rebuilt on every refresh, so a slot can be detached by
                // the time its thumbnail arrives -- filling a detached slot is harmless.
                StackPane slot = new StackPane(new LoadingSpinner(32));
                cache.request(entry, thumb -> slot.getChildren().setAll(
                    thumb != null ? textureThumb(thumb) : GameIcons.screwAttackPlaceholder(36)));
                return slot;
            }
            if ((entry.type() == AssetType.MODEL || entry.type() == AssetType.VIDEO)
                && entry.sourceRef() instanceof PakAssetRef ref && !entry.id().startsWith("CHPR:")) {
                AssetIconCache cache = AssetIconCache.get();
                AssetIconCache.Thumb ready = cache.getIfLoaded(ref.asset().assetId());
                if (ready != null) {
                    return modelThumb(ready);
                }
                StackPane slot = new StackPane(new LoadingSpinner(32));
                cache.request(entry, thumb -> slot.getChildren().setAll(
                    thumb != null ? modelThumb(thumb) : GameIcons.screwAttackPlaceholder(36)));
                return slot;
            }
            if (entry.type() == AssetType.SOUND && entry.sourceRef() instanceof PakAssetRef ref) {
                UUID assetId = ref.asset().assetId();
                CachedWaveform cached = SoundThumbnailCache.get().get(assetId);
                if (cached != null) {
                    return miniWaveform(cached);
                }
            }
            return GameIcons.screwAttackPlaceholder(36);
        }

        private ImageView textureThumb(TextureThumbnailCache.Thumb thumb) {
            WritableImage image = new WritableImage(thumb.width(), thumb.height());
            image.getPixelWriter().setPixels(0, 0, thumb.width(), thumb.height(),
                PixelFormat.getIntArgbInstance(), thumb.argb(), 0, thumb.width());
            ImageView view = new ImageView(image);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            double box = cardSize - 44; // leaves room for the name label under it
            if (thumb.width() >= thumb.height()) {
                view.setFitWidth(Math.min(box, thumb.width() * 2.0));
            } else {
                view.setFitHeight(Math.min(box, thumb.height() * 2.0));
            }
            return view;
        }

        private ImageView modelThumb(AssetIconCache.Thumb thumb) {
            ImageView view = new ImageView(AssetIconCache.toImage(thumb));
            view.setPreserveRatio(true);
            view.setSmooth(true);
            double box = cardSize - 44;
            if (thumb.width() >= thumb.height()) {
                view.setFitWidth(box);
            } else {
                view.setFitHeight(box);
            }
            return view;
        }

        private Canvas miniWaveform(CachedWaveform waveform) {
            double width = cardSize - 20;
            Canvas canvas = new Canvas(width, THUMB_WAVEFORM_HEIGHT);
            GraphicsContext gc = canvas.getGraphicsContext2D();
            gc.setStroke(Color.web("#4fd8f0", 0.85));
            gc.setLineWidth(1);
            double mid = THUMB_WAVEFORM_HEIGHT / 2;
            int bins = waveform.minBins().length;
            for (int i = 0; i < bins; i++) {
                double x = (double) i / bins * width;
                double y1 = mid - (waveform.maxBins()[i] / 32768.0) * mid;
                double y2 = mid - (waveform.minBins()[i] / 32768.0) * mid;
                gc.strokeLine(x + 0.5, y1, x + 0.5, y2);
            }
            return canvas;
        }
    }
}
