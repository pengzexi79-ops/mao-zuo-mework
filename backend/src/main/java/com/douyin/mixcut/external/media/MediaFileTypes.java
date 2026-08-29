package com.douyin.mixcut.external.media;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;

/** Identifies raster image files by content so generated files are not mislabeled by an API gateway. */
public final class MediaFileTypes {
    private MediaFileTypes() { }

    public record ImageFormat(String extension, String mediaType) { }

    public static Optional<ImageFormat> detectImage(Path file) throws IOException {
        byte[] header;
        try (var input = Files.newInputStream(file)) {
            header = input.readNBytes(64);
        }
        Optional<ImageFormat> detected = detectImage(header);
        if (detected.isPresent()) return detected;

        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) return Optional.empty();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return Optional.empty();
            String format = readers.next().getFormatName().toLowerCase(Locale.ROOT);
            return switch (format) {
                case "jpeg", "jpg" -> Optional.of(new ImageFormat("jpg", "image/jpeg"));
                case "png" -> Optional.of(new ImageFormat("png", "image/png"));
                case "gif" -> Optional.of(new ImageFormat("gif", "image/gif"));
                case "bmp" -> Optional.of(new ImageFormat("bmp", "image/bmp"));
                case "webp" -> Optional.of(new ImageFormat("webp", "image/webp"));
                default -> Optional.empty();
            };
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public static Optional<ImageFormat> detectImage(byte[] header) {
        if (header == null) return Optional.empty();
        if (startsWith(header, 0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) return Optional.of(new ImageFormat("png", "image/png"));
        if (startsWith(header, 0, 0xff, 0xd8, 0xff)) return Optional.of(new ImageFormat("jpg", "image/jpeg"));
        if (asciiAt(header, 0, "GIF87a") || asciiAt(header, 0, "GIF89a")) return Optional.of(new ImageFormat("gif", "image/gif"));
        if (asciiAt(header, 0, "RIFF") && asciiAt(header, 8, "WEBP")) return Optional.of(new ImageFormat("webp", "image/webp"));
        if (startsWith(header, 0, 0x42, 0x4d)) return Optional.of(new ImageFormat("bmp", "image/bmp"));
        if (asciiAt(header, 4, "ftyp") && hasIsoBrand(header, "avif", "avis")) return Optional.of(new ImageFormat("avif", "image/avif"));
        return Optional.empty();
    }

    private static boolean hasIsoBrand(byte[] header, String... brands) {
        for (int offset = 8; offset + 4 <= header.length; offset += 4) {
            for (String brand : brands) if (asciiAt(header, offset, brand)) return true;
        }
        return false;
    }

    private static boolean startsWith(byte[] input, int offset, int... expected) {
        if (input.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) if ((input[offset + i] & 0xff) != expected[i]) return false;
        return true;
    }

    private static boolean asciiAt(byte[] input, int offset, String expected) {
        if (input.length < offset + expected.length()) return false;
        for (int i = 0; i < expected.length(); i++) if ((char) input[offset + i] != expected.charAt(i)) return false;
        return true;
    }
}
