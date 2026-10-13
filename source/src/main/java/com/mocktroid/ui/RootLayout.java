package com.mocktroid.ui;

import com.mocktroid.library.AnimationSupport;
import com.mocktroid.model.AssetEntry;
import com.mocktroid.model.AssetLibrary;
import com.mocktroid.model.AssetType;

import javafx.geometry.Insets;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Top-level window content: an always-visible {@link WindowChromeBar}, then
 * either [{@link CategoryTabBar} + split {@link BrowsePane}/{@link PreviewPane}]
 * or a full-window {@link SettingsView}, toggled by the settings tab /
 * Settings' own Back button.
 * <p>
 * This class only wires navigation between already-built modules -- it
 * doesn't know anything about asset formats.
 */
public final class RootLayout extends StackPane {

    private final BorderPane appShell = new BorderPane();
    private final CategoryTabBar tabBar;
    private final BrowsePane browsePane = new BrowsePane();
    private final PreviewPane previewPane = new PreviewPane();
    private final SettingsView settingsView;
    private final AssetLibrary library;
    private HBox splitArea;
    private final VBox wipPane = new VBox(14);

    public RootLayout(Stage stage, AssetLibrary library) {
        this.library = library;
        getStyleClass().add("root-layout");

        tabBar = new CategoryTabBar(stage, this::selectType, this::openSettings);
        settingsView = new SettingsView(this::closeSettings, library);
        settingsView.setOwnerWindow(stage);

        HBox.setHgrow(previewPane, Priority.ALWAYS);
        browsePane.setPrefWidth(420);
        browsePane.setMinWidth(320);

        splitArea = new HBox(20, browsePane, previewPane);
        splitArea.setPadding(new Insets(0, 20, 16, 20));
        HBox.setHgrow(splitArea, Priority.ALWAYS);

        VBox topArea = new VBox(new WindowChromeBar(stage), tabBar);

        appShell.setTop(topArea);
        appShell.setCenter(splitArea);
        wipPane.getStyleClass().add("wip-pane");
        wipPane.setAlignment(javafx.geometry.Pos.CENTER);

        getChildren().addAll(appShell, settingsView);
        settingsView.setVisible(false);
        settingsView.setManaged(false);

        browsePane.selectedEntryProperty().addListener((obs, old, val) -> {
            if (val != null) {
                previewPane.showEntry(val);
            }
        });
        previewPane.setNavigation(browsePane::nextEntry, browsePane::previousEntry, browsePane::selectExternally);
        previewPane.setLibrary(library);
        com.mocktroid.ui.preview.AssetIconCache.get().setLibrary(library);
        browsePane.setOnActivate(this::activateEntry);
        previewPane.setOnClipFinished(browsePane::selectNextClip);
        previewPane.setOnStepClip(browsePane::stepClip);
        // Fullscreen preview: only the preview pane, window fullscreen; Esc (JavaFX's exit key) leaves.
        previewPane.setOnFullscreen(on -> {
            browsePane.setVisible(!on);
            browsePane.setManaged(!on);
            topArea.setVisible(!on);
            topArea.setManaged(!on);
            stage.setFullScreenExitHint("");
            stage.setFullScreen(on);
        });
        stage.fullScreenProperty().addListener((obs, was, is) -> {
            if (!is) {
                previewPane.setFullscreen(false);
            }
        });
        browsePane.setOnMenu(this::showEntryMenu);
        browsePane.watchNames(library);
        library.applyCustomNames();
        // F2 renames the asset being previewed (unless a text field has the key).
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.F2 && appShell.isVisible()
                && !(getScene() != null && getScene().getFocusOwner() instanceof javafx.scene.control.TextInputControl)) {
                previewPane.beginRename();
                e.consume();
            }
        });

        // Land on the first non-empty category so the app isn't blank on launch.
        for (AssetType type : AssetType.values()) {
            if (!isSupported(type)) continue;
            if (!library.category(type).entries().isEmpty()) {
                tabBar.setActiveType(type);
                selectType(type);
                break;
            }
        }
    }

    /** Double-click: models open their animation player -- or, with no
     *  animation data, blink the card/row outline red instead. */
    private void activateEntry(AssetEntry entry, javafx.scene.Node source) {
        if (entry.type() != AssetType.MODEL) {
            return;
        }
        Thread lookup = new Thread(() -> {
            java.util.List<AnimationSupport.ClipRef> clips = AnimationSupport.clipsFor(entry, library);
            javafx.application.Platform.runLater(() -> {
                if (clips.isEmpty()) {
                    DenyBlink.play(source);
                    return;
                }
                UiSounds.get().play(UiSounds.Cue.OPEN_VIEWER);
                browsePane.showClips(entry.displayName(), clips, previewPane::showClip, () -> {
                    UiSounds.get().play(UiSounds.Cue.BACK);
                    browsePane.exitClips();
                    previewPane.showClip(null);
                });
            });
        }, "clip-lookup");
        lookup.setDaemon(true);
        lookup.start();
    }

    /** Right-click menu on an asset row/card. */
    private void showEntryMenu(AssetEntry entry, javafx.scene.Node node, double x, double y) {
        javafx.scene.control.MenuItem rename = new javafx.scene.control.MenuItem("Rename…");
        rename.setOnAction(e -> {
            if (previewPane.currentEntry() != entry) {
                browsePane.selectExternally(entry);
            }
            previewPane.beginRename();
        });
        javafx.scene.control.MenuItem reset = new javafx.scene.control.MenuItem("Restore original name");
        reset.setDisable(!entry.hasCustomName());
        reset.setOnAction(e -> {
            library.rename(entry, null);
        });
        javafx.scene.control.MenuItem copyName = new javafx.scene.control.MenuItem("Copy name");
        copyName.setOnAction(e -> copyToClipboard(entry.displayName()));
        javafx.scene.control.MenuItem copyId = new javafx.scene.control.MenuItem("Copy GUID");
        copyId.setOnAction(e -> copyToClipboard(com.mocktroid.library.CustomNames.keyOf(entry)));
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu(
            rename, reset, new javafx.scene.control.SeparatorMenuItem(), copyName, copyId);
        menu.getStyleClass().add("mocktroid-menu");
        menu.show(node, x, y);
    }

    private static void copyToClipboard(String text) {
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }

    /** Categories with a working browser + preview; the rest show a work-in-progress screen. */
    private static boolean isSupported(AssetType type) {
        return switch (type) {
            case TEXTURE, SOUND, MODEL, VIDEO, ANIMATED_TEXTURE, ALL -> true;
            default -> false;
        };
    }

    private void selectType(AssetType type) {
        UiSounds.get().play(UiSounds.Cue.TAB);
        if (!isSupported(type)) {
            browsePane.showCategory(null);
            previewPane.showEmpty();
            showWip(type);
            return;
        }
        appShell.setCenter(splitArea);
        browsePane.showCategory(library.category(type));
        previewPane.showEmpty();
    }

    private void showWip(AssetType type) {
        javafx.scene.control.Label title = new javafx.scene.control.Label(type.displayName.toUpperCase() + "  ·  NOT IMPLEMENTED YET");
        title.getStyleClass().add("wip-title");
        int count = library.category(type).entries().size();
        String body = switch (type) {
            case ROOM -> "Rooms tie together area geometry, lighting, actors and scripting -- the biggest format in the game. "
                + "Browsing and previewing them is on the roadmap once models, materials and textures are fully covered.";
            case MATERIAL -> "Materials (MTRL) need their compressed shader data decoded first.";
            default -> "This category doesn't have a browser or preview yet.";
        };
        javafx.scene.control.Label text = new javafx.scene.control.Label(body
            + (count > 0 ? String.format("%n%n%,d %s found in your romfs.", count, type.displayName.toLowerCase()) : ""));
        text.getStyleClass().add("wip-text");
        text.setWrapText(true);
        text.setMaxWidth(560);
        text.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        wipPane.getChildren().setAll(GameIcons.screwAttackPlaceholder(90), title, text);
        appShell.setCenter(wipPane);
    }

    private void openSettings() {
        tabBar.setSettingsActive(true);
        appShell.setVisible(false);
        appShell.setManaged(false);
        settingsView.setVisible(true);
        settingsView.setManaged(true);
    }

    private void closeSettings() {
        UiSounds.get().play(UiSounds.Cue.BACK);
        tabBar.setSettingsActive(false);
        settingsView.setVisible(false);
        settingsView.setManaged(false);
        appShell.setVisible(true);
        appShell.setManaged(true);
    }
}
