package com.mocktroid.ui;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

/**
 * Small factory for the handful of unique-content raster assets that stay
 * as real art rather than CSS (everything else -- panels, tabs, buttons,
 * backgrounds, scrollbars -- is CSS-drawn; see roadmap.md for the reasoning).
 */
public final class GameIcons {

    private GameIcons() {}

    private static Image visor;
    private static Image visorFilled;
    private static Image screwAttackS;
    private static Image infiniteLogo;

    public static ImageView visor(double size) {
        if (visor == null) {
            visor = new Image(GameIcons.class.getResourceAsStream("/icons/visor.png"));
        }
        return sized(visor, size);
    }

    public static ImageView visorFilled(double size) {
        if (visorFilled == null) {
            visorFilled = new Image(GameIcons.class.getResourceAsStream("/icons/visor-filled.png"));
        }
        return sized(visorFilled, size);
    }

    /** The universal "nothing to show" glyph: empty preview viewport, and an
     *  uncached grid thumbnail before its render exists (or before the
     *  loading spinner starts, for very quick lookups). */
    public static ImageView screwAttackPlaceholder(double size) {
        if (screwAttackS == null) {
            screwAttackS = new Image(GameIcons.class.getResourceAsStream("/icons/screw-attack-s.png"));
        }
        return sized(screwAttackS, size);
    }

    public static ImageView infiniteLogo(double size) {
        if (infiniteLogo == null) {
            infiniteLogo = new Image(GameIcons.class.getResourceAsStream("/icons/infinite-logo.png"));
        }
        return sized(infiniteLogo, size);
    }

    private static ImageView sized(Image image, double size) {
        ImageView view = new ImageView(image);
        view.setFitWidth(size);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        return view;
    }
}
