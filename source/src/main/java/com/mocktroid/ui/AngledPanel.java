package com.mocktroid.ui;

import javafx.scene.layout.StackPane;
import javafx.scene.shape.Polygon;

/**
 * A panel with angled-cut corners, matching the beveled category headers,
 * cards, and buttons throughout the reference UI (see
 * {@code Mocktroid/reference/mp4LogbookMenu.JPG} etc.).
 * <p>
 * The cut is a reactive clip {@link Polygon} bound to the panel's own
 * width/height, so it stays correct as content resizes it -- a fixed CSS
 * {@code -fx-shape} path can't do that for variable-width elements like a
 * category header whose label length varies.
 * <p>
 * Styling (background color, border, glow) is left entirely to CSS via the
 * {@code angled-panel} style class plus whatever extra classes the caller
 * adds, so one component serves headers, cards, and buttons alike.
 */
public class AngledPanel extends StackPane {

    private final double cut;

    public AngledPanel(double cutSize) {
        this.cut = cutSize;
        getStyleClass().add("angled-panel");

        Polygon clipShape = new Polygon();
        widthProperty().addListener((obs, oldV, newV) -> updateClip(clipShape));
        heightProperty().addListener((obs, oldV, newV) -> updateClip(clipShape));
        setClip(clipShape);
    }

    private void updateClip(Polygon poly) {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        double c = Math.min(cut, Math.min(w, h) / 2);
        // Cut the top-left and bottom-right corners -- the signature angled-pill
        // silhouette seen on the reference logbook/settings category headers.
        poly.getPoints().setAll(
            c, 0.0,
            w, 0.0,
            w, h - c,
            w - c, h,
            0.0, h,
            0.0, c
        );
    }
}
