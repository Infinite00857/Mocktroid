package com.mocktroid.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.mocktroid.library.AnimationSupport;
import com.mocktroid.library.CachedWaveform;
import com.mocktroid.library.PakAssetRef;
import com.mocktroid.library.SoundDurations;
import com.mocktroid.library.SoundThumbnailCache;
import com.mocktroid.model.AssetCategory;
import com.mocktroid.model.AssetEntry;
import com.mocktroid.settings.AppSettings;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

/**
 * The left-hand browse panel: an "assets: N" header, a pak filter + sort
 * dropdown row and the list/grid asset view (whichever
 * {@code AppSettings.BrowseMode} picks), under the filter box at the top
 * (click to expand; type to narrow the current category by name).
 * <p>
 * Both views are themselves virtualized {@code ListView}s (see their class
 * docs), so this deliberately doesn't wrap either in its own ScrollPane --
 * that would fight their internal virtualization instead of just adding a
 * redundant scrollbar.
 * <p>
 * Name filter, pak selection, and sort all funnel through {@link #refreshView()}
 * into one derived list handed to whichever view is active, so they compose
 * correctly together rather than being three independent code paths.
 */
public final class BrowsePane extends VBox {

    private final ObjectProperty<AssetEntry> selectedEntry = new SimpleObjectProperty<>();
    private final Label countLabel = new Label();
    private final AssetListView listView = new AssetListView();
    private final AssetGridView gridView = new AssetGridView();
    private final StackPane viewSlot = new StackPane();
    private final TextField filterField = new TextField();
    private final StackPane filterBox = new StackPane();
    private final Button paksButton = new Button("Paks \u25BE");
    private final ComboBox<SortOption> sortBox = new ComboBox<>();

    private AssetCategory currentCategory;
    private HBox toolsRow;
    /** Animation mode: the list shows a model's clips instead of assets. */
    private final ListView<AnimationSupport.ClipRef> clipList = new ListView<>();
    private final HBox clipHeader = new HBox(10);
    private final Label clipTitle = new Label();
    private List<AnimationSupport.ClipRef> clips = List.of();
    private Runnable onClipsBack = () -> {};
    private java.util.function.Consumer<AnimationSupport.ClipRef> onClipSelect = c -> {};
    private boolean clipMode = false;
    private final Set<String> selectedPaks = new HashSet<>();
    private boolean allPaksSelected = true;
    private Popup paksPopup;

    public BrowsePane() {
        getStyleClass().add("browse-pane");
        setSpacing(10);

        countLabel.getStyleClass().add("browse-count-label");

        toolsRow = buildToolsRow();
        buildClipHeader();

        VBox.setVgrow(viewSlot, Priority.ALWAYS);

        buildFilter();

        getChildren().addAll(filterBox, countLabel, toolsRow, viewSlot);

        listView.selectedEntryProperty().addListener((obs, old, val) -> {
            if (val != null) selectedEntry.set(val);
        });
        gridView.setOnSelect(selectedEntry::set);

        AppSettings.get().browseModeProperty().addListener((obs, old, val) -> applyBrowseMode());
        AppSettings.get().gridColumnsProperty().addListener((obs, old, val) -> refreshView());
        // Sound lengths arrive from a background header scan; re-sort if they matter.
        SoundDurations.get().revisionProperty().addListener((obs, old, val) -> {
            if (sortBox.getValue() != null && sortBox.getValue().isDuration()) {
                refreshView();
            }
        });
        applyBrowseMode();
    }

