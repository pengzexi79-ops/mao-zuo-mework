package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.dto.MixParams;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixPlannerAudioIntentTest {

    @Test
    void picksBgmMatchingMoodKeywordsOverRandomFallback() {
        MixPlanner planner = new MixPlanner();
        MixParams params = baseParams();

        MixPlanner.AudioIntent intent = new MixPlanner.AudioIntent();
        intent.setMoodKeywords(List.of("upbeat"));
        intent.setPresent(true);

        MixPlanner.Pool pool = planner.buildPool(List.of(
                visual(1L, "body.mp4", 60, MaterialRole.body),
                audio(10L, "bgm-calm.mp3", 120, MaterialRole.bgm, "舒缓"),
                audio(11L, "bgm-upbeat.mp3", 120, MaterialRole.bgm, "upbeat")));

        MixPlanner.Plan plan = planner.plan(pool, params, 0, "hook", intent);

        assertNotNull(plan.getBgmMaterialId());
        assertEquals(11L, plan.getBgmMaterialId(), "content-driven BGM should prefer the mood match");
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("按项目语义偏好选择 BGM")));
    }

    @Test
    void fallsBackToLeastUsedBgmWhenNoMoodMatch() {
        MixPlanner planner = new MixPlanner();
        MixParams params = baseParams();

        MixPlanner.AudioIntent intent = new MixPlanner.AudioIntent();
        intent.setMoodKeywords(List.of("nonexistent-mood"));
        intent.setPresent(true);

        MixPlanner.Pool pool = planner.buildPool(List.of(
                visual(1L, "body.mp4", 60, MaterialRole.body),
                audio(10L, "bgm-a.mp3", 120, MaterialRole.bgm, "平静"),
                audio(11L, "bgm-b.mp3", 120, MaterialRole.bgm, "平静")));

        MixPlanner.Plan plan = planner.plan(pool, params, 0, "hook", intent);

        assertNotNull(plan.getBgmMaterialId());
        assertFalse(plan.getNotes().stream().anyMatch(n -> n.contains("按项目语义偏好选择 BGM")),
                "no mood match should keep the silent least-used fallback");
    }

    @Test
    void setsDuckFlagWhenVoiceAndBgmPresent() {
        MixPlanner planner = new MixPlanner();
        MixParams params = baseParams();

        MixPlanner.AudioIntent intent = new MixPlanner.AudioIntent();
        intent.setDuckBgm(true);
        intent.setPresent(true);

        MixPlanner.Pool pool = planner.buildPool(List.of(
                visual(1L, "body.mp4", 60, MaterialRole.body),
                audio(10L, "voice.mp3", 30, MaterialRole.voice, null),
                audio(11L, "bgm.mp3", 120, MaterialRole.bgm, null)));

        MixPlanner.Plan plan = planner.plan(pool, params, 0, "hook", intent);

        assertNotNull(plan.getVoiceMaterialId());
        assertNotNull(plan.getBgmMaterialId());
        assertTrue(plan.getDuckBgm(), "human voice priority should set the duck flag");
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("ducking")));
    }

    private MixParams baseParams() {
        MixParams params = new MixParams();
        params.setMinSec(20);
        params.setMaxSec(25);
        params.setTargetDurationSec(22);
        params.setSliceSec(3.0);
        params.setSliceJitter(0.0);
        params.setProductSlots(0);
        params.setEndcard(false);
        params.setSeed(42L);
        return params;
    }

    private Material visual(Long id, String name, double duration, MaterialRole role) {
        Material m = new Material();
        m.setId(id);
        m.setName(name);
        m.setFilePath("C:/fixtures/" + name);
        m.setDurationSec(duration);
        m.setRole(role);
        m.setFileType(Material.FileType.video);
        m.setStatus(Material.Status.ready);
        return m;
    }

    private Material audio(Long id, String name, double duration, MaterialRole role, String tags) {
        Material m = new Material();
        m.setId(id);
        m.setName(name);
        m.setFilePath("C:/fixtures/" + name);
        m.setDurationSec(duration);
        m.setRole(role);
        m.setFileType(Material.FileType.audio);
        m.setTags(tags);
        m.setStatus(Material.Status.ready);
        return m;
    }
}
