package com.mocktroid.ui;

import java.io.File;

import com.mocktroid.audio.SoundManager;
import com.mocktroid.settings.AppSettings;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

/**
 * First-run wizard: romfs path (required) -> thumbnail cache prompt -> list
 * or grid preference. Shown by {@code MocktroidApp} instead of the main
 * shell when {@code AppSettings.onboardingComplete} is false; calls
 * {@link #onComplete} once the user finishes step 3.
 */
public final class OnboardingWizard extends StackPane {

    private final Window ownerWindow;
    private final Runnable onComplete;
    private final VBox stepHost = new VBox();
    private int stepIndex = 0;
    private String chosenRomfsPath;

    public OnboardingWizard(Window ownerWindow, Runnable onComplete) {
        this.ownerWindow = ownerWindow;
        this.onComplete = onComplete;
        getStyleClass().add("onboarding-wizard");

        VBox card = new VBox(24);
        card.getStyleClass().add("onboarding-card");
        card.setAlignment(Pos.TOP_CENTER);
        card.setPadding(new Insets(36));
        card.setMaxWidth(560);

        Label title = new Label("WELCOME TO MOCKTROID");
        title.getStyleClass().add("onboarding-title");

        card.getChildren().addAll(title, stepHost);
        getChildren().add(card);

        showStep(0);
    }

    private void showStep(int index) {
        stepIndex = index;
        stepHost.getChildren().setAll(switch (index) {
            case 0 -> buildRomfsStep();
            case 1 -> buildThumbnailCacheStep();
            default -> buildBrowseModeStep();
        });
    }

    private VBox buildRomfsStep() {
        Label desc = new Label("Point Mocktroid at your Metroid Prime 4: Beyond romfs dump. This is required -- "
            + "nothing can be browsed until a valid folder is set.");
        desc.getStyleClass().add("onboarding-desc");
        desc.setWrapText(true);

        TextField pathField = new TextField(chosenRomfsPath != null ? chosenRomfsPath : "");
        pathField.getStyleClass().add("settings-textfield");
        pathField.setPromptText("/path/to/romfs");
        pathField.setPrefWidth(380);

        Button browse = new Button("Browse\u2026");
        browse.getStyleClass().add("settings-secondary-button");

        Label error = new Label();
        error.getStyleClass().add("onboarding-error");
        error.setVisible(false);

        Button next = new Button("Next \u2192");
        next.getStyleClass().add("onboarding-primary-button");
        next.setDisable(pathField.getText().isBlank());

        pathField.textProperty().addListener((obs, old, val) -> next.setDisable(val.isBlank()));

        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select the Metroid Prime 4 romfs folder");
            File chosen = chooser.showDialog(ownerWindow);
            if (chosen != null) {
                pathField.setText(chosen.getAbsolutePath());
            }
        });

        next.setOnAction(e -> {
            File dir = new File(pathField.getText());
            if (!dir.isDirectory()) {
                error.setText("That path doesn't exist or isn't a folder.");
                error.setVisible(true);
                return;
            }
            error.setVisible(false);
            chosenRomfsPath = dir.getAbsolutePath();
            SoundManager.get().play(SoundManager.Cue.SELECT);
            showStep(1);
        });

        HBox pathRow = new HBox(8, pathField, browse);
        HBox nav = navRow(null, next);
        VBox box = new VBox(16, desc, pathRow, error, nav);
        box.setAlignment(Pos.TOP_CENTER);
        return box;
    }

    private VBox buildThumbnailCacheStep() {
        Label desc = new Label("Build a model thumbnail cache now? This renders a preview image for every "
            + "model up front so the grid view has real thumbnails instead of placeholders -- it can take a "
            + "long time on a full romfs dump.");
        desc.getStyleClass().add("onboarding-desc");
        desc.setWrapText(true);

        Label note = new Label("(Thumbnail generation isn't implemented yet -- this just records your "
            + "preference for when it is.)");
        note.getStyleClass().add("onboarding-note");
        note.setWrapText(true);

        Button yes = new Button("Yes, build it");
        Button later = new Button("Later");
        Button no = new Button("No");
        for (Button b : new Button[]{yes, later, no}) {
            b.getStyleClass().add("onboarding-choice-button");
        }

        Runnable[] advance = new Runnable[1];
        advance[0] = () -> {
            SoundManager.get().play(SoundManager.Cue.SELECT);
            showStep(2);
        };
        yes.setOnAction(e -> { AppSettings.get().thumbnailCacheBuiltProperty().set(false); advance[0].run(); });
        later.setOnAction(e -> advance[0].run());
        no.setOnAction(e -> advance[0].run());

        HBox choices = new HBox(12, yes, later, no);
        choices.setAlignment(Pos.CENTER);

        HBox nav = navRow(backButton(0), null);
        VBox box = new VBox(16, desc, note, choices, nav);
        box.setAlignment(Pos.TOP_CENTER);
        return box;
    }

    private VBox buildBrowseModeStep() {
        Label desc = new Label("How would you like to browse assets by default? You can change this anytime "
            + "in Settings.");
        desc.getStyleClass().add("onboarding-desc");
        desc.setWrapText(true);

        Button listChoice = new Button("List");
        Button gridChoice = new Button("Grid");
        listChoice.getStyleClass().add("onboarding-choice-button");
        gridChoice.getStyleClass().add("onboarding-choice-button");

        listChoice.setOnAction(e -> finish(AppSettings.BrowseMode.LIST));
        gridChoice.setOnAction(e -> finish(AppSettings.BrowseMode.GRID));

        HBox choices = new HBox(12, listChoice, gridChoice);
        choices.setAlignment(Pos.CENTER);

        HBox nav = navRow(backButton(1), null);
        VBox box = new VBox(16, desc, choices, nav);
        box.setAlignment(Pos.TOP_CENTER);
        return box;
    }

    private void finish(AppSettings.BrowseMode mode) {
        AppSettings settings = AppSettings.get();
        settings.romfsPathProperty().set(chosenRomfsPath);
        settings.browseModeProperty().set(mode);
        settings.onboardingCompleteProperty().set(true);
        SoundManager.get().play(SoundManager.Cue.SELECT);
        onComplete.run();
    }

    private Button backButton(int targetStep) {
        Button back = new Button("\u2190 Back");
        back.getStyleClass().add("onboarding-secondary-button");
        back.setOnAction(e -> showStep(targetStep));
        return back;
    }

    private HBox navRow(Button left, Button right) {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER_LEFT);
        if (left != null) {
            row.getChildren().add(left);
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().add(spacer);
        if (right != null) {
            row.getChildren().add(right);
        }
        return row;
    }
}
