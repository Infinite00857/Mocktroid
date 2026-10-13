package com.mocktroid.format.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mocktroid.settings.AppDirs;
import com.mocktroid.settings.AppSettings;

/**
 * Shells out to {@code vgmstream-cli} to inspect and decode a CSMP asset's
 * raw (RFRM-wrapped) bytes. vgmstream has native "Retro Studios RFRM
 * header" support covering exactly the container this game uses --
 * confirmed against real extracted samples -- so no manual header
 * stripping is needed; the extracted bytes are handed to it as-is.
 * <p>
 * The executable is looked up, in order: the explicit location configured in
 * Settings (a file or the folder containing it), {@code vgmstream-cli} on
 * PATH (the Homebrew route on macOS), then a {@code tools/vgmstream} folder
 * next to the working directory (handy for an unzipped Windows release).
 */
public final class VgmstreamClient {

    /** Loop metadata for one sound, from {@code vgmstream-cli -m}. Sample
     *  positions are in the *source* stream's sample rate, i.e. before any
     *  loop-count expansion -- convert with {@link #loopStartSeconds()} etc. */
    public record Metadata(int sampleRate, long loopStartSamples, long loopEndSamples,
                            long totalSamples, boolean loops) {
        public double loopStartSeconds() {
            return sampleRate == 0 ? 0 : (double) loopStartSamples / sampleRate;
        }

        /** Estimated playback duration at a given loop count, without
         *  decoding -- matches the {@code -l N -F} decode math (intro +
         *  N loop-body repeats, no fade tail) closely enough for sorting.
         *  For a non-looping sound, loopCount is ignored. */
        public double estimatedDurationSeconds(double loopCount) {
            if (sampleRate == 0) {
                return 0;
            }
            if (!loops) {
                return (double) totalSamples / sampleRate;
            }
            double loopBodySamples = Math.max(0, loopEndSamples - loopStartSamples);
            return (loopStartSamples + loopBodySamples * loopCount) / (double) sampleRate;
        }
    }

    private static final Pattern SAMPLE_RATE = Pattern.compile("sample rate:\\s*(\\d+)\\s*Hz");
    private static final Pattern LOOP_START = Pattern.compile("loop start:\\s*(\\d+)\\s*samples");
    private static final Pattern LOOP_END = Pattern.compile("loop end:\\s*(\\d+)\\s*samples");
    private static final Pattern TOTAL_SAMPLES = Pattern.compile("stream total samples:\\s*(\\d+)");

    private static final String EXE = AppDirs.isWindows() ? "vgmstream-cli.exe" : "vgmstream-cli";

    /** Resolved executable, or "" once resolution has failed; null = not resolved yet. */
    private static String executable;

    private VgmstreamClient() {}

    /** Whether a working {@code vgmstream-cli} was found. Cached until
     *  {@link #resetLookup()} (called when the configured path changes). */
    public static synchronized boolean isAvailable() {
        return !resolveExecutable().isEmpty();
    }

    public static synchronized void resetLookup() {
        executable = null;
    }

    /** The executable actually in use, or null if none was found. */
    public static synchronized String executablePath() {
        String exe = resolveExecutable();
        return exe.isEmpty() ? null : exe;
    }

    private static String resolveExecutable() {
        if (executable == null) {
            executable = "";
            for (String candidate : candidates()) {
                if (runs(candidate)) {
                    executable = candidate;
                    break;
                }
            }
        }
        return executable;
    }

    private static List<String> candidates() {
        List<String> out = new ArrayList<>();
        String configured = AppSettings.get().vgmstreamPathProperty().get();
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.trim());
            out.add(Files.isDirectory(p) ? p.resolve(EXE).toString() : p.toString());
        }
        out.add("vgmstream-cli");
        out.add(Path.of(System.getProperty("user.dir"), "tools", "vgmstream", EXE).toString());
        return out;
    }

    private static boolean runs(String exe) {
        try {
            Process p = new ProcessBuilder(exe, "-h").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes(); // drain so the process can exit
            return p.waitFor(5, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Reads loop points and sample rate without decoding audio. */
    public static Metadata getMetadata(byte[] csmpBytes) throws IOException, InterruptedException {
        requireAvailable();
        Path tempIn = Files.createTempFile("mocktroid-", ".csmp");
        try {
            Files.write(tempIn, csmpBytes);
            String output = runVgmstream(new String[]{"-m", tempIn.toString()}, 15);
            int sampleRate = intGroup(SAMPLE_RATE, output, 0);
            long loopStart = longGroup(LOOP_START, output, -1);
            long loopEnd = longGroup(LOOP_END, output, -1);
            long total = longGroup(TOTAL_SAMPLES, output, 0);
            boolean loops = loopStart >= 0 && loopEnd >= 0;
            return new Metadata(sampleRate, Math.max(0, loopStart), Math.max(0, loopEnd), total, loops);
        } finally {
            Files.deleteIfExists(tempIn);
        }
    }

    /**
     * Decodes raw CSMP bytes to a WAV. {@code loopCount} controls how many
     * times a looping track's loop body repeats (matches vgmstream's
     * {@code -l} flag, combined with {@code -F} to skip the default 10s
     * fade-after-loops so duration scales cleanly and linearly with loop
     * count, matching what a "loop count" setting should feel like); for a
     * non-looping sound this has no effect and it just plays once
     * regardless. Caller owns the returned temp file.
     */
    public static Path decodeToWav(byte[] csmpBytes, double loopCount) throws IOException, InterruptedException {
        requireAvailable();
        Path tempIn = Files.createTempFile("mocktroid-", ".csmp");
        Path tempOut = Files.createTempFile("mocktroid-", ".wav");
        try {
            Files.write(tempIn, csmpBytes);
            runVgmstream(new String[]{
                "-l", String.valueOf(loopCount), "-F", "-o", tempOut.toString(), tempIn.toString()
            }, 90);
            return tempOut;
        } finally {
            Files.deleteIfExists(tempIn);
        }
    }

    private static void requireAvailable() throws IOException {
        if (!isAvailable()) {
            throw new IOException("vgmstream-cli wasn't found. macOS: `brew install vgmstream`; "
                + "Windows: unzip vgmstream-win64.zip from github.com/vgmstream/vgmstream/releases "
                + "and set its location in Settings > Audio.");
        }
    }

    private static String runVgmstream(String[] args, int timeoutSeconds) throws IOException, InterruptedException {
        String[] full = new String[args.length + 1];
        full[0] = executablePath();
        System.arraycopy(args, 0, full, 1, args.length);
        Process process = new ProcessBuilder(full).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("vgmstream-cli timed out");
        }
        if (process.exitValue() != 0) {
            throw new IOException("vgmstream-cli failed (exit " + process.exitValue() + "): " + output);
        }
        return output;
    }

    private static int intGroup(Pattern p, String text, int fallback) {
        Matcher m = p.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private static long longGroup(Pattern p, String text, long fallback) {
        Matcher m = p.matcher(text);
        return m.find() ? Long.parseLong(m.group(1)) : fallback;
    }
}
