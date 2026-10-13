package com.mocktroid.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.util.Duration;

/** Flashes a node's outline red a few times -- "that action isn't available here". */
public final class DenyBlink {

    private static final String STYLE = "deny-blink";
    private static final String RUNNING_KEY = "deny-blink-running";

    private DenyBlink() {}

    public static void play(Node node) {
        UiSounds.get().play(UiSounds.Cue.REFUSE);
        if (node.getProperties().containsKey(RUNNING_KEY)) {
            return;
        }
        node.getProperties().put(RUNNING_KEY, Boolean.TRUE);
        Timeline timeline = new Timeline();
        for (int i = 0; i < 6; i++) {
            boolean on = i % 2 == 0;
            timeline.getKeyFrames().add(new KeyFrame(Duration.millis(i * 110), e -> {
                node.getStyleClass().remove(STYLE);
                if (on) {
                    node.getStyleClass().add(STYLE);
                }
            }));
        }
        timeline.getKeyFrames().add(new KeyFrame(Duration.millis(6 * 110), e -> {
            node.getStyleClass().remove(STYLE);
            node.getProperties().remove(RUNNING_KEY);
        }));
        timeline.play();
    }
}
