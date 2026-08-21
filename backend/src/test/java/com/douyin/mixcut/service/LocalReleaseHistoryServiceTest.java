package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
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
