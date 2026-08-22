package com.douyin.mixcut.service;

import com.douyin.mixcut.service.BootstrapService.CapabilityManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能力清单（backend/src/main/resources/capabilities.json）的结构与修复安装边界校验。
 * 任何打包改动只要触碰清单，都必须先通过这里的约定：版本化、key 唯一、
 * 修复安装目标只允许 approvedPip 中固定的 name==version、与 requirements-windows.txt 无版本漂移。
 */
class CapabilityManifestTest {

    private static final Set<String> EXPECTED_KEYS = Set.of(
            "video-render", "database", "video-download", "video-download-2", "asr", "asr-local",
            "ocr", "tts", "chattts", "loudness", "vocals", "matting", "auto-editor", "opencv",
            "magick", "image-gallery", "whisper-model", "nvenc", "pixabay-video", "pexels-video",
            "freesound");

    private static final Pattern PINNED = Pattern.compile("^([A-Za-z0-9_.-]+)==(\\S+)$");

    @Test
    void loadsVersionedManifestWithAllExpectedCapabilities() {
        CapabilityManifest manifest = CapabilityManifest.load();
        assertTrue(manifest.schemaVersion() >= 2, "schemaVersion 必须 >= 2");
        assertTrue(!manifest.manifestVersion().isBlank(), "manifestVersion 不能为空");
        assertEquals(EXPECTED_KEYS.size(), manifest.size(), "能力数量与既有清单约定一致");
        Set<String> keys = new HashSet<>();
        for (CapabilityManifest.Entry entry : manifest.entries()) {
            keys.add(entry.key());
            assertTrue(entry.name() != null && !entry.name().isBlank());
            assertTrue(entry.executionPolicy() != null && !entry.executionPolicy().isBlank());
            assertTrue(entry.verifySteps() != null && !entry.verifySteps().isEmpty());
            if (entry.isExternal()) {
                assertTrue(entry.installMode() != null && !entry.installMode().isBlank());
                assertTrue(entry.officialUrl() != null && !entry.officialUrl().isBlank());
                assertTrue(entry.guide() != null && !entry.guide().isBlank());
            } else {
                assertTrue(entry.envKey() != null && !entry.envKey().isBlank());
                assertTrue(entry.usedBy() != null && !entry.usedBy().isBlank());
            }
        }
        assertEquals(EXPECTED_KEYS, keys, "清单 key 集合必须与既有能力中心完全一致");
    }

    @Test
    void everyEnvCapabilityHasStableContractFields() {
        CapabilityManifest manifest = CapabilityManifest.load();
        for (CapabilityManifest.Entry entry : manifest.entries()) {
            if (entry.isExternal()) continue;
            assertEquals("env", entry.type());
            assertTrue(EXPECTED_KEYS.contains(entry.key()), "env 能力 key 必须在既有清单约定内：" + entry.key());
        }
    }

    @Test
    void repairableCapabilitiesResolveOnlyToPinnedApprovedTargets() {
        CapabilityManifest manifest = CapabilityManifest.load();
        // 当前唯一允许修复安装的两个能力：人声分离（demucs）与图集抓取（gallery-dl）。
        assertEquals("demucs==4.1.0", manifest.approvedSpec("demucs"));
        assertEquals("gallery-dl==1.32.9", manifest.approvedSpec("gallery-dl"));
        // 随包预置但不可修复的包不允许通过修复安装路径解析。
        assertNull(manifest.approvedSpec("rembg"));
        assertNull(manifest.approvedSpec("auto-editor"));
        assertNull(manifest.approvedSpec("opencv-python"));
        assertNull(manifest.approvedSpec("edge-tts"));
        assertNull(manifest.approvedSpec("not-a-package"));
        assertNull(manifest.approvedSpec(null));
        assertNull(manifest.approvedSpec(""));
    }

