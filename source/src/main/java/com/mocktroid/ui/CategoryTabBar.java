package com.mocktroid.ui;

import java.util.List;
import java.util.function.Consumer;

import com.mocktroid.audio.SoundManager;
import com.mocktroid.model.AssetType;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * The category tab row: every non-selected asset-type tab sits in a cluster
 * on the left, the currently selected one sits alone on the right (larger,
 * highlighted), with the settings tab (visor icon + tiny "settings" label)
 * locked to the exact horizontal center between them.
 * <p>
 * Centering is done by binding the left and right regions to always be the
 * same width (each {@code (barWidth - settingsWidth) / 2}), with their tabs
 * anchored toward the settings tab (right-aligned on the left, left-aligned
 * on the right) -- rather than just centering a tightly-packed cluster,
 * which would drift off-center whenever the left group's tabs outweigh the
 * single right-side tab. The leftover space in each region (and the region
 * itself, when clicking outside a tab) doubles as the window drag handle.
 * <p>
 * Clicking a tab in the left cluster promotes it to the right slot and
 * demotes whatever was there back into the left cluster.
 */
public final class CategoryTabBar extends HBox {

    private static final List<AssetType> ORDER =
        List.of(AssetType.VIDEO, AssetType.TEXTURE, AssetType.ANIMATED_TEXTURE, AssetType.SOUND,
                AssetType.MODEL, AssetType.ROOM);

    private final Consumer<AssetType> onSelectType;
    private final HBox leftRegion = new HBox(8);
    private final HBox rightRegion = new HBox(8);
    private final StackPane settingsIcon = new StackPane();
    private AssetType selectedType;
    private boolean settingsActive = false;

    public CategoryTabBar(Stage stage, Consumer<AssetType> onSelectType, Runnable onOpenSettings) {
        this.onSelectType = onSelectType;
        getStyleClass().add("tab-bar");
        setSpacing(0);
        setPadding(new Insets(0, 16, 10, 16));

        leftRegion.setAlignment(Pos.CENTER_RIGHT);
        rightRegion.setAlignment(Pos.CENTER_LEFT);
        installDrag(leftRegion, stage);
        installDrag(rightRegion, stage);

        AngledPanel settingsTab = buildSettingsTab(onOpenSettings);

        getChildren().addAll(leftRegion, settingsTab, rightRegion);

        // Lock settingsTab to the true center: both flanking regions are
        // always forced to the same width, regardless of how much wider the
        // left tab cluster is than the single right-side tab.
        leftRegion.prefWidthProperty().bind(Bindings.createDoubleBinding(
            () -> Math.max(0, (widthProperty().get() - getPadding().getLeft() - getPadding().getRight()
                - settingsTab.getWidth()) / 2.0),
            widthProperty(), settingsTab.widthProperty()));
        rightRegion.prefWidthProperty().bind(leftRegion.prefWidthProperty());
    }

    private void installDrag(Region region, Stage stage) {
        WindowChromeBar.attach(region, stage);
    }

    /** Sets which category is active, rebuilding the left cluster / right
     *  slot around it. Safe to call for the initial selection too. */
    public void setActiveType(AssetType type) {
        this.selectedType = type;
        rebuild();
    }

    private void rebuild() {
        leftRegion.getChildren().clear();
        for (AssetType type : ORDER) {
            if (type != selectedType) {
                leftRegion.getChildren().add(buildTab(type, false));
            }
        }
        // "All" stays pinned just right of the settings tab; the selected category follows it.
        rightRegion.getChildren().setAll(buildTab(AssetType.ALL, selectedType == AssetType.ALL));
        if (selectedType != AssetType.ALL) {
            rightRegion.getChildren().add(buildTab(selectedType, true));
        }
    }

    private AngledPanel buildTab(AssetType type, boolean active) {
        AngledPanel tab = new AngledPanel(10);
        tab.getStyleClass().add("category-tab");
        if (active) {
            tab.getStyleClass().add("active");
        }
        Label label = new Label(type.displayName);
        label.getStyleClass().add("category-tab-label");
        label.setMinWidth(Region.USE_PREF_SIZE);
        HBox content = new HBox(label);
        content.setPadding(new Insets(8, 16, 8, 16));
        tab.getChildren().add(content);

        tab.setOnMouseEntered(e -> SoundManager.get().play(SoundManager.Cue.HOVER));
        tab.setOnMouseClicked(e -> {
            SoundManager.get().play(SoundManager.Cue.SELECT);
            setActiveType(type);
            onSelectType.accept(type);
        });
        return tab;
    }

    private AngledPanel buildSettingsTab(Runnable onOpenSettings) {
        AngledPanel tab = new AngledPanel(10);
        tab.getStyleClass().add("settings-tab");
        // Protect against the centering binding below squeezing this tab: its
        // own width feeds that binding, and if a transient layout pass ever
        // measures it slightly low, HBox will happily shrink a Label (which
        // supports ellipsis truncation) to make the numbers fit -- which is
        // exactly what produced the "settin..." truncation. Locking min width
        // to preferred width makes this tab (and its label) non-shrinkable.
        tab.setMinWidth(Region.USE_PREF_SIZE);

        settingsIcon.getChildren().setAll(GameIcons.visor(24));
        Label label = new Label("settings");
        label.getStyleClass().add("settings-tab-label");
        label.setMinWidth(Region.USE_PREF_SIZE);
        VBox content = new VBox(2, settingsIcon, label);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(6, 16, 6, 16));
        tab.getChildren().add(content);

        tab.setOnMouseEntered(e -> {
            SoundManager.get().play(SoundManager.Cue.HOVER);
            settingsIcon.getChildren().setAll(GameIcons.visorFilled(24));
        });
        tab.setOnMouseExited(e -> {
            if (!settingsActive) {
                settingsIcon.getChildren().setAll(GameIcons.visor(24));
            }
        });
        tab.setOnMouseClicked(e -> {
            SoundManager.get().play(SoundManager.Cue.SELECT);
            onOpenSettings.run();
        });
        return tab;
    }

    /** Called by RootLayout when the settings screen opens/closes, so the
     *  visor icon reflects whether Settings is the active view. */
    public void setSettingsActive(boolean active) {
        this.settingsActive = active;
        settingsIcon.getChildren().setAll(active ? GameIcons.visorFilled(24) : GameIcons.visor(24));
    }
}
