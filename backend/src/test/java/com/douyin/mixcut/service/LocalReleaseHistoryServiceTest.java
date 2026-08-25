package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalReleaseHistoryServiceTest {
    @Test
    void initializesAndPersistsOnlyUnderDataDirectory() throws Exception {
        AppProps props = new AppProps();
        Path data = Files.createTempDirectory("mework-release-");
        props.setDataDir(data.toString());
        LocalReleaseHistoryService service = new LocalReleaseHistoryService(new ObjectMapper(), props);

        Map<String, Object> status = service.status();
        assertTrue(Boolean.TRUE.equals(status.get("portable")));
        assertTrue(Path.of(String.valueOf(status.get("storagePath"))).startsWith(data));
        assertFalse(Files.exists(data.resolve("release-history/pending.json")));
    }

    @Test
    void appliesDraftAndArchivesPreviousCurrent() throws Exception {
        AppProps props = new AppProps();
        Path data = Files.createTempDirectory("mework-release-");
        props.setDataDir(data.toString());
        LocalReleaseHistoryService service = new LocalReleaseHistoryService(new ObjectMapper(), props);
        Map<String, Object> initialStatus = service.status();
        String expectedVersion = String.valueOf(initialStatus.get("nextVersion"));
        String initialVersion = String.valueOf(initialStatus.get("currentVersion"));
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("title", "本机测试记录");
        draft.put("summary", "验证本机记录不依赖源码目录。");
        draft.put("changes", List.of("测试应用内记录"));
        draft.put("fixes", List.of("无"));
        draft.put("verification", List.of("单元测试"));
        draft.put("compatibility", "只写 data 目录。");
        draft.put("evidence", List.of("LocalReleaseHistoryServiceTest.java"));

        Map<String, Object> result = service.apply(draft);
        assertEquals(expectedVersion, result.get("currentVersion"));
        assertTrue(Files.exists(data.resolve("release-history/local-release-notes.json")));
        assertFalse(result.containsKey("notes"));
        Map<String, Object> notes = service.view(500);
        assertEquals(expectedVersion, notes.get("version"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> history = (List<Map<String, Object>>) notes.get("history");
        assertEquals(initialVersion, history.get(0).get("version"));
    }

    @Test
    void upgradesOldLocalHistoryToBundledAugustRecordsWithoutDuplicates() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AppProps props = new AppProps();
        Path data = Files.createTempDirectory("mework-release-upgrade-");
        props.setDataDir(data.toString());
        Map<String, Object> bundled;
        try (var input = getClass().getClassLoader().getResourceAsStream("release-notes.json")) {
            bundled = mapper.readValue(input, new TypeReference<LinkedHashMap<String, Object>>() { });
        }
        @SuppressWarnings("unchecked") List<Map<String, Object>> bundledHistory = (List<Map<String, Object>>) bundled.get("history");
        Map<String, Object> oldCurrent = new LinkedHashMap<>(bundledHistory.stream()
                .filter(item -> "2.2.126".equals(item.get("version"))).findFirst().orElseThrow());
        oldCurrent.put("kind", "当前本机构建");
        oldCurrent.put("history", bundledHistory.stream().filter(item -> {
            String version = String.valueOf(item.get("version"));
            return !version.startsWith("2.2.")
                    || Integer.parseInt(version.substring("2.2.".length())) < 126;
        }).toList());
        Path local = data.resolve("release-history/local-release-notes.json");
        Files.createDirectories(local.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(local.toFile(), oldCurrent);

        LocalReleaseHistoryService service = new LocalReleaseHistoryService(mapper, props);
        Map<String, Object> upgraded = service.view(500);

        assertEquals("2.2.147", upgraded.get("version"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> history = (List<Map<String, Object>>) upgraded.get("history");
        for (int patch = 127; patch <= 146; patch++) {
            String version = "2.2." + patch;
            assertEquals(1, history.stream().filter(item -> version.equals(item.get("version"))).count(), version);
        }
    }

    @Test
    void rejectsSensitiveEvidence() {
        AppProps props = new AppProps();
        props.setDataDir(Path.of("target", "test-release-data").toString());
        LocalReleaseHistoryService service = new LocalReleaseHistoryService(new ObjectMapper(), props);
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("title", "x");
        draft.put("summary", "x");
        draft.put("changes", List.of("x"));
        draft.put("fixes", List.of("x"));
        draft.put("verification", List.of("x"));
        draft.put("compatibility", "x");
        draft.put("evidence", List.of("token=secret"));
        assertThrows(IllegalArgumentException.class, () -> service.validateDraft(draft));
    }
}
