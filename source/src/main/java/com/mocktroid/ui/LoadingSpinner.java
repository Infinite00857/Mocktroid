package com.mocktroid.ui;

import javafx.animation.Interpolator;
import javafx.animation.RotateTransition;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.util.Duration;

/**
 * The spinning morph-ball loading indicator ({@code mball-loading.png}),
 * shown wherever content is actively being generated -- e.g. a grid
 * thumbnail that's being rendered for the cache. Spins clockwise,
 * continuously, for as long as the Node is part of the scene.
 */
public final class LoadingSpinner extends ImageView {

    private static Image sharedImage;

    public LoadingSpinner(double size) {
        super(loadImage());
        setFitWidth(size);
        setFitHeight(size);
        setPreserveRatio(true);
        setSmooth(true);

        RotateTransition spin = new RotateTransition(Duration.seconds(1.4), this);
        spin.setByAngle(360); // positive = clockwise in JavaFX's 2D screen rotation
        spin.setCycleCount(RotateTransition.INDEFINITE);
        spin.setInterpolator(Interpolator.LINEAR);
        // A running Animation is held by JavaFX's master timer, so an
        // unconditional play() kept every spinner ever created (one per
        // loading grid thumbnail) ticking forever after it left the scene.
        sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) {
                spin.play();
            } else {
                spin.stop();
            }
        });
    }

    private static Image loadImage() {
        if (sharedImage == null) {
            sharedImage = new Image(LoadingSpinner.class.getResourceAsStream("/icons/mball-loading.png"));
        }
        return sharedImage;
    }
}
