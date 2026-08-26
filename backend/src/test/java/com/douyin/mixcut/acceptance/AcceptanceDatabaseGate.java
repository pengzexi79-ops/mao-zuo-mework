package com.douyin.mixcut.acceptance;

import org.junit.jupiter.api.Assumptions;

import java.net.URI;

final class AcceptanceDatabaseGate {
    private AcceptanceDatabaseGate() {
    }

    static String url() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("ACCEPTANCE_DB_RUN")),
                "P3-4 MySQL integration is opt-in: set ACCEPTANCE_DB_RUN=true");
        String url = System.getenv("ACCEPTANCE_DB_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "ACCEPTANCE_DB_URL is not configured");
        Assumptions.assumeTrue(url.startsWith("jdbc:mysql://"), "acceptance URL must be MySQL JDBC");
        String withoutPrefix = url.substring("jdbc:mysql://".length());
        String hostPart = withoutPrefix.substring(0, withoutPrefix.indexOf('/'));
        String database = withoutPrefix.substring(withoutPrefix.indexOf('/') + 1).split("\\?")[0];
        String host = hostPart.split(":")[0];
        Assumptions.assumeTrue(host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1"),
                "acceptance database must be local");
        Assumptions.assumeTrue("ai_mix_video_acceptance".equals(database),
                "acceptance database name mismatch: " + database);
        return url;
    }
}
