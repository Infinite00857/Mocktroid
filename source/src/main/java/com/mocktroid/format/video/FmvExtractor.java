package com.mocktroid.format.video;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Turns an FMV0 resource into a file JavaFX can play. FMV0 is an RFRM header
 * around a plain container: almost always an MP4 (H.264 + AAC, written
 * straight out), occasionally a WebM (VP9 + Vorbis), which JavaFX can't
 * decode -- those are converted to H.264 with ffmpeg when it's installed.
 */
public final class FmvExtractor {

    private FmvExtractor() {}

    /** Thrown when the FMV0 holds a WebM and no ffmpeg is available to convert it. */
    public static final class NeedsFfmpegException extends IOException {
        public NeedsFfmpegException() {
            super("This video is WebM (VP9), which JavaFX can't play -- install ffmpeg to convert it");
        }
    }

    /** Offset of the MP4's first box (the 4-byte size before {@code ftyp}), or -1. */
    public static int mp4Start(byte[] d) {
        for (int i = 4; i + 4 <= Math.min(d.length, 4096); i++) {
            if (d[i] == 'f' && d[i + 1] == 't' && d[i + 2] == 'y' && d[i + 3] == 'p') {
                return i - 4;
            }
        }
        return -1;
    }

    /** Offset of a Matroska/WebM EBML header, or -1. */
    public static int webmStart(byte[] d) {
        for (int i = 0; i + 4 <= Math.min(d.length, 4096); i++) {
            if ((d[i] & 0xFF) == 0x1A && (d[i + 1] & 0xFF) == 0x45 && (d[i + 2] & 0xFF) == 0xDF && (d[i + 3] & 0xFF) == 0xA3) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Writes a playable MP4 for the FMV0 to a temp file (caller deletes it).
     * {@code maxSeconds} > 0 limits a WebM conversion (icons only need the start).
     */
    public static Path toPlayableMp4(byte[] raw, double maxSeconds) throws IOException, InterruptedException {
        int mp4 = mp4Start(raw);
        if (mp4 >= 0) {
            Path file = Files.createTempFile("mocktroid-fmv-", ".mp4");
            file.toFile().deleteOnExit();
            try (var out = Files.newOutputStream(file)) {
                out.write(raw, mp4, raw.length - mp4);
            }
            return file;
        }
        int webm = webmStart(raw);
        if (webm < 0) {
            throw new IOException("No video stream inside this FMV0");
        }
        String ffmpeg = findFfmpeg();
        if (ffmpeg == null) {
            throw new NeedsFfmpegException();
        }
        Path in = Files.createTempFile("mocktroid-fmv-", ".webm");
        Path out = Files.createTempFile("mocktroid-fmv-", ".mp4");
        in.toFile().deleteOnExit();
        out.toFile().deleteOnExit();
        try {
            try (var o = Files.newOutputStream(in)) {
                o.write(raw, webm, raw.length - webm);
            }
            List<String> cmd = new java.util.ArrayList<>(List.of(ffmpeg, "-v", "error", "-y", "-i", in.toString()));
            if (maxSeconds > 0) {
                cmd.addAll(List.of("-t", String.valueOf(maxSeconds)));
            }
            cmd.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-movflags", "+faststart", out.toString()));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
                p.destroyForcibly();
                throw new IOException("ffmpeg couldn't convert this WebM video");
            }
            return out;
        } finally {
            Files.deleteIfExists(in);
        }
    }

    private static volatile String ffmpegPath;
    private static volatile boolean ffmpegChecked;

    /** "ffmpeg" if it runs from PATH, else null. */
    public static String findFfmpeg() {
        if (!ffmpegChecked) {
            String found = null;
            try {
                Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
                p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    found = "ffmpeg";
                }
            } catch (IOException | InterruptedException e) {
                found = null;
            }
            ffmpegPath = found;
            ffmpegChecked = true;
        }
        return ffmpegPath;
    }
}
