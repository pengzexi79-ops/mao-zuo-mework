package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseNotesSchemaTest {

    @Test
    void shippedReleaseHistoryIsValid() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("release-notes.json")) {
            Map<String, Object> notes = new ObjectMapper().readValue(input, new TypeReference<LinkedHashMap<String, Object>>() { });
            assertDoesNotThrow(() -> ReleaseNotesService.validate(notes, new AppProps().releaseVersion()));
        }
    }

    @Test
    void shippedHistoryStartsFromUnifiedVersionSequence() throws Exception {
        Map<String, Object> notes = shippedNotes();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) notes.get("history");
        String[] current = String.valueOf(notes.get("version")).split("\\.");
        assertEquals(3, current.length);
        int patch = Integer.parseInt(current[2]);
        assertTrue(history.size() >= 23);
        for (int index = 0; index < 23; index++) {
            assertEquals(current[0] + "." + current[1] + "." + (patch - index - 1),
                    history.get(index).get("version"));
        }
        assertTrue(history.stream().anyMatch(item -> "2.2.23".equals(item.get("version"))));
        assertTrue(history.stream().anyMatch(item -> "2.2.22".equals(item.get("version"))));
        for (int expectedPatch = 127; expectedPatch <= 146; expectedPatch++) {
            String expectedVersion = "2.2." + expectedPatch;
            assertTrue(expectedVersion.equals(notes.get("version"))
                    || history.stream().anyMatch(item -> expectedVersion.equals(item.get("version"))),
                    () -> "缺少 2026-08-24 至 2026-08-25 的连续记录：" + expectedVersion);
        }
    }

    @Test
    void missingRequiredRecordFieldFailsValidation() {
        Map<String, Object> broken = new LinkedHashMap<>();
        broken.put("version", "2.2.12");
        broken.put("history", java.util.List.of());
        assertThrows(IllegalArgumentException.class, () -> ReleaseNotesService.validate(broken, "2.2.12"));
    }

    @Test
    void duplicateCurrentAndHistoricalVersionFailsValidation() throws Exception {
        Map<String, Object> notes = shippedNotes();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) notes.get("history");
        history.get(0).put("version", notes.get("version"));

        assertThrows(IllegalArgumentException.class, () -> ReleaseNotesService.validate(notes, "2.2.15"));
    }

    @Test
    void invalidVersionFutureDateAndUnorderedHistoryFailValidation() throws Exception {
        Map<String, Object> invalidVersion = shippedNotes();
        invalidVersion.put("version", "v2.2.5-local");
        assertThrows(IllegalArgumentException.class, () -> ReleaseNotesService.validate(invalidVersion, "v2.2.5-local"));

        Map<String, Object> future = shippedNotes();
        future.put("releasedAt", LocalDate.now().plusDays(1).toString());
        assertThrows(IllegalArgumentException.class, () -> ReleaseNotesService.validate(future, "2.2.12"));

        Map<String, Object> unordered = shippedNotes();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unorderedHistory = (List<Map<String, Object>>) unordered.get("history");
        unorderedHistory.get(1).put("version", "2.2.12");
        unorderedHistory.get(1).put("id", "release-2-2-11");
        assertThrows(IllegalArgumentException.class, () -> ReleaseNotesService.validate(unordered, "2.2.12"));
    }

    @Test
    void sensitiveEvidenceAndUnapprovedKindFailValidation() throws Exception {
        Map<String, Object> sensitive = shippedNotes();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sensitiveHistory = (List<Map<String, Object>>) sensitive.get("history");
        sensitiveHistory.get(0).put("evidence", List.of("to" + "ken=must-not-be-recorded"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseNotesService.validate(sensitive, "2.2.12"));

        Map<String, Object> invalidKind = shippedNotes();
        invalidKind.put("kind", "昨日版本归档");
        assertThrows(IllegalArgumentException.class, () -> ReleaseNotesService.validate(invalidKind, "2.2.12"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> shippedNotes() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("release-notes.json")) {
            Map<String, Object> notes = new ObjectMapper().readValue(input, new TypeReference<LinkedHashMap<String, Object>>() { });
            List<Map<String, Object>> copiedHistory = new ArrayList<>();
            for (Map<String, Object> item : (List<Map<String, Object>>) notes.get("history")) {
                copiedHistory.add(new LinkedHashMap<>(item));
            }
            notes.put("history", copiedHistory);
            return notes;
        }
    }
}
