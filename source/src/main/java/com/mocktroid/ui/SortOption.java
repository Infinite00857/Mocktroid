package com.mocktroid.ui;

/** Sort orders for the browse list/grid. Duration sort uses each sound's
 *  length from its CSMP header ({@code SoundDurations}, read in the
 *  background after the scan), so it's only offered on the Sounds tab. */
public enum SortOption {
    ALPHABETICAL_ASC("Alphabetical (A\u2192Z)"),
    ALPHABETICAL_DESC("Alphabetical (Z\u2192A)"),
    DURATION_ASC("Duration (shortest first)"),
    DURATION_DESC("Duration (longest first)"),
    PAK_ASC("Pak (A\u2192Z)"),
    PAK_DESC("Pak (Z\u2192A)");

    private final String label;

    SortOption(String label) {
        this.label = label;
    }

    public boolean isDuration() {
        return this == DURATION_ASC || this == DURATION_DESC;
    }

    @Override
    public String toString() {
        return label;
    }
}
