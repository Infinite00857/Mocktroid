package com.mocktroid.ui.preview;

import java.util.function.Consumer;
import java.util.function.Supplier;

import com.mocktroid.model.AssetEntry;

/**
 * "Play the next one when this finishes": the finishing preview arms the chain and moves to the
 * next item; the preview built for it takes the flag and starts playing even when plain autoplay
 * is off. FX thread only.
 */
public final class PlayChain {

    private static boolean armed;

    private PlayChain() {}

    public static void arm() {
        armed = true;
    }

    /** True once after {@link #arm}: the new preview should start playing. */
    public static boolean take() {
        boolean was = armed;
        armed = false;
        return was;
    }

    /** Arms the chain and navigates to the next entry (does nothing at the end of the list). */
    public static void next(Supplier<AssetEntry> nextEntry, Consumer<AssetEntry> navigate) {
        AssetEntry target = nextEntry == null ? null : nextEntry.get();
        if (target != null && navigate != null) {
            arm();
            navigate.accept(target);
        }
    }
}
