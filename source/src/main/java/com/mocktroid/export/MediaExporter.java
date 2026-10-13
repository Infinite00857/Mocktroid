package com.mocktroid.export;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

import com.mocktroid.format.audio.VgmstreamClient;

/**
 * Sound and flipbook exports. Sounds decode to WAV with vgmstream; FLAC / OGG / MP3 are
 * converted from that WAV with ffmpeg when it's on PATH. Flipbooks (animated textures) write an
 * animated GIF with the JDK's encoder, or WebP through ffmpeg.
 */
public final class MediaExporter {

    private MediaExporter() {}

    public enum SoundFormat {
        WAV("WAV (*.wav)", "wav", false),
        FLAC("FLAC (*.flac)", "flac", true),
        OGG("Ogg Vorbis (*.ogg)", "ogg", true),
        MP3("MP3 (*.mp3)", "mp3", true);

        public final String label;
        public final String extension;
        public final boolean needsFfmpeg;

        SoundFormat(String label, String extension, boolean needsFfmpeg) {
            this.label = label;
            this.extension = extension;
            this.needsFfmpeg = needsFfmpeg;
        }
    }

    /** Formats offered right now (ffmpeg ones only when ffmpeg is found). */
    public static List<SoundFormat> soundFormats() {
        List<SoundFormat> out = new ArrayList<>();
        boolean ff = ffmpegAvailable();
        for (SoundFormat f : SoundFormat.values()) {
            if (!f.needsFfmpeg || ff) {
                out.add(f);
            }
        }
        return out;
    }

    public static void exportSound(byte[] csmp, double loopCount, Path target, SoundFormat format) throws IOException, InterruptedException {
        Path wav = VgmstreamClient.decodeToWav(csmp, loopCount);
        try {
            if (format == SoundFormat.WAV) {
                Files.copy(wav, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                ffmpeg(List.of("-i", wav.toString(), target.toString()));
            }
        } finally {
            Files.deleteIfExists(wav);
        }
    }

    /** Animated GIF from ARGB frames (fps frames per second, looping). */
    public static void exportGif(List<int[]> frames, int width, int height, double fps, Path target) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            int delay = (int) Math.max(2, Math.round(100 / Math.max(1, fps)));
            for (int i = 0; i < frames.size(); i++) {
                BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                img.setRGB(0, 0, width, height, frames.get(i), 0, width);
                ImageTypeSpecifier spec = ImageTypeSpecifier.createFromRenderedImage(img);
                IIOMetadata meta = writer.getDefaultImageMetadata(spec, null);
                String fmt = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(fmt);
                IIOMetadataNode gce = child(root, "GraphicControlExtension");
                gce.setAttribute("disposalMethod", "restoreToBackgroundColor");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", String.valueOf(delay));
                gce.setAttribute("transparentColorIndex", "0");
                if (i == 0) {
                    IIOMetadataNode apps = child(root, "ApplicationExtensions");
                    IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
                    app.setAttribute("applicationID", "NETSCAPE");
                    app.setAttribute("authenticationCode", "2.0");
                    app.setUserObject(new byte[] {1, 0, 0}); // loop forever
                    apps.appendChild(app);
                }
                meta.setFromTree(fmt, root);
                writer.writeToSequence(new IIOImage(img, null, meta), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    /** Animated WebP through ffmpeg (frames written as PNGs first). */
    public static void exportWebp(List<int[]> frames, int width, int height, double fps, Path target) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("mocktroid-webp");
        try {
            for (int i = 0; i < frames.size(); i++) {
                com.mocktroid.format.image.PngWriter.write(dir.resolve(String.format("f%05d.png", i)), width, height, frames.get(i));
            }
            ffmpeg(List.of("-framerate", String.valueOf(fps), "-i", dir.resolve("f%05d.png").toString(),
                "-loop", "0", "-lossless", "1", "-c:v", "libwebp_anim", target.toString()));
        } finally {
            try (var walk = Files.list(dir)) {
                walk.forEach(p -> p.toFile().delete());
            }
            Files.deleteIfExists(dir);
        }
    }

    public static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void ffmpeg(List<String> args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-y", "-loglevel", "error"));
        cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IOException("ffmpeg failed: " + output.strip());
        }
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }
}
