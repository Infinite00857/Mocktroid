package com.mocktroid.ui;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import com.mocktroid.audio.SoundManager;
import com.mocktroid.format.audio.VgmstreamClient;
import com.mocktroid.library.CustomNames;
import com.mocktroid.library.SoundThumbnailCache;
import com.mocktroid.library.SoundThumbnailCacheBuilder;
import com.mocktroid.library.TextureThumbnailCache;
import com.mocktroid.model.AssetLibrary;
import com.mocktroid.model.AssetType;
import com.mocktroid.settings.AppSettings;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * The settings screen. Takes over the entire window while open (RootLayout
 * hides the tab bar and browse/preview panes) -- see the two-pane layout
 * with a section rail on the left, that section's rows on the right.
 */
public final class SettingsView extends HBox {

    private static final double CUT = 10;

    private final VBox sectionRail = new VBox(6);
    private final VBox content = new VBox(18);
    private final Map<String, Region> sectionContent = new LinkedHashMap<>();
    private final AssetLibrary library;
    private Window ownerWindow;
    private Task<Void> soundCacheTask;

    public SettingsView(Runnable onBack, AssetLibrary library) {
        this.library = library;
        getStyleClass().add("settings-view");

        VBox rail = new VBox(10);
        rail.getStyleClass().add("settings-rail");
        rail.setPadding(new Insets(20, 16, 16, 16));
        rail.setPrefWidth(240);

        Label title = new Label("SETTINGS");
        title.getStyleClass().add("rail-title");

        Button back = new Button("\u2190 Back");
        back.getStyleClass().add("back-button");
        back.setOnAction(e -> {
            SoundManager.get().play(SoundManager.Cue.BACK);
            onBack.run();
        });

        buildSections();
        rail.getChildren().addAll(back, title, sectionRail);

        Region watermarkSpacer = new Region();
        VBox.setVgrow(watermarkSpacer, Priority.ALWAYS);
        var watermark = GameIcons.infiniteLogo(80);
        watermark.setOpacity(0.35);
        HBox watermarkBox = new HBox(watermark);
        watermarkBox.setAlignment(Pos.BOTTOM_CENTER);
        watermarkBox.setPadding(new Insets(0, 0, 8, 0));
        rail.getChildren().addAll(watermarkSpacer, watermarkBox);

        content.getStyleClass().add("settings-content");
        content.setPadding(new Insets(24, 32, 20, 32));
        HBox.setHgrow(content, Priority.ALWAYS);

        VBox contentColumn = new VBox(content, buildFooter());
        VBox.setVgrow(content, Priority.ALWAYS);
        HBox.setHgrow(contentColumn, Priority.ALWAYS);

        getChildren().addAll(rail, contentColumn);

        selectSection("General");
    }

    /** RootLayout calls this once it has a Stage, so the folder/file pickers
     *  used below have an owner window. */
    public void setOwnerWindow(Window ownerWindow) {
        this.ownerWindow = ownerWindow;
    }

    private void buildSections() {
        addSection("General", buildGeneralSection());
        addSection("Audio", buildAudioSection());
        addSection("Preview", buildPreviewSection());
        addSection("About", buildAboutSection());
    }

    /** Legal notice (shown since exporting assets became possible). */
    static final String NOTICE = "Metroid Prime 4: Beyond and everything in it -- models, textures, animations, sounds, music, "
        + "videos and names -- belongs to Nintendo (developed by Retro Studios). Mocktroid is an unofficial fan project, not "
        + "affiliated with or endorsed by Nintendo. It contains no game data: it only reads files from your own dump of a game "
        + "you own. Anything you view or export is Nintendo's property -- keep it to personal, non-commercial use (study, "
        + "fan art, modding) and don't redistribute the game's assets.";

    private Region buildAboutSection() {
        Label title = new Label("About Mocktroid");
        title.getStyleClass().add("settings-row-label");
        Label notice = new Label(NOTICE);
        notice.getStyleClass().add("settings-hint");
        notice.setWrapText(true);
        Label trademark = new Label("Nintendo, Metroid and Metroid Prime are trademarks of Nintendo.");
        trademark.getStyleClass().add("settings-hint");
        trademark.setWrapText(true);
        return sectionBox(settingRow(title, null), notice, trademark);
    }

