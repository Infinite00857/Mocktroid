package com.mocktroid.ui;

import javafx.scene.Cursor;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Adds click-and-drag edge/corner resizing to an undecorated {@link Stage}.
 * JavaFX gives undecorated stages a draggable-move story for free (regular
 * mouse-drag handlers on any node) but no window-edge resize at all --
 * this installs eight thin invisible grips (4 edges + 4 corners, corners on
 * top so diagonal dragging near a corner takes priority) onto the scene's
 * root StackPane.
 */
public final class WindowResizer {

    private static final double EDGE_THICKNESS = 6;
    private static final double CORNER_SIZE = 12;

    private WindowResizer() {}

    public static void install(StackPane root, Stage stage) {
        root.getChildren().addAll(
            edgeGrip(stage, Cursor.N_RESIZE, false, false, true, false),
            edgeGrip(stage, Cursor.S_RESIZE, false, false, false, true),
            edgeGrip(stage, Cursor.W_RESIZE, true, false, false, false),
            edgeGrip(stage, Cursor.E_RESIZE, false, true, false, false)
        );
        root.getChildren().addAll(
            cornerGrip(stage, Cursor.NW_RESIZE, javafx.geometry.Pos.TOP_LEFT, true, false, true, false),
            cornerGrip(stage, Cursor.NE_RESIZE, javafx.geometry.Pos.TOP_RIGHT, false, true, true, false),
            cornerGrip(stage, Cursor.SW_RESIZE, javafx.geometry.Pos.BOTTOM_LEFT, true, false, false, true),
            cornerGrip(stage, Cursor.SE_RESIZE, javafx.geometry.Pos.BOTTOM_RIGHT, false, true, false, true)
        );
    }

    private static Region edgeGrip(Stage stage, Cursor cursor, boolean left, boolean right, boolean top, boolean bottom) {
        Region grip = new Region();
        grip.setCursor(cursor);
        grip.setMouseTransparent(false);
        grip.setPickOnBounds(true);

        boolean vertical = top || bottom; // top/bottom edges span full width, thin height
        if (vertical) {
            grip.setMaxWidth(Double.MAX_VALUE);
            grip.setPrefHeight(EDGE_THICKNESS);
            grip.setMaxHeight(EDGE_THICKNESS);
            StackPane.setAlignment(grip, top ? javafx.geometry.Pos.TOP_CENTER : javafx.geometry.Pos.BOTTOM_CENTER);
        } else {
            grip.setMaxHeight(Double.MAX_VALUE);
            grip.setPrefWidth(EDGE_THICKNESS);
            grip.setMaxWidth(EDGE_THICKNESS);
            StackPane.setAlignment(grip, left ? javafx.geometry.Pos.CENTER_LEFT : javafx.geometry.Pos.CENTER_RIGHT);
        }
        installResizeDrag(grip, stage, left, right, top, bottom);
        return grip;
    }

    private static Region cornerGrip(Stage stage, Cursor cursor, javafx.geometry.Pos pos,
                                      boolean left, boolean right, boolean top, boolean bottom) {
        Region grip = new Region();
        grip.setCursor(cursor);
        grip.setPrefSize(CORNER_SIZE, CORNER_SIZE);
        grip.setMaxSize(CORNER_SIZE, CORNER_SIZE);
        StackPane.setAlignment(grip, pos);
        installResizeDrag(grip, stage, left, right, top, bottom);
        return grip;
    }

    private static void installResizeDrag(Region grip, Stage stage, boolean left, boolean right, boolean top, boolean bottom) {
        double[] lastScreenPos = new double[2];
        grip.setOnMousePressed(e -> {
            lastScreenPos[0] = e.getScreenX();
            lastScreenPos[1] = e.getScreenY();
            e.consume();
        });
        grip.setOnMouseDragged(e -> {
            double dx = e.getScreenX() - lastScreenPos[0];
            double dy = e.getScreenY() - lastScreenPos[1];
            lastScreenPos[0] = e.getScreenX();
            lastScreenPos[1] = e.getScreenY();

            if (right) {
                stage.setWidth(Math.max(stage.getMinWidth(), stage.getWidth() + dx));
            }
            if (left) {
                double newWidth = Math.max(stage.getMinWidth(), stage.getWidth() - dx);
                double actualDelta = stage.getWidth() - newWidth;
                stage.setX(stage.getX() + actualDelta);
                stage.setWidth(newWidth);
            }
            if (bottom) {
                stage.setHeight(Math.max(stage.getMinHeight(), stage.getHeight() + dy));
            }
            if (top) {
                double newHeight = Math.max(stage.getMinHeight(), stage.getHeight() - dy);
                double actualDelta = stage.getHeight() - newHeight;
                stage.setY(stage.getY() + actualDelta);
                stage.setHeight(newHeight);
            }
            e.consume();
        });
    }
}
