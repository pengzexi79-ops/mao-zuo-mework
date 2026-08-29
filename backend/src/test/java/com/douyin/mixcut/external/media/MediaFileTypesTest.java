package com.douyin.mixcut.external.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaFileTypesTest {
    @Test
    void detectsAWebpPayloadEvenWhenTheFileHasPngExtension(@TempDir Path tempDir) throws Exception {
        Path mislabeled = tempDir.resolve("gateway-result.png");
        Files.write(mislabeled, new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '});

        var detected = MediaFileTypes.detectImage(mislabeled);

        assertTrue(detected.isPresent());
        assertEquals("webp", detected.get().extension());
        assertEquals("image/webp", detected.get().mediaType());
    }

    @Test
    void detectsCommonGatewayImageHeaders() {
        assertEquals("png", MediaFileTypes.detectImage(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}).orElseThrow().extension());
        assertEquals("jpg", MediaFileTypes.detectImage(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00}).orElseThrow().extension());
        assertEquals("gif", MediaFileTypes.detectImage("GIF89a".getBytes()).orElseThrow().extension());
    }
}
