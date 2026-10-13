package com.mocktroid.library;

/**
 * A small persisted waveform (min/max amplitude bins, not full sample data)
 * plus loop/duration metadata for one sound -- what a grid thumbnail needs
 * to render without re-decoding. See {@link SoundThumbnailCache}.
 */
public record CachedWaveform(short[] minBins, short[] maxBins, double durationSeconds,
                              boolean loops, double loopStartSeconds) {
}
