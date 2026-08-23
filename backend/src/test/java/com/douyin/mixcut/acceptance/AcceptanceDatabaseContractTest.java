package com.douyin.mixcut.acceptance;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcceptanceDatabaseContractTest {
    @Test
    void databaseContractIsGatedAndNeverDefaultsToBusinessDatabase() {
        String url = System.getenv("ACCEPTANCE_DB_URL");
        if (url == null || url.isBlank()) {
            assertTrue(true, "database check is intentionally skipped without explicit acceptance credentials");
            return;
        }
        assertTrue(url.startsWith("jdbc:mysql://"));
        String database = url.substring(url.indexOf('/', "jdbc:mysql://".length()) + 1).split("\\?")[0];
        assertEquals("ai_mix_video_acceptance", database);
        assertTrue(!url.contains("ai_mix_video?") && !url.endsWith("/ai_mix_video"));
    }
}
