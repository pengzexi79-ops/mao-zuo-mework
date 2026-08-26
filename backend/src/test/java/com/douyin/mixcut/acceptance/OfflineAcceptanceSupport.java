package com.douyin.mixcut.acceptance;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.repository.MaterialStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.mockito.stubbing.Answer;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

final class OfflineAcceptanceSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    private OfflineAcceptanceSupport() {
    }

    static AppProps props(Path work, Path projectRoot) {
        AppProps props = new AppProps();
        props.setDataDir(work.resolve("data").toString());
        props.setMaterialsDir(work.resolve("materials").toString());
        props.setCacheDir(work.resolve("cache").toString());
        props.setOutputDir(work.resolve("output").toString());
        props.setMediaToolsOutputDir(work.resolve("media-tools").toString());
        props.setFfmpeg(projectRoot.resolve("portable/ffmpeg/bin/ffmpeg.exe").toString());
        props.setFfprobe(projectRoot.resolve("portable/ffmpeg/bin/ffprobe.exe").toString());
        props.setLocalPython(projectRoot.resolve("backend/.venv/Scripts/python.exe").toString());
        props.setMediaDiagnoseScript(projectRoot.resolve("backend/tools/media_diagnose.py").toString());
        return props;
    }

    static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("portable")) && Files.isDirectory(current.resolve("backend"))) return current;
        Path parent = current.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("portable"))) return parent;
        throw new IllegalStateException("project root with portable runtime is unavailable");
    }

    static JsonNode manifest() throws Exception {
        try (InputStream input = OfflineAcceptanceSupport.class.getResourceAsStream("/acceptance/fixture-manifest.json")) {
            Assertions.assertNotNull(input, "fixture manifest is missing");
            return JSON.readTree(input);
        }
    }

    static Path copyFixture(String id, Path inputRoot) throws Exception {
        JsonNode fixture = null;
        for (JsonNode item : manifest().path("fixtures")) {
            if (id.equals(item.path("id").asText())) {
                fixture = item;
                break;
            }
        }
        Assertions.assertNotNull(fixture, "unknown fixture: " + id);
        String relative = fixture.path("relativePath").asText();
        Assertions.assertFalse(Path.of(relative).isAbsolute());
        Assertions.assertFalse(relative.contains(".."));
        Path source = Path.of("src/test/resources/acceptance").toAbsolutePath().normalize().resolve(relative).normalize();
        Assertions.assertTrue(Files.isRegularFile(source));
        Assertions.assertFalse(Files.isSymbolicLink(source));
        Path target = inputRoot.resolve(Path.of(relative).getFileName()).normalize();
        Files.createDirectories(inputRoot);
        Files.copy(source, target);
        Assertions.assertEquals(fixture.path("sizeBytes").asLong(), Files.size(target));
        Assertions.assertEquals(fixture.path("sha256").asText(), sha256(target));
        return target;
    }

    static Map<Long, Material> stubStore(MaterialStore store) {
        Map<Long, Material> byId = new LinkedHashMap<>();
        Map<String, Material> byPath = new LinkedHashMap<>();
        AtomicLong ids = new AtomicLong(1);
        when(store.findById(any())).thenAnswer(invocation -> Optional.ofNullable(byId.get(invocation.getArgument(0))));
        when(store.findByFilePath(any())).thenAnswer(invocation -> Optional.ofNullable(byPath.get(invocation.getArgument(0))));
        when(store.save(any(Material.class))).thenAnswer((Answer<Material>) invocation -> {
            Material material = invocation.getArgument(0);
            if (material.getId() == null) material.setId(ids.getAndIncrement());
            byId.put(material.getId(), material);
            byPath.put(material.getFilePath(), material);
            return material;
        });
        when(store.findAll()).thenAnswer(invocation -> byId.values().stream().toList());
        when(store.findByFileType(any())).thenAnswer(invocation -> byId.values().stream()
                .filter(item -> item.getFileType() == invocation.getArgument(0)).toList());
        return byId;
    }

    static void assertUnder(Path root, Path path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        Assertions.assertTrue(normalized.startsWith(normalizedRoot), "path escaped temp root: " + normalized);
        Assertions.assertFalse(Files.isSymbolicLink(normalized), "symlink is forbidden: " + normalized);
    }

    static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = new byte[8192];
            int count;
            while ((count = input.read(bytes)) >= 0) {
                if (count > 0) digest.update(bytes, 0, count);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
}