    @Test
    void approvedPipTargetsDoNotDriftFromInstalledBundleVersions() throws IOException {
        CapabilityManifest manifest = CapabilityManifest.load();
        Path requirements = Path.of(System.getProperty("user.dir"), "requirements-windows.txt");
        assertTrue(Files.isRegularFile(requirements), "requirements-windows.txt 必须存在于 backend 根目录");
        for (String line : Files.readAllLines(requirements, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            Matcher matcher = PINNED.matcher(trimmed);
            assertTrue(matcher.matches(), "requirements-windows.txt 每一行必须固定版本：" + line);
            String packageName = matcher.group(1);
            String requirementVersion = matcher.group(2);
            // 清单 approvedPip 中若出现 requirements 里已有的包，版本必须完全一致，不允许漂移。
            for (CapabilityManifest.PipSpec pip : manifest.approvedPipEntries()) {
                if (pip.packageName().equals(packageName)) {
                    assertEquals(requirementVersion, pip.spec().substring(pip.spec().indexOf('=') + 2),
                            "清单 approvedPip." + packageName + " 与 requirements-windows.txt 版本漂移");
                }
            }
        }
    }

    @Test
    void rejectsManifestWithoutVersion() {
        ObjectNode root = new ObjectMapper().createObjectNode();
        root.putArray("capabilities").addObject().put("type", "env").put("key", "x");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CapabilityManifest.parse(root));
        assertTrue(e.getMessage().contains("schemaVersion") || e.getMessage().contains("capabilities"),
                "错误信息应指出缺失的版本字段或清单结构：" + e.getMessage());
    }

    @Test
    void rejectsDuplicateCapabilityKeys() {
        ObjectNode root = baseManifestNode();
        ArrayNode caps = (ArrayNode) root.path("capabilities");
        caps.addObject().put("type", "env").put("key", "dup").put("group", "g").put("name", "n")
                .put("tool", "t").put("envKey", "k");
        caps.addObject().put("type", "env").put("key", "dup").put("group", "g").put("name", "n")
                .put("tool", "t").put("envKey", "k");
        assertThrows(IllegalStateException.class, () -> CapabilityManifest.parse(root));
    }

    @Test
    void rejectsRepairableCapabilityWithoutApprovedPinnedTarget() {
        ObjectNode root = baseManifestNode();
        ((ObjectNode) root.path("capabilities").get(0))
                .put("repairable", true).put("pipRef", "ghost");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CapabilityManifest.parse(root));
        assertTrue(e.getMessage().contains("approvedPip"), "错误信息应指向 approvedPip：" + e.getMessage());
    }

    @Test
    void rejectsUnpinnedApprovedPipSpec() {
        ObjectNode root = baseManifestNode();
        ObjectNode approved = root.putObject("approvedPip");
        approved.putObject("demucs").put("spec", "demucs").put("repairable", true);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CapabilityManifest.parse(root));
        assertTrue(e.getMessage().contains("固定版本"), "错误信息应要求固定版本：" + e.getMessage());
    }

    @Test
    void rejectsApprovedPipSpecWhoseNameDiffersFromKey() {
        ObjectNode root = baseManifestNode();
        ObjectNode approved = root.putObject("approvedPip");
        approved.putObject("demucs").put("spec", "otherpkg==1.0.0").put("repairable", true);
        assertThrows(IllegalStateException.class, () -> CapabilityManifest.parse(root));
    }

    @Test
    void manifestExposesAllCapabilitiesByKey() {
        CapabilityManifest manifest = CapabilityManifest.load();
        assertNotNull(manifest.byKey("video-render"));
        assertNotNull(manifest.byKey("freesound"));
        assertNull(manifest.byKey("nope"));
        assertNull(manifest.byKey(null));
    }

    private static ObjectNode baseManifestNode() {
        ObjectNode root = new ObjectMapper().createObjectNode();
        root.put("schemaVersion", 1);
        root.put("manifestVersion", "9.9.9");
        root.putArray("capabilities").addObject()
                .put("type", "env").put("key", "base").put("group", "g").put("name", "n")
                .put("tool", "t").put("envKey", "k");
        return root;
    }
}
