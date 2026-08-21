package com.douyin.mixcut.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseNotesPendingTest {

    @Test
    void pendingReleaseRecordMustBeAppliedBeforeBuild() throws Exception {
        Path pending = Path.of("release-notes.pending.json");
        assertTrue(Files.exists(pending), "缺少 backend/release-notes.pending.json；请使用 python tools/release_notes.py new 创建本次更新记录");
        assertEquals("{}", Files.readString(pending).trim(),
                "存在未应用的更新记录；请运行 python tools/release_notes.py check 和 apply 后再构建");
    }
}