    private HBox buildToolsRow() {
        paksButton.getStyleClass().add("settings-secondary-button");
        paksButton.setOnAction(e -> togglePaksPopup());

        sortBox.getItems().setAll(SortOption.values());
        sortBox.setValue(SortOption.ALPHABETICAL_ASC);
        sortBox.getStyleClass().add("settings-combo");
        sortBox.valueProperty().addListener((obs, old, val) -> refreshView());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(8, paksButton, spacer, sortBox);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void togglePaksPopup() {
        if (paksPopup != null && paksPopup.isShowing()) {
            paksPopup.hide();
            return;
        }
        paksPopup = buildPaksPopup();
        Bounds screenBounds = paksButton.localToScreen(paksButton.getBoundsInLocal());
        if (screenBounds != null) {
            paksPopup.show(paksButton, screenBounds.getMinX(), screenBounds.getMaxY() + 4);
        }
    }

    private Popup buildPaksPopup() {
        Popup popup = new Popup();
        popup.setAutoHide(true);

        List<String> paks = distinctPaksInCurrentCategory();

        VBox content = new VBox(8);
        content.getStyleClass().add("paks-popup");
        content.setPadding(new Insets(10));
        content.setPrefWidth(260);

        HBox quickRow = new HBox(8);
        Button allButton = new Button("All");
        Button noneButton = new Button("None");
        allButton.getStyleClass().add("settings-secondary-button");
        noneButton.getStyleClass().add("settings-secondary-button");
        quickRow.getChildren().addAll(allButton, noneButton);

        VBox checkList = new VBox(4);
        List<CheckBox> checkBoxes = new ArrayList<>();
        for (String pak : paks) {
            CheckBox cb = new CheckBox(pak);
            cb.getStyleClass().add("settings-checkbox");
            cb.setSelected(allPaksSelected || selectedPaks.contains(pak));
            cb.selectedProperty().addListener((obs, old, val) -> {
                if (val) selectedPaks.add(pak); else selectedPaks.remove(pak);
                allPaksSelected = selectedPaks.size() == paks.size();
                refreshView();
            });
            checkBoxes.add(cb);
            checkList.getChildren().add(cb);
        }
        allButton.setOnAction(e -> {
            selectedPaks.clear();
            selectedPaks.addAll(paks);
            allPaksSelected = true;
            checkBoxes.forEach(cb -> cb.setSelected(true));
            refreshView();
        });
        noneButton.setOnAction(e -> {
            selectedPaks.clear();
            allPaksSelected = false;
            checkBoxes.forEach(cb -> cb.setSelected(false));
            refreshView();
        });

        ScrollPane scroll = new ScrollPane(checkList);
        scroll.getStyleClass().add("paks-popup-scroll");
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(Math.min(320, Math.max(60, paks.size() * 28 + 16)));

        content.getChildren().addAll(quickRow, scroll);
        popup.getContent().add(content);
        return popup;
    }

    private List<String> distinctPaksInCurrentCategory() {
        if (currentCategory == null) {
            return List.of();
        }
        return currentCategory.entries().stream()
            .map(AssetEntry::sourcePakName)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
    }

    private void buildFilter() {
        // Always a plain text field: one click anywhere in the box puts the cursor in it.
        filterField.setPromptText("Search\u2026");
        filterField.getStyleClass().add("filter-field");
        filterField.textProperty().addListener((obs, old, val) -> refreshView());
        HBox.setHgrow(filterField, Priority.ALWAYS);
        filterField.setMaxWidth(Double.MAX_VALUE);

        filterBox.getChildren().add(filterField);
        filterBox.setAlignment(Pos.CENTER_LEFT);
        filterBox.getStyleClass().add("filter-box");
        filterBox.setOnMousePressed(e -> filterField.requestFocus());
    }

    /** The browse search and scroll position from before animation mode, restored when it's left. */
    private String savedFilter = "";
    private double savedScroll;

    private javafx.scene.control.skin.VirtualFlow<?> activeFlow() {
        javafx.scene.Node view = viewSlot.getChildren().isEmpty() ? null : viewSlot.getChildren().get(0);
        return view == null ? null : (javafx.scene.control.skin.VirtualFlow<?>) view.lookup(".virtual-flow");
    }

    /** Re-filter/re-sort when derived names arrive (they change what's shown and its order). */
    public void watchNames(com.mocktroid.model.AssetLibrary library) {
        library.namesRevisionProperty().addListener((obs, old, val) -> refreshView());
    }

    private void buildClipHeader() {
        Button back = new Button("\u25C2 Back");
        back.getStyleClass().add("settings-secondary-button");
        back.setOnAction(e -> onClipsBack.run());
        clipTitle.getStyleClass().add("browse-count-label");
        clipHeader.getChildren().addAll(back, clipTitle);
        clipHeader.setAlignment(Pos.CENTER_LEFT);

        clipList.getStyleClass().add("asset-list-view");
        clipList.getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> {
            if (val != null && clipMode) {
                onClipSelect.accept(val);
            }
            clipList.refresh();
        });
        clipList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(AnimationSupport.ClipRef item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label name = new Label(item.clip().name());
                name.getStyleClass().add("asset-list-row-label");
                Label frames = new Label(item.clip().frames() + " f");
                frames.getStyleClass().add("transport-label");
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                HBox row = new HBox(8, name, spacer, frames);
                row.setPadding(new Insets(7, 12, 7, 12));
                AngledPanel panel = new AngledPanel(8);
                panel.getStyleClass().add("asset-list-row");
                if (isSelected()) {
                    panel.getStyleClass().add("selected");
                }
                panel.getChildren().add(row);
                setGraphic(panel);
                setText(null);
            }
        });
    }

    /** Swaps the browse list for a model's animation clips (animation mode). */
    public void showClips(String title, List<AnimationSupport.ClipRef> clips,
                          java.util.function.Consumer<AnimationSupport.ClipRef> onSelect, Runnable onBack) {
        this.onClipsBack = onBack;
        boolean wasClips = clipMode;
        if (!wasClips) {
            javafx.scene.control.skin.VirtualFlow<?> flow = activeFlow();
            savedScroll = flow != null ? flow.getPosition() : 0;
            savedFilter = filterField.getText() == null ? "" : filterField.getText();
        }
        this.clips = clips;
        clipTitle.setText(title + "  \u00b7  " + clips.size() + (clips.size() == 1 ? " animation" : " animations"));
        this.onClipSelect = onSelect;
        clipMode = true;
        filterField.clear();
        getChildren().setAll(clipHeader, filterBox, viewSlot);
        viewSlot.getChildren().setAll(clipList);
        refreshClips();
    }

    /** Leaves animation mode, back to the asset list/grid. */
    public void exitClips() {
        if (!clipMode) {
            return;
        }
        clipMode = false;
        clipList.getSelectionModel().clearSelection();
        clipList.getItems().clear();
        getChildren().setAll(filterBox, countLabel, toolsRow, viewSlot);
        applyBrowseMode();
        filterField.setText(savedFilter);
        refreshView();
        double scroll = savedScroll;
        // After the rebuilt list has been laid out.
        javafx.application.Platform.runLater(() -> {
            javafx.scene.control.skin.VirtualFlow<?> flow = activeFlow();
            if (flow != null) {
                flow.setPosition(scroll);
            }
        });
    }

    /** Animation mode: selects the clip after the current one (wraps to the first). */
    public void selectNextClip() {
        stepClip(1);
    }

    /** Animation mode: selects the clip {@code delta} away from the current one (wrapping). */
    public void stepClip(int delta) {
        if (!clipMode || clipList.getItems().isEmpty()) {
            return;
        }
        int i = clipList.getSelectionModel().getSelectedIndex();
        int n = clipList.getItems().size();
        int next = ((i < 0 ? (delta > 0 ? -1 : 0) : i) + delta % n + n) % n;
        clipList.getSelectionModel().select(next);
        clipList.scrollTo(next);
    }

    public boolean inClipMode() {
        return clipMode;
    }

    private void refreshClips() {
        String needle = filterField.getText() == null ? "" : filterField.getText().toLowerCase();
        clipList.getItems().setAll(clips.stream()
            .filter(c -> needle.isEmpty() || c.clip().name().toLowerCase().contains(needle)).toList());
    }

    /** Categories filled in the background (Animated Textures) refresh the view when their entries land. */
    private final javafx.collections.ListChangeListener<AssetEntry> categoryListener = c -> refreshView();

    public void showCategory(AssetCategory category) {
        exitClips();
        if (currentCategory != null) {
            currentCategory.entries().removeListener(categoryListener);
        }
        if (category != null) {
            category.entries().addListener(categoryListener);
        }
        this.currentCategory = category;
        offerSortOptionsFor(category);
        filterField.clear();
        savedFilter = "";
        selectedPaks.clear();
        selectedPaks.addAll(distinctPaksInCurrentCategory());
        allPaksSelected = true;
        refreshView();
    }

    /** Duration sort only means something for sounds, so it's hidden elsewhere. */
    private void offerSortOptionsFor(AssetCategory category) {
        boolean timed = category != null && (category.type() == com.mocktroid.model.AssetType.SOUND
            || category.type() == com.mocktroid.model.AssetType.ALL);
        SortOption current = sortBox.getValue();
        List<SortOption> options = java.util.Arrays.stream(SortOption.values())
            .filter(o -> timed || !o.isDuration()).toList();
        if (!sortBox.getItems().equals(options)) {
            sortBox.getItems().setAll(options);
            sortBox.setValue(current != null && options.contains(current) ? current : SortOption.ALPHABETICAL_ASC);
        }
    }

    private void refreshView() {
        if (clipMode) {
            refreshClips();
            return;
        }
        if (currentCategory == null) {
            listView.showCategory(null);
            gridView.showCategory(null);
            countLabel.setText("assets: 0");
            return;
        }

        String needle = filterField.getText() == null ? "" : filterField.getText().toLowerCase();
        List<AssetEntry> filtered = new ArrayList<>();
        for (AssetEntry entry : currentCategory.entries()) {
            boolean nameOk = matchesFilter(entry, needle);
            boolean pakOk = allPaksSelected || selectedPaks.contains(entry.sourcePakName());
            if (nameOk && pakOk) {
                filtered.add(entry);
            }
        }
        sortEntries(filtered);

        AssetCategory view = new AssetCategory(currentCategory.type(), currentCategory.label());
        view.entries().setAll(FXCollections.observableArrayList(filtered));
        listView.showCategory(view);
        gridView.showCategory(view);

        int total = currentCategory.entries().size();
        int shown = filtered.size();
        boolean models = currentCategory.type() == com.mocktroid.model.AssetType.MODEL
            || currentCategory.type() == com.mocktroid.model.AssetType.ALL;
        countLabel.setText((shown == total ? "assets: " + total : "assets: " + shown + " / " + total)
            + (models ? "   ·   double-click a model to open its animations" : ""));
    }

    /** Name filter: the shown name, the original name (for renamed assets) or the GUID. */
    private static boolean matchesFilter(AssetEntry entry, String needle) {
        return needle.isEmpty()
            || entry.displayName().toLowerCase().contains(needle)
            || (entry.hasCustomName() && entry.originalName().toLowerCase().contains(needle))
            || entry.id().toLowerCase().contains(needle);
    }

    private void sortEntries(List<AssetEntry> entries) {
        SortOption option = sortBox.getValue();
        if (option == null) {
            return;
        }
        Comparator<AssetEntry> comparator = switch (option) {
            case ALPHABETICAL_ASC -> Comparator.comparing(AssetEntry::displayName, String.CASE_INSENSITIVE_ORDER);
            case ALPHABETICAL_DESC ->
                Comparator.comparing(AssetEntry::displayName, String.CASE_INSENSITIVE_ORDER).reversed();
            case DURATION_ASC -> byDuration(false);
            case DURATION_DESC -> byDuration(true);
            case PAK_ASC -> Comparator.comparing(BrowsePane::pakNameOf, String.CASE_INSENSITIVE_ORDER);
            case PAK_DESC -> Comparator.comparing(BrowsePane::pakNameOf, String.CASE_INSENSITIVE_ORDER).reversed();
        };
        entries.sort(comparator);
    }

    private static String pakNameOf(AssetEntry entry) {
        return entry.sourcePakName() == null ? "" : entry.sourcePakName();
    }

    /** Duration order; entries with no known length (non-sounds, or headers
     *  still being read) go last either way, and ties fall back to the name. */
    private Comparator<AssetEntry> byDuration(boolean longestFirst) {
        Comparator<Double> known = longestFirst ? Comparator.reverseOrder() : Comparator.naturalOrder();
        return Comparator.<AssetEntry, Boolean>comparing(e -> durationOf(e) < 0)
            .thenComparing(BrowsePane::durationOf, known)
            .thenComparing(AssetEntry::displayName, String.CASE_INSENSITIVE_ORDER);
    }

    /** Seconds from the CSMP header (SoundDurations), else from a Sound
     *  Waveform Cache entry, else -1 (unknown). */
    private static double durationOf(AssetEntry entry) {
        if (entry.sourceRef() instanceof PakAssetRef ref) {
            double s = SoundDurations.get().seconds(ref.asset().assetId());
            if (s >= 0) {
                return s;
            }
            CachedWaveform cached = SoundThumbnailCache.get().get(ref.asset().assetId());
            if (cached != null) {
                return cached.durationSeconds();
            }
        }
        return -1;
    }

    private void applyBrowseMode() {
        boolean grid = AppSettings.get().browseModeProperty().get() == AppSettings.BrowseMode.GRID;
        viewSlot.getChildren().setAll(grid ? gridView : listView);
    }

    /** Right-click on an entry in whichever view is active. */
    public void setOnMenu(EntryMenuHandler onMenu) {
        listView.setOnMenu(onMenu);
        gridView.setOnMenu(onMenu);
    }

    /** Double-click on an entry in whichever view is active. */
    public void setOnActivate(java.util.function.BiConsumer<AssetEntry, javafx.scene.Node> onActivate) {
        listView.setOnActivate(onActivate);
        gridView.setOnActivate(onActivate);
    }

    public ObjectProperty<AssetEntry> selectedEntryProperty() {
        return selectedEntry;
    }

    /** Selects an entry programmatically (e.g. from the preview pane's
     *  next/previous buttons), keeping the browse view's own highlight in
     *  sync -- routes to whichever of list/grid is currently active. */
    public void selectExternally(AssetEntry entry) {
        if (AppSettings.get().browseModeProperty().get() == AppSettings.BrowseMode.GRID) {
            gridView.select(entry);
        } else {
            listView.select(entry);
        }
    }

    /** The entry after {@code current} in the currently-shown (filtered +
     *  sorted) list, or null at the end / if not found. Used for the
     *  preview pane's next/previous navigation. */
    public AssetEntry nextEntry(AssetEntry current) {
        return adjacentEntry(current, 1);
    }

    public AssetEntry previousEntry(AssetEntry current) {
        return adjacentEntry(current, -1);
    }

    private AssetEntry adjacentEntry(AssetEntry current, int delta) {
        // Re-derive the same filtered+sorted order refreshView() last computed,
        // rather than caching it, since it's cheap and avoids a second
        // source of truth to keep in sync.
        if (currentCategory == null) {
            return null;
        }
        String needle = filterField.getText() == null ? "" : filterField.getText().toLowerCase();
        List<AssetEntry> shown = new ArrayList<>();
        for (AssetEntry entry : currentCategory.entries()) {
            boolean nameOk = matchesFilter(entry, needle);
            boolean pakOk = allPaksSelected || selectedPaks.contains(entry.sourcePakName());
            if (nameOk && pakOk) {
                shown.add(entry);
            }
        }
        sortEntries(shown);
        int index = shown.indexOf(current);
        int target = index + delta;
        return index < 0 || target < 0 || target >= shown.size() ? null : shown.get(target);
    }
}
