package com.mocktroid.library;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.mocktroid.format.audio.VgmstreamClient;
import com.mocktroid.format.audio.WavPcmReader;
import com.mocktroid.format.pack.PackCatalog;
import com.mocktroid.model.AssetEntry;

import javafx.concurrent.Task;

/**
 * Background job that decodes every sound entry once and stores a small
 * waveform thumbnail + loop/duration metadata via {@link SoundThumbnailCache}.
 * Already-cached entries are skipped, so re-running after a partial/cancelled
 * run only fills in the gaps.
 * <p>
 * Uses a natural one-shot decode (loop count 1, not the user's live-preview
 * loop-count setting) purely to keep this as fast as reasonably possible --
 * a thumbnail doesn't need to reflect exact configured playback length.
 * There's still no way to decode less than the full natural length
 * (vgmstream has no "decode first N seconds" flag), so this is a genuinely
 * long-running task across a full sound library (~9,500 CSMP assets) --
 * report progress and make it cancellable rather than pretending it's quick.
 */
public final class SoundThumbnailCacheBuilder extends Task<Void> {

    private final List<AssetEntry> soundEntries;
    private final SoundThumbnailCache cache;

    public SoundThumbnailCacheBuilder(List<AssetEntry> soundEntries) {
        this.soundEntries = soundEntries;
        this.cache = SoundThumbnailCache.get();
    }

    @Override
    protected Void call() {
        int total = soundEntries.size();
        for (int i = 0; i < total && !isCancelled(); i++) {
            AssetEntry entry = soundEntries.get(i);
            updateMessage(entry.displayName());
            updateProgress(i, total);
            try {
                generateOne(entry);
            } catch (Exception e) {
                // Skip failures (corrupt asset, vgmstream hiccup, etc.) and keep going --
                // one bad sound shouldn't abort caching the rest of the library.
            }
        }
        updateProgress(total, total);
        return null;
    }

    private void generateOne(AssetEntry entry) throws Exception {
        if (!(entry.sourceRef() instanceof PakAssetRef ref)) {
            return;
        }
        UUID assetId = ref.asset().assetId();
        if (cache.has(assetId)) {
            return;
        }
        byte[] raw = PackCatalog.extractDecompressed(ref.pakFile(), ref.asset());
        VgmstreamClient.Metadata meta = VgmstreamClient.getMetadata(raw);
        Path wav = VgmstreamClient.decodeToWav(raw, 1.0);
        try {
            WavPcmReader.WavData wavData = WavPcmReader.read(wav);
            short[] minBins = new short[SoundThumbnailCache.BIN_COUNT];
            short[] maxBins = new short[SoundThumbnailCache.BIN_COUNT];
            computeBins(wavData, minBins, maxBins);
            cache.put(assetId, new CachedWaveform(minBins, maxBins,
                wavData.durationSeconds(), meta.loops(), meta.loopStartSeconds()));
        } finally {
            Files.deleteIfExists(wav);
        }
    }

    private static void computeBins(WavPcmReader.WavData wav, short[] minBins, short[] maxBins) {
        int channels = wav.channels();
        short[] samples = wav.samples();
        int perChannel = channels == 0 ? 0 : samples.length / channels;
        int bins = minBins.length;
        if (perChannel <= 0) {
            return;
        }
        for (int b = 0; b < bins; b++) {
            long start = (long) b * perChannel / bins;
            long end = (long) (b + 1) * perChannel / bins;
            if (end <= start) end = start + 1;
            short min = Short.MAX_VALUE;
            short max = Short.MIN_VALUE;
            for (long s = start; s < end && s < perChannel; s++) {
                for (int c = 0; c < channels; c++) {
                    int idx = (int) (s * channels + c);
                    if (idx >= samples.length) continue;
                    short v = samples[idx];
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
            }
            minBins[b] = min;
            maxBins[b] = max;
        }
    }
}
