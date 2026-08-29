package com.douyin.mixcut.acceptance;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreshInstallContractTest {
    @Test
    void freshInstallGateUsesIndependentPortsAndDoesNotTouchProjectEnv() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Path script = root.resolve("verify_fresh_install.ps1");
        assertTrue(Files.isRegularFile(script));
        String text = Files.readString(script);
        assertTrue(text.contains("APP_PORT"));
        assertTrue(text.contains("MYSQL_PORT"));
        assertTrue(text.contains("Pick-Port"));
        assertTrue(text.contains("/api/system/env"));
        assertTrue(text.contains("fresh-install:0"));
        assertFalse(text.contains("fresh-install:skipped"), "missing release assets must fail, not skip");
        assertFalse(text.contains("taskkill"), "gate must not use broad taskkill");
        assertFalse(text.contains(".env.example"), "gate must not copy env templates into install test");
        assertTrue(text.contains("APP_DATA_DIR"));
        assertTrue(text.contains("Stop-Process -Id $pid"));
    }
}
