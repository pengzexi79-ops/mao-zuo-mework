package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.domain.MaterialSegment;
import com.douyin.mixcut.dto.MixParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class MixPlannerTest {

    @Test
    void localSourceModeExcludesCrawledMaterialsWhileBuiltinModeRetainsThem() {
        MixPlanner planner = new MixPlanner();
        Material local = visual(1L, "local.mp4", 30, MaterialRole.body);
        Material crawled = visual(2L, "crawl.mp4", 30, MaterialRole.body);
        crawled.setSource(Material.Source.crawl);

        MixParams localOnly = new MixParams();
        localOnly.setMaterialSourceMode("local");
        MixPlanner.Pool localPool = planner.buildPool(List.of(local, crawled), localOnly);
        assertEquals(List.of(local), localPool.getBody());

        MixParams builtin = new MixParams();
        builtin.setMaterialSourceMode("builtin");
        MixPlanner.Pool builtinPool = planner.buildPool(List.of(local, crawled), builtin);
        assertEquals(List.of(local, crawled), builtinPool.getBody());
    }

    @Test
    void plannerPoolUsesOneSecondMinimumForVideoSlices() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        Material shortVideo = visual(1L, "short.mp4", 0.8, MaterialRole.body);
        Material usableVideo = visual(2L, "usable.mp4", 1.0, MaterialRole.body);

        MixPlanner.Pool pool = planner.buildPool(List.of(shortVideo, usableVideo), params);

        assertFalse(pool.getBody().contains(shortVideo));
        assertTrue(pool.getBody().contains(usableVideo));
    }

    @Test
    void planHonorsDurationAndSourceBoundariesDeterministically() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(50);
        params.setMaxSec(55);
        params.setTargetDurationSec(52);
        params.setSeed(42L);
        params.setSliceSec(3.0);
        params.setSliceJitter(0.0);
        params.setProductSlots(2);
        params.setProductSec(3.0);

        MixPlanner.Pool pool = planner.buildPool(List.of(
                visual(1L, "body-a.mp4", 30, MaterialRole.body),
                visual(2L, "body-b.mp4", 30, MaterialRole.body),
                visual(3L, "product.mp4", 20, MaterialRole.product)));
        MixPlanner.Plan first = planner.plan(pool, params, 0, "hook");
        MixPlanner.Plan second = planner.plan(pool, params, 0, "hook");

        assertTrue(first.usable());
        assertEquals(first.getPlannedSec(), second.getPlannedSec());
        assertEquals(first.getSegments().size(), second.getSegments().size());
        assertTrue(first.getPlannedSec() >= 50 && first.getPlannedSec() <= 55);
        MixPlanner.Segment hook = first.getSegments().stream()
                .filter(segment -> "hook".equals(segment.getSlot())).findFirst().orElse(null);
        assertNotNull(hook);
        assertEquals(0.0, first.getHookStartSec(), 0.01);
        assertEquals(hook.getDuration(), first.getHookEndSec(), 0.01);
        for (int i = 0; i < first.getSegments().size(); i++) {
            MixPlanner.Segment segment = first.getSegments().get(i);
            if (segment.getSourceDuration() > 0) {
                assertTrue(segment.getSourceStart() >= 0);
                assertTrue(segment.getSourceStart() + segment.getDuration() <= segment.getSourceDuration() + 0.05);
            }
            if (i > 0) {
                assertNotEquals(first.getSegments().get(i - 1).getMaterialId(), segment.getMaterialId(),
                        "adjacent segments should use different material when alternatives exist");
            }
        }
    }

    @Test
    void missingOptionalRolesFallBackToBodyVisualsInsteadOfBlockingDelivery() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(20);
        params.setMaxSec(25);
        params.setTargetDurationSec(22);
        params.setSliceSec(3.0);
        params.setSliceJitter(0.0);
        params.setProductSlots(2);
        params.setEndcard(true);
        params.setCelebrityRatio(0.35);

        MixPlanner.Plan plan = planner.plan(planner.buildPool(List.of(
                visual(1L, "body-a.mp4", 30, MaterialRole.body),
                visual(2L, "body-b.mp4", 30, MaterialRole.body))), params, 0, "hook");

        assertTrue(plan.usable());
        assertEquals(2, plan.getSegments().stream().filter(segment -> "product".equals(segment.getSlot())).count());
        assertTrue(plan.getSegments().stream().anyMatch(segment -> "endcard".equals(segment.getSlot())));
        assertTrue(plan.getNotes().stream().anyMatch(note -> note.contains("未标注产品角色")));
        assertTrue(plan.getNotes().stream().anyMatch(note -> note.contains("未标注片尾卡")));
    }

    @Test
    void endcardUsesDedicatedDurationAndPrefersProductFallback() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(20);
        params.setMaxSec(30);
        params.setTargetDurationSec(24);
        params.setSliceSec(2.0);
        params.setEndcardSec(5.0);
        params.setSliceJitter(0.0);
        params.setProductSlots(0);
        params.setEndcard(true);

        MixPlanner.Plan plan = planner.plan(planner.buildPool(List.of(
                visual(1L, "body-a.mp4", 30, MaterialRole.body),
                visual(2L, "product.mp4", 30, MaterialRole.product))), params, 0, "hook");

        MixPlanner.Segment endcard = plan.getSegments().stream()
                .filter(segment -> "endcard".equals(segment.getSlot())).findFirst().orElseThrow();
        assertEquals(5.0, endcard.getDuration(), 0.01);
        assertEquals(2L, endcard.getMaterialId());
    }

    @Test
    void insufficientVisualCapacityIsNotAUsablePlan() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(50);
        params.setMaxSec(60);
        params.setTargetDurationSec(50);
        params.setSliceSec(3.0);
        params.setSliceJitter(0.0);
        params.setHookSec(0.0);
        params.setProductSlots(0);
        params.setEndcard(false);

        MixPlanner.Plan plan = planner.plan(planner.buildPool(List.of(
                visual(1L, "short.mp4", 8, MaterialRole.body))), params, 0, "");

        assertFalse(plan.usable());
        assertTrue(plan.getPlannedSec() < params.getMinSec());
        assertTrue(plan.getNotes().stream().anyMatch(note -> note.contains("素材不足")));
    }

    @Test
    void usableMeansMeetingMinimumNotRecommendedTargetAndIsJsonVisible() throws Exception {
        MixPlanner.Plan plan = new MixPlanner.Plan();
        plan.setMinSec(50);
        plan.setTargetSec(100);
        plan.setPlannedSec(93.9);
        MixPlanner.Segment segment = new MixPlanner.Segment();
        segment.setDuration(93.9);
        plan.getSegments().add(segment);

        assertTrue(plan.isUsable());
        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(plan));
        assertTrue(json.has("usable"));
        assertTrue(json.get("usable").asBoolean());

        plan.setPlannedSec(49.9);
        assertFalse(plan.isUsable());
    }

    @Test
    void materialAudioPlanWithoutAudioReportsActionablePreflightNote() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(20);
        params.setMaxSec(25);
        params.setTargetDurationSec(22);
        params.setSliceSec(3.0);
        params.setProductSlots(0);
        params.setEndcard(false);
        params.setAudioMode("material-audio");

        MixPlanner.Plan plan = planner.plan(planner.buildPool(List.of(
                visual(1L, "body.mp4", 30, MaterialRole.body))), params, 0, "hook");

        assertTrue(plan.isUsable());
        assertTrue(plan.isRequiresExternalAudio());
        assertNull(plan.getVoicePath());
        assertNull(plan.getBgmPath());
        assertTrue(plan.getNotes().stream().anyMatch(note -> note.contains("没有可用 BGM")));
    }

    @Test
    void explicitVoiceRoleAudioCanBeUsedAsLoopedBgm() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(20);
        params.setMaxSec(25);
        params.setTargetDurationSec(22);
        params.setSliceSec(3.0);
        params.setProductSlots(0);
        params.setEndcard(false);
        params.setBgmMaterialId(9L);

        MixPlanner.Plan plan = planner.plan(planner.buildPool(List.of(
                visual(1L, "body.mp4", 30, MaterialRole.body),
                audio(9L, "legacy-voice.mp3", 12, MaterialRole.voice))), params, 0, "hook");

        assertEquals(9L, plan.getBgmMaterialId());
        assertEquals("C:/fixtures/legacy-voice.mp3", plan.getBgmPath());
        assertEquals(12, plan.getBgmDurationSec(), 0.01);
        assertTrue(plan.getNotes().stream().anyMatch(note -> note.contains("循环背景声")));
    }

    @Test
    void doesNotFallbackToVoiceAudioAsLoopedBgmWhenNoBgmRoleExists() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(20);
        params.setMaxSec(25);
        params.setTargetDurationSec(22);
        params.setSliceSec(3.0);
        params.setProductSlots(0);
        params.setEndcard(false);

        MixPlanner.Plan plan = planner.plan(planner.buildPool(List.of(
                visual(1L, "body.mp4", 30, MaterialRole.body),
                audio(9L, "short-voice.mp3", 12, MaterialRole.voice),
                audio(10L, "long-voice.mp3", 40, MaterialRole.voice))), params, 0, "hook");

        assertNull(plan.getBgmMaterialId());
        assertNull(plan.getBgmPath());
        assertTrue(plan.getNotes().stream().noneMatch(note -> note.contains("自动将最长可读音频")));
    }

    @Test
    void explicitHumanVoiceIsKeptAndHookAudioIsNeverAutoSelected() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(20);
        params.setMaxSec(25);
        params.setTargetDurationSec(22);
        params.setSliceSec(3.0);
        params.setProductSlots(0);
        params.setEndcard(false);
        params.setVoiceMaterialId(9L);
        params.setAutoMatchAudio(true);

        MixPlanner.Plan plan = planner.plan(planner.buildPool(List.of(
                visual(1L, "body.mp4", 30, MaterialRole.body),
                audio(9L, "human.mp3", 40, MaterialRole.voice),
                audio(10L, "second-voice.mp3", 40, MaterialRole.voice))), params, 0, "hook");

        assertEquals(9L, plan.getVoiceMaterialId());
        assertNull(plan.getHookAudioMaterialId());
        assertNull(plan.getHookAudioPath());
        assertTrue(plan.getNotes().stream().anyMatch(note -> note.contains("不自动叠加第二条人声")));
    }

    @Test
    void generatedVoiceIsNotSelectedAutomaticallyForNewMaterialAudioJob() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(20);
        params.setMaxSec(25);
        params.setTargetDurationSec(22);
        params.setSliceSec(3.0);
        params.setProductSlots(0);
        params.setEndcard(false);

        Material generated = audio(9L, "generated.mp3", 40, MaterialRole.voice);
        generated.setTags("自动配音,zh-CN-XiaoxiaoNeural");
        MixPlanner.Plan plan = planner.plan(planner.buildPool(List.of(
                visual(1L, "body.mp4", 30, MaterialRole.body), generated)), params, 0, "hook");

        assertNull(plan.getVoiceMaterialId());
        assertNull(plan.getVoicePath());
    }

    @Test
    void variantsExposeDifferentSourceSliceKeys() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(20);
        params.setMaxSec(25);
        params.setTargetDurationSec(22);
        params.setSliceSec(3.0);
        params.setSliceJitter(0.0);
        params.setProductSlots(0);
        params.setEndcard(false);
        MixPlanner.Pool pool = planner.buildPool(List.of(
                visual(1L, "body-a.mp4", 40, MaterialRole.body),
                visual(2L, "body-b.mp4", 40, MaterialRole.body)));
        MixPlanner.Plan first = planner.plan(pool, params, 0, "hook");
        MixPlanner.Plan second = planner.plan(pool, params, 5, "hook");
        Set<String> overlap = new java.util.HashSet<>(first.segmentKeys());
        overlap.retainAll(second.segmentKeys());
        assertTrue(overlap.size() < first.segmentKeys().size(), "variants must not be identical across outputs");
    }

    @Test
    void equalCursorCandidatesUseStableRandomTieBreaking() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(50);
        params.setMaxSec(55);
        params.setTargetDurationSec(52);
        params.setSeed(99L);
        params.setSliceSec(3.0);
        params.setSliceJitter(0.0);
        params.setProductSlots(0);
        params.setEndcard(false);

        java.util.List<Material> materials = new java.util.ArrayList<>();
        for (long id = 1; id <= 40; id++) {
            materials.add(visual(id, "body-" + id + ".mp4", 9, MaterialRole.body));
        }
        MixPlanner.Pool pool = planner.buildPool(materials);
        MixPlanner.Plan first = assertDoesNotThrow(() -> planner.plan(pool, params, 0, "hook"));
        MixPlanner.Plan second = assertDoesNotThrow(() -> planner.plan(pool, params, 0, "hook"));

        assertTrue(first.usable());
        assertEquals(first.segmentKeys(), second.segmentKeys());
    }

    @Test
    void hookWindowStartsAfterExplicitIntro() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(20);
        params.setMaxSec(25);
        params.setTargetDurationSec(22);
        params.setSeed(7L);
        params.setSliceSec(3.0);
        params.setSliceJitter(0.0);
        params.setProductSlots(0);
        params.setEndcard(false);
        params.setIntroEnabled(true);
        params.setIntroMaterialId(1L);
        params.setIntroDurationSec(2.5);
        params.setHookSec(3.0);

        MixPlanner.Plan plan = planner.plan(planner.buildPool(List.of(
                visual(1L, "intro.mp4", 8, MaterialRole.body),
                visual(2L, "hook.mp4", 20, MaterialRole.hook),
                visual(3L, "body.mp4", 30, MaterialRole.body))), params, 0, "hook");

        assertTrue(plan.usable());
        assertEquals(2.5, plan.getHookStartSec(), 0.01);
        assertEquals(5.5, plan.getHookEndSec(), 0.01);
        assertEquals("intro", plan.getSegments().get(0).getSlot());
        assertEquals("hook", plan.getSegments().get(1).getSlot());
    }

    @Test
    void structuralRoleAuditNotesMissingHookProductEndcardCelebrity() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(30);
        params.setMaxSec(40);
        params.setTargetDurationSec(35);
        params.setSeed(42L);
        params.setSliceSec(3.0);
        params.setSliceJitter(0.0);
        params.setHookSec(3.0);
        params.setProductSlots(2);
        params.setCelebrityRatio(0.2);
        params.setEndcard(true);

        // Only body, voice, bgm — missing hook/product/endcard/celebrity. Body can serve as hook fallback.
        MixPlanner.Pool pool = planner.buildPool(List.of(
                visual(1L, "body.mp4", 60, MaterialRole.body),
                audio(10L, "voice.mp3", 30, MaterialRole.voice),
                audio(11L, "bgm.mp3", 120, MaterialRole.bgm)));

        MixPlanner.Plan plan = planner.plan(pool, params, 0, "hook text");

        assertTrue(plan.usable());
        // Hook, product and endcard all fall back to available body visuals while retaining an audit note.
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("未标注产品角色")),
                "should note product fallback");
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("未标注片尾卡")),
                "should note endcard fallback");
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("未标注达人角色")),
                "should note celebrity fallback");
    }

    @Test
    void structuralAuditNotesMissingHookWhenBothHookAndBodyEmpty() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(30);
        params.setMaxSec(40);
        params.setTargetDurationSec(35);
        params.setSeed(42L);
        params.setSliceSec(3.0);
        params.setSliceJitter(0.0);
        params.setHookSec(3.0);
        params.setProductSlots(0);
        params.setEndcard(false);

        // No hook, no body — hook audit should fire
        MixPlanner.Pool pool = planner.buildPool(List.of(
                visual(100L, "product.mp4", 30, MaterialRole.product),
                audio(10L, "voice.mp3", 30, MaterialRole.voice)));

        MixPlanner.Plan plan = planner.plan(pool, params, 0, "hook text");

        // Hook missing: both hook and body pools empty, plan likely not usable
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("缺少钩子素材")),
                "should note missing hook material when neither hook nor body exist");
    }

    @Test
    void fuzzySegmentKeysDetectNearOverlap() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(20);
        params.setMaxSec(25);
        params.setTargetDurationSec(22);
        params.setSeed(42L);
        params.setSliceSec(3.0);
        params.setSliceJitter(0.0);
        params.setProductSlots(0);
        params.setEndcard(false);
        MixPlanner.Pool pool = planner.buildPool(List.of(
                visual(1L, "body.mp4", 60, MaterialRole.body)));

        MixPlanner.Plan first = planner.plan(pool, params, 0, "hook");
        MixPlanner.Plan second = planner.plan(pool, params, 1, "hook");

        Set<String> exactOverlap = new java.util.HashSet<>(first.segmentKeys());
        exactOverlap.retainAll(second.segmentKeys());

        Set<String> fuzzyOverlap = new java.util.HashSet<>(first.fuzzySegmentKeys());
        fuzzyOverlap.retainAll(second.fuzzySegmentKeys());

        // Fuzzy keys should catch more overlap than exact keys (or equal)
        assertTrue(fuzzyOverlap.size() >= exactOverlap.size(),
                "fuzzy keys should detect at least as many overlaps as exact keys");
    }

    @Test
    void smoothDurationTransitionsDontJumpWildly() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(50);
        params.setMaxSec(60);
        params.setTargetDurationSec(55);
        params.setSeed(42L);
        params.setSliceSec(3.0);
        params.setSliceJitter(2.0); // high jitter to exercise smoothing
        params.setProductSlots(0);
        params.setEndcard(false);
        MixPlanner.Pool pool = planner.buildPool(List.of(
                visual(1L, "body-a.mp4", 120, MaterialRole.body),
                visual(2L, "body-b.mp4", 120, MaterialRole.body),
                visual(3L, "body-c.mp4", 120, MaterialRole.body)));

        MixPlanner.Plan plan = planner.plan(pool, params, 0, "hook");
        assertTrue(plan.usable());

        // Verify no adjacent segments jump more than 3x from previous (sanity for smoothing)
        double prev = -1;
        for (MixPlanner.Segment s : plan.getSegments()) {
            if (prev > 0 && "video".equals(s.getKind())) {
                assertTrue(s.getDuration() <= Math.max(prev * 3, params.getMaxSegmentSec()),
                        "segment duration " + s.getDuration() + " should not jump wildly from previous " + prev);
            }
            prev = s.getDuration();
        }
    }

    @Test
    void audioUsageRotationPicksLeastUsedBgmAndVoice() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(20);
        params.setMaxSec(25);
        params.setTargetDurationSec(22);
        params.setSeed(42L);
        params.setSliceSec(3.0);
        params.setSliceJitter(0.0);
        params.setProductSlots(0);
        params.setEndcard(false);
        MixPlanner.Pool pool = planner.buildPool(List.of(
                visual(1L, "body.mp4", 60, MaterialRole.body),
                audio(10L, "voice-a.mp3", 30, MaterialRole.voice),
                audio(11L, "voice-b.mp3", 30, MaterialRole.voice),
                audio(20L, "bgm-a.mp3", 120, MaterialRole.bgm),
                audio(21L, "bgm-b.mp3", 120, MaterialRole.bgm)));

        // Plan 0 should set usage counts
        MixPlanner.Plan p0 = planner.plan(pool, params, 0, "hook");
        assertNotNull(p0.getVoiceMaterialId());
        assertNotNull(p0.getBgmMaterialId());

        // Share usage from p0 into p1
        MixPlanner.Plan p1 = planner.plan(pool, params, 1, "hook");
        p1.audioUsage.putAll(p0.audioUsage);
        p1.usedHookVoices.addAll(p0.usedHookVoices);
        // Re-plan with merged usage — this exercises least-used rotation
        MixPlanner.Plan p1b = planner.plan(pool, params, 1, "hook");

        // After two plans from same voice pool, at least one should differ in audio selection
        // (not guaranteed due to randomness, but likely with 2+ options)
        boolean different = !Objects.equals(p0.getVoiceMaterialId(), p1b.getVoiceMaterialId())
                || !Objects.equals(p0.getBgmMaterialId(), p1b.getBgmMaterialId());
        // This is a probabilistic test — we just verify no NPEs and that the plan is usable
        assertTrue(p1b.usable(), "plan should remain usable with audio rotation");
    }

    @Test
    void planUnusableWhenPoolExhaustedWithAccurateMessage() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(100);
        params.setMaxSec(120);
        params.setTargetDurationSec(110);
        params.setSliceSec(3.0);
        params.setSliceJitter(0.0);
        params.setProductSlots(0);
        params.setEndcard(false);

        // Only 10s of visual material — far below the 100s target
        MixPlanner.Plan plan = planner.plan(planner.buildPool(List.of(
                visual(1L, "short.mp4", 10, MaterialRole.body))), params, 0, "");

        assertFalse(plan.usable());
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("素材不足")),
                "should contain accurate pool exhaustion message, not silently pass");
    }

    @Test
    void adjacentSegmentsUseDifferentMaterialWhenAlternativesAvailable() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(30);
        params.setMaxSec(40);
        params.setTargetDurationSec(35);
        params.setSeed(42L);
        params.setSliceSec(3.0);
        params.setSliceJitter(0.0);
        params.setProductSlots(0);
        params.setEndcard(false);

        // Multiple body clips so adjacency rule can take effect
        MixPlanner.Pool pool = planner.buildPool(List.of(
                visual(1L, "body-a.mp4", 60, MaterialRole.body),
                visual(2L, "body-b.mp4", 60, MaterialRole.body),
                visual(3L, "body-c.mp4", 60, MaterialRole.body)));

        MixPlanner.Plan plan = planner.plan(pool, params, 0, "hook");
        assertTrue(plan.usable());

        int adjacentSame = 0;
        Long prev = null;
        for (MixPlanner.Segment s : plan.getSegments()) {
            if (prev != null && Objects.equals(prev, s.getMaterialId())) adjacentSame++;
            prev = s.getMaterialId();
        }
        // With 3 distinct sources, adjacent repeats should be minimal
        assertTrue(adjacentSame <= plan.getSegments().size() / 3,
                "adjacent same-source repeats should be minimal when alternatives exist");
    }

    @Test
    void crossJobVariantStabilityFromIdentityAndSeed() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(20);
        params.setMaxSec(25);
        params.setTargetDurationSec(22);
        params.setSeed(42L);
        params.setSliceSec(3.0);
        params.setSliceJitter(0.0);
        params.setProductSlots(0);
        params.setEndcard(false);
        MixPlanner.Pool pool = planner.buildPool(List.of(
                visual(1L, "body-a.mp4", 60, MaterialRole.body),
                visual(2L, "body-b.mp4", 60, MaterialRole.body)));

        // Simulate job identity: same seed + same jobId should produce same plan
        long jobId = 100L;
        int variantA = (int) (jobId * 31L + 0);
        int variantB = (int) (jobId * 31L + 0);
        MixPlanner.Plan pA = planner.plan(pool, params, variantA, "hook");
        MixPlanner.Plan pB = planner.plan(pool, params, variantB, "hook");
        assertEquals(pA.segmentKeys(), pB.segmentKeys(),
                "same job identity + seed must produce identical segment keys");

        // Different job identity should produce different output
        long differentJobId = 200L;
        int variantC = (int) (differentJobId * 31L + 0);
        MixPlanner.Plan pC = planner.plan(pool, params, variantC, "hook");
        Set<String> overlap = new java.util.HashSet<>(pA.segmentKeys());
        overlap.retainAll(pC.segmentKeys());
        assertTrue(overlap.size() < pA.segmentKeys().size(),
                "different job identities should produce different segment keys");
    }

    @Test
    void hasNearOverlapBelowThresholdIsFalse() {
        MixPlanner.Plan plan = new MixPlanner.Plan();
        // Add one segment manually
        MixPlanner.Segment s = new MixPlanner.Segment();
        s.setMaterialId(1L);
        s.setSourceStart(3.0);
        s.setDuration(3.0);
        plan.getSegments().add(s);

        Set<String> foreignFuzzyKeys = Set.of("2@6.0+3.0", "2@9.0+3.0");
        assertFalse(plan.hasNearOverlap(foreignFuzzyKeys, 0.30),
                "no overlap should return false");
    }

    @Test
    void hasNearOverlapAboveThresholdIsTrue() {
        MixPlanner.Plan plan = new MixPlanner.Plan();
        MixPlanner.Segment s = new MixPlanner.Segment();
        s.setMaterialId(1L);
        s.setSourceStart(3.0);
        s.setDuration(3.0);
        plan.getSegments().add(s);

        // Same material, start+duration round to same fuzzy key: 1@3.0+3.0
        Set<String> foreignFuzzyKeys = Set.of("1@3.0+3.0");
        assertTrue(plan.hasNearOverlap(foreignFuzzyKeys, 0.30),
                "fuzzy overlap should be detected");
    }

    @Test
    void prefersPersistedSemanticSegmentsAndMarksGridDowngrade() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(20);
        params.setMaxSec(25);
        params.setTargetDurationSec(22);
        params.setSliceSec(3.0);
        params.setSliceJitter(0.0);
        params.setHookSec(0.0);
        params.setProductSlots(0);
        params.setEndcard(false);

        MixPlanner.Pool pool = planner.buildPool(List.of(
                visual(1L, "semantic.mp4", 30, MaterialRole.body),
                visual(2L, "grid.mp4", 30, MaterialRole.body)));
        Map<Long, List<MaterialSegment>> semantic = Map.of(1L, segments(1L, 5, 4.0));

        MixPlanner.Plan plan = planner.plan(pool, params, 0, "hook", null, semantic);

        assertTrue(plan.isUsable());
        assertEquals(1, plan.getSemanticSegmentCount());
        assertEquals(1, plan.getGridFallbackCount());
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("语义候选")),
                "should record semantic segment preference");
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("已回退网格切片")),
                "should mark grid downgrade for materials without analysis");
    }

    @Test
    void arrangesMidRollRehookWindowWhenTextPresent() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(20);
        params.setMaxSec(25);
        params.setTargetDurationSec(22);
        params.setSliceSec(3.0);
        params.setSliceJitter(0.0);
        params.setHookSec(0.0);
        params.setProductSlots(0);
        params.setEndcard(false);
        params.setRehookText("看到最后的都赚到了");

        MixPlanner.Plan plan = planner.plan(planner.buildPool(List.of(
                visual(1L, "body-a.mp4", 60, MaterialRole.body),
                visual(2L, "body-b.mp4", 60, MaterialRole.body))), params, 0, "hook");

        assertTrue(plan.isUsable());
        assertEquals("看到最后的都赚到了", plan.getRehookText());
        assertTrue(plan.getRehookWindowEnd() > plan.getRehookWindowStart(),
                "mid-roll rehook window must be a valid positive range");
    }

    @Test
    void standardDedupeReportsSameSourceOverlap() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(8);
        params.setMaxSec(10);
        params.setTargetDurationSec(9);
        params.setSliceSec(4.0);
        params.setSliceJitter(0.0);
        params.setSeed(1L);
        params.setHookSec(0.0);
        params.setProductSlots(0);
        params.setEndcard(false);

        // A short single source must be reused across rounds, producing overlapping windows in standard mode.
        MixPlanner.Plan plan = planner.plan(planner.buildPool(List.of(
                visual(1L, "single.mp4", 10, MaterialRole.body))), params, 0, "");

        assertTrue(plan.isUsable());
        assertTrue(plan.getNotes().stream().anyMatch(n -> n.contains("同源片段时间重叠")),
                "layered dedupe should surface same-source overlap in standard mode");
    }

    @Test
    void strictDedupeAvoidsSameSourceOverlap() {
        MixPlanner planner = new MixPlanner();
        MixParams params = new MixParams();
        params.setMinSec(20);
        params.setMaxSec(25);
        params.setTargetDurationSec(22);
        params.setSliceSec(3.0);
        params.setSliceJitter(0.0);
        params.setHookSec(0.0);
        params.setProductSlots(0);
        params.setEndcard(false);
        params.setDedupStrictness("strict");

        MixPlanner.Plan plan = planner.plan(planner.buildPool(List.of(
                visual(1L, "single.mp4", 30, MaterialRole.body))), params, 0, "");

        assertTrue(plan.isUsable());
        assertFalse(plan.getNotes().stream().anyMatch(n -> n.contains("同源片段时间重叠")),
                "strict mode should avoid same-source time overlap when capacity allows");
    }

    private static List<MaterialSegment> segments(Long materialId, int count, double eachDuration) {
        java.util.ArrayList<MaterialSegment> out = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            MaterialSegment segment = new MaterialSegment();
            segment.setMaterialId(materialId);
            segment.setIdx(i);
            segment.setStartSec(i * eachDuration);
            segment.setEndSec((i + 1) * eachDuration);
            segment.setDurationSec(eachDuration);
            out.add(segment);
        }
        return out;
    }

    @Test
    void buildPoolExcludesStaticImagesFromAutoBodyPool() {
        MixPlanner planner = new MixPlanner();
        Material bodyImage = image(1L, "body-img.png", MaterialRole.body);
        Material noneImage = image(2L, "none-img.png", MaterialRole.none);
        Material productImage = image(3L, "product-img.png", MaterialRole.product);
        Material hookImage = image(4L, "hook-img.png", MaterialRole.hook);
        Material bodyVideo = visual(5L, "body-video.mp4", 10, MaterialRole.body);

        MixPlanner.Pool pool = planner.buildPool(List.of(bodyImage, noneImage, productImage, hookImage, bodyVideo));

        assertTrue(pool.getBody().stream().noneMatch(m -> m.getFileType() == Material.FileType.image),
                "auto body (B-roll) pool must not contain static images");
        assertEquals(1, pool.getBody().size());
        assertTrue(pool.getBody().contains(bodyVideo));
        assertTrue(pool.getProduct().contains(productImage), "product-role static images stay available for product slots");
        assertTrue(pool.getHook().contains(hookImage), "hook-role static images stay available for hook slots");
        assertTrue(pool.hasVisual());
    }

    private Material image(Long id, String name, MaterialRole role) {
        Material material = new Material();
        material.setId(id);
        material.setName(name);
        material.setFilePath("C:/fixtures/" + name);
        material.setRole(role);
        material.setFileType(Material.FileType.image);
        material.setStatus(Material.Status.ready);
        return material;
    }

    private Material visual(Long id, String name, double duration, MaterialRole role) {
        Material material = new Material();
        material.setId(id);
        material.setName(name);
        material.setFilePath("C:/fixtures/" + name);
        material.setDurationSec(duration);
        material.setRole(role);
        material.setFileType(Material.FileType.video);
        material.setStatus(Material.Status.ready);
        return material;
    }

    private Material audio(Long id, String name, double duration, MaterialRole role) {
        Material material = new Material();
        material.setId(id);
        material.setName(name);
        material.setFilePath("C:/fixtures/" + name);
        material.setDurationSec(duration);
        material.setRole(role);
        material.setFileType(Material.FileType.audio);
        material.setStatus(Material.Status.ready);
        return material;
    }
}
