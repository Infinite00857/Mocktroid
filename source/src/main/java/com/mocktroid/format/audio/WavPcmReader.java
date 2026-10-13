package com.mocktroid.format.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal RIFF/WAVE parser for exactly what {@code vgmstream-cli} produces
 * (PCM16, standard fmt/data chunk layout) -- just enough to pull out raw
 * samples for waveform rendering, not a general-purpose WAV library.
 */
public final class WavPcmReader {
    private WavPcmReader() {}

    public record WavData(int sampleRate, int channels, int bitsPerSample, short[] samples) {
        public double durationSeconds() {
            int perChannel = channels == 0 ? 0 : samples.length / channels;
            return sampleRate == 0 ? 0 : (double) perChannel / sampleRate;
        }
    }

    public static WavData read(Path wavFile) throws IOException {
        byte[] data = Files.readAllBytes(wavFile);
        if (data.length < 12 || !tag(data, 0, "RIFF") || !tag(data, 8, "WAVE")) {
            throw new IOException("Not a RIFF/WAVE file: " + wavFile);
        }

        int sampleRate = 0, channels = 0, bitsPerSample = 16;
        int dataOffset = -1, dataLength = 0;
        int pos = 12;
        while (pos + 8 <= data.length) {
            String chunkId = new String(data, pos, 4, StandardCharsets.US_ASCII);
            int chunkSize = readIntLE(data, pos + 4);
            int chunkDataStart = pos + 8;
            if ("fmt ".equals(chunkId)) {
                channels = readShortLE(data, chunkDataStart + 2);
                sampleRate = readIntLE(data, chunkDataStart + 4);
                bitsPerSample = readShortLE(data, chunkDataStart + 14);
            } else if ("data".equals(chunkId)) {
                dataOffset = chunkDataStart;
                dataLength = Math.min(chunkSize, data.length - chunkDataStart);
            }
            pos = chunkDataStart + chunkSize + (chunkSize % 2 == 0 ? 0 : 1); // chunks are word-aligned
        }
        if (dataOffset < 0) {
            throw new IOException("No data chunk found in " + wavFile);
        }
        if (bitsPerSample != 16) {
            throw new IOException("Only 16-bit PCM is supported, got " + bitsPerSample + "-bit");
        }

        int totalSamples = dataLength / 2;
        short[] samples = new short[totalSamples];
        for (int i = 0; i < totalSamples; i++) {
            samples[i] = (short) readShortLE(data, dataOffset + i * 2);
        }
        return new WavData(sampleRate, Math.max(1, channels), bitsPerSample, samples);
    }

    private static boolean tag(byte[] data, int offset, String expected) {
        for (int i = 0; i < 4; i++) {
            if (data[offset + i] != (byte) expected.charAt(i)) return false;
        }
        return true;
    }

    private static int readIntLE(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8) | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
    }

    private static int readShortLE(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }
}