    private void addSection(String name, Region body) {
        sectionContent.put(name, body);

        AngledPanel row = new AngledPanel(CUT);
        row.getStyleClass().add("category-row");
        row.setMaxWidth(Double.MAX_VALUE);
        Label label = new Label(name);
        label.getStyleClass().add("category-label");
        HBox inner = new HBox(label);
        inner.setPadding(new Insets(8, 12, 8, 12));
        row.getChildren().add(inner);

        row.setOnMouseEntered(e -> SoundManager.get().play(SoundManager.Cue.HOVER));
        row.setOnMouseClicked(e -> {
            SoundManager.get().play(SoundManager.Cue.SELECT);
            selectSection(name);
        });
        row.getProperties().put("section-name", name);
        sectionRail.getChildren().add(row);
    }

    private void selectSection(String name) {
        for (var node : sectionRail.getChildren()) {
            boolean isSelected = name.equals(node.getProperties().get("section-name"));
            node.getStyleClass().removeAll("selected");
            if (isSelected) {
                node.getStyleClass().add("selected");
            }
        }
        content.getChildren().setAll(sectionContent.get(name));
    }

    private Region buildGeneralSection() {
        AppSettings settings = AppSettings.get();

        Label pathLabel = new Label("Romfs Path");
        pathLabel.getStyleClass().add("settings-row-label");
        TextField pathField = new TextField();
        pathField.getStyleClass().add("settings-textfield");
        pathField.textProperty().bindBidirectional(settings.romfsPathProperty());
        pathField.setPrefWidth(360);
        Button browse = new Button("Browse\u2026");
        browse.getStyleClass().add("settings-secondary-button");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select the Metroid Prime 4 romfs folder");
            String current = settings.romfsPathProperty().get();
            if (current != null && !current.isBlank() && new File(current).isDirectory()) {
                chooser.setInitialDirectory(new File(current));
            }
            File chosen = chooser.showDialog(ownerWindow);
            if (chosen != null) {
                settings.romfsPathProperty().set(chosen.getAbsolutePath());
            }
        });
        HBox pathRow = settingRow(pathLabel, new HBox(8, pathField, browse));

        Label updateLabel = new Label("Update RomFS Path");
        updateLabel.getStyleClass().add("settings-row-label");
        TextField updateField = new TextField();
        updateField.getStyleClass().add("settings-textfield");
        updateField.textProperty().bindBidirectional(settings.updateRomfsPathProperty());
        updateField.setPromptText("None");
        updateField.setPrefWidth(360);
        Button updateBrowse = new Button("Browse…");
        updateBrowse.getStyleClass().add("settings-secondary-button");
        updateBrowse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select the game update's romfs folder");
            File chosen = chooser.showDialog(ownerWindow);
            if (chosen != null) {
                settings.updateRomfsPathProperty().set(chosen.getAbsolutePath());
            }
        });
        HBox updateRow = settingRow(updateLabel, new HBox(8, updateField, updateBrowse));
        Label updateHint = new Label("The game update's romfs (dump it from your emulator: right-click the game > Dump RomFS, "
            + "with the update installed). Its paks replace the base game's; takes effect on the next launch.");
        updateHint.getStyleClass().add("settings-hint");
        updateHint.setWrapText(true);

        Label modeLabel = new Label("Default Browse Mode");
        modeLabel.getStyleClass().add("settings-row-label");
        ComboBox<AppSettings.BrowseMode> modeBox =
            new ComboBox<>(javafx.collections.FXCollections.observableArrayList(AppSettings.BrowseMode.values()));
        modeBox.getStyleClass().add("settings-combo");
        modeBox.valueProperty().bindBidirectional(settings.browseModeProperty());

        Label columnsLabel = new Label("Grid Columns");
        columnsLabel.getStyleClass().add("settings-row-label");
        Spinner<Integer> columnsSpinner = new Spinner<>(1, 8, settings.gridColumnsProperty().get());
        columnsSpinner.setEditable(true);
        columnsSpinner.getStyleClass().add("settings-spinner");
        columnsSpinner.valueProperty().addListener((obs, old, val) -> settings.gridColumnsProperty().set(val));

        Label cacheLabel = new Label("Model / Video Icon Cache");
        cacheLabel.getStyleClass().add("settings-row-label");
        com.mocktroid.ui.preview.AssetIconCache modelCache = com.mocktroid.ui.preview.AssetIconCache.get();
        Button clearModelCache = new Button("Clear");
        clearModelCache.getStyleClass().add("settings-secondary-button");
        Label modelCacheHint = new Label();
        modelCacheHint.getStyleClass().add("settings-hint");
        modelCacheHint.setWrapText(true);
        Runnable refreshModelCache = () -> modelCacheHint.setText(modelCache.countOnDisk() + " model and video icons cached -- rendered "
            + "automatically as models and videos scroll into view in grid mode. The preview's \"Cache icon\" button saves the current view instead.");
        refreshModelCache.run();
        modelCacheHint.sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) {
                refreshModelCache.run();
            }
        });
        clearModelCache.setOnAction(e -> {
            modelCache.clear();
            refreshModelCache.run();
        });
        Button buildCache = new Button(settings.thumbnailCacheBuiltProperty().get() ? "Rebuild" : "Build Now\u2026");
        buildCache.getStyleClass().add("settings-secondary-button");
        buildCache.setDisable(true); // no thumbnail generator exists yet
        Label cacheHint = new Label("Not available yet -- needs the model preview module first.");
        cacheHint.getStyleClass().add("settings-hint");

        Region soundCacheRow = buildSoundCacheRow();

        return sectionBox(
            pathRow,
            updateRow, updateHint,
            settingRow(modeLabel, modeBox),
            settingRow(columnsLabel, columnsSpinner),
            settingRow(cacheLabel, clearModelCache),
            modelCacheHint,
            buildTextureCacheRow(),
            soundCacheRow,
            buildCustomNamesRow()
        );
    }

    /** The user's own asset names (CustomNames): count, import/export, clear. */
    private Region buildCustomNamesRow() {
        CustomNames names = CustomNames.get();
        Label label = new Label("Custom Asset Names");
        label.getStyleClass().add("settings-row-label");
        Label status = new Label();
        status.getStyleClass().add("settings-hint");
        status.setWrapText(true);
        Runnable refresh = () -> status.setText(names.count() + " assets renamed. Rename from the preview "
            + "header's ✎ button, F2, or right-click → Rename. Names live only in Mocktroid ("
            + names.file() + "), never in the game files.");
        refresh.run();
        status.sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) {
                refresh.run();
            }
        });

        FileChooser.ExtensionFilter tsv = new FileChooser.ExtensionFilter("Name lists (*.tsv, *.txt)", "*.tsv", "*.txt");
        Button importButton = new Button("Import…");
        importButton.getStyleClass().add("settings-secondary-button");
        importButton.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Import a custom name list");
            chooser.getExtensionFilters().add(tsv);
            File chosen = chooser.showOpenDialog(ownerWindow);
            if (chosen != null) {
                try {
                    int n = names.importFrom(chosen.toPath());
                    library.applyCustomNames();
                    refresh.run();
                    status.setText("Imported " + n + " names. " + status.getText());
                } catch (Exception ex) {
                    status.setText("Couldn't import " + chosen + ": " + ex.getMessage());
                }
            }
        });
        Button exportButton = new Button("Export…");
        exportButton.getStyleClass().add("settings-secondary-button");
        exportButton.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Export custom names");
            chooser.getExtensionFilters().add(tsv);
            chooser.setInitialFileName("mocktroid-names.tsv");
            File chosen = chooser.showSaveDialog(ownerWindow);
            if (chosen != null) {
                try {
                    names.exportTo(chosen.toPath());
                    status.setText("Exported " + names.count() + " names to " + chosen);
                } catch (Exception ex) {
                    status.setText("Couldn't export to " + chosen + ": " + ex.getMessage());
                }
            }
        });
        // Clearing can't be undone, so it takes a second click to confirm.
        Button clearButton = new Button("Clear All");
        clearButton.getStyleClass().add("settings-secondary-button");
        clearButton.setOnAction(e -> {
            if (!"confirm".equals(clearButton.getUserData())) {
                clearButton.setUserData("confirm");
                clearButton.setText("Click again to clear");
                return;
            }
            clearButton.setUserData(null);
            clearButton.setText("Clear All");
            names.clear();
            library.applyCustomNames();
            refresh.run();
        });
        clearButton.focusedProperty().addListener((obs, was, is) -> {
            if (!is) {
                clearButton.setUserData(null);
                clearButton.setText("Clear All");
            }
        });
        return sectionBox(settingRow(label, new HBox(8, importButton, exportButton, clearButton)), status);
    }

    private Region buildTextureCacheRow() {
        TextureThumbnailCache cache = TextureThumbnailCache.get();
        Label label = new Label("Texture Thumbnail Cache");
        label.getStyleClass().add("settings-row-label");
        Label status = new Label();
        status.getStyleClass().add("settings-hint");
        Runnable refresh = () -> status.setText(cache.countOnDisk() + " texture thumbnails cached -- "
            + "generated automatically as textures scroll into view in grid mode.");
        refresh.run();
        Button clear = new Button("Clear");
        clear.getStyleClass().add("settings-secondary-button");
        clear.setOnAction(e -> {
            cache.clear();
            refresh.run();
        });
        // The count changes while browsing, so re-read it whenever this section is shown.
        status.sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) {
                refresh.run();
            }
        });
        return sectionBox(settingRow(label, clear), status);
    }

    private Region buildSoundCacheRow() {
        SoundThumbnailCache cache = SoundThumbnailCache.get();
        var soundEntries = library.category(AssetType.SOUND).entries();

        Label label = new Label("Sound Waveform Cache");
        label.getStyleClass().add("settings-row-label");

        Button buildButton = new Button("Build Now\u2026");
        buildButton.getStyleClass().add("settings-secondary-button");

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.getStyleClass().add("settings-progress");
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        progressBar.setMaxWidth(Double.MAX_VALUE);

        Label statusLabel = new Label(cache.countOnDisk() + " of " + soundEntries.size() + " sounds cached");
        statusLabel.getStyleClass().add("settings-hint");

        buildButton.setOnAction(e -> {
            if (soundCacheTask != null && soundCacheTask.isRunning()) {
                soundCacheTask.cancel();
                buildButton.setText("Build Now\u2026");
                return;
            }
            SoundThumbnailCacheBuilder task = new SoundThumbnailCacheBuilder(new java.util.ArrayList<>(soundEntries));
            soundCacheTask = task;
            progressBar.progressProperty().bind(task.progressProperty());
            progressBar.setVisible(true);
            progressBar.setManaged(true);
            buildButton.setText("Cancel");
            statusLabel.textProperty().bind(task.messageProperty());
            task.setOnSucceeded(ev -> onSoundCacheTaskDone(buildButton, progressBar, statusLabel, cache, soundEntries.size()));
            task.setOnCancelled(ev -> onSoundCacheTaskDone(buildButton, progressBar, statusLabel, cache, soundEntries.size()));
            task.setOnFailed(ev -> onSoundCacheTaskDone(buildButton, progressBar, statusLabel, cache, soundEntries.size()));
            Thread thread = new Thread(task, "sound-thumbnail-cache");
            thread.setDaemon(true);
            thread.start();
        });

        Label hint = new Label("Generates a small waveform image for every sound so grid view can show a "
            + "real preview instead of a placeholder icon. Decodes every sound once (~"
            + soundEntries.size() + " sounds) -- this can take a long time on a full romfs.");
        hint.getStyleClass().add("settings-hint");
        hint.setWrapText(true);

        return sectionBox(settingRow(label, buildButton), progressBar, statusLabel, hint);
    }

    private void onSoundCacheTaskDone(Button button, ProgressBar progressBar, Label statusLabel,
                                       SoundThumbnailCache cache, int total) {
        button.setText("Build Now\u2026");
        progressBar.progressProperty().unbind();
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        statusLabel.textProperty().unbind();
        statusLabel.setText(cache.countOnDisk() + " of " + total + " sounds cached");
        if (cache.countOnDisk() > 0) {
            AppSettings.get().thumbnailCacheBuiltProperty().set(true);
        }
    }

    private Region buildAudioSection() {
        AppSettings settings = AppSettings.get();

        CheckBox muteBox = new CheckBox("Mute all sounds");
        muteBox.getStyleClass().add("settings-checkbox");
        muteBox.selectedProperty().bindBidirectional(settings.mutedProperty());

        Label volumeLabel = new Label("Master Volume");
        volumeLabel.getStyleClass().add("settings-row-label");
        Slider volumeSlider = new Slider(0, 1, settings.masterVolumeProperty().get());
        volumeSlider.getStyleClass().add("settings-slider");
        volumeSlider.valueProperty().bindBidirectional(settings.masterVolumeProperty());
        volumeSlider.disableProperty().bind(settings.mutedProperty());
        volumeSlider.setPrefWidth(260);

        CheckBox useGameSoundsBox = new CheckBox("Use game UI sounds");
        useGameSoundsBox.getStyleClass().add("settings-checkbox");
        useGameSoundsBox.selectedProperty().bindBidirectional(settings.useGameUiSoundsProperty());
        Label useGameSoundsHint = new Label("Plays the game's own menu sounds on Mocktroid's interface: hover, select, tabs, "
            + "back, scrolling, play/pause, opening the animation list. Needs vgmstream.");
        useGameSoundsHint.getStyleClass().add("settings-hint");
        useGameSoundsHint.setWrapText(true);

        CheckBox autoplaySoundBox = new CheckBox("Autoplay sounds");
        autoplaySoundBox.getStyleClass().add("settings-checkbox");
        autoplaySoundBox.selectedProperty().bindBidirectional(settings.autoplaySoundProperty());

        CheckBox autoplayVideoBox = new CheckBox("Autoplay videos");
        autoplayVideoBox.getStyleClass().add("settings-checkbox");
        autoplayVideoBox.selectedProperty().bindBidirectional(settings.autoplayVideoProperty());
        CheckBox nextSoundBox = new CheckBox("Play the next sound when one finishes");
        nextSoundBox.getStyleClass().add("settings-checkbox");
        nextSoundBox.selectedProperty().bindBidirectional(settings.autoplayNextSoundProperty());
        CheckBox nextVideoBox = new CheckBox("Play the next video when one finishes");
        nextVideoBox.getStyleClass().add("settings-checkbox");
        nextVideoBox.selectedProperty().bindBidirectional(settings.autoplayNextVideoProperty());

        Label loopCountLabel = new Label("Sound Loop Count");
        loopCountLabel.getStyleClass().add("settings-row-label");
        Spinner<Double> loopCountSpinner = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(1.0, 20.0, settings.soundLoopCountProperty().get(), 1.0));
        loopCountSpinner.setEditable(true);
        loopCountSpinner.getStyleClass().add("settings-spinner");
        loopCountSpinner.valueProperty().addListener((obs, old, val) -> settings.soundLoopCountProperty().set(val));
        Label loopCountHint = new Label("Only affects sounds that have loop points (e.g. music) -- a "
            + "one-shot sound effect just plays once regardless. Higher counts mean a longer preview: "
            + "e.g. a 4-minute track with 1 loop becomes ~8 minutes at 2 loops.");
        loopCountHint.getStyleClass().add("settings-hint");
        loopCountHint.setWrapText(true);

        Label vgmLabel = new Label("vgmstream-cli");
        vgmLabel.getStyleClass().add("settings-row-label");
        TextField vgmField = new TextField();
        vgmField.getStyleClass().add("settings-textfield");
        vgmField.setPromptText("Auto-detect");
        vgmField.setPrefWidth(300);
        vgmField.textProperty().bindBidirectional(settings.vgmstreamPathProperty());
        Button vgmBrowse = new Button("Browse\u2026");
        vgmBrowse.getStyleClass().add("settings-secondary-button");
        vgmBrowse.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Locate vgmstream-cli");
            File chosen = chooser.showOpenDialog(ownerWindow);
            if (chosen != null) {
                settings.vgmstreamPathProperty().set(chosen.getAbsolutePath());
            }
        });
        Label vgmStatus = new Label();
        vgmStatus.getStyleClass().add("settings-hint");
        vgmStatus.setWrapText(true);
        Runnable checkVgmstream = () -> {
            vgmStatus.setText("Checking\u2026");
            Thread t = new Thread(() -> {
                VgmstreamClient.resetLookup();
                String exe = VgmstreamClient.executablePath();
                Platform.runLater(() -> vgmStatus.setText(exe != null
                    ? "Using " + exe
                    : "Not found -- sound previews need vgmstream-cli. macOS: brew install vgmstream. "
                        + "Windows: unzip vgmstream-win64.zip (github.com/vgmstream/vgmstream/releases) "
                        + "and point this at vgmstream-cli.exe or its folder."));
            }, "vgmstream-check");
            t.setDaemon(true);
            t.start();
        };
        settings.vgmstreamPathProperty().addListener((obs, old, val) -> checkVgmstream.run());
        checkVgmstream.run();

        return sectionBox(settingRow(vgmLabel, new HBox(8, vgmField, vgmBrowse)), vgmStatus,
            settingRow(muteBox, null), settingRow(volumeLabel, volumeSlider),
            settingRow(useGameSoundsBox, null), useGameSoundsHint,
            settingRow(autoplaySoundBox, null), settingRow(nextSoundBox, null),
            settingRow(autoplayVideoBox, null), settingRow(nextVideoBox, null),
            settingRow(loopCountLabel, loopCountSpinner), loopCountHint);
    }

    private Region buildPreviewSection() {
        AppSettings settings = AppSettings.get();

        Label resLabel = new Label("Preview Render Resolution");
        resLabel.getStyleClass().add("settings-row-label");
        ComboBox<AppSettings.RenderResolution> resBox = new ComboBox<>(
            javafx.collections.FXCollections.observableArrayList(AppSettings.RenderResolution.values()));
        resBox.getStyleClass().add("settings-combo");
        resBox.valueProperty().bindBidirectional(settings.renderResolutionProperty());
        TextField customWidth = numberField(settings.customRenderWidthProperty());
        TextField customHeight = numberField(settings.customRenderHeightProperty());
        Label times = new Label("\u00d7");
        times.getStyleClass().add("settings-row-label");
        Label widthLabel = new Label("Width");
        widthLabel.getStyleClass().add("settings-hint");
        Label heightLabel = new Label("Height");
        heightLabel.getStyleClass().add("settings-hint");
        HBox customRow = new HBox(8, widthLabel, customWidth, times, customHeight, heightLabel);
        customRow.setAlignment(Pos.CENTER_RIGHT);
        Runnable showCustom = () -> {
            boolean custom = settings.renderResolutionProperty().get() == AppSettings.RenderResolution.CUSTOM;
            customRow.setVisible(custom);
            customRow.setManaged(custom);
        };
        settings.renderResolutionProperty().addListener((obs, old, val) -> showCustom.run());
        showCustom.run();
        Label resHint = new Label("The size the 3D model preview is rendered at before it's scaled to fit the viewport. "
            + "Custom takes horizontal \u00d7 vertical pixels (" + AppSettings.MIN_RENDER + "\u2013" + AppSettings.MAX_RENDER + ").");
        resHint.getStyleClass().add("settings-hint");
        resHint.setWrapText(true);

        Label skyboxLabel = new Label("Model Preview Skybox");
        skyboxLabel.getStyleClass().add("settings-row-label");
        TextField skyboxField = new TextField();
        skyboxField.getStyleClass().add("settings-textfield");
        skyboxField.textProperty().bindBidirectional(settings.customSkyboxPathProperty());
        skyboxField.setPromptText("Default");
        skyboxField.setPrefWidth(280);
        Button skyboxBrowse = new Button("Browse\u2026");
        skyboxBrowse.getStyleClass().add("settings-secondary-button");
        skyboxBrowse.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select a skybox image");
            chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.hdr"));
            File chosen = chooser.showOpenDialog(ownerWindow);
            if (chosen != null) {
                settings.customSkyboxPathProperty().set(chosen.getAbsolutePath());
            }
        });
        Label skyboxHint = new Label("An image wrapped around the model preview (equirectangular works best).");
        skyboxHint.getStyleClass().add("settings-hint");

        CheckBox visorBox = new CheckBox("Transparent visor");
        visorBox.getStyleClass().add("settings-checkbox");
        visorBox.selectedProperty().bindBidirectional(settings.transparentVisorProperty());
        Label visorHint = new Label("Draws see-through model parts (Samus's visor, glass) transparent. "
            + "Off matches the game, where the visor is opaque.");
        visorHint.getStyleClass().add("settings-hint");
        visorHint.setWrapText(true);

        CheckBox hideVisorBox = new CheckBox("Hide visors");
        hideVisorBox.getStyleClass().add("settings-checkbox");
        hideVisorBox.selectedProperty().bindBidirectional(settings.hideVisorsProperty());
        visorBox.disableProperty().bind(settings.hideVisorsProperty());
        Label hideVisorHint = new Label("Leaves see-through model parts (Samus's visor, glass) out entirely -- shows the face underneath.");
        hideVisorHint.getStyleClass().add("settings-hint");
        hideVisorHint.setWrapText(true);

        Label frostLabel = new Label("Arm Cannon Frost");
        frostLabel.getStyleClass().add("settings-row-label");
        Slider frostSlider = new Slider(0, 100, settings.cannonFrostProperty().get());
        frostSlider.getStyleClass().add("settings-slider");
        frostSlider.setPrefWidth(260);
        Label frostValue = new Label(settings.cannonFrostProperty().get() + "%");
        frostValue.getStyleClass().add("settings-row-label");
        frostValue.setMinWidth(48);
        frostSlider.valueProperty().addListener((obs, old, val) -> frostValue.setText(Math.round(val.doubleValue()) + "%"));
        // Applied on release: every change re-decodes the cannon's textures.
        frostSlider.valueChangingProperty().addListener((obs, was, is) -> {
            if (!is) {
                settings.cannonFrostProperty().set((int) Math.round(frostSlider.getValue()));
            }
        });
        frostSlider.setOnMouseReleased(e -> settings.cannonFrostProperty().set((int) Math.round(frostSlider.getValue())));
        HBox frostBox = new HBox(10, frostSlider, frostValue);
        frostBox.setAlignment(Pos.CENTER_RIGHT);
        Label frostHint = new Label("The first-person arm cannons carry an ice shell (snow and icicles) the game shows in cold "
            + "areas. 0% hides it.");
        frostHint.getStyleClass().add("settings-hint");
        frostHint.setWrapText(true);

        CheckBox floorBox = new CheckBox("Show floor");
        floorBox.getStyleClass().add("settings-checkbox");
        floorBox.selectedProperty().bindBidirectional(settings.showFloorProperty());
        Label floorHint = new Label("A see-through grid under models at their feet -- shows when an animation moves the character.");
        floorHint.getStyleClass().add("settings-hint");
        floorHint.setWrapText(true);

        CheckBox autoplayAnimBox = new CheckBox("Autoplay animations");
        autoplayAnimBox.getStyleClass().add("settings-checkbox");
        autoplayAnimBox.selectedProperty().bindBidirectional(settings.autoplayAnimationsProperty());
        CheckBox nextAnimBox = new CheckBox("Play the next animation when one finishes");
        nextAnimBox.getStyleClass().add("settings-checkbox");
        nextAnimBox.selectedProperty().bindBidirectional(settings.autoplayNextAnimationProperty());

        Label fpsLabel = new Label("Animation Speed");
        fpsLabel.getStyleClass().add("settings-row-label");
        Slider fpsSlider = new Slider(1, 120, settings.animationFpsProperty().get());
        fpsSlider.getStyleClass().add("settings-slider");
        fpsSlider.setPrefWidth(260);
        fpsSlider.setBlockIncrement(1);
        fpsSlider.setMajorTickUnit(1);
        fpsSlider.setMinorTickCount(0);
        fpsSlider.setSnapToTicks(true);
        Label fpsValue = new Label();
        fpsValue.getStyleClass().add("settings-row-label");
        fpsValue.setMinWidth(60);
        fpsSlider.valueProperty().addListener((obs, old, val) -> {
            int fps = (int) Math.round(val.doubleValue());
            settings.animationFpsProperty().set(fps);
            fpsValue.setText(fps + " fps");
        });
        settings.animationFpsProperty().addListener((obs, old, val) -> fpsSlider.setValue(val.intValue()));
        fpsValue.setText(settings.animationFpsProperty().get() + " fps");
        HBox fpsBox = new HBox(10, fpsSlider, fpsValue);
        fpsBox.setAlignment(Pos.CENTER_RIGHT);
        Label fpsHint = new Label("Frames per second animation clips play at (default 60, the game's rate). "
            + "Change it to slow animations down or speed them up.");
        fpsHint.getStyleClass().add("settings-hint");
        fpsHint.setWrapText(true);

        return sectionBox(
            settingRow(resLabel, resBox),
            customRow,
            resHint,
            settingRow(skyboxLabel, new HBox(8, skyboxField, skyboxBrowse)),
            skyboxHint,
            settingRow(visorBox, null), visorHint,
            settingRow(hideVisorBox, null), hideVisorHint,
            settingRow(frostLabel, frostBox), frostHint,
            settingRow(floorBox, null), floorHint,
            settingRow(autoplayAnimBox, null), settingRow(nextAnimBox, null),
            settingRow(fpsLabel, fpsBox), fpsHint
        );
    }

    /** A digits-only field bound to an int setting (applied on Enter / focus loss, clamped). */
    private TextField numberField(javafx.beans.property.IntegerProperty property) {
        TextField field = new TextField(String.valueOf(property.get()));
        field.getStyleClass().add("settings-textfield");
        field.setPrefWidth(80);
        field.setTextFormatter(new javafx.scene.control.TextFormatter<String>(change ->
            change.getControlNewText().matches("\\d{0,4}") ? change : null));
        Runnable commit = () -> {
            String text = field.getText();
            int value = text == null || text.isEmpty() ? property.get() : Integer.parseInt(text);
            value = Math.max(AppSettings.MIN_RENDER, Math.min(AppSettings.MAX_RENDER, value));
            property.set(value);
            field.setText(String.valueOf(value));
        };
        field.setOnAction(e -> commit.run());
        field.focusedProperty().addListener((obs, was, is) -> {
            if (!is) {
                commit.run();
            }
        });
        return field;
    }

    private VBox sectionBox(Region... rows) {
        return new VBox(16, rows);
    }

    private HBox settingRow(Region left, Region right) {
        HBox row = new HBox(20, left);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        if (right != null) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            row.getChildren().addAll(spacer, right);
        }
        return row;
    }

    private HBox buildFooter() {
        Button restore = new Button("Restore All Defaults");
        restore.getStyleClass().add("restore-defaults-button");
        restore.setOnAction(e -> {
            SoundManager.get().play(SoundManager.Cue.SELECT);
            AppSettings.get().restoreDefaults();
        });
        Label legal = new Label("Unofficial fan project · all game assets © Nintendo · see About");
        legal.getStyleClass().add("settings-hint");
        legal.setMinWidth(0);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(12, legal, spacer, restore);
        footer.getStyleClass().add("settings-footer");
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(10, 28, 16, 28));
        return footer;
    }
}
