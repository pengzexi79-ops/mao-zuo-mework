package com.douyin.mixcut.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixtureManifestTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> IDS = Set.of(
            "video_motion", "video_av", "video_black", "video_solid", "audio_voice", "cover");

    @Test
    void manifestHasStableSafeFixtureContract() throws Exception {
        JsonNode manifest = readManifest();
        assertEquals(1, manifest.path("schemaVersion").asInt());
        assertEquals(IDS.size(), manifest.path("fixtures").size());
        Path acceptanceRoot = Path.of("src/test/resources/acceptance").toAbsolutePath().normalize();
        for (JsonNode fixture : manifest.path("fixtures")) {
            String id = fixture.path("id").asText();
            assertTrue(IDS.contains(id), "unexpected fixture id: " + id);
            String relative = fixture.path("relativePath").asText();
            assertFalse(Path.of(relative).isAbsolute(), id + " must be relative");
            assertFalse(relative.contains(".."), id + " must not contain traversal");
            Path path = acceptanceRoot.resolve(relative).normalize();
            assertTrue(path.startsWith(acceptanceRoot), id + " escaped acceptance root");
            assertTrue(Files.isRegularFile(path), id + " fixture is missing");
            assertFalse(Files.isSymbolicLink(path), id + " fixture must not be a symbolic link");
            assertEquals(fixture.path("sizeBytes").asLong(), Files.size(path), id + " size mismatch");
            assertEquals(fixture.path("sha256").asText(), sha256(path), id + " SHA256 mismatch");
            assertTrue(fixture.path("expected").has("readable"), id + " expected readable missing");
            assertTrue(fixture.path("expected").has("qualityGate"), id + " expected qualityGate missing");
            assertFalse(fixture.toString().contains("apiKey"), id + " must not contain credentials");
            assertFalse(fixture.toString().contains("http://"), id + " must not contain URLs");
            assertFalse(fixture.toString().contains("https://"), id + " must not contain URLs");
        }
    }

    @Test
    void manifestResourceIsPresent() throws Exception {
        assertNotNull(getClass().getResource("/acceptance/fixture-manifest.json"));
    }

    private JsonNode readManifest() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/acceptance/fixture-manifest.json")) {
            assertNotNull(input, "fixture manifest resource is missing");
            return JSON.readTree(input);
        }
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
