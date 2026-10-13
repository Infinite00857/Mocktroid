package com.mocktroid;

import java.nio.file.Path;

import com.mocktroid.library.RomfsLibrary;
import com.mocktroid.model.AssetLibrary;
import com.mocktroid.settings.AppSettings;
import com.mocktroid.ui.LoadingSpinner;
import com.mocktroid.ui.OnboardingWizard;
import com.mocktroid.ui.RootLayout;
import com.mocktroid.ui.WindowResizer;

import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Application entry point. Runs undecorated (custom window chrome; see
 * {@code WindowChromeBar}/{@code WindowResizer}). Shows the first-run
 * {@link OnboardingWizard} if {@code AppSettings.onboardingComplete} is
 * false; otherwise scans the configured romfs path (background thread --
 * a full romfs is hundreds of paks, cataloging it is not instant) and shows
 * {@link RootLayout} once that finishes.
 */
public final class MocktroidApp extends Application {

    private static final double DEFAULT_WIDTH = 1360;
    private static final double DEFAULT_HEIGHT = 840;

    @Override
    public void start(Stage stage) {
        stage.initStyle(StageStyle.UNDECORATED);

        // contentSlot holds whichever of {onboarding, loading, shell, error} is
        // active; the resize grips below are added as sceneRoot's *other*
        // children so that swapping contentSlot's child never disturbs them.
        StackPane contentSlot = new StackPane();
        StackPane sceneRoot = new StackPane(contentSlot);

        Scene scene = new Scene(sceneRoot, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.getStylesheets().add(getClass().getResource("/css/mocktroid.css").toExternalForm());

        stage.setTitle("Mocktroid");
        stage.getIcons().add(new Image(getClass().getResourceAsStream("/icons/app-icon.png")));
        stage.setScene(scene);
        stage.setMinWidth(1024);
        stage.setMinHeight(640);

        if (AppSettings.get().onboardingCompleteProperty().get()) {
            loadLibraryAndShowShell(stage, contentSlot);
        } else {
            contentSlot.getChildren().add(new OnboardingWizard(stage,
                () -> loadLibraryAndShowShell(stage, contentSlot)));
        }

        // Installed after the initial content so the thin edge/corner grips
        // sit on top in z-order, and as siblings of contentSlot (not its
        // children) so later swaps of contentSlot's content never disturb them.
        WindowResizer.install(sceneRoot, stage);

        stage.show();
    }

    private void loadLibraryAndShowShell(Stage stage, StackPane contentSlot) {
        Path romfsRoot = Path.of(AppSettings.get().romfsPathProperty().get());
        contentSlot.getChildren().setAll(buildLoadingView());

        Task<AssetLibrary> scanTask = new Task<>() {
            @Override
            protected AssetLibrary call() throws Exception {
                return RomfsLibrary.scan(romfsRoot);
            }
        };
        scanTask.setOnSucceeded(e -> {
            AssetLibrary library = scanTask.getValue();
            contentSlot.getChildren().setAll(new RootLayout(stage, library));
            com.mocktroid.ui.UiSounds.get().init(library);
            com.mocktroid.ui.UiSounds.get().install(stage.getScene());
            startNameIndex(library, romfsRoot);
            com.mocktroid.library.SoundDurations.get().loadInBackground(
                library.category(com.mocktroid.model.AssetType.SOUND).entries());
            com.mocktroid.library.AnimatedTextures.get().loadInBackground(library);
        });
        scanTask.setOnFailed(e -> contentSlot.getChildren().setAll(
            buildErrorView(romfsRoot, scanTask.getException())));

        Thread thread = new Thread(scanTask, "romfs-scan");
        thread.setDaemon(true);
        thread.start();
    }

    /** Derived names (see NameIndex) are worked out in the background -- the
     *  browser is usable immediately and entries get renamed when it finishes. */
    private void startNameIndex(AssetLibrary library, Path romfsRoot) {
        Task<com.mocktroid.library.NameIndex.Result> task = new Task<>() {
            @Override
            protected com.mocktroid.library.NameIndex.Result call() {
                return com.mocktroid.library.NameIndex.loadOrBuild(library, romfsRoot);
            }
        };
        task.setOnSucceeded(e -> library.applyDerivedNames(task.getValue().names(), task.getValue().charactersByModel()));
        Thread thread = new Thread(task, "name-index");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private VBox buildLoadingView() {
        Label label = new Label("Scanning romfs\u2026");
        label.getStyleClass().add("preview-not-ready");
        VBox box = new VBox(18, new LoadingSpinner(64), label);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("onboarding-wizard");
        return box;
    }

    private VBox buildErrorView(Path romfsRoot, Throwable error) {
        Label message = new Label("Couldn't read the romfs at:\n" + romfsRoot
            + "\n\n" + (error != null ? error : "(unknown error)")
            + "\n\nFix the path in Settings and restart Mocktroid.");
        message.getStyleClass().add("onboarding-error");
        message.setWrapText(true);
        message.setMaxWidth(560);
        VBox box = new VBox(message);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("onboarding-wizard");
        return box;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
