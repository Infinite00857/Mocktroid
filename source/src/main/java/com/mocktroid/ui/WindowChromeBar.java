package com.mocktroid.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * The always-visible top strip: window drag handle plus custom
 * minimize/maximize/close buttons (no app name/logo -- explicitly asked to
 * be dropped, and the reference mockups don't show one either). Kept
 * separate from {@link CategoryTabBar} so these controls stay reachable
 * even while Settings covers the rest of the window.
 * <p>
 * The whole bar is a drag handle, since the app runs in an undecorated
 * {@link Stage} with no native title bar.
 * <p>
 * Maximize is implemented manually (tracked bounds + resize to the current
 * screen's visible area) rather than via {@code Stage.setMaximized()},
 * which is unreliable on undecorated stages -- on macOS in particular it's
 * known to reposition the window without actually resizing it.
 */
public final class WindowChromeBar extends HBox {

    public WindowChromeBar(Stage stage) {
        getStyleClass().add("chrome-bar");
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(6, 10, 6, 14));

        Region dragRegion = new Region();
        HBox.setHgrow(dragRegion, Priority.ALWAYS);
        installDrag(dragRegion, stage);

        rememberBounds(stage);
        Button minimize = windowButton("\u2013", "window-button-minimize", () -> stage.setIconified(true));
        Button maximize = windowButton("\u25A1", "window-button-maximize", () -> toggleMaximize(stage));
        Button close = windowButton("\u2715", "window-button-close", () -> {
            UiSounds.get().play(UiSounds.Cue.CLOSE);
            // Let the close sound start before the window goes.
            javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(javafx.util.Duration.millis(
                com.mocktroid.settings.AppSettings.get().useGameUiSoundsProperty().get() ? 350 : 1));
            wait.setOnFinished(e -> stage.close());
            wait.play();
        });

        HBox controls = new HBox(6, minimize, maximize, close);
        controls.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(dragRegion, controls);
    }

    /** Maximized or snapped to half the screen; the restore bounds are the size before that. */
    private boolean maximized = false;
    private boolean snapped = false;
    private double restoreX, restoreY, restoreWidth, restoreHeight;

    private void rememberBounds(Stage stage) {
        if (!maximized && !snapped) {
            restoreX = stage.getX();
            restoreY = stage.getY();
            restoreWidth = stage.getWidth();
            restoreHeight = stage.getHeight();
        }
    }

    private void toggleMaximize(Stage stage) {
        if (maximized || snapped) {
            stage.setX(restoreX);
            stage.setY(restoreY);
            stage.setWidth(restoreWidth);
            stage.setHeight(restoreHeight);
            maximized = false;
            snapped = false;
        } else {
            maximize(stage, screenFor(stage));
        }
    }

    private void maximize(Stage stage, Screen screen) {
        rememberBounds(stage);
        Rectangle2D visualBounds = screen.getVisualBounds();
        stage.setX(visualBounds.getMinX());
        stage.setY(visualBounds.getMinY());
        stage.setWidth(visualBounds.getWidth());
        stage.setHeight(visualBounds.getHeight());
        maximized = true;
        snapped = false;
    }

    private void snapHalf(Stage stage, Screen screen, boolean left) {
        rememberBounds(stage);
        Rectangle2D b = screen.getVisualBounds();
        stage.setX(left ? b.getMinX() : b.getMinX() + b.getWidth() / 2);
        stage.setY(b.getMinY());
        stage.setWidth(b.getWidth() / 2);
        stage.setHeight(b.getHeight());
        snapped = true;
        maximized = false;
    }

    private Screen screenFor(Stage stage) {
        var screens = Screen.getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        return screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
    }

    private static Screen screenAt(double x, double y) {
        var screens = Screen.getScreensForRectangle(x, y, 1, 1);
        return screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
    }

    /*
     * Drag moves the window; double-click maximizes / restores. Dragging a maximized or snapped
     * window restores its size under the cursor. Releasing at the screen's top edge maximizes,
     * at the left / right edge snaps to that half (the undecorated window can't use Windows' own
     * snapping, so this mimics it).
     */
    private final double[] dragDelta = new double[2];
    private boolean moved;
    private Stage stage;

    /** Makes {@code region} a window drag handle with the title bar's behaviour (also used by the tab bar). */
    public static void attach(Region region, Stage stage) {
        region.setOnMousePressed(e -> of(stage).pressed(e));
        region.setOnMouseDragged(e -> of(stage).dragged(e));
        region.setOnMouseReleased(e -> of(stage).released(e));
        region.setOnMouseClicked(e -> of(stage).clicked(e));
    }

    private static WindowChromeBar of(Stage stage) {
        return (WindowChromeBar) stage.getProperties().get(WindowChromeBar.class);
    }

    private void installDrag(Region region, Stage stage) {
        this.stage = stage;
        stage.getProperties().put(WindowChromeBar.class, this);
        attach(region, stage);
    }

    private void pressed(javafx.scene.input.MouseEvent e) {
        dragDelta[0] = stage.getX() - e.getScreenX();
        dragDelta[1] = stage.getY() - e.getScreenY();
        moved = false;
    }

    private void dragged(javafx.scene.input.MouseEvent e) {
        if (!moved && (maximized || snapped)) {
            // Restore, keeping the cursor at the same relative spot of the bar.
            double fraction = (e.getScreenX() - stage.getX()) / stage.getWidth();
            stage.setWidth(restoreWidth);
            stage.setHeight(restoreHeight);
            maximized = false;
            snapped = false;
            dragDelta[0] = -fraction * restoreWidth;
        }
        moved = true;
        stage.setX(e.getScreenX() + dragDelta[0]);
        stage.setY(e.getScreenY() + dragDelta[1]);
    }

    private void released(javafx.scene.input.MouseEvent e) {
        if (!moved) {
            return;
        }
        Screen screen = screenAt(e.getScreenX(), e.getScreenY());
        Rectangle2D b = screen.getBounds();
        double edge = 6;
        if (e.getScreenY() <= b.getMinY() + edge) {
            maximize(stage, screen);
        } else if (e.getScreenX() <= b.getMinX() + edge) {
            snapHalf(stage, screen, true);
        } else if (e.getScreenX() >= b.getMaxX() - edge) {
            snapHalf(stage, screen, false);
        } else if (stage.getY() < screen.getVisualBounds().getMinY()) {
            stage.setY(screen.getVisualBounds().getMinY()); // keep the title bar reachable
        }
    }

    private void clicked(javafx.scene.input.MouseEvent e) {
        if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.getClickCount() == 2) {
            toggleMaximize(stage);
        }
    }

    private Button windowButton(String glyph, String styleClass, Runnable action) {
        Button button = new Button(glyph);
        button.getStyleClass().addAll("window-button", styleClass);
        button.setOnAction(e -> action.run());
        return button;
    }
}
