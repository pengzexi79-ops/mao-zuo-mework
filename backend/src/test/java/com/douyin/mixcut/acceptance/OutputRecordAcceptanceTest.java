package com.douyin.mixcut.acceptance;

import com.douyin.mixcut.domain.JobOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P3-3 output-record contract only: no JobService, repository, database, or external service. */
class OutputRecordAcceptanceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void exposesPassedOutputAndStructuredQcFieldsWithStablePersistenceNames() throws Exception {
        JobOutput output = new JobOutput();
        output.setJobId(41L);
        output.setIdx(2);
        output.setFilePath("C:/isolated/output/accepted.mp4");
        output.setDurationSec(6.0);
        output.setThumbnail("/files/thumbs/accepted.jpg");
        output.setQcStatus("warn");
        output.setQcReport("9 项质检：8 通过，1 提示");
        output.setQcJson("{\"status\":\"warn\",\"categories\":[{\"category\":\"rhythm\",\"status\":\"warn\"}]}");
        output.setRetryCount(1);
        output.setHookStrategy("fixture");
        output.setDowngradeInfo("[\"fixture warning\"]");
        output.setUsedMaterials("[{\"materialId\":1,\"slot\":\"hook\",\"duration\":2.0}]");
        output.setSegmentKeys("[\"1@0.000+2.000\"]");

        JsonNode serialized = json.readTree(json.writeValueAsString(output));

        for (String field : List.of("jobId", "idx", "filePath", "durationSec", "thumbnail", "qcStatus",
                "qcReport", "qcJson", "retryCount", "hookStrategy", "downgradeInfo", "usedMaterials", "segmentKeys")) {
            assertTrue(serialized.has(field), "missing output contract field: " + field);
        }
        assertEquals("warn", serialized.path("qcStatus").asText());
        assertEquals("warn", json.readTree(serialized.path("qcJson").asText()).path("status").asText());
        assertEquals("fixture", serialized.path("hookStrategy").asText());
        assertEquals("hook", json.readTree(serialized.path("usedMaterials").asText()).get(0).path("slot").asText());
        assertEquals("1@0.000+2.000", json.readTree(serialized.path("segmentKeys").asText()).get(0).asText());

        assertEquals("job_output", JobOutput.class.getAnnotation(Table.class).name());
        assertColumn("qcJson", "qc_json");
        assertColumn("qcReport", "qc_report");
        assertColumn("retryCount", "retry_count");
        assertColumn("hookStrategy", "hook_strategy");
        assertColumn("downgradeInfo", "downgrade_info");
        assertColumn("usedMaterials", "used_materials");
        assertColumn("segmentKeys", "segment_keys");
    }

    @Test
    void keepsQcBlockedRecordDiagnosticOnlyWithoutDeliveryPathOrThumbnail() throws Exception {
        JobOutput blocked = new JobOutput();
        blocked.setJobId(41L);
        blocked.setIdx(3);
        blocked.setQcStatus("fail");
        blocked.setQcReport("黑屏比例过高");
        blocked.setQcJson("{\"status\":\"fail\",\"categories\":[{\"category\":\"video\",\"status\":\"fail\"}]}");
        blocked.setRetryCount(0);
        blocked.setSegmentKeys("[\"2@0.000+2.000\"]");

        JsonNode serialized = json.readTree(json.writeValueAsString(blocked));

        assertEquals("fail", serialized.path("qcStatus").asText());
        assertEquals("fail", json.readTree(serialized.path("qcJson").asText()).path("status").asText());
        assertNull(blocked.getFilePath());
        assertNull(blocked.getDurationSec());
        assertNull(blocked.getThumbnail());
        assertFalse(serialized.hasNonNull("filePath"));
        assertFalse(serialized.hasNonNull("thumbnail"));
    }

    private void assertColumn(String fieldName, String columnName) throws Exception {
        Field field = JobOutput.class.getDeclaredField(fieldName);
        Column column = field.getAnnotation(Column.class);
        assertNotNull(column, "missing @Column on " + fieldName);
        assertEquals(columnName, column.name());
    }
}
